package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import com.mycorp.distributedlock.core.threadpool.SharedExecutorService;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Optimized ZooKeeper distributed lock factory.
 * 
 * Key improvements:
 * 1. Shared executor service for async operations
 * 2. Connection state monitoring and auto-recovery
 * 3. Proper curator configuration for production use
 * 4. Namespace isolation for multi-tenant scenarios
 * 5. Enhanced monitoring and metrics
 * 
 * Based on Apache Curator best practices and Netflix/LinkedIn production patterns.
 */
public class OptimizedZooKeeperDistributedLockFactory implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(OptimizedZooKeeperDistributedLockFactory.class);

    private final CuratorFramework curator;
    private final LockConfiguration configuration;
    private final LockMetrics metrics;
    private final LockTracing tracing;
    private final SharedExecutorService executorService;
    private final ConcurrentHashMap<String, DistributedLock> locks;
    private final ConcurrentHashMap<String, ZooKeeperDistributedReadWriteLock> readWriteLocks;
    private final boolean ownsCurator;
    private final ConnectionStateMonitor connectionMonitor;

    /**
     * Create factory with existing Curator instance.
     */
    public OptimizedZooKeeperDistributedLockFactory(CuratorFramework curator) {
        this(curator, new LockConfiguration(), null, null, false);
    }

    /**
     * Create factory with full configuration.
     */
    public OptimizedZooKeeperDistributedLockFactory(
            CuratorFramework curator,
            LockConfiguration configuration,
            MeterRegistry meterRegistry,
            OpenTelemetry openTelemetry,
            boolean ownsCurator) {
        this.curator = curator;
        this.configuration = configuration;
        this.metrics = new LockMetrics(meterRegistry, configuration.isMetricsEnabled());
        this.tracing = new LockTracing(openTelemetry, configuration.isTracingEnabled());
        this.executorService = SharedExecutorService.getInstance();
        this.locks = new ConcurrentHashMap<>();
        this.readWriteLocks = new ConcurrentHashMap<>();
        this.ownsCurator = ownsCurator;
        this.connectionMonitor = new ConnectionStateMonitor();

        // Start curator if we own it
        if (ownsCurator && curator.getState() != org.apache.curator.framework.imps.CuratorFrameworkState.STARTED) {
            curator.start();
            try {
                if (!curator.blockUntilConnected(
                        (int) configuration.getZookeeperConnectionTimeout().toMillis(),
                        TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("Failed to connect to ZooKeeper within timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while connecting to ZooKeeper", e);
            }
        }

        // Add connection state listener
        curator.getConnectionStateListenable().addListener(connectionMonitor);

        logger.info("Optimized ZooKeeper distributed lock factory initialized (connected: {})",
            curator.getZookeeperClient().isConnected());
    }

    /**
     * Create factory with optimized Curator configuration.
     */
    public static OptimizedZooKeeperDistributedLockFactory createWithOptimizedConfig(
            String connectString,
            String namespace,
            LockConfiguration configuration,
            MeterRegistry meterRegistry,
            OpenTelemetry openTelemetry) {

        // Build curator with production-ready settings
        CuratorFramework curator = CuratorFrameworkFactory.builder()
            .connectString(connectString)
            .namespace(namespace)
            .sessionTimeoutMs((int) configuration.getZookeeperSessionTimeout().toMillis())
            .connectionTimeoutMs((int) configuration.getZookeeperConnectionTimeout().toMillis())
            .retryPolicy(new ExponentialBackoffRetry(
                (int) configuration.getRetryInterval().toMillis(),
                configuration.getMaxRetries(),
                (int) Duration.ofSeconds(30).toMillis()
            ))
            // Connection management settings
            .connectionTimeoutMs(15000)
            .sessionTimeoutMs(60000)
            // Performance tuning
            .compressionProvider(new org.apache.curator.framework.imps.GzipCompressionProvider())
            .build();

        return new OptimizedZooKeeperDistributedLockFactory(
            curator, configuration, meterRegistry, openTelemetry, true
        );
    }

    /**
     * Get or create a distributed lock.
     */
    public DistributedLock getLock(String name) {
        String lockPath = buildLockPath(name);
        return locks.computeIfAbsent(name, lockName ->
            new OptimizedZooKeeperDistributedLock(
                lockName,
                curator,
                configuration,
                metrics,
                tracing,
                executorService,
                lockPath
            )
        );
    }

    /**
     * Get or create a read-write lock.
     */
    public DistributedReadWriteLock getReadWriteLock(String name) {
        String lockPath = buildLockPath(name);
        return readWriteLocks.computeIfAbsent(name, lockName ->
            new ZooKeeperDistributedReadWriteLock(
                lockName,
                curator,
                configuration,
                metrics,
                tracing,
                lockPath
            )
        );
    }

    /**
     * Remove a lock from the cache.
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
        factoryMetrics.connected = curator.getZookeeperClient().isConnected();
        factoryMetrics.connectionState = connectionMonitor.getCurrentState();
        return factoryMetrics;
    }

    /**
     * Check if connection to ZooKeeper is active.
     */
    public boolean isConnected() {
        return curator.getZookeeperClient().isConnected();
    }

    /**
     * Wait for connection to be established.
     */
    public boolean waitForConnection(long timeout, TimeUnit unit) throws InterruptedException {
        return curator.blockUntilConnected((int) unit.toMillis(timeout), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        shutdown();
    }

    /**
     * Gracefully shutdown the factory and release all resources.
     */
    public void shutdown() {
        logger.info("Shutting down optimized ZooKeeper distributed lock factory");

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

        // Remove connection listener
        curator.getConnectionStateListenable().removeListener(connectionMonitor);

        // Close curator if we own it
        if (ownsCurator) {
            try {
                curator.close();
            } catch (Exception e) {
                logger.warn("Error closing Curator framework", e);
            }
        }

        // Release shared executor service
        executorService.release();

        // Clear caches
        locks.clear();
        readWriteLocks.clear();

        logger.info("Optimized ZooKeeper distributed lock factory shutdown completed");
    }

    private String buildLockPath(String name) {
        String basePath = configuration.getZookeeperBasePath();
        if (!basePath.endsWith("/")) {
            basePath += "/";
        }
        return basePath + name;
    }

    /**
     * Monitor ZooKeeper connection state for observability.
     */
    private class ConnectionStateMonitor implements ConnectionStateListener {
        private volatile ConnectionState currentState = ConnectionState.CONNECTED;

        @Override
        public void stateChanged(CuratorFramework client, ConnectionState newState) {
            ConnectionState oldState = currentState;
            currentState = newState;
            
            logger.info("ZooKeeper connection state changed: {} -> {}", oldState, newState);

            switch (newState) {
                case CONNECTED:
                    logger.info("Connected to ZooKeeper");
                    break;
                case RECONNECTED:
                    logger.warn("Reconnected to ZooKeeper after connection loss");
                    break;
                case SUSPENDED:
                    logger.warn("ZooKeeper connection suspended - locks may expire");
                    break;
                case LOST:
                    logger.error("ZooKeeper connection lost - all ephemeral locks will be released");
                    break;
                case READ_ONLY:
                    logger.warn("ZooKeeper in read-only mode");
                    break;
            }
        }

        public ConnectionState getCurrentState() {
            return currentState;
        }
    }

    /**
     * Factory metrics for monitoring.
     */
    public static class FactoryMetrics {
        public int totalLocks;
        public int totalReadWriteLocks;
        public SharedExecutorService.ExecutorMetrics executorMetrics;
        public boolean connected;
        public ConnectionState connectionState;

        @Override
        public String toString() {
            return String.format(
                "FactoryMetrics{locks=%d, rwLocks=%d, executor=%s, connected=%s, state=%s}",
                totalLocks, totalReadWriteLocks, executorMetrics, connected, connectionState
            );
        }
    }
}
