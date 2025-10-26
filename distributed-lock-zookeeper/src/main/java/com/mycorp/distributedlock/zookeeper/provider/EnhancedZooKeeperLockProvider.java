package com.mycorp.distributedlock.zookeeper.provider;

import com.mycorp.distributedlock.api.*;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import com.mycorp.distributedlock.zookeeper.*;
import com.mycorp.distributedlock.zookeeper.factory.EnhancedZooKeeperDistributedLockFactory;
import com.mycorp.distributedlock.zookeeper.health.ZooKeeperHealthChecker;
import com.mycorp.distributedlock.zookeeper.operation.ZooKeeperBatchLockOperations;
import com.mycorp.distributedlock.zookeeper.operation.ZooKeeperAsyncLockOperations;
import com.mycorp.distributedlock.zookeeper.event.ZooKeeperLockEventManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.curator.framework.CuratorFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Enhanced ZooKeeper-backed {@link LockProvider} with enterprise-grade features.
 * 
 * Features:
 * - High availability cluster management
 * - Batch and async operations
 * - Health monitoring
 * - Event listeners
 * - Advanced performance metrics
 * - Graceful shutdown
 */
public class EnhancedZooKeeperLockProvider implements LockProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(EnhancedZooKeeperLockProvider.class);
    private static final String PROVIDER_TYPE = "zookeeper-enhanced";
    private static final int PROVIDER_PRIORITY = 160;
    private static final Duration DEFAULT_HEALTH_CHECK_INTERVAL = Duration.ofSeconds(30);
    
    private final AtomicReference<ProviderState> state = new AtomicReference<>(ProviderState.UNINITIALIZED);
    
    private CuratorFramework curatorFramework;
    private LockConfiguration configuration;
    private LockMetrics metrics;
    private LockTracing tracing;
    private MeterRegistry meterRegistry;
    private OpenTelemetry openTelemetry;
    private String lockBasePath;
    
    private ZooKeeperConnectionManager connectionManager;
    private ZooKeeperClusterManager clusterManager;
    private EnhancedZooKeeperDistributedLockFactory lockFactory;
    private ZooKeeperHealthChecker healthChecker;
    private ZooKeeperBatchLockOperations batchOperations;
    private ZooKeeperAsyncLockOperations asyncOperations;
    private ZooKeeperLockEventManager eventManager;
    
    private ExecutorService executorService;
    private final Map<String, DistributedLock> lockCache = new ConcurrentHashMap<>();
    private final Map<String, DistributedReadWriteLock> readWriteLockCache = new ConcurrentHashMap<>();
    private final Set<RuntimeException> initializationErrors = Collections.synchronizedSet(new HashSet<>());
    
    // Configuration flags
    private boolean enableBatchOperations = true;
    private boolean enableAsyncOperations = true;
    private boolean enableEventListeners = true;
    private boolean enableHealthMonitoring = true;
    private boolean enableClusterManagement = true;
    private Duration healthCheckInterval = DEFAULT_HEALTH_CHECK_INTERVAL;
    
    public EnhancedZooKeeperLockProvider() {
        // Default constructor for ServiceLoader
    }
    
    public EnhancedZooKeeperLockProvider(CuratorFramework curatorFramework,
                                       LockConfiguration configuration,
                                       MeterRegistry meterRegistry,
                                       OpenTelemetry openTelemetry) {
        this.configuration = configuration;
        this.curatorFramework = curatorFramework;
        this.meterRegistry = meterRegistry;
        this.openTelemetry = openTelemetry;
        initialize();
    }
    
    /**
     * Builder pattern for configuration
     */
    public static class Builder {
        private CuratorFramework curatorFramework;
        private LockConfiguration configuration;
        private MeterRegistry meterRegistry;
        private OpenTelemetry openTelemetry;
        private boolean enableBatchOperations = true;
        private boolean enableAsyncOperations = true;
        private boolean enableEventListeners = true;
        private boolean enableHealthMonitoring = true;
        private boolean enableClusterManagement = true;
        private Duration healthCheckInterval = DEFAULT_HEALTH_CHECK_INTERVAL;
        
        public Builder curatorFramework(CuratorFramework curatorFramework) {
            this.curatorFramework = curatorFramework;
            return this;
        }
        
        public Builder configuration(LockConfiguration configuration) {
            this.configuration = configuration;
            return this;
        }
        
        public Builder meterRegistry(MeterRegistry meterRegistry) {
            this.meterRegistry = meterRegistry;
            return this;
        }
        
        public Builder openTelemetry(OpenTelemetry openTelemetry) {
            this.openTelemetry = openTelemetry;
            return this;
        }
        
        public Builder enableBatchOperations(boolean enable) {
            this.enableBatchOperations = enable;
            return this;
        }
        
        public Builder enableAsyncOperations(boolean enable) {
            this.enableAsyncOperations = enable;
            return this;
        }
        
        public Builder enableEventListeners(boolean enable) {
            this.enableEventListeners = enable;
            return this;
        }
        
        public Builder enableHealthMonitoring(boolean enable) {
            this.enableHealthMonitoring = enable;
            return this;
        }
        
        public Builder enableClusterManagement(boolean enable) {
            this.enableClusterManagement = enable;
            return this;
        }
        
        public Builder healthCheckInterval(Duration interval) {
            this.healthCheckInterval = interval;
            return this;
        }
        
        public EnhancedZooKeeperLockProvider build() {
            EnhancedZooKeeperLockProvider provider = new EnhancedZooKeeperLockProvider();
            provider.curatorFramework = this.curatorFramework;
            provider.configuration = this.configuration;
            provider.meterRegistry = this.meterRegistry;
            provider.openTelemetry = this.openTelemetry;
            provider.enableBatchOperations = this.enableBatchOperations;
            provider.enableAsyncOperations = this.enableAsyncOperations;
            provider.enableEventListeners = this.enableEventListeners;
            provider.enableHealthMonitoring = this.enableHealthMonitoring;
            provider.enableClusterManagement = this.enableClusterManagement;
            provider.healthCheckInterval = this.healthCheckInterval;
            return provider;
        }
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
        
        return lockCache.computeIfAbsent(key, k -> {
            if (configuration.isPerformanceOptimizationEnabled()) {
                return lockFactory.createOptimizedLock(k);
            } else {
                return lockFactory.createLock(k);
            }
        });
    }
    
    @Override
    public DistributedReadWriteLock createReadWriteLock(String key) {
        ensureProviderReady();
        
        return readWriteLockCache.computeIfAbsent(key, k -> {
            if (configuration.isPerformanceOptimizationEnabled()) {
                return lockFactory.createOptimizedReadWriteLock(k);
            } else {
                return lockFactory.createReadWriteLock(k);
            }
        });
    }
    
    @Override
    public void close() {
        shutdown();
    }
    
    @Override
    public void shutdown() {
        ProviderState currentState = state.get();
        if (currentState == ProviderState.SHUTDOWN || currentState == ProviderState.SHUTTING_DOWN) {
            return;
        }
        
        if (state.compareAndSet(currentState, ProviderState.SHUTTING_DOWN)) {
            logger.info("Starting ZooKeeperLockProvider shutdown");
            
            try {
                // Release all held locks
                releaseAllLocks();
                
                // Stop background services
                stopBackgroundServices();
                
                // Close event manager
                if (eventManager != null) {
                    eventManager.close();
                }
                
                // Stop health monitoring
                if (healthChecker != null) {
                    healthChecker.shutdown();
                }
                
                // Close executor service
                if (executorService != null && !executorService.isShutdown()) {
                    executorService.shutdown();
                    if (!executorService.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                        logger.warn("Executor service did not shutdown cleanly, forcing termination");
                        executorService.shutdownNow();
                    }
                }
                
                // Close curator framework if we own it
                if (curatorFramework != null) {
                    curatorFramework.close();
                }
                
                state.set(ProviderState.SHUTDOWN);
                logger.info("ZooKeeperLockProvider shutdown completed");
                
            } catch (Exception e) {
                logger.error("Error during ZooKeeperLockProvider shutdown", e);
                state.set(ProviderState.ERROR);
            }
        }
    }
    
    // Enhanced features
    
    /**
     * Get batch operations interface
     */
    public BatchLockOperations getBatchOperations() {
        ensureProviderReady();
        return enableBatchOperations ? batchOperations : null;
    }
    
    /**
     * Get async operations interface
     */
    public AsyncLockOperations getAsyncOperations() {
        ensureProviderReady();
        return enableAsyncOperations ? asyncOperations : null;
    }
    
    /**
     * Get event manager
     */
    public ZooKeeperLockEventManager getEventManager() {
        ensureProviderReady();
        return enableEventListeners ? eventManager : null;
    }
    
    /**
     * Get health checker
     */
    public ZooKeeperHealthChecker getHealthChecker() {
        ensureProviderReady();
        return enableHealthMonitoring ? healthChecker : null;
    }
    
    /**
     * Get cluster manager
     */
    public ZooKeeperClusterManager getClusterManager() {
        ensureProviderReady();
        return enableClusterManagement ? clusterManager : null;
    }
    
    /**
     * Perform health check
     */
    public HealthCheck.HealthCheckResult performHealthCheck() {
        ensureProviderReady();
        return enableHealthMonitoring ? healthChecker.checkHealth() : 
            new HealthCheck.HealthCheckResult(true, "Health monitoring disabled", null);
    }
    
    /**
     * Get provider information about current state
     */
    public ProviderInfo getProviderInfo() {
        return new ProviderInfo(
            getType(),
            getPriority(),
            state.get(),
            configuration != null,
            enableBatchOperations,
            enableAsyncOperations,
            enableEventListeners,
            enableHealthMonitoring,
            enableClusterManagement,
            initializationErrors.isEmpty(),
            getInitializationErrors()
        );
    }
    
    /**
     * Get current operational statistics
     */
    public ProviderStatistics getStatistics() {
        ensureProviderReady();
        return new ProviderStatistics(
            lockCache.size(),
            readWriteLockCache.size(),
            clusterManager != null && enableClusterManagement ?
                clusterManager.getAvailableNodesCount() : 0,
            enableHealthMonitoring && healthChecker != null ?
                healthChecker.getLastHealthCheckTimestamp() : null,
            getOperationalMetrics()
        );
    }
    
    private void initialize() {
        if (state.get() != ProviderState.UNINITIALIZED) {
            return;
        }
        
        try {
            logger.info("Initializing Enhanced ZooKeeperLockProvider");
            
            // Validate preconditions
            this.configuration = configuration != null ? configuration : new LockConfiguration();
            this.lockBasePath = configuration.getZookeeperBasePath();
            
            // Create executor service for background tasks
            this.executorService = createExecutorService();
            
            // Initialize core components
            initializeConnectionManager();
            initializeClusterManager();
            initializeLockFactory();
            initializeHealthMonitoring();
            initializeEventManager();
            initializeOperations();
            
            state.set(ProviderState.INITIALIZED);
            logger.info("Enhanced ZooKeeperLockProvider initialized successfully with base path: {}", lockBasePath);
            
        } catch (Exception e) {
            initializationErrors.add(e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e));
            state.set(ProviderState.ERROR);
            logger.error("Failed to initialize Enhanced ZooKeeperLockProvider", e);
            throw e;
        }
    }
    
    private void initializeConnectionManager() {
        this.connectionManager = new ZooKeeperConnectionManager(curatorFramework, configuration, meterRegistry);
        if (curatorFramework == null) {
            curatorFramework = connectionManager.createCuratorFramework();
        }
    }
    
    private void initializeClusterManager() {
        if (enableClusterManagement) {
            this.clusterManager = new ZooKeeperClusterManager(
                curatorFramework,
                configuration,
                meterRegistry
            );
            this.clusterManager.initialize();
        }
    }
    
    private void initializeLockFactory() {
        this.lockFactory = new EnhancedZooKeeperDistributedLockFactory(
            curatorFramework,
            connectionManager,
            clusterManager,
            configuration,
            metrics,
            tracing,
            meterRegistry
        );
    }
    
    private void initializeHealthMonitoring() {
        if (enableHealthMonitoring) {
            this.healthChecker = new ZooKeeperHealthChecker(
                curatorFramework,
                connectionManager,
                clusterManager,
                lockFactory,
                configuration,
                meterRegistry
            );
            this.healthChecker.start(healthCheckInterval);
        }
    }
    
    private void initializeEventManager() {
        if (enableEventListeners) {
            this.eventManager = new ZooKeeperLockEventManager(
                curatorFramework,
                connectionManager,
                configuration,
                meterRegistry
            );
            this.eventManager.initialize();
        }
    }
    
    private void initializeOperations() {
        if (enableBatchOperations) {
            this.batchOperations = new ZooKeeperBatchLockOperations(
                curatorFramework,
                lockFactory,
                connectionManager,
                configuration,
                meterRegistry
            );
        }
        
        if (enableAsyncOperations) {
            this.asyncOperations = new ZooKeeperAsyncLockOperations(
                curatorFramework,
                lockFactory,
                connectionManager,
                configuration,
                meterRegistry,
                executorService
            );
        }
    }
    
    private void ensureProviderReady() {
        if (state.get() == ProviderState.UNINITIALIZED) {
            initialize();
        }
        
        if (state.get() == ProviderState.ERROR) {
            throw new IllegalStateException("Provider is in error state. Check initialization errors: " + 
                getInitializationErrors());
        }
        
        if (state.get() == ProviderState.SHUTDOWN || state.get() == ProviderState.SHUTTING_DOWN) {
            throw new IllegalStateException("Provider has been shut down");
        }
        
        // Validate core components are available
        if (curatorFramework == null || configuration == null) {
            throw new IllegalStateException("Provider not properly initialized");
        }
    }
    
    private void releaseAllLocks() {
        logger.info("Releasing all locks before shutdown");
        
        // Release regular locks
        for (DistributedLock lock : lockCache.values()) {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception e) {
                logger.warn("Error releasing lock {} during shutdown", lock.getName(), e);
            }
        }
        
        // Release read-write locks
        for (DistributedReadWriteLock readWriteLock : readWriteLockCache.values()) {
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
        }
        
        // Clear caches
        lockCache.clear();
        readWriteLockCache.clear();
    }
    
    private void stopBackgroundServices() {
        if (healthChecker != null) {
            healthChecker.shutdown();
        }
        
        if (clusterManager != null) {
            clusterManager.shutdown();
        }
    }
    
    private ExecutorService createExecutorService() {
        return Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "ZooKeeperLockProvider-Background");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) -> 
                logger.error("Uncaught exception in background thread {}", t.getName(), e));
            return thread;
        });
    }
    
    private List<String> getInitializationErrors() {
        return initializationErrors.stream()
            .map(Throwable::getMessage)
            .collect(Collectors.toList());
    }
    
    private Map<String, Object> getOperationalMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        if (enableHealthMonitoring && healthChecker != null) {
            metrics.put("lastHealthCheck", healthChecker.getLastHealthCheckTimestamp());
            metrics.put("healthStatus", healthChecker.getCurrentHealthStatus().toString());
        }
        
        if (enableClusterManagement && clusterManager != null) {
            metrics.put("availableNodes", clusterManager.getAvailableNodesCount());
            metrics.put("activeConnections", clusterManager.getActiveConnectionsCount());
        }
        
        if (enableBatchOperations && batchOperations != null) {
            metrics.put("batchOperationsCount", batchOperations.getOperationCount());
        }
        
        if (enableAsyncOperations && asyncOperations != null) {
            metrics.put("asyncOperationsCount", asyncOperations.getOperationCount());
        }
        
        return metrics;
    }
    
    // Provider state enum
    private enum ProviderState {
        UNINITIALIZED,
        INITIALIZING,
        INITIALIZED,
        SHUTTING_DOWN,
        SHUTDOWN,
        ERROR
    }
    
    // Provider information record
    public record ProviderInfo(
        String type,
        int priority,
        ProviderState state,
        boolean configured,
        boolean batchOperationsEnabled,
        boolean asyncOperationsEnabled,
        boolean eventListenersEnabled,
        boolean healthMonitoringEnabled,
        boolean clusterManagementEnabled,
        boolean initializedSuccessfully,
        List<String> initializationErrors
    ) {
    }
    
    // Provider statistics record
    public record ProviderStatistics(
        int activeLocks,
        int activeReadWriteLocks,
        int availableClusterNodes,
        java.time.Instant lastHealthCheck,
        Map<String, Object> operationalMetrics
    ) {
    }
}