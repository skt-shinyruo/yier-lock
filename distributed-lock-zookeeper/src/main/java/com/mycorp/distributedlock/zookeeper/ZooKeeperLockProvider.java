package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.api.LockProvider;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * ZooKeeper backed {@link LockProvider} that mirrors the initialization pattern of Apache Curator recipes.
 */
public class ZooKeeperLockProvider implements LockProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperLockProvider.class);
    private static final String PROVIDER_TYPE = "zookeeper";
    private static final int PROVIDER_PRIORITY = 150;
    
    private final ConcurrentHashMap<String, ZooKeeperDistributedLock> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ZooKeeperDistributedReadWriteLock> readWriteLocks = new ConcurrentHashMap<>();
    private final Object initializationMonitor = new Object();

    private CuratorFramework curatorFramework;
    private LockConfiguration configuration;
    private LockMetrics metrics;
    private LockTracing tracing;
    private String lockBasePath;

    private volatile boolean initialized = false;
    private volatile boolean shutdown = false;
    private boolean ownsCurator = false;
    
    public ZooKeeperLockProvider() {
        // Lazy initialization path
    }
    
    public ZooKeeperLockProvider(CuratorFramework curatorFramework,
                                 LockConfiguration configuration,
                                 MeterRegistry meterRegistry,
                                 OpenTelemetry openTelemetry) {
        initialize(curatorFramework, configuration, meterRegistry, openTelemetry);
    }

    @Override
    public String getType() {
        return PROVIDER_TYPE;
    }

    @Override
    public int getPriority() {
        return PROVIDER_PRIORITY;
    }
    
    @Override
    public DistributedLock createLock(String key) {
        ensureProviderReady();
        return locks.computeIfAbsent(key, k ->
            new ZooKeeperDistributedLock(
                k,
                curatorFramework,
                configuration,
                metrics,
                tracing,
                lockBasePath + "/" + k
            )
        );
    }
    
    @Override
    public DistributedReadWriteLock createReadWriteLock(String key) {
        ensureProviderReady();
        return readWriteLocks.computeIfAbsent(key, k ->
            new ZooKeeperDistributedReadWriteLock(
                k,
                curatorFramework,
                configuration,
                metrics,
                tracing,
                lockBasePath + "/" + k
            )
        );
    }
    
    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        if (!initialized && shutdown) {
            return;
        }

        synchronized (initializationMonitor) {
            if (shutdown) {
                return;
            }
            shutdown = true;
        }
        
        logger.info("Shutting down ZooKeeperLockProvider");
        
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

        if (ownsCurator && curatorFramework != null) {
            try {
                curatorFramework.close();
            } catch (RuntimeException e) {
                logger.warn("Error closing CuratorFramework", e);
            }
        }

        initialized = false;
        logger.info("ZooKeeperLockProvider shutdown completed");
    }

    private void ensureProviderReady() {
        ensureNotShutdown();
        ensureInitialized();
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialize(null, null, null, null);
    }

    private void initialize(CuratorFramework client,
                            LockConfiguration config,
                            MeterRegistry meterRegistry,
                            OpenTelemetry openTelemetry) {
        if (initialized) {
            return;
        }

        synchronized (initializationMonitor) {
            if (initialized) {
                return;
            }

            this.configuration = config != null ? config : new LockConfiguration();
            this.curatorFramework = client != null ? client : createCuratorFramework(this.configuration);
            this.ownsCurator = client == null;
            this.metrics = new LockMetrics(meterRegistry, this.configuration.isMetricsEnabled());
            this.tracing = new LockTracing(openTelemetry, this.configuration.isTracingEnabled());
            this.lockBasePath = this.configuration.getZookeeperBasePath();

            this.initialized = true;
            this.shutdown = false;

            logger.info("ZooKeeperLockProvider initialized with base path: {}", lockBasePath);
        }
    }

    private CuratorFramework createCuratorFramework(LockConfiguration configuration) {
        ExponentialBackoffRetry retryPolicy = new ExponentialBackoffRetry(
            (int) configuration.getRetryInterval().toMillis(),
            configuration.getMaxRetries());

        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
            .connectString(configuration.getZookeeperConnectString())
            .sessionTimeoutMs((int) configuration.getZookeeperSessionTimeout().toMillis())
            .connectionTimeoutMs((int) configuration.getZookeeperConnectionTimeout().toMillis())
            .retryPolicy(retryPolicy);

        if (configuration.isZookeeperAuthEnabled() && configuration.getZookeeperAuthInfo() != null) {
            builder.authorization(
                configuration.getZookeeperAuthScheme(),
                configuration.getZookeeperAuthInfo().getBytes(StandardCharsets.UTF_8));
        }

        CuratorFramework curator = builder.build();
        curator.start();

        try {
            curator.blockUntilConnected(
                (int) configuration.getZookeeperConnectionTimeout().toMillis(),
                TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for ZooKeeper connection", e);
        }

        return curator;
    }
    
    private void ensureNotShutdown() {
        if (shutdown) {
            throw new IllegalStateException("ZooKeeperLockProvider already shut down.");
        }
    }
}
