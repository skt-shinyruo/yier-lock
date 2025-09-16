package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.curator.framework.CuratorFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

public class ZooKeeperDistributedLockFactory implements DistributedLockFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperDistributedLockFactory.class);
    
    private final CuratorFramework curatorFramework;
    private final LockConfiguration configuration;
    private final LockMetrics metrics;
    private final LockTracing tracing;
    private final ConcurrentHashMap<String, ZooKeeperDistributedLock> locks;
    private final ConcurrentHashMap<String, ZooKeeperDistributedReadWriteLock> readWriteLocks;
    private final String lockBasePath;
    
    public ZooKeeperDistributedLockFactory(CuratorFramework curatorFramework) {
        this(curatorFramework, new LockConfiguration(), null, null);
    }
    
    public ZooKeeperDistributedLockFactory(CuratorFramework curatorFramework,
                                         LockConfiguration configuration,
                                         MeterRegistry meterRegistry,
                                         OpenTelemetry openTelemetry) {
        this.curatorFramework = curatorFramework;
        this.configuration = configuration;
        this.metrics = new LockMetrics(meterRegistry, configuration.isMetricsEnabled());
        this.tracing = new LockTracing(openTelemetry, configuration.isTracingEnabled());
        this.locks = new ConcurrentHashMap<>();
        this.readWriteLocks = new ConcurrentHashMap<>();
        this.lockBasePath = configuration.getConfig().hasPath("distributed-lock.zookeeper.base-path")
            ? configuration.getConfig().getString("distributed-lock.zookeeper.base-path")
            : "/distributed-locks";
        
        logger.info("ZooKeeper distributed lock factory initialized with base path: {}", lockBasePath);
    }
    
    @Override
    public DistributedLock getLock(String name) {
        return locks.computeIfAbsent(name, lockName ->
            new ZooKeeperDistributedLock(
                lockName,
                curatorFramework,
                configuration,
                metrics,
                tracing,
                lockBasePath + "/" + lockName
            )
        );
    }
    
    @Override
    public DistributedReadWriteLock getReadWriteLock(String name) {
        return readWriteLocks.computeIfAbsent(name, lockName ->
            new ZooKeeperDistributedReadWriteLock(
                lockName,
                curatorFramework,
                configuration,
                metrics,
                tracing,
                lockBasePath + "/" + lockName
            )
        );
    }
    
    @Override
    public void shutdown() {
        logger.info("Shutting down ZooKeeper distributed lock factory");
        
        locks.values().forEach(lock -> {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception e) {
                logger.warn("Error releasing lock {} during shutdown", lock.getName(), e);
            }
        });
        
        readWriteLocks.values().forEach(readWriteLock -> {
            try {
                if (readWriteLock.readLock().isHeldByCurrentThread()) {
                    readWriteLock.readLock().unlock();
                }
                if (readWriteLock.writeLock().isHeldByCurrentThread()) {
                    readWriteLock.writeLock().unlock();
                }
            } catch (Exception e) {
                logger.warn("Error releasing read-write lock {} during shutdown", readWriteLock.getName(), e);
            }
        });
        
        locks.clear();
        readWriteLocks.clear();
    }
}