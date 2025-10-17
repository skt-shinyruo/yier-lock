package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class RedisDistributedLockFactory implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RedisDistributedLockFactory.class);

    private final RedisClient redisClient;
    private final ClientResources clientResources;
    private final StatefulRedisConnection<String, String> connection;
    private final StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private final LockConfiguration configuration;
    private final LockMetrics metrics;
    private final LockTracing tracing;
    private final ScheduledExecutorService watchdogExecutor;
    private final ConcurrentHashMap<String, DistributedLock> locks;
    private final ConcurrentHashMap<String, RedisDistributedReadWriteLock> readWriteLocks;
    private final boolean ownsClientResources;
    
    public RedisDistributedLockFactory(RedisClient redisClient) {
        this(redisClient, new LockConfiguration(), null, null);
    }
    
    public RedisDistributedLockFactory(RedisClient redisClient,
    LockConfiguration configuration,
    MeterRegistry meterRegistry,
    OpenTelemetry openTelemetry) {
    this.redisClient = redisClient;
    this.clientResources = redisClient.getResources();
    this.ownsClientResources = false; // Client resources provided by RedisClient
    this.connection = redisClient.connect();
    this.pubSubConnection = redisClient.connectPubSub();
    this.configuration = configuration;
    this.metrics = new LockMetrics(meterRegistry, configuration.isMetricsEnabled());
    this.tracing = new LockTracing(openTelemetry, configuration.isTracingEnabled());
    this.watchdogExecutor = configuration.isWatchdogEnabled()
    ? Executors.newScheduledThreadPool(2, r -> {
    Thread t = new Thread(r, "redis-lock-watchdog");
        t.setDaemon(true);
            return t;
        }) : null;
    this.locks = new ConcurrentHashMap<>();
    this.readWriteLocks = new ConcurrentHashMap<>();

        logger.info("Redis distributed lock factory initialized with configuration: {}", configuration);
    }

    /**
     * Create a factory with custom ClientResources for better connection pooling.
     * Based on Redisson's connection management approach.
     */
    public static RedisDistributedLockFactory createWithCustomResources(
            LockConfiguration configuration,
            MeterRegistry meterRegistry,
            OpenTelemetry openTelemetry) {

        RedisClusterFactory clusterFactory = new RedisClusterFactory(configuration, meterRegistry, openTelemetry);
        return clusterFactory.createLockFactory();
    }

    /**
     * Create a factory for Redis Cluster mode.
     */
    public static RedisDistributedLockFactory createClusterFactory(
            LockConfiguration configuration,
            MeterRegistry meterRegistry,
            OpenTelemetry openTelemetry) {

        RedisClusterFactory clusterFactory = new RedisClusterFactory(configuration, meterRegistry, openTelemetry);
        clusterFactory.mode = RedisClusterFactory.RedisMode.CLUSTER;
        return clusterFactory.createLockFactory();
    }

    /**
     * Create a factory for Redis Sentinel mode.
     */
    public static RedisDistributedLockFactory createSentinelFactory(
            LockConfiguration configuration,
            MeterRegistry meterRegistry,
            OpenTelemetry openTelemetry) {

        RedisClusterFactory clusterFactory = new RedisClusterFactory(configuration, meterRegistry, openTelemetry);
        clusterFactory.mode = RedisClusterFactory.RedisMode.SENTINEL;
        return clusterFactory.createLockFactory();
    }

    @Override
    public void close() {
        shutdown();
    }
    
    public DistributedLock getLock(String name) {
        return locks.computeIfAbsent(name, lockName ->
            new RedisDistributedLock(
                lockName,
                connection,
                pubSubConnection,
                configuration,
                metrics,
                tracing,
                watchdogExecutor
            )
        );
    }

    /**
     * Get a fair distributed lock that acquires locks in request order.
     * Based on Redisson's FairLock implementation.
     */
    public DistributedLock getFairLock(String name) {
        return locks.computeIfAbsent("fair:" + name, lockName ->
            new RedisFairLock(
                lockName.substring(5), // Remove "fair:" prefix
                connection,
                pubSubConnection,
                configuration,
                metrics,
                tracing,
                watchdogExecutor
            )
        );
    }


    
    public DistributedReadWriteLock getReadWriteLock(String name) {
        return readWriteLocks.computeIfAbsent(name, lockName ->
            new RedisDistributedReadWriteLock(
                lockName,
                connection,
                pubSubConnection,
                configuration,
                metrics,
                tracing,
                watchdogExecutor
            )
        );
    }
    
    public void shutdown() {
        logger.info("Shutting down Redis distributed lock factory");

        // Shutdown watchdog executor first to stop renewal tasks
        if (watchdogExecutor != null) {
            watchdogExecutor.shutdown();
            try {
                if (!watchdogExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    watchdogExecutor.shutdownNow();
                    logger.warn("Watchdog executor did not terminate gracefully");
                }
            } catch (InterruptedException e) {
                watchdogExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Attempt to release locks held by current thread
        // Note: We cannot release locks held by other threads as that would be unsafe
        locks.values().forEach(lock -> {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                    logger.info("Released lock {} during shutdown", lock.getName());
                }
            } catch (Exception e) {
                logger.warn("Error releasing lock {} during shutdown", lock.getName(), e);
            }
        });

        readWriteLocks.values().forEach(rwLock -> {
            try {
                if (rwLock.readLock().isHeldByCurrentThread()) {
                    rwLock.readLock().unlock();
                    logger.info("Released read lock {} during shutdown", rwLock.getName());
                }
                if (rwLock.writeLock().isHeldByCurrentThread()) {
                    rwLock.writeLock().unlock();
                    logger.info("Released write lock {} during shutdown", rwLock.getName());
                }
            } catch (Exception e) {
                logger.warn("Error releasing read-write lock {} during shutdown", rwLock.getName(), e);
            }
        });

        // Close Redis connections
        try {
            pubSubConnection.close();
            connection.close();
        } catch (Exception e) {
            logger.warn("Error closing Redis connections", e);
        }

        // Shutdown Redis client
        try {
            redisClient.shutdown();
        } catch (Exception e) {
            logger.warn("Error shutting down Redis client", e);
        }

        // Close ClientResources if we own them
        if (ownsClientResources && clientResources != null) {
            try {
                clientResources.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down client resources", e);
            }
        }

        // Clear caches
        locks.clear();
        readWriteLocks.clear();

        logger.info("Redis distributed lock factory shutdown completed");
    }
}