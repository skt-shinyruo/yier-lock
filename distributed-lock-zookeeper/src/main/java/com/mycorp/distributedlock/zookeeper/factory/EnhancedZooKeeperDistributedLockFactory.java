package com.mycorp.distributedlock.zookeeper.factory;

import com.mycorp.distributedlock.api.*;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import com.mycorp.distributedlock.zookeeper.connection.ZooKeeperConnectionManager;
import com.mycorp.distributedlock.zookeeper.cluster.ZooKeeperClusterManager;
import com.mycorp.distributedlock.zookeeper.event.ZooKeeperLockEventManager;
import com.mycorp.distributedlock.zookeeper.ha.ZooKeeperHighAvailabilityStrategy;
import com.mycorp.distributedlock.zookeeper.lock.OptimizedZooKeeperDistributedLock;
import com.mycorp.distributedlock.zookeeper.lock.OptimizedZooKeeperDistributedReadWriteLock;
import com.mycorp.distributedlock.zookeeper.operation.ZooKeeperBatchLockOperations;
import com.mycorp.distributedlock.zookeeper.operation.ZooKeeperAsyncLockOperations;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 增强的Zookeeper分布式锁工厂
 * 
 * 主要功能：
 * - 统一管理和协调所有Zookeeper分布式锁组件
 * - 提供高性能的锁创建和管理
 * - 集成批量操作、异步操作、事件监听等高级特性
 * - 支持高可用策略和自动故障转移
 * - 提供完整的生命周期管理和资源清理
 * 
 * 核心组件：
 * - ZooKeeperConnectionManager: 连接池和会话管理
 * - ZooKeeperClusterManager: 集群管理和高可用
 * - ZooKeeperLockEventManager: 事件监听和管理
 * - ZooKeeperHighAvailabilityStrategy: 高可用策略
 * - ZooKeeperBatchLockOperations: 批量锁操作
 * - ZooKeeperAsyncLockOperations: 异步锁操作
 * 
 * 特性：
 * - 组件自动初始化和协调
 * - 资源管理和生命周期控制
 * - 配置验证和错误处理
 * - 性能监控和指标收集
 * - 优雅关闭和清理
 */
public class EnhancedZooKeeperDistributedLockFactory implements DistributedLockFactory, AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(EnhancedZooKeeperDistributedLockFactory.class);
    
    // 组件管理
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final AtomicInteger referenceCount = new AtomicInteger(0);
    
    // 核心配置
    private final LockConfiguration configuration;
    private final MeterRegistry meterRegistry;
    private final OpenTelemetry openTelemetry;
    
    // 核心组件
    private ZooKeeperConnectionManager connectionManager;
    private ZooKeeperClusterManager clusterManager;
    private ZooKeeperLockEventManager eventManager;
    private ZooKeeperHighAvailabilityStrategy highAvailabilityStrategy;
    private ZooKeeperBatchLockOperations batchOperations;
    private ZooKeeperAsyncLockOperations asyncOperations;
    
    // 锁缓存管理
    private final Map<String, OptimizedZooKeeperDistributedLock> lockCache = new ConcurrentHashMap<>();
    private final Map<String, OptimizedZooKeeperDistributedReadWriteLock> readWriteLockCache = new ConcurrentHashMap<>();
    
    // 生命周期管理
    private final AtomicLong initializationTime = new AtomicLong(0);
    private final AtomicLong shutdownTime = new AtomicLong(0);
    
    // 统计信息
    private final AtomicLong totalLocksCreated = new AtomicLong(0);
    private final AtomicLong totalReadWriteLocksCreated = new AtomicLong(0);
    private final AtomicLong totalLocksDestroyed = new AtomicLong(0);
    
    // 性能指标
    private final LockMetrics metrics;
    private final LockTracing tracing;
    
    // 事件监听
    private final java.util.List<FactoryEventListener> eventListeners = new CopyOnWriteArrayList<>();
    
    /**
     * 构造函数
     * 
     * @param configuration 锁配置
     * @param meterRegistry 指标注册器
     * @param openTelemetry 分布式追踪
     */
    public EnhancedZooKeeperDistributedLockFactory(LockConfiguration configuration,
                                                 MeterRegistry meterRegistry,
                                                 OpenTelemetry openTelemetry) {
        this.configuration = configuration != null ? configuration : new LockConfiguration();
        this.meterRegistry = meterRegistry;
        this.openTelemetry = openTelemetry;
        
        // 初始化指标和追踪
        this.metrics = new LockMetrics(meterRegistry, this.configuration.isMetricsEnabled());
        this.tracing = new LockTracing(openTelemetry, this.configuration.isTracingEnabled());
        
        logger.debug("EnhancedZooKeeperDistributedLockFactory created");
    }
    
    /**
     * 初始化工厂
     * 
     * @param connectString Zookeeper连接字符串
     * @param nodeId 节点ID（可选）
     * @param nodeAddress 节点地址（可选）
     */
    public synchronized void initialize(String connectString, String nodeId, String nodeAddress) {
        if (isInitialized.get()) {
            logger.warn("Factory is already initialized");
            return;
        }
        
        logger.info("Initializing EnhancedZooKeeperDistributedLockFactory");
        
        try {
            // 验证配置
            validateConfiguration();
            
            // 初始化核心组件
            initializeComponents(connectString, nodeId, nodeAddress);
            
            // 组件间协调
            coordinateComponents();
            
            // 启动事件管理
            eventManager.start();
            
            // 初始化高可用策略
            highAvailabilityStrategy.initialize();
            
            // 验证所有组件
            validateAllComponents();
            
            isInitialized.set(true);
            initializationTime.set(System.currentTimeMillis());
            
            logger.info("EnhancedZooKeeperDistributedLockFactory initialized successfully");
            notifyEvent(FactoryEventType.INITIALIZED, null, "Factory initialized successfully");
            
        } catch (Exception e) {
            logger.error("Failed to initialize factory", e);
            isInitialized.set(false);
            notifyEvent(FactoryEventType.INITIALIZATION_FAILED, e, "Factory initialization failed: " + e.getMessage());
            throw new RuntimeException("Failed to initialize ZooKeeper lock factory", e);
        }
    }
    
    @Override
    public DistributedLock createLock(String key) {
        ensureInitialized();
        ensureNotShutdown();
        
        try (var spanContext = tracing.startLockAcquisitionSpan(key, "createLock")) {
            try {
                OptimizedZooKeeperDistributedLock lock = lockCache.computeIfAbsent(key, lockName -> {
                    String lockPath = configuration.getZookeeperBasePath() + "/" + lockName;
                    
                    OptimizedZooKeeperDistributedLock newLock = new OptimizedZooKeeperDistributedLock(
                        lockName, lockPath, connectionManager, clusterManager,
                        configuration, metrics, tracing
                    );
                    
                    totalLocksCreated.incrementAndGet();
                    
                    // 添加事件监听
                    if (eventManager != null) {
                        eventManager.registerLockEventListener(lockName, new LockEventListener<OptimizedZooKeeperDistributedLock>() {
                            @Override
                            public void onLockAcquired(OptimizedZooKeeperDistributedLock lock, Instant eventTime, LockEventMetadata metadata) {
                                logger.debug("Lock acquired event: {}", lock.getName());
                            }
                            
                            @Override
                            public void onLockReleased(OptimizedZooKeeperDistributedLock lock, Instant eventTime, LockEventMetadata metadata) {
                                logger.debug("Lock released event: {}", lock.getName());
                            }
                            
                            @Override
                            public void onEventAsync(LockEvent<OptimizedZooKeeperDistributedLock> event) {
                                return eventManager.publishLockEvent(event);
                            }
                        }, 0);
                    }
                    
                    logger.debug("Created new lock: {}", lockName);
                    return newLock;
                });
                
                spanContext.setStatus("success");
                return lock;
                
            } catch (Exception e) {
                spanContext.setError(e);
                logger.error("Failed to create lock: {}", key, e);
                throw new RuntimeException("Failed to create lock: " + key, e);
            }
        }
    }
    
    @Override
    public DistributedReadWriteLock createReadWriteLock(String key) {
        ensureInitialized();
        ensureNotShutdown();
        
        try (var spanContext = tracing.startLockAcquisitionSpan(key, "createReadWriteLock")) {
            try {
                OptimizedZooKeeperDistributedReadWriteLock readWriteLock = readWriteLockCache.computeIfAbsent(key, lockName -> {
                    OptimizedZooKeeperDistributedReadWriteLock newReadWriteLock = new OptimizedZooKeeperDistributedReadWriteLock(
                        lockName, connectionManager, clusterManager,
                        configuration, metrics, tracing
                    );
                    
                    totalReadWriteLocksCreated.incrementAndGet();
                    
                    logger.debug("Created new read-write lock: {}", lockName);
                    return newReadWriteLock;
                });
                
                spanContext.setStatus("success");
                return readWriteLock;
                
            } catch (Exception e) {
                spanContext.setError(e);
                logger.error("Failed to create read-write lock: {}", key, e);
                throw new RuntimeException("Failed to create read-write lock: " + key, e);
            }
        }
    }
    
    /**
     * 获取批量锁操作实例
     * 
     * @return 批量锁操作实例
     */
    public ZooKeeperBatchLockOperations getBatchLockOperations() {
        ensureInitialized();
        return batchOperations;
    }
    
    /**
     * 获取异步锁操作实例
     * 
     * @return 异步锁操作实例
     */
    public ZooKeeperAsyncLockOperations getAsyncLockOperations() {
        ensureInitialized();
        return asyncOperations;
    }
    
    /**
     * 获取高可用策略实例
     * 
     * @return 高可用策略实例
     */
    public ZooKeeperHighAvailabilityStrategy getHighAvailabilityStrategy() {
        ensureInitialized();
        return highAvailabilityStrategy;
    }
    
    /**
     * 获取事件管理器实例
     * 
     * @return 事件管理器实例
     */
    public ZooKeeperLockEventManager getEventManager() {
        ensureInitialized();
        return eventManager;
    }
    
    /**
     * 获取集群管理器实例
     * 
     * @return 集群管理器实例
     */
    public ZooKeeperClusterManager getClusterManager() {
        ensureInitialized();
        return clusterManager;
    }
    
    /**
     * 获取连接管理器实例
     * 
     * @return 连接管理器实例
     */
    public ZooKeeperConnectionManager getConnectionManager() {
        ensureInitialized();
        return connectionManager;
    }
    
    /**
     * 获取工厂健康状态
     * 
     * @return 健康状态信息
     */
    public FactoryHealthStatus getHealthStatus() {
        ensureInitialized();
        
        try {
            // 检查各个组件的健康状态
            boolean connectionHealthy = connectionManager != null;
            boolean clusterHealthy = clusterManager != null;
            boolean eventManagerHealthy = eventManager != null;
            boolean haStrategyHealthy = highAvailabilityStrategy != null;
            boolean batchOpsHealthy = batchOperations != null;
            boolean asyncOpsHealthy = asyncOperations != null;
            
            // 整体健康状态
            FactoryHealthStatus.HealthLevel overallHealth = calculateOverallHealth(
                connectionHealthy, clusterHealthy, eventManagerHealthy,
                haStrategyHealthy, batchOpsHealthy, asyncOpsHealthy
            );
            
            // 性能指标
            FactoryHealthStatus.PerformanceMetrics performanceMetrics = collectPerformanceMetrics();
            
            return new FactoryHealthStatus(
                overallHealth,
                isInitialized.get(),
                connectionHealthy,
                clusterHealthy,
                eventManagerHealthy,
                haStrategyHealthy,
                batchOpsHealthy,
                asyncOpsHealthy,
                Instant.ofEpochMilli(initializationTime.get()),
                Instant.now(),
                performanceMetrics
            );
            
        } catch (Exception e) {
            logger.error("Failed to get health status", e);
            return FactoryHealthStatus.unhealthy("Failed to collect health status: " + e.getMessage());
        }
    }
    
    /**
     * 获取工厂统计信息
     * 
     * @return 统计信息
     */
    public FactoryStatistics getStatistics() {
        return new FactoryStatistics(
            totalLocksCreated.get(),
            totalReadWriteLocksCreated.get(),
            totalLocksDestroyed.get(),
            lockCache.size(),
            readWriteLockCache.size(),
            isInitialized.get(),
            Instant.ofEpochMilli(initializationTime.get()),
            Instant.now()
        );
    }
    
    /**
     * 添加工厂事件监听器
     * 
     * @param listener 监听器
     */
    public void addFactoryEventListener(FactoryEventListener listener) {
        if (listener != null) {
            eventListeners.add(listener);
        }
    }
    
    /**
     * 移除工厂事件监听器
     * 
     * @param listener 监听器
     */
    public void removeFactoryEventListener(FactoryEventListener listener) {
        eventListeners.remove(listener);
    }
    
    /**
     * 执行健康检查
     * 
     * @return 健康检查结果
     */
    public FactoryHealthCheckResult performHealthCheck() {
        ensureInitialized();
        
        Instant startTime = Instant.now();
        
        try {
            // 检查连接健康
            ZooKeeperConnectionManager.ConnectionHealthResult connectionHealth = 
                connectionManager.performHealthCheck();
            
            // 检查集群健康
            ZooKeeperClusterManager.ClusterState clusterState = clusterManager.getClusterState();
            
            // 检查高可用策略
            boolean haStrategyHealthy = highAvailabilityStrategy.isEnabled();
            
            // 计算整体状态
            FactoryHealthStatus.HealthLevel overallHealth = calculateHealthLevel(
                connectionHealth, clusterState, haStrategyHealthy
            );
            
            Duration checkDuration = Duration.between(startTime, Instant.now());
            
            return new FactoryHealthCheckResult(
                overallHealth == FactoryHealthStatus.HealthLevel.HEALTHY,
                overallHealth,
                checkDuration.toMillis(),
                String.format("Connection: %s, Cluster: %d/%d nodes healthy, HA: %s",
                    connectionHealth.getStatus(),
                    clusterState.getHealthyNodes(),
                    clusterState.getTotalNodes(),
                    haStrategyHealthy ? "enabled" : "disabled"
                ),
                checkDuration,
                null
            );
            
        } catch (Exception e) {
            logger.error("Health check failed", e);
            
            return new FactoryHealthCheckResult(
                false,
                FactoryHealthStatus.HealthLevel.UNHEALTHY,
                Duration.between(startTime, Instant.now()).toMillis(),
                "Health check failed: " + e.getMessage(),
                Duration.between(startTime, Instant.now()),
                e.getMessage()
            );
        }
    }
    
    /**
     * 清理所有缓存的锁实例
     */
    public void clearLockCache() {
        ensureInitialized();
        
        int clearedLocks = lockCache.size();
        int clearedReadWriteLocks = readWriteLockCache.size();
        
        lockCache.clear();
        readWriteLockCache.clear();
        
        totalLocksDestroyed.addAndGet(clearedLocks + clearedReadWriteLocks);
        
        logger.info("Cleared lock cache: {} locks, {} read-write locks", 
                   clearedLocks, clearedReadWriteLocks);
        
        notifyEvent(FactoryEventType.CACHE_CLEARED, null, 
                   String.format("Cleared cache: %d locks, %d read-write locks", 
                               clearedLocks, clearedReadWriteLocks));
    }
    
    /**
     * 强制重置工厂（仅用于特殊情况）
     */
    public void forceReset() {
        logger.warn("Force resetting factory");
        
        shutdown();
        clearLockCache();
        
        isInitialized.set(false);
        initializationTime.set(0);
        
        logger.info("Factory force reset completed");
        notifyEvent(FactoryEventType.FORCE_RESET, null, "Factory force reset completed");
    }
    
    /**
     * 获取引用计数
     * 
     * @return 当前引用计数
     */
    public int getReferenceCount() {
        return referenceCount.get();
    }
    
    /**
     * 增加引用计数
     * 
     * @return 新的引用计数
     */
    public int incrementReferenceCount() {
        return referenceCount.incrementAndGet();
    }
    
    /**
     * 减少引用计数
     * 
     * @return 新的引用计数
     */
    public int decrementReferenceCount() {
        return referenceCount.decrementAndGet();
    }
    
    @Override
    public void close() {
        if (isShutdown.get()) {
            return;
        }
        
        logger.info("Shutting down EnhancedZooKeeperDistributedLockFactory");
        
        try {
            shutdown();
            logger.info("EnhancedZooKeeperDistributedLockFactory shutdown completed");
        } catch (Exception e) {
            logger.error("Error during factory shutdown", e);
        }
    }
    
    // 私有方法区域
    
    private void validateConfiguration() {
        if (configuration.getZookeeperConnectString() == null || 
            configuration.getZookeeperConnectString().trim().isEmpty()) {
            throw new IllegalArgumentException("Zookeeper connection string is required");
        }
        
        if (configuration.getZookeeperSessionTimeout().toMillis() < 1000) {
            logger.warn("Session timeout is too low: {}, may cause instability", 
                       configuration.getZookeeperSessionTimeout());
        }
        
        logger.debug("Configuration validation passed");
    }
    
    private void initializeComponents(String connectString, String nodeId, String nodeAddress) {
        // 1. 初始化连接管理器
        connectionManager = new ZooKeeperConnectionManager(
            connectString, configuration, meterRegistry, openTelemetry
        );
        
        // 2. 初始化集群管理器
        String finalNodeId = nodeId != null ? nodeId : generateNodeId();
        String finalNodeAddress = nodeAddress != null ? nodeAddress : "unknown";
        
        clusterManager = new ZooKeeperClusterManager(
            connectionManager, configuration, meterRegistry, openTelemetry,
            finalNodeId, finalNodeAddress
        );
        
        // 3. 初始化事件管理器
        eventManager = new ZooKeeperLockEventManager(
            configuration, metrics, tracing
        );
        
        // 4. 初始化高可用策略
        highAvailabilityStrategy = new ZooKeeperHighAvailabilityStrategy(
            connectionManager, clusterManager, configuration, metrics, tracing
        );
        
        // 5. 初始化批量操作
        batchOperations = new ZooKeeperBatchLockOperations(
            connectionManager, clusterManager, configuration, metrics, tracing
        );
        
        // 6. 初始化异步操作
        asyncOperations = new ZooKeeperAsyncLockOperations(
            connectionManager, clusterManager, configuration, metrics, tracing
        );
        
        logger.debug("All components initialized");
    }
    
    private void coordinateComponents() {
        // 集群管理器需要连接管理器
        clusterManager.initialize();
        
        // 事件管理器需要监听连接状态变化
        connectionManager.addConnectionStateListener((client, newState) -> {
            logger.info("Connection state changed: {}", newState);
            
            // 通知集群管理器
            clusterManager.handleConnectionStateChanged(newState);
            
            // 发布事件
            if (eventManager != null) {
                eventManager.publishConnectionStateEvent(
                    "factory", newState.name(), Instant.now()
                );
            }
        });
        
        // 高可用策略需要监听集群事件
        highAvailabilityStrategy.addHighAvailabilityEventListener(event -> {
            logger.info("HA event: {}", event.getType());
            
            // 可以在这里添加额外的处理逻辑
        });
        
        logger.debug("Components coordinated successfully");
    }
    
    private void validateAllComponents() {
        // 验证连接管理器
        ZooKeeperConnectionManager.ConnectionHealthResult connectionHealth = 
            connectionManager.performHealthCheck();
        if (!connectionHealth.isHealthy()) {
            throw new RuntimeException("Connection manager health check failed: " + connectionHealth.getErrorMessage());
        }
        
        // 验证集群管理器
        clusterManager.getClusterState(); // 这会触发内部验证
        
        logger.debug("All components validation passed");
    }
    
    private void shutdown() {
        if (isShutdown.getAndSet(true)) {
            return;
        }
        
        shutdownTime.set(System.currentTimeMillis());
        
        try {
            // 通知开始关闭
            notifyEvent(FactoryEventType.SHUTDOWN_STARTED, null, "Factory shutdown started");
            
            // 关闭高级特性组件
            if (highAvailabilityStrategy != null) {
                highAvailabilityStrategy.shutdown();
            }
            
            if (batchOperations != null) {
                batchOperations.close();
            }
            
            if (asyncOperations != null) {
                asyncOperations.close();
            }
            
            if (eventManager != null) {
                eventManager.close();
            }
            
            // 清理所有锁实例
            clearLockCache();
            
            // 关闭集群管理器
            if (clusterManager != null) {
                clusterManager.close();
            }
            
            // 关闭连接管理器（最后关闭）
            if (connectionManager != null) {
                connectionManager.close();
            }
            
            // 清理事件监听器
            eventListeners.clear();
            
            logger.info("Factory shutdown completed");
            notifyEvent(FactoryEventType.SHUTDOWN_COMPLETED, null, "Factory shutdown completed");
            
        } catch (Exception e) {
            logger.error("Error during factory shutdown", e);
            notifyEvent(FactoryEventType.SHUTDOWN_FAILED, e, "Factory shutdown failed: " + e.getMessage());
            throw new RuntimeException("Failed to shutdown factory properly", e);
        }
    }
    
    private void ensureInitialized() {
        if (!isInitialized.get()) {
            throw new IllegalStateException("Factory is not initialized. Call initialize() first.");
        }
    }
    
    private void ensureNotShutdown() {
        if (isShutdown.get()) {
            throw new IllegalStateException("Factory is already shutdown");
        }
    }
    
    private String generateNodeId() {
        return "node-" + System.currentTimeMillis() + "-" + 
               Integer.toHexString(hashCode());
    }
    
    private FactoryHealthStatus.HealthLevel calculateOverallHealth(boolean connectionHealthy, 
                                                                boolean clusterHealthy,
                                                                boolean eventManagerHealthy,
                                                                boolean haStrategyHealthy,
                                                                boolean batchOpsHealthy,
                                                                boolean asyncOpsHealthy) {
        
        if (!connectionHealthy || !clusterHealthy) {
            return FactoryHealthStatus.HealthLevel.UNHEALTHY;
        }
        
        int healthyComponents = 0;
        int totalComponents = 6;
        
        if (connectionHealthy) healthyComponents++;
        if (clusterHealthy) healthyComponents++;
        if (eventManagerHealthy) healthyComponents++;
        if (haStrategyHealthy) healthyComponents++;
        if (batchOpsHealthy) healthyComponents++;
        if (asyncOpsHealthy) healthyComponents++;
        
        double healthRatio = (double) healthyComponents / totalComponents;
        
        if (healthRatio >= 0.9) {
            return FactoryHealthStatus.HealthLevel.HEALTHY;
        } else if (healthRatio >= 0.7) {
            return FactoryHealthStatus.HealthLevel.DEGRADED;
        } else {
            return FactoryHealthStatus.HealthLevel.UNHEALTHY;
        }
    }
    
    private FactoryHealthStatus.HealthLevel calculateHealthLevel(
            ZooKeeperConnectionManager.ConnectionHealthResult connectionHealth,
            ZooKeeperClusterManager.ClusterState clusterState,
            boolean haStrategyHealthy) {
        
        if (!connectionHealth.isHealthy()) {
            return FactoryHealthStatus.HealthLevel.UNHEALTHY;
        }
        
        if (clusterState.getHealthyNodes() < clusterState.getTotalNodes() * 0.5) {
            return FactoryHealthStatus.HealthLevel.DEGRADED;
        }
        
        if (!haStrategyHealthy) {
            return FactoryHealthStatus.HealthLevel.DEGRADED;
        }
        
        return FactoryHealthStatus.HealthLevel.HEALTHY;
    }
    
    private FactoryHealthStatus.PerformanceMetrics collectPerformanceMetrics() {
        // 简化的性能指标收集
        return new FactoryHealthStatus.PerformanceMetrics(
            lockCache.size(),
            readWriteLockCache.size(),
            totalLocksCreated.get(),
            totalLocksDestroyed.get(),
            referenceCount.get()
        );
    }
    
    private void notifyEvent(FactoryEventType eventType, Throwable error, String message) {
        FactoryEvent event = new FactoryEvent(eventType, Instant.now(), error, message);
        
        for (FactoryEventListener listener : eventListeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                logger.warn("Error in factory event listener", e);
            }
        }
    }
    
    // 内部类定义
    
    public static class FactoryHealthStatus {
        public enum HealthLevel {
            HEALTHY, DEGRADED, UNHEALTHY
        }
        
        private final HealthLevel overallHealth;
        private final boolean initialized;
        private final boolean connectionHealthy;
        private final boolean clusterHealthy;
        private final boolean eventManagerHealthy;
        private final boolean haStrategyHealthy;
        private final boolean batchOpsHealthy;
        private final boolean asyncOpsHealthy;
        private final Instant initializationTime;
        private final Instant lastCheckTime;
        private final PerformanceMetrics performanceMetrics;
        
        public FactoryHealthStatus(HealthLevel overallHealth, boolean initialized,
                                 boolean connectionHealthy, boolean clusterHealthy,
                                 boolean eventManagerHealthy, boolean haStrategyHealthy,
                                 boolean batchOpsHealthy, boolean asyncOpsHealthy,
                                 Instant initializationTime, Instant lastCheckTime,
                                 PerformanceMetrics performanceMetrics) {
            this.overallHealth = overallHealth;
            this.initialized = initialized;
            this.connectionHealthy = connectionHealthy;
            this.clusterHealthy = clusterHealthy;
            this.eventManagerHealthy = eventManagerHealthy;
            this.haStrategyHealthy = haStrategyHealthy;
            this.batchOpsHealthy = batchOpsHealthy;
            this.asyncOpsHealthy = asyncOpsHealthy;
            this.initializationTime = initializationTime;
            this.lastCheckTime = lastCheckTime;
            this.performanceMetrics = performanceMetrics;
        }
        
        public static FactoryHealthStatus unhealthy(String reason) {
            return new FactoryHealthStatus(
                HealthLevel.UNHEALTHY, false, false, false, false, false, false, false,
                Instant.now(), Instant.now(), new PerformanceMetrics(0, 0, 0, 0, 0)
            );
        }
        
        public HealthLevel getOverallHealth() { return overallHealth; }
        public boolean isInitialized() { return initialized; }
        public boolean isConnectionHealthy() { return connectionHealthy; }
        public boolean isClusterHealthy() { return clusterHealthy; }
        public boolean isEventManagerHealthy() { return eventManagerHealthy; }
        public boolean isHaStrategyHealthy() { return haStrategyHealthy; }
        public boolean isBatchOpsHealthy() { return batchOpsHealthy; }
        public boolean isAsyncOpsHealthy() { return asyncOpsHealthy; }
        public Instant getInitializationTime() { return initializationTime; }
        public Instant getLastCheckTime() { return lastCheckTime; }
        public PerformanceMetrics getPerformanceMetrics() { return performanceMetrics; }
        
        public static class PerformanceMetrics {
            private final int cachedLocks;
            private final int cachedReadWriteLocks;
            private final long totalLocksCreated;
            private final long totalLocksDestroyed;
            private final int referenceCount;
            
            public PerformanceMetrics(int cachedLocks, int cachedReadWriteLocks,
                                    long totalLocksCreated, long totalLocksDestroyed,
                                    int referenceCount) {
                this.cachedLocks = cachedLocks;
                this.cachedReadWriteLocks = cachedReadWriteLocks;
                this.totalLocksCreated = totalLocksCreated;
                this.totalLocksDestroyed = totalLocksDestroyed;
                this.referenceCount = referenceCount;
            }
            
            public int getCachedLocks() { return cachedLocks; }
            public int getCachedReadWriteLocks() { return cachedReadWriteLocks; }
            public long getTotalLocksCreated() { return totalLocksCreated; }
            public long getTotalLocksDestroyed() { return totalLocksDestroyed; }
            public int getReferenceCount() { return referenceCount; }
        }
    }
    
    public static class FactoryStatistics {
        private final long totalLocksCreated;
        private final long totalReadWriteLocksCreated;
        private final long totalLocksDestroyed;
        private final int activeLocks;
        private final int activeReadWriteLocks;
        private final boolean initialized;
        private final Instant initializationTime;
        private final Instant lastCheckTime;
        
        public FactoryStatistics(long totalLocksCreated, long totalReadWriteLocksCreated,
                               long totalLocksDestroyed, int activeLocks, int activeReadWriteLocks,
                               boolean initialized, Instant initializationTime, Instant lastCheckTime) {
            this.totalLocksCreated = totalLocksCreated;
            this.totalReadWriteLocksCreated = totalReadWriteLocksCreated;
            this.totalLocksDestroyed = totalLocksDestroyed;
            this.activeLocks = activeLocks;
            this.activeReadWriteLocks = activeReadWriteLocks;
            this.initialized = initialized;
            this.initializationTime = initializationTime;
            this.lastCheckTime = lastCheckTime;
        }
        
        public long getTotalLocksCreated() { return totalLocksCreated; }
        public long getTotalReadWriteLocksCreated() { return totalReadWriteLocksCreated; }
        public long getTotalLocksDestroyed() { return totalLocksDestroyed; }
        public int getActiveLocks() { return activeLocks; }
        public int getActiveReadWriteLocks() { return activeReadWriteLocks; }
        public boolean isInitialized() { return initialized; }
        public Instant getInitializationTime() { return initializationTime; }
        public Instant getLastCheckTime() { return lastCheckTime; }
        
        public long getNetLocksCreated() {
            return totalLocksCreated + totalReadWriteLocksCreated - totalLocksDestroyed;
        }
    }
    
    public static class FactoryHealthCheckResult {
        private final boolean healthy;
        private final FactoryHealthStatus.HealthLevel healthLevel;
        private final long checkTimeMs;
        private final String details;
        private final Duration duration;
        private final String errorMessage;
        
        public FactoryHealthCheckResult(boolean healthy, FactoryHealthStatus.HealthLevel healthLevel,
                                      long checkTimeMs, String details, Duration duration,
                                      String errorMessage) {
            this.healthy = healthy;
            this.healthLevel = healthLevel;
            this.checkTimeMs = checkTimeMs;
            this.details = details;
            this.duration = duration;
            this.errorMessage = errorMessage;
        }
        
        public boolean isHealthy() { return healthy; }
        public FactoryHealthStatus.HealthLevel getHealthLevel() { return healthLevel; }
        public long getCheckTimeMs() { return checkTimeMs; }
        public String getDetails() { return details; }
        public Duration getDuration() { return duration; }
        public String getErrorMessage() { return errorMessage; }
    }
    
    public static class FactoryEvent {
        private final FactoryEventType type;
        private final Instant timestamp;
        private final Throwable error;
        private final String message;
        
        public FactoryEvent(FactoryEventType type, Instant timestamp, Throwable error, String message) {
            this.type = type;
            this.timestamp = timestamp;
            this.error = error;
            this.message = message;
        }
        
        public FactoryEventType getType() { return type; }
        public Instant getTimestamp() { return timestamp; }
        public Throwable getError() { return error; }
        public String getMessage() { return message; }
    }
    
    public interface FactoryEventListener {
        void onEvent(FactoryEvent event);
    }
    
    public enum FactoryEventType {
        INITIALIZED,
        INITIALIZATION_FAILED,
        SHUTDOWN_STARTED,
        SHUTDOWN_COMPLETED,
        SHUTDOWN_FAILED,
        CACHE_CLEARED,
        FORCE_RESET,
        HEALTH_CHECK_PERFORMED,
        COMPONENT_ERROR
    }
}