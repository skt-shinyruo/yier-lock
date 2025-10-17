package com.mycorp.distributedlock.redis.pubsub;

import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;

/**
 * Shared pub/sub manager that efficiently manages Redis pub/sub subscriptions.
 * Inspired by Redisson's PubSubConnectionEntry pattern.
 * 
 * Key optimizations:
 * - Reuses single pub/sub connection across multiple lock instances
 * - Multiplexes multiple listeners on same channel
 * - Automatic cleanup of unused subscriptions
 * - Thread-safe listener management
 */
public class SharedPubSubManager {

    private static final Logger logger = LoggerFactory.getLogger(SharedPubSubManager.class);

    private final StatefulRedisPubSubConnection<String, String> connection;
    private final RedisPubSubAsyncCommands<String, String> async;
    private final ConcurrentHashMap<String, ChannelSubscription> subscriptions;
    private final GlobalMessageListener globalListener;

    public SharedPubSubManager(StatefulRedisPubSubConnection<String, String> connection) {
        this.connection = connection;
        this.async = connection.async();
        this.subscriptions = new ConcurrentHashMap<>();
        this.globalListener = new GlobalMessageListener();
        
        // Register global listener once
        connection.addListener(globalListener);
        logger.info("Initialized shared pub/sub manager");
    }

    /**
     * Subscribe to a channel and register a listener.
     * Returns a future that completes when subscription is active.
     */
    public CompletableFuture<Subscription> subscribe(String channel, MessageListener listener) {
        ChannelSubscription sub = subscriptions.computeIfAbsent(channel, ch -> {
            logger.debug("Creating new subscription for channel: {}", ch);
            return new ChannelSubscription(ch);
        });

        return sub.addListener(listener).thenApply(v -> new Subscription(channel, listener, this));
    }

    /**
     * Unsubscribe a specific listener from a channel.
     */
    public CompletableFuture<Void> unsubscribe(String channel, MessageListener listener) {
        ChannelSubscription sub = subscriptions.get(channel);
        if (sub == null) {
            return CompletableFuture.completedFuture(null);
        }

        return sub.removeListener(listener).thenCompose(hasListeners -> {
            if (!hasListeners) {
                // No more listeners, unsubscribe from Redis
                subscriptions.remove(channel);
                logger.debug("Unsubscribing from channel (no more listeners): {}", channel);
                return async.unsubscribe(channel).toCompletableFuture().thenApply(v -> null);
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    /**
     * Publish a message to a channel.
     */
    public CompletableFuture<Long> publish(String channel, String message) {
        return async.publish(channel, message).toCompletableFuture();
    }

    /**
     * Close all subscriptions and cleanup.
     */
    public void close() {
        logger.info("Closing shared pub/sub manager");
        try {
            connection.removeListener(globalListener);
            subscriptions.clear();
        } catch (Exception e) {
            logger.warn("Error during pub/sub manager cleanup", e);
        }
    }

    /**
     * Get metrics for monitoring.
     */
    public PubSubMetrics getMetrics() {
        PubSubMetrics metrics = new PubSubMetrics();
        metrics.activeSubscriptions = subscriptions.size();
        metrics.totalListeners = subscriptions.values().stream()
            .mapToInt(sub -> sub.listeners.size())
            .sum();
        return metrics;
    }

    /**
     * Represents a subscription that manages multiple listeners for a single channel.
     */
    private class ChannelSubscription {
        private final String channel;
        private final Set<MessageListener> listeners;
        private volatile boolean subscribed;

        ChannelSubscription(String channel) {
            this.channel = channel;
            this.listeners = new CopyOnWriteArraySet<>();
            this.subscribed = false;
        }

        CompletableFuture<Void> addListener(MessageListener listener) {
            listeners.add(listener);
            
            if (!subscribed) {
                synchronized (this) {
                    if (!subscribed) {
                        logger.debug("Subscribing to Redis channel: {}", channel);
                        return async.subscribe(channel)
                            .toCompletableFuture()
                            .thenAccept(v -> {
                                subscribed = true;
                                logger.trace("Successfully subscribed to channel: {}", channel);
                            });
                    }
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Boolean> removeListener(MessageListener listener) {
            listeners.remove(listener);
            return CompletableFuture.completedFuture(!listeners.isEmpty());
        }

        void notifyListeners(String message) {
            for (MessageListener listener : listeners) {
                try {
                    listener.onMessage(channel, message);
                } catch (Exception e) {
                    logger.warn("Error notifying listener for channel {}: {}", channel, e.getMessage());
                }
            }
        }
    }

    /**
     * Global listener that dispatches messages to channel-specific subscriptions.
     */
    private class GlobalMessageListener extends RedisPubSubAdapter<String, String> {
        @Override
        public void message(String channel, String message) {
            ChannelSubscription sub = subscriptions.get(channel);
            if (sub != null) {
                sub.notifyListeners(message);
            }
        }
    }

    /**
     * Listener interface for receiving messages.
     */
    public interface MessageListener {
        void onMessage(String channel, String message);
    }

    /**
     * Handle to a subscription that can be cancelled.
     */
    public static class Subscription {
        private final String channel;
        private final MessageListener listener;
        private final SharedPubSubManager manager;
        private volatile boolean cancelled;

        Subscription(String channel, MessageListener listener, SharedPubSubManager manager) {
            this.channel = channel;
            this.listener = listener;
            this.manager = manager;
            this.cancelled = false;
        }

        public CompletableFuture<Void> cancel() {
            if (!cancelled) {
                cancelled = true;
                return manager.unsubscribe(channel, listener);
            }
            return CompletableFuture.completedFuture(null);
        }

        public String getChannel() {
            return channel;
        }
    }

    public static class PubSubMetrics {
        public int activeSubscriptions;
        public int totalListeners;

        @Override
        public String toString() {
            return String.format("PubSubMetrics{subscriptions=%d, listeners=%d}",
                activeSubscriptions, totalListeners);
        }
    }
}
