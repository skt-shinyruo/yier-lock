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
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class RedisDistributedLockFactory implements DistributedLockFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(RedisDistributedLockFactory.class);
    
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private final LockConfiguration configuration;
    private final LockMetrics metrics;
    private final LockTracing tracing;
    private final ScheduledExecutorService watchdogExecutor;
    private final ConcurrentHashMap<String, RedisDistributedLock> locks;
    private final ConcurrentHashMap<String, RedisDistributedReadWriteLock> readWriteLocks;
    
    public RedisDistributedLockFactory(RedisClient redisClient) {
        this(redisClient, new LockConfiguration(), null, null);
    }
    
    public RedisDistributedLockFactory(RedisClient redisClient, 
                                     LockConfiguration configuration,
                                     MeterRegistry meterRegistry,
                                     OpenTelemetry openTelemetry) {
        this.redisClient = redisClient;
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
    
    @Override
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
    
    @Override
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
    
    @Override
    public void shutdown() {
        logger.info("Shutting down Redis distributed lock factory");
        
        locks.values().forEach(lock -> {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception e) {
                logger.warn("Error releasing lock {} during shutdown", lock.getName(), e);
            }
        });
        
        if (watchdogExecutor != null) {
            watchdogExecutor.shutdown();
        }
        
        try {
            pubSubConnection.close();
            connection.close();
        } catch (Exception e) {
            logger.warn("Error closing Redis connections", e);
        }
        
        locks.clear();
        readWriteLocks.clear();
    }
}