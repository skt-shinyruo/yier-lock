package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import com.mycorp.distributedlock.core.threadpool.SharedExecutorService;
import com.mycorp.distributedlock.redis.pubsub.SharedPubSubManager;
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

/**
 * Optimized Redis distributed lock factory using shared resources.
 * 
 * Key improvements:
 * 1. Shared executor service across all locks
 * 2. Shared pub/sub manager for efficient subscription multiplexing
 * 3. Connection pooling with Lettuce best practices
 * 4. Better resource lifecycle management
 * 5. Monitoring and metrics support
 * 
 * Based on Redisson's architecture and Lettuce best practices.
 */
public class OptimizedRedisDistributedLockFactory implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(OptimizedRedisDistributedLockFactory.class);

    private final RedisClient redisClient;
    private final ClientResources clientResources;
    private final StatefulRedisConnection<String, String> connection;
    private final StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private final SharedPubSubManager pubSubManager;
    private final LockConfiguration configuration;
    private final LockMetrics metrics;
    private final LockTracing tracing;
    private final SharedExecutorService executorService;
    private final ConcurrentHashMap<String, DistributedLock> locks;
    private final ConcurrentHashMap<String, RedisDistributedReadWriteLock> readWriteLocks;
    private final boolean ownsClientResources;
    private final boolean ownsRedisClient;

    /**
     * Create factory with existing Redis client.
     */
    public OptimizedRedisDistributedLockFactory(RedisClient redisClient) {
        this(redisClient, new LockConfiguration(), null, null, false);
    }

    /**
     * Create factory with full configuration.
     */
    public OptimizedRedisDistributedLockFactory(
            RedisClient redisClient,
            LockConfiguration configuration,
            MeterRegistry meterRegistry,
            OpenTelemetry openTelemetry,
            boolean ownsRedisClient) {
        this.redisClient = redisClient;
        this.clientResources = redisClient.getResources();
        this.ownsClientResources = false; // RedisClient owns its resources
        this.ownsRedisClient = ownsRedisClient;
        this.connection = redisClient.connect();
        this.pubSubConnection = redisClient.connectPubSub();
        this.pubSubManager = new SharedPubSubManager(pubSubConnection);
        this.configuration = configuration;
        this.metrics = new LockMetrics(meterRegistry, configuration.isMetricsEnabled());
        this.tracing = new LockTracing(openTelemetry, configuration.isTracingEnabled());
        this.executorService = SharedExecutorService.getInstance();
        this.locks = new ConcurrentHashMap<>();
        this.readWriteLocks = new ConcurrentHashMap<>();

        logger.info("Optimized Redis distributed lock factory initialized");
        logResourceUsage();
    }

    /**
     * Create factory with custom client resources for connection pooling.
     */
    public static OptimizedRedisDistributedLockFactory createWithCustomResources(
            String redisUri,
            LockConfiguration configuration,
            MeterRegistry meterRegistry,
            OpenTelemetry openTelemetry) {
        
        // Create optimized client resources
        ClientResources resources = DefaultClientResources.builder()
            .ioThreadPoolSize(4)  // Based on Lettuce recommendations
            .computationThreadPoolSize(4)
            .build();

        RedisClient client = RedisClient.create(resources, redisUri);
        
        OptimizedRedisDistributedLockFactory factory = new OptimizedRedisDistributedLockFactory(
            client, configuration, meterRegistry, openTelemetry, true
        );
        
        return factory;
    }

    /**
     * Get or create a distributed lock.
     */
    public DistributedLock getLock(String name) {
        return locks.computeIfAbsent(name, lockName ->
            new OptimizedRedisDistributedLock(
                lockName,
                connection,
                pubSubManager,
                configuration,
                metrics,
                tracing,
                executorService
            )
        );
    }

    /**
     * Get or create a fair distributed lock.
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
                executorService.getWatchdogExecutor()
            )
        );
    }

    /**
     * Get or create a read-write lock.
     */
    public DistributedReadWriteLock getReadWriteLock(String name) {
        return readWriteLocks.computeIfAbsent(name, lockName ->
            new RedisDistributedReadWriteLock(
                lockName,
                connection,
                pubSubConnection,
                configuration,
                metrics,
                tracing,
                executorService.getWatchdogExecutor()
            )
        );
    }

    /**
     * Remove a lock from the cache (useful for testing or cleanup).
     */
    public void removeLock(String name) {
        locks.remove(name);
        readWriteLocks.remove(name);
    }

    /**
     * Get factory metrics for monitoring.
     */
    public FactoryMetrics getMetrics() {
        FactoryMetrics factoryMetrics = new FactoryMetrics();
        factoryMetrics.totalLocks = locks.size();
        factoryMetrics.totalReadWriteLocks = readWriteLocks.size();
        factoryMetrics.executorMetrics = executorService.getMetrics();
        factoryMetrics.pubSubMetrics = pubSubManager.getMetrics();
        return factoryMetrics;
    }

    @Override
    public void close() {
        shutdown();
    }

    /**
     * Gracefully shutdown the factory and release all resources.
     */
    public void shutdown() {
        logger.info("Shutting down optimized Redis distributed lock factory");

        // Attempt to release locks held by current thread
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
                }
                if (rwLock.writeLock().isHeldByCurrentThread()) {
                    rwLock.writeLock().unlock();
                }
            } catch (Exception e) {
                logger.warn("Error releasing read-write lock {} during shutdown", rwLock.getName(), e);
            }
        });

        // Close pub/sub manager
        try {
            pubSubManager.close();
        } catch (Exception e) {
            logger.warn("Error closing pub/sub manager", e);
        }

        // Close Redis connections
        try {
            pubSubConnection.close();
            connection.close();
        } catch (Exception e) {
            logger.warn("Error closing Redis connections", e);
        }

        // Shutdown Redis client if we own it
        if (ownsRedisClient) {
            try {
                redisClient.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down Redis client", e);
            }
        }

        // Release shared executor service
        executorService.release();

        // Clear caches
        locks.clear();
        readWriteLocks.clear();

        logger.info("Optimized Redis distributed lock factory shutdown completed");
    }

    private void logResourceUsage() {
        if (logger.isDebugEnabled()) {
            logger.debug("Resource usage - Executor: {}, PubSub: {}",
                executorService.getMetrics(),
                pubSubManager.getMetrics());
        }
    }

    /**
     * Factory metrics for monitoring.
     */
    public static class FactoryMetrics {
        public int totalLocks;
        public int totalReadWriteLocks;
        public SharedExecutorService.ExecutorMetrics executorMetrics;
        public SharedPubSubManager.PubSubMetrics pubSubMetrics;

        @Override
        public String toString() {
            return String.format(
                "FactoryMetrics{locks=%d, rwLocks=%d, executor=%s, pubsub=%s}",
                totalLocks, totalReadWriteLocks, executorMetrics, pubSubMetrics
            );
        }
    }
}
