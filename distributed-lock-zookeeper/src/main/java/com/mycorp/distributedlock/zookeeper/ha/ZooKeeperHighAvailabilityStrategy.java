package com.mycorp.distributedlock.zookeeper.ha;

import com.mycorp.distributedlock.api.HighAvailabilityStrategy;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import com.mycorp.distributedlock.zookeeper.cluster.ZooKeeperClusterManager;
import com.mycorp.distributedlock.zookeeper.connection.ZooKeeperConnectionManager;
import com.mycorp.distributedlock.zookeeper.lock.OptimizedZooKeeperDistributedLock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Zookeeper高可用策略实现
 * 
 * 主要功能：
 * - 自动故障转移和恢复
 * - 健康检查和状态监控
 * - 负载均衡和节点选择
 * - 重试机制和退避策略
 * - 会话管理和恢复
 * - 集群拓扑感知
 * 
 * 高可用特性：
 * - 基于集群状态的自适应故障转移
 * - 多种负载均衡算法支持
 * - 健康检查和性能监控
 * - 自动重试和熔断机制
 * - 优雅降级和恢复
 */
public class ZooKeeperHighAvailabilityStrategy implements HighAvailabilityStrategy<OptimizedZooKeeperDistributedLock> {
    
    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperHighAvailabilityStrategy.class);
    
    // 策略配置
    private static final Duration DEFAULT_FAILOVER_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_HEALTH_CHECK_INTERVAL = Duration.ofSeconds(10);
    private static final int DEFAULT_MAX_FAILOVER_COUNT = 3;
    private static final double DEFAULT_LOAD_THRESHOLD = 0.8;
    private static final Duration DEFAULT_RECOVERY_WAIT_TIME = Duration.ofSeconds(5);
    
    // 状态管理
    private final AtomicBoolean isEnabled = new AtomicBoolean(true);
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicInteger failoverCount = new AtomicInteger(0);
    private final ReentrantLock strategyLock = new ReentrantLock();
    
    // Zookeeper相关
    private final ZooKeeperConnectionManager connectionManager;
    private final ZooKeeperClusterManager clusterManager;
    private final LockConfiguration configuration;
    private final LockMetrics metrics;
    private final LockTracing tracing;
    
    // 线程池
    private final ExecutorService failoverExecutor;
    private final ExecutorService healthCheckExecutor;
    private final ScheduledExecutorService healthCheckScheduler;
    private final ExecutorService loadBalanceExecutor;
    
    // 健康检查管理
    private final Map<String, HealthCheckState> nodeHealthStates = new ConcurrentHashMap<>();
    private final AtomicLong lastHealthCheckTime = new AtomicLong(0);
    
    // 故障转移管理
    private final Map<String, FailoverOperation> activeFailoverOperations = new ConcurrentHashMap<>();
    private final AtomicLong failoverOperationIdGenerator = new AtomicLong(0);
    
    // 负载均衡管理
    private final LoadBalancer loadBalancer;
    private final NodeSelector nodeSelector;
    
    // 熔断器管理
    private final Map<String, CircuitBreaker> nodeCircuitBreakers = new ConcurrentHashMap<>();
    
    // 策略配置
    private final AtomicReference<ZooKeeperStrategyConfiguration> strategyConfig;
    
    // 性能指标
    private final Timer failoverTimer;
    private final Timer healthCheckTimer;
    private final Timer loadBalanceTimer;
    private final Counter failoverTriggerCounter;
    private final Counter failoverSuccessCounter;
    private final Counter failoverFailedCounter;
    private final Counter healthCheckCounter;
    private final Counter circuitBreakerTriggerCounter;
    private final Gauge healthyNodesGauge;
    private final Gauge totalNodesGauge;
    private final Gauge failedNodesGauge;
    private final AtomicLong totalFailovers = new AtomicLong(0);
    private final AtomicLong successfulFailovers = new AtomicLong(0);
    private final AtomicLong failedFailovers = new AtomicLong(0);
    
    // 事件监听
    private final java.util.List<HighAvailabilityEventListener> eventListeners = new CopyOnWriteArrayList<>();
    
    /**
     * 构造函数
     * 
     * @param connectionManager 连接管理器
     * @param clusterManager 集群管理器
     * @param configuration 配置
     * @param metrics 指标收集
     * @param tracing 分布式追踪
     */
    public ZooKeeperHighAvailabilityStrategy(ZooKeeperConnectionManager connectionManager,
                                           ZooKeeperClusterManager clusterManager,
                                           LockConfiguration configuration,
                                           LockMetrics metrics,
                                           LockTracing tracing) {
        this.connectionManager = connectionManager;
        this.clusterManager = clusterManager;
        this.configuration = configuration;
        this.metrics = metrics;
        this.tracing = tracing;
        
        // 初始化线程池
        this.failoverExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "zookeeper-ha-failover");
            t.setDaemon(true);
            return t;
        });
        
        this.healthCheckExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "zookeeper-ha-health-check");
            t.setDaemon(true);
            return t;
        });
        
        this.healthCheckScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zookeeper-ha-health-check-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        this.loadBalanceExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "zookeeper-ha-load-balance");
            t.setDaemon(true);
            return t;
        });
        
        // 初始化负载均衡器和节点选择器
        this.loadBalancer = new LoadBalancer();
        this.nodeSelector = new NodeSelector();
        
        // 初始化策略配置
        this.strategyConfig = new AtomicReference<>(createDefaultConfiguration());
        
        // 初始化性能指标
        this.failoverTimer = metrics.createTimer("zookeeper.ha.failover.time");
        this.healthCheckTimer = metrics.createTimer("zookeeper.ha.health.check.time");
        this.loadBalanceTimer = metrics.createTimer("zookeeper.ha.load.balance.time");
        this.failoverTriggerCounter = metrics.createCounter("zookeeper.ha.failover.trigger.count");
        this.failoverSuccessCounter = metrics.createCounter("zookeeper.ha.failover.success.count");
        this.failoverFailedCounter = metrics.createCounter("zookeeper.ha.failover.failed.count");
        this.healthCheckCounter = metrics.createCounter("zookeeper.ha.health.check.count");
        this.circuitBreakerTriggerCounter = metrics.createCounter("zookeeper.ha.circuit.breaker.trigger.count");
        this.healthyNodesGauge = metrics.createGauge("zookeeper.ha.healthy.nodes.count", this::getHealthyNodesCount);
        this.totalNodesGauge = metrics.createGauge("zookeeper.ha.total.nodes.count", this::getTotalNodesCount);
        this.failedNodesGauge = metrics.createGauge("zookeeper.ha.failed.nodes.count", this::getFailedNodesCount);
        
        logger.debug("ZooKeeperHighAvailabilityStrategy initialized");
    }
    
    /**
     * 初始化高可用策略
     */
    public synchronized void initialize() {
        if (isInitialized.getAndSet(true)) {
            return;
        }
        
        logger.info("Initializing ZooKeeperHighAvailabilityStrategy");
        
        try {
            // 启动健康检查
            startHealthCheck();
            
            // 启动负载均衡
            startLoadBalancing();
            
            // 初始化熔断器
            initializeCircuitBreakers();
            
            // 通知初始化完成
            notifyEvent(HighAvailabilityEventType.STRATEGY_INITIALIZED, null, 
                       "High availability strategy initialized successfully");
            
            logger.info("ZooKeeperHighAvailabilityStrategy initialized successfully");
        } catch (Exception e) {
            isInitialized.set(false);
            notifyEvent(HighAvailabilityEventType.STRATEGY_INITIALIZATION_FAILED, e,
                       "Failed to initialize high availability strategy");
            throw e;
        }
    }
    
    @Override
    public OptimizedZooKeeperDistributedLock failover(OptimizedZooKeeperDistributedLock originalLock, 
                                                     Throwable failureReason) throws Exception {
        return executeFailover(originalLock, failureReason, false).join();
    }
    
    @Override
    public CompletableFuture<OptimizedZooKeeperDistributedLock> failoverAsync(OptimizedZooKeeperDistributedLock originalLock,
                                                                             Throwable failureReason) {
        return executeFailover(originalLock, failureReason, true);
    }
    
    @Override
    public HealthCheckResult performHealthCheck(String lockName) {
        return executeHealthCheck(lockName, false).join();
    }
    
    @Override
    public CompletableFuture<HealthCheckResult> performHealthCheckAsync(String lockName) {
        return executeHealthCheck(lockName, true);
    }
    
    @Override
    public boolean isNodeAvailable(String nodeId) {
        HealthCheckState healthState = nodeHealthStates.get(nodeId);
        if (healthState == null) {
            return false;
        }
        
        CircuitBreaker circuitBreaker = nodeCircuitBreakers.get(nodeId);
        if (circuitBreaker != null && circuitBreaker.isOpen()) {
            return false;
        }
        
        return healthState.isHealthy();
    }
    
    @Override
    public List<String> getBackupNodes(String primaryNodeId) {
        try {
            // 获取集群状态
            ZooKeeperClusterManager.ClusterState clusterState = clusterManager.getClusterState();
            
            return clusterState.getTotalNodes() > 0 ?
                clusterManager.getHealthyClusterNodes().stream()
                    .filter(node -> !node.getNodeId().equals(primaryNodeId))
                    .map(node -> node.getNodeId())
                    .collect(Collectors.toList()) :
                List.of(); // 返回空列表表示没有备用节点
            
        } catch (Exception e) {
            logger.warn("Failed to get backup nodes for: {}", primaryNodeId, e);
            return List.of();
        }
    }
    
    @Override
    public String selectOptimalNode(List<String> availableNodes, String lockName) {
        Timer.Sample sample = metrics.startTimer(loadBalanceTimer);
        
        try {
            // 使用负载均衡算法选择最优节点
            return loadBalancer.selectOptimalNode(availableNodes, lockName);
        } finally {
            sample.stop(loadBalanceTimer);
        }
    }
    
    @Override
    public String getStrategyName() {
        return "ZooKeeperHighAvailabilityStrategy";
    }
    
    @Override
    public StrategyConfiguration getConfiguration() {
        return new ImmutableStrategyConfiguration(strategyConfig.get());
    }
    
    @Override
    public void updateConfiguration(StrategyConfiguration configuration) {
        if (configuration instanceof ZooKeeperStrategyConfiguration) {
            strategyConfig.set((ZooKeeperStrategyConfiguration) configuration);
            logger.info("Updated high availability strategy configuration");
        } else {
            throw new IllegalArgumentException("Configuration must be ZooKeeperStrategyConfiguration");
        }
    }
    
    @Override
    public boolean isEnabled() {
        return isEnabled.get() && isInitialized.get();
    }
    
    @Override
    public void setEnabled(boolean enabled) {
        isEnabled.set(enabled);
        logger.info("High availability strategy {}", enabled ? "enabled" : "disabled");
        
        if (enabled) {
            notifyEvent(HighAvailabilityEventType.STRATEGY_ENABLED, null, 
                       "High availability strategy enabled");
        } else {
            notifyEvent(HighAvailabilityEventType.STRATEGY_DISABLED, null,
                       "High availability strategy disabled");
        }
    }
    
    /**
     * 执行手动故障转移
     * 
     * @param lockName 锁名称
     * @param targetNodeId 目标节点ID（可选）
     * @return 是否转移成功
     */
    public boolean executeManualFailover(String lockName, String targetNodeId) {
        if (!isEnabled()) {
            logger.warn("High availability strategy is not enabled");
            return false;
        }
        
        try {
            CompletableFuture<OptimizedZooKeeperDistributedLock> failoverFuture = 
                executeFailover(null, new RuntimeException("Manual failover requested"), true);
            
            OptimizedZooKeeperDistributedLock newLock = failoverFuture.join();
            return newLock != null;
            
        } catch (Exception e) {
            logger.error("Manual failover failed for lock: {}", lockName, e);
            return false;
        }
    }
    
    /**
     * 重置节点健康状态
     * 
     * @param nodeId 节点ID
     * @return 是否重置成功
     */
    public boolean resetNodeHealth(String nodeId) {
        HealthCheckState healthState = nodeHealthStates.get(nodeId);
        if (healthState != null) {
            healthState.reset();
            
            // 重置熔断器
            CircuitBreaker circuitBreaker = nodeCircuitBreakers.get(nodeId);
            if (circuitBreaker != null) {
                circuitBreaker.reset();
            }
            
            logger.info("Reset health state for node: {}", nodeId);
            notifyEvent(HighAvailabilityEventType.NODE_HEALTH_RESET, null,
                       "Node health state reset: " + nodeId);
            return true;
        }
        
        return false;
    }
    
    /**
     * 获取高可用统计信息
     * 
     * @return 统计信息
     */
    public HighAvailabilityStatistics getStatistics() {
        return new HighAvailabilityStatistics(
            totalFailovers.get(),
            successfulFailovers.get(),
            failedFailovers.get(),
            nodeHealthStates.size(),
            (int) nodeHealthStates.values().stream().filter(HealthCheckState::isHealthy).count(),
            nodeCircuitBreakers.size(),
            activeFailoverOperations.size()
        );
    }
    
    /**
     * 添加高可用事件监听器
     * 
     * @param listener 监听器
     */
    public void addHighAvailabilityEventListener(HighAvailabilityEventListener listener) {
        if (listener != null) {
            eventListeners.add(listener);
        }
    }
    
    /**
     * 移除高可用事件监听器
     * 
     * @param listener 监听器
     */
    public void removeHighAvailabilityEventListener(HighAvailabilityEventListener listener) {
        eventListeners.remove(listener);
    }
    
    /**
     * 强制重置所有统计信息
     */
    public void resetStatistics() {
        totalFailovers.set(0);
        successfulFailovers.set(0);
        failedFailovers.set(0);
        failoverCount.set(0);
        
        // 重置节点状态
        nodeHealthStates.values().forEach(HealthCheckState::reset);
        
        // 重置熔断器
        nodeCircuitBreakers.values().forEach(CircuitBreaker::reset);
        
        logger.info("High availability statistics reset");
    }
    
    @Override
    public void shutdown() {
        if (!isInitialized.get()) {
            return;
        }
        
        logger.info("Shutting down ZooKeeperHighAvailabilityStrategy");
        
        try {
            // 停止高可用策略
            isEnabled.set(false);
            
            // 关闭线程池
            shutdownExecutor(failoverExecutor, "failover");
            shutdownExecutor(healthCheckExecutor, "health-check");
            shutdownExecutor(healthCheckScheduler, "health-check-scheduler");
            shutdownExecutor(loadBalanceExecutor, "load-balance");
            
            // 清理状态
            nodeHealthStates.clear();
            activeFailoverOperations.clear();
            nodeCircuitBreakers.clear();
            eventListeners.clear();
            
            isInitialized.set(false);
            
            logger.info("ZooKeeperHighAvailabilityStrategy shutdown completed");
        } catch (Exception e) {
            logger.error("Error during shutdown", e);
        }
    }
    
    // 私有方法区域
    
    private CompletableFuture<OptimizedZooKeeperDistributedLock> executeFailover(
            OptimizedZooKeeperDistributedLock originalLock, Throwable failureReason, boolean async) {
        
        long operationId = failoverOperationIdGenerator.incrementAndGet();
        FailoverOperation failoverOperation = new FailoverOperation(operationId, originalLock, failureReason);
        activeFailoverOperations.put(String.valueOf(operationId), failoverOperation);
        
        CompletableFuture<OptimizedZooKeeperDistributedLock> failoverFuture = CompletableFuture.supplyAsync(() -> {
            Timer.Sample sample = metrics.startTimer(failoverTimer);
            
            try {
                logger.info("Starting failover operation {} for lock: {}", 
                           operationId, originalLock != null ? originalLock.getName() : "unknown");
                
                failoverTriggerCounter.increment();
                totalFailovers.incrementAndGet();
                
                // 检查是否超过最大故障转移次数
                if (failoverCount.get() >= strategyConfig.get().getMaxFailoverCount()) {
                    throw new RuntimeException("Maximum failover count exceeded");
                }
                
                // 执行故障转移步骤
                OptimizedZooKeeperDistributedLock newLock = performFailoverSteps(originalLock, failureReason);
                
                if (newLock != null) {
                    successfulFailovers.incrementAndGet();
                    failoverSuccessCounter.increment();
                    
                    logger.info("Failover operation {} completed successfully", operationId);
                    notifyEvent(HighAvailabilityEventType.FAILOVER_SUCCESS, failureReason,
                               "Failover completed successfully for lock: " + newLock.getName());
                } else {
                    failedFailovers.incrementAndGet();
                    failoverFailedCounter.increment();
                    
                    logger.error("Failover operation {} failed", operationId);
                    notifyEvent(HighAvailabilityEventType.FAILOVER_FAILED, failureReason,
                               "Failover failed for lock: " + (originalLock != null ? originalLock.getName() : "unknown"));
                }
                
                return newLock;
                
            } catch (Exception e) {
                failedFailovers.incrementAndGet();
                failoverFailedCounter.increment();
                
                logger.error("Failover operation {} threw exception", operationId, e);
                notifyEvent(HighAvailabilityEventType.FAILOVER_FAILED, e,
                           "Failover operation failed: " + e.getMessage());
                
                throw e;
            } finally {
                sample.stop(failoverTimer);
                activeFailoverOperations.remove(String.valueOf(operationId));
            }
        }, failoverExecutor);
        
        if (async) {
            return failoverFuture;
        } else {
            try {
                return CompletableFuture.completedFuture(failoverFuture.join());
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }
    }
    
    private OptimizedZooKeeperDistributedLock performFailoverSteps(OptimizedZooKeeperDistributedLock originalLock,
                                                                  Throwable failureReason) throws Exception {
        
        ZooKeeperStrategyConfiguration config = strategyConfig.get();
        Instant startTime = Instant.now();
        
        try {
            // 步骤1: 分析失败原因
            FailoverType failoverType = analyzeFailureType(failureReason);
            
            // 步骤2: 选择备用节点
            String backupNodeId = selectBackupNode(originalLock, failoverType);
            if (backupNodeId == null) {
                logger.warn("No backup node available for failover");
                return null;
            }
            
            // 步骤3: 在备用节点上创建新锁
            OptimizedZooKeeperDistributedLock newLock = createLockOnBackupNode(originalLock, backupNodeId);
            
            // 步骤4: 同步锁状态（如果可能）
            if (originalLock != null) {
                synchronizeLockState(originalLock, newLock);
            }
            
            // 步骤5: 验证新锁
            if (!validateNewLock(newLock, config.getFailoverTimeoutMs())) {
                throw new RuntimeException("New lock validation failed");
            }
            
            // 步骤6: 更新统计信息
            failoverCount.incrementAndGet();
            
            Duration failoverDuration = Duration.between(startTime, Instant.now());
            logger.info("Failover completed in {} ms", failoverDuration.toMillis());
            
            return newLock;
            
        } catch (Exception e) {
            logger.error("Failover steps failed", e);
            throw e;
        }
    }
    
    private FailoverType analyzeFailureType(Throwable failureReason) {
        if (failureReason == null) {
            return FailoverType.MANUAL;
        }
        
        String message = failureReason.getMessage().toLowerCase();
        
        if (message.contains("connection") || message.contains("network")) {
            return FailoverType.CONNECTION_FAILURE;
        } else if (message.contains("timeout")) {
            return FailoverType.TIMEOUT;
        } else if (message.contains("session") || message.contains("expired")) {
            return FailoverType.SESSION_EXPIRED;
        } else if (message.contains("quorum") || message.contains("leader")) {
            return FailoverType.QUORUM_LOSS;
        } else {
            return FailoverType.UNKNOWN_ERROR;
        }
    }
    
    private String selectBackupNode(OptimizedZooKeeperDistributedLock originalLock, FailoverType failoverType) {
        try {
            // 获取集群状态
            ZooKeeperClusterManager.ClusterState clusterState = clusterManager.getClusterState();
            
            // 获取健康节点
            List<String> availableNodes = clusterManager.getHealthyClusterNodes().stream()
                .map(node -> node.getNodeId())
                .collect(Collectors.toList());
            
            if (availableNodes.isEmpty()) {
                logger.warn("No healthy nodes available for failover");
                return null;
            }
            
            // 根据故障转移类型选择节点
            switch (failoverType) {
                case CONNECTION_FAILURE:
                case QUORUM_LOSS:
                    // 选择网络延迟最低的节点
                    return selectNodeByLatency(availableNodes);
                    
                case TIMEOUT:
                    // 选择负载最低的节点
                    return selectNodeByLoad(availableNodes);
                    
                case SESSION_EXPIRED:
                    // 选择会话最稳定的节点
                    return selectNodeBySessionStability(availableNodes);
                    
                default:
                    // 使用通用负载均衡
                    return selectOptimalNode(availableNodes, originalLock != null ? originalLock.getName() : "");
            }
            
        } catch (Exception e) {
            logger.error("Failed to select backup node", e);
            return null;
        }
    }
    
    private OptimizedZooKeeperDistributedLock createLockOnBackupNode(OptimizedZooKeeperDistributedLock originalLock,
                                                                     String targetNodeId) {
        try {
            // 在目标节点上创建新的锁实例
            String lockName = originalLock != null ? originalLock.getName() : "failover-lock-" + System.currentTimeMillis();
            String lockPath = "/distributed-locks/" + lockName;
            
            // 这里应该连接到指定的节点创建锁
            // 简化实现，返回一个新的锁实例
            return new OptimizedZooKeeperDistributedLock(
                lockName, lockPath, connectionManager, clusterManager,
                configuration, metrics, tracing
            );
            
        } catch (Exception e) {
            logger.error("Failed to create lock on backup node: {}", targetNodeId, e);
            throw new RuntimeException("Failed to create lock on backup node", e);
        }
    }
    
    private void synchronizeLockState(OptimizedZooKeeperDistributedLock originalLock,
                                    OptimizedZooKeeperDistributedLock newLock) {
        try {
            // 同步锁状态（如果原锁持有中）
            if (originalLock.isHeldByCurrentThread()) {
                // 简化的状态同步实现
                logger.info("Synchronizing lock state from original lock to backup lock");
                
                // 这里应该实现更复杂的锁状态同步逻辑
                // 包括重入计数、租约时间等信息
            }
        } catch (Exception e) {
            logger.warn("Failed to synchronize lock state", e);
            // 状态同步失败不应阻止故障转移
        }
    }
    
    private boolean validateNewLock(OptimizedZooKeeperDistributedLock newLock, long timeoutMs) {
        try {
            // 简单验证：尝试获取和释放锁
            return newLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            logger.error("New lock validation failed", e);
            return false;
        } finally {
            try {
                newLock.unlock();
            } catch (Exception e) {
                logger.warn("Failed to unlock after validation", e);
            }
        }
    }
    
    private CompletableFuture<HealthCheckResult> executeHealthCheck(String lockName, boolean async) {
        return CompletableFuture.supplyAsync(() -> {
            Timer.Sample sample = metrics.startTimer(healthCheckTimer);
            
            try {
                long startTime = System.currentTimeMillis();
                healthCheckCounter.increment();
                
                // 执行连接健康检查
                ZooKeeperConnectionManager.ConnectionHealthResult connectionHealth = 
                    connectionManager.performHealthCheck();
                
                // 执行集群健康检查
                ZooKeeperClusterManager.ClusterState clusterState = clusterManager.getClusterState();
                
                // 评估整体健康状态
                HealthStatus overallHealth = evaluateOverallHealth(connectionHealth, clusterState);
                
                long responseTime = System.currentTimeMillis() - startTime;
                
                HealthCheckResult result = new ImmutableHealthCheckResult(
                    overallHealth == HealthStatus.HEALTHY,
                    overallHealth,
                    responseTime,
                    String.format("Connection: %s, Cluster: %d nodes, %d healthy", 
                                connectionHealth.getStatus(), 
                                clusterState.getTotalNodes(),
                                clusterState.getHealthyNodes()),
                    responseTime,
                    null
                );
                
                // 更新节点健康状态
                updateNodeHealthState(lockName, overallHealth);
                
                lastHealthCheckTime.set(System.currentTimeMillis());
                
                return result;
                
            } catch (Exception e) {
                logger.error("Health check failed for lock: {}", lockName, e);
                
                return new ImmutableHealthCheckResult(
                    false,
                    HealthStatus.UNHEALTHY,
                    System.currentTimeMillis() - startTime,
                    "Health check failed: " + e.getMessage(),
                    System.currentTimeMillis() - startTime,
                    e.getMessage()
                );
            } finally {
                sample.stop(healthCheckTimer);
            }
        }, healthCheckExecutor);
    }
    
    private HealthStatus evaluateOverallHealth(ZooKeeperConnectionManager.ConnectionHealthResult connectionHealth,
                                             ZooKeeperClusterManager.ClusterState clusterState) {
        
        if (!connectionHealth.isHealthy()) {
            return HealthStatus.UNHEALTHY;
        }
        
        if (clusterState.getHealthyNodes() < clusterState.getTotalNodes() * 0.5) {
            return HealthStatus.DEGRADED;
        }
        
        if (connectionHealth.getResponseTimeMs() > 5000) {
            return HealthStatus.DEGRADED;
        }
        
        return HealthStatus.HEALTHY;
    }
    
    private void updateNodeHealthState(String lockName, HealthStatus healthStatus) {
        HealthCheckState healthState = nodeHealthStates.computeIfAbsent(lockName, 
            k -> new HealthCheckState(k));
        
        healthState.updateStatus(healthStatus, Instant.now());
        
        // 如果节点连续失败，启动熔断器
        if (healthStatus == HealthStatus.UNHEALTHY && healthState.getConsecutiveFailures() >= 3) {
            activateCircuitBreaker(lockName);
        }
        
        // 如果节点恢复，关闭熔断器
        if (healthStatus == HealthStatus.HEALTHY && healthState.getConsecutiveFailures() == 0) {
            deactivateCircuitBreaker(lockName);
        }
    }
    
    private void activateCircuitBreaker(String nodeId) {
        CircuitBreaker circuitBreaker = nodeCircuitBreakers.computeIfAbsent(nodeId, 
            k -> new CircuitBreaker());
        
        if (circuitBreaker.activate()) {
            circuitBreakerTriggerCounter.increment();
            logger.warn("Activated circuit breaker for node: {}", nodeId);
            notifyEvent(HighAvailabilityEventType.CIRCUIT_BREAKER_ACTIVATED, null,
                       "Circuit breaker activated for node: " + nodeId);
        }
    }
    
    private void deactivateCircuitBreaker(String nodeId) {
        CircuitBreaker circuitBreaker = nodeCircuitBreakers.get(nodeId);
        if (circuitBreaker != null && circuitBreaker.deactivate()) {
            logger.info("Deactivated circuit breaker for node: {}", nodeId);
            notifyEvent(HighAvailabilityEventType.CIRCUIT_BREAKER_DEACTIVATED, null,
                       "Circuit breaker deactivated for node: " + nodeId);
        }
    }
    
    private String selectNodeByLatency(List<String> availableNodes) {
        // 简化的延迟选择逻辑
        return availableNodes.isEmpty() ? null : availableNodes.get(0);
    }
    
    private String selectNodeByLoad(List<String> availableNodes) {
        // 简化的负载选择逻辑
        return availableNodes.isEmpty() ? null : availableNodes.get(0);
    }
    
    private String selectNodeBySessionStability(List<String> availableNodes) {
        // 简化的会话稳定性选择逻辑
        return availableNodes.isEmpty() ? null : availableNodes.get(0);
    }
    
    private void startHealthCheck() {
        ZooKeeperStrategyConfiguration config = strategyConfig.get();
        
        healthCheckScheduler.scheduleAtFixedRate(() -> {
            try {
                if (!isEnabled()) {
                    return;
                }
                
                // 对所有已知节点执行健康检查
                for (String nodeId : nodeHealthStates.keySet()) {
                    performHealthCheckAsync(nodeId);
                }
                
            } catch (Exception e) {
                logger.debug("Health check scheduling error", e);
            }
        }, 0, (long) config.getHealthCheckIntervalMs(), TimeUnit.MILLISECONDS);
    }
    
    private void startLoadBalancing() {
        // 启动负载均衡监控
        loadBalanceExecutor.submit(() -> {
            while (isEnabled() && !Thread.currentThread().isInterrupted()) {
                try {
                    // 定期更新节点负载信息
                    updateNodeLoadInformation();
                    
                    Thread.sleep(30000); // 每30秒更新一次
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.debug("Load balancing error", e);
                }
            }
        });
    }
    
    private void initializeCircuitBreakers() {
        ZooKeeperStrategyConfiguration config = strategyConfig.get();
        
        // 启动熔断器恢复检查
        healthCheckScheduler.scheduleAtFixedRate(() -> {
            try {
                for (CircuitBreaker circuitBreaker : nodeCircuitBreakers.values()) {
                    circuitBreaker.checkAndReset();
                }
            } catch (Exception e) {
                logger.debug("Circuit breaker recovery check error", e);
            }
        }, 0, (long) config.getHealthCheckIntervalMs(), TimeUnit.MILLISECONDS);
    }
    
    private void updateNodeLoadInformation() {
        try {
            ZooKeeperClusterManager.ClusterState clusterState = clusterManager.getClusterState();
            
            // 更新节点负载信息
            for (ZooKeeperClusterManager.ClusterNodeInfo node : clusterManager.getAllClusterNodes()) {
                HealthCheckState healthState = nodeHealthStates.computeIfAbsent(node.getNodeId(),
                    k -> new HealthCheckState(k));
                
                // 简化的负载更新
                healthState.updateLoad(node.getConnectionCount(), node.getLatencyMs());
            }
            
        } catch (Exception e) {
            logger.debug("Failed to update node load information", e);
        }
    }
    
    private ZooKeeperStrategyConfiguration createDefaultConfiguration() {
        return new ZooKeeperStrategyConfiguration(
            3, // 重试次数
            1000, // 重试间隔
            DEFAULT_FAILOVER_TIMEOUT.toMillis(),
            DEFAULT_HEALTH_CHECK_INTERVAL.toMillis(),
            DEFAULT_MAX_FAILOVER_COUNT,
            HighAvailabilityStrategy.LoadBalancingStrategy.ROUND_ROBIN,
            HighAvailabilityStrategy.NodeSelectionStrategy.OPTIMAL,
            true, // 自动故障转移
            true  // 健康检查
        );
    }
    
    private int getHealthyNodesCount() {
        return (int) nodeHealthStates.values().stream()
            .filter(HealthCheckState::isHealthy)
            .count();
    }
    
    private int getTotalNodesCount() {
        return nodeHealthStates.size();
    }
    
    private int getFailedNodesCount() {
        return (int) nodeHealthStates.values().stream()
            .filter(state -> !state.isHealthy())
            .count();
    }
    
    private void notifyEvent(HighAvailabilityEventType eventType, Throwable error, String message) {
        HighAvailabilityEvent event = new HighAvailabilityEvent(
            eventType, Instant.now(), error, message
        );
        
        for (HighAvailabilityEventListener listener : eventListeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                logger.warn("Error in high availability event listener", e);
            }
        }
    }
    
    private void shutdownExecutor(ExecutorService executor, String name) {
        try {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.debug("Shut down {} executor", name);
    }
    
    // 内部类定义
    
    private static class ZooKeeperStrategyConfiguration implements StrategyConfiguration {
        private final int retryCount;
        private final long retryIntervalMs;
        private final long failoverTimeoutMs;
        private final long healthCheckIntervalMs;
        private final int maxFailoverCount;
        private final LoadBalancingStrategy loadBalancingStrategy;
        private final NodeSelectionStrategy nodeSelectionStrategy;
        private final boolean autoFailoverEnabled;
        private final boolean healthCheckEnabled;
        
        public ZooKeeperStrategyConfiguration(int retryCount, long retryIntervalMs, long failoverTimeoutMs,
                                            long healthCheckIntervalMs, int maxFailoverCount,
                                            LoadBalancingStrategy loadBalancingStrategy,
                                            NodeSelectionStrategy nodeSelectionStrategy,
                                            boolean autoFailoverEnabled, boolean healthCheckEnabled) {
            this.retryCount = retryCount;
            this.retryIntervalMs = retryIntervalMs;
            this.failoverTimeoutMs = failoverTimeoutMs;
            this.healthCheckIntervalMs = healthCheckIntervalMs;
            this.maxFailoverCount = maxFailoverCount;
            this.loadBalancingStrategy = loadBalancingStrategy;
            this.nodeSelectionStrategy = nodeSelectionStrategy;
            this.autoFailoverEnabled = autoFailoverEnabled;
            this.healthCheckEnabled = healthCheckEnabled;
        }
        
        @Override
        public int getRetryCount() { return retryCount; }
        @Override
        public long getRetryIntervalMs() { return retryIntervalMs; }
        @Override
        public long getFailoverTimeoutMs() { return failoverTimeoutMs; }
        @Override
        public long getHealthCheckIntervalMs() { return healthCheckIntervalMs; }
        @Override
        public int getMaxFailoverCount() { return maxFailoverCount; }
        @Override
        public LoadBalancingStrategy getLoadBalancingStrategy() { return loadBalancingStrategy; }
        @Override
        public NodeSelectionStrategy getNodeSelectionStrategy() { return nodeSelectionStrategy; }
        @Override
        public boolean isAutoFailoverEnabled() { return autoFailoverEnabled; }
        @Override
        public boolean isHealthCheckEnabled() { return healthCheckEnabled; }
        
        @Override
        public StrategyConfiguration setRetryCount(int retryCount) {
            return new ZooKeeperStrategyConfiguration(retryCount, retryIntervalMs, failoverTimeoutMs,
                                                    healthCheckIntervalMs, maxFailoverCount,
                                                    loadBalancingStrategy, nodeSelectionStrategy,
                                                    autoFailoverEnabled, healthCheckEnabled);
        }
        
        @Override
        public StrategyConfiguration setRetryIntervalMs(long retryIntervalMs) {
            return new ZooKeeperStrategyConfiguration(retryCount, retryIntervalMs, failoverTimeoutMs,
                                                    healthCheckIntervalMs, maxFailoverCount,
                                                    loadBalancingStrategy, nodeSelectionStrategy,
                                                    autoFailoverEnabled, healthCheckEnabled);
        }
        
        @Override
        public StrategyConfiguration setFailoverTimeoutMs(long failoverTimeoutMs) {
            return new ZooKeeperStrategyConfiguration(retryCount, retryIntervalMs, failoverTimeoutMs,
                                                    healthCheckIntervalMs, maxFailoverCount,
                                                    loadBalancingStrategy, nodeSelectionStrategy,
                                                    autoFailoverEnabled, healthCheckEnabled);
        }
        
        @Override
        public StrategyConfiguration setHealthCheckIntervalMs(long healthCheckIntervalMs) {
            return new ZooKeeperStrategyConfiguration(retryCount, retryIntervalMs, failoverTimeoutMs,
                                                    healthCheckIntervalMs, maxFailoverCount,
                                                    loadBalancingStrategy, nodeSelectionStrategy,
                                                    autoFailoverEnabled, healthCheckEnabled);
        }
        
        @Override
        public StrategyConfiguration setMaxFailoverCount(int maxFailoverCount) {
            return new ZooKeeperStrategyConfiguration(retryCount, retryIntervalMs, failoverTimeoutMs,
                                                    healthCheckIntervalMs, maxFailoverCount,
                                                    loadBalancingStrategy, nodeSelectionStrategy,
                                                    autoFailoverEnabled, healthCheckEnabled);
        }
        
        @Override
        public StrategyConfiguration setLoadBalancingStrategy(LoadBalancingStrategy strategy) {
            return new ZooKeeperStrategyConfiguration(retryCount, retryIntervalMs, failoverTimeoutMs,
                                                    healthCheckIntervalMs, maxFailoverCount,
                                                    strategy, nodeSelectionStrategy,
                                                    autoFailoverEnabled, healthCheckEnabled);
        }
        
        @Override
        public StrategyConfiguration setNodeSelectionStrategy(NodeSelectionStrategy strategy) {
            return new ZooKeeperStrategyConfiguration(retryCount, retryIntervalMs, failoverTimeoutMs,
                                                    healthCheckIntervalMs, maxFailoverCount,
                                                    loadBalancingStrategy, strategy,
                                                    autoFailoverEnabled, healthCheckEnabled);
        }
        
        @Override
        public StrategyConfiguration setAutoFailoverEnabled(boolean autoFailoverEnabled) {
            return new ZooKeeperStrategyConfiguration(retryCount, retryIntervalMs, failoverTimeoutMs,
                                                    healthCheckIntervalMs, maxFailoverCount,
                                                    loadBalancingStrategy, nodeSelectionStrategy,
                                                    autoFailoverEnabled, healthCheckEnabled);
        }
        
        @Override
        public StrategyConfiguration setHealthCheckEnabled(boolean healthCheckEnabled) {
            return new ZooKeeperStrategyConfiguration(retryCount, retryIntervalMs, failoverTimeoutMs,
                                                    healthCheckIntervalMs, maxFailoverCount,
                                                    loadBalancingStrategy, nodeSelectionStrategy,
                                                    autoFailoverEnabled, healthCheckEnabled);
        }
    }
    
    private static class ImmutableStrategyConfiguration implements StrategyConfiguration {
        private final StrategyConfiguration delegate;
        
        public ImmutableStrategyConfiguration(StrategyConfiguration delegate) {
            this.delegate = delegate;
        }
        
        @Override
        public int getRetryCount() { return delegate.getRetryCount(); }
        @Override
        public long getRetryIntervalMs() { return delegate.getRetryIntervalMs(); }
        @Override
        public long getFailoverTimeoutMs() { return delegate.getFailoverTimeoutMs(); }
        @Override
        public long getHealthCheckIntervalMs() { return delegate.getHealthCheckIntervalMs(); }
        @Override
        public int getMaxFailoverCount() { return delegate.getMaxFailoverCount(); }
        @Override
        public LoadBalancingStrategy getLoadBalancingStrategy() { return delegate.getLoadBalancingStrategy(); }
        @Override
        public NodeSelectionStrategy getNodeSelectionStrategy() { return delegate.getNodeSelectionStrategy(); }
        @Override
        public boolean isAutoFailoverEnabled() { return delegate.isAutoFailoverEnabled(); }
        @Override
        public boolean isHealthCheckEnabled() { return delegate.isHealthCheckEnabled(); }
        
        @Override
        public StrategyConfiguration setRetryCount(int retryCount) { 
            return delegate.setRetryCount(retryCount);
        }
        
        @Override
        public StrategyConfiguration setRetryIntervalMs(long retryIntervalMs) { 
            return delegate.setRetryIntervalMs(retryIntervalMs);
        }
        
        @Override
        public StrategyConfiguration setFailoverTimeoutMs(long failoverTimeoutMs) { 
            return delegate.setFailoverTimeoutMs(failoverTimeoutMs);
        }
        
        @Override
        public StrategyConfiguration setHealthCheckIntervalMs(long healthCheckIntervalMs) { 
            return delegate.setHealthCheckIntervalMs(healthCheckIntervalMs);
        }
        
        @Override
        public StrategyConfiguration setMaxFailoverCount(int maxFailoverCount) { 
            return delegate.setMaxFailoverCount(maxFailoverCount);
        }
        
        @Override
        public StrategyConfiguration setLoadBalancingStrategy(LoadBalancingStrategy strategy) { 
            return delegate.setLoadBalancingStrategy(strategy);
        }
        
        @Override
        public StrategyConfiguration setNodeSelectionStrategy(NodeSelectionStrategy strategy) { 
            return delegate.setNodeSelectionStrategy(strategy);
        }
        
        @Override
        public StrategyConfiguration setAutoFailoverEnabled(boolean autoFailoverEnabled) { 
            return delegate.setAutoFailoverEnabled(autoFailoverEnabled);
        }
        
        @Override
        public StrategyConfiguration setHealthCheckEnabled(boolean healthCheckEnabled) { 
            return delegate.setHealthCheckEnabled(healthCheckEnabled);
        }
    }
    
    private static class ImmutableHealthCheckResult implements HealthCheckResult {
        private final boolean healthy;
        private final HealthStatus status;
        private final long responseTimeMs;
        private final String details;
        private final long responseTime;
        private final String errorMessage;
        
        public ImmutableHealthCheckResult(boolean healthy, HealthStatus status, long responseTimeMs,
                                        String details, long responseTime, String errorMessage) {
            this.healthy = healthy;
            this.status = status;
            this.responseTimeMs = responseTimeMs;
            this.details = details;
            this.responseTime = responseTime;
            this.errorMessage = errorMessage;
        }
        
        @Override
        public boolean isHealthy() { return healthy; }
        @Override
        public HealthStatus getStatus() { return status; }
        @Override
        public long getCheckTimeMs() { return responseTimeMs; }
        @Override
        public String getDetails() { return details; }
        @Override
        public long getResponseTimeMs() { return responseTime; }
        @Override
        public String getErrorMessage() { return errorMessage; }
    }
    
    private static class HealthCheckState {
        private final String nodeId;
        private volatile HealthStatus status = HealthStatus.UNKNOWN;
        private volatile Instant lastCheckTime;
        private volatile int consecutiveFailures = 0;
        private volatile int connectionCount = 0;
        private volatile long latencyMs = 0;
        
        public HealthCheckState(String nodeId) {
            this.nodeId = nodeId;
        }
        
        public void updateStatus(HealthStatus status, Instant checkTime) {
            this.status = status;
            this.lastCheckTime = checkTime;
            
            if (status == HealthStatus.HEALTHY) {
                consecutiveFailures = 0;
            } else {
                consecutiveFailures++;
            }
        }
        
        public void updateLoad(int connectionCount, long latencyMs) {
            this.connectionCount = connectionCount;
            this.latencyMs = latencyMs;
        }
        
        public void reset() {
            this.status = HealthStatus.UNKNOWN;
            this.consecutiveFailures = 0;
        }
        
        public boolean isHealthy() {
            return status == HealthStatus.HEALTHY;
        }
        
        public String getNodeId() { return nodeId; }
        public HealthStatus getStatus() { return status; }
        public Instant getLastCheckTime() { return lastCheckTime; }
        public int getConsecutiveFailures() { return consecutiveFailures; }
        public int getConnectionCount() { return connectionCount; }
        public long getLatencyMs() { return latencyMs; }
    }
    
    private static class FailoverOperation {
        private final long operationId;
        private final OptimizedZooKeeperDistributedLock originalLock;
        private final Throwable failureReason;
        private final Instant startTime;
        
        public FailoverOperation(long operationId, OptimizedZooKeeperDistributedLock originalLock,
                               Throwable failureReason) {
            this.operationId = operationId;
            this.originalLock = originalLock;
            this.failureReason = failureReason;
            this.startTime = Instant.now();
        }
        
        public long getOperationId() { return operationId; }
        public OptimizedZooKeeperDistributedLock getOriginalLock() { return originalLock; }
        public Throwable getFailureReason() { return failureReason; }
        public Instant getStartTime() { return startTime; }
    }
    
    private static class LoadBalancer {
        private int roundRobinIndex = 0;
        
        public String selectOptimalNode(List<String> availableNodes, String lockName) {
            if (availableNodes == null || availableNodes.isEmpty()) {
                return null;
            }
            
            // 简化的轮询算法
            String selectedNode = availableNodes.get(roundRobinIndex % availableNodes.size());
            roundRobinIndex++;
            
            return selectedNode;
        }
    }
    
    private static class NodeSelector {
        public String selectOptimalNode(List<String> availableNodes, String lockName) {
            if (availableNodes == null || availableNodes.isEmpty()) {
                return null;
            }
            
            // 简化选择：返回第一个可用节点
            return availableNodes.get(0);
        }
    }
    
    private static class CircuitBreaker {
        private final AtomicBoolean isOpen = new AtomicBoolean(false);
        private final AtomicLong lastFailureTime = new AtomicLong(0);
        private final AtomicInteger failureCount = new AtomicLong(0);
        
        public boolean activate() {
            if (isOpen.getAndSet(true)) {
                return false; // 已经开启
            }
            lastFailureTime.set(System.currentTimeMillis());
            return true;
        }
        
        public boolean deactivate() {
            if (isOpen.getAndSet(false)) {
                failureCount.set(0);
                return true;
            }
            return false;
        }
        
        public void reset() {
            isOpen.set(false);
            failureCount.set(0);
            lastFailureTime.set(0);
        }
        
        public boolean isOpen() {
            return isOpen.get();
        }
        
        public void checkAndReset() {
            if (isOpen.get()) {
                long timeSinceLastFailure = System.currentTimeMillis() - lastFailureTime.get();
                if (timeSinceLastFailure > 60000) { // 60秒后尝试恢复
                    deactivate();
                }
            }
        }
    }
    
    public static class HighAvailabilityStatistics {
        private final long totalFailovers;
        private final long successfulFailovers;
        private final long failedFailovers;
        private final int totalNodes;
        private final int healthyNodes;
        private final int circuitBreakers;
        private final int activeFailoverOperations;
        
        public HighAvailabilityStatistics(long totalFailovers, long successfulFailovers, long failedFailovers,
                                        int totalNodes, int healthyNodes, int circuitBreakers,
                                        int activeFailoverOperations) {
            this.totalFailovers = totalFailovers;
            this.successfulFailovers = successfulFailovers;
            this.failedFailovers = failedFailovers;
            this.totalNodes = totalNodes;
            this.healthyNodes = healthyNodes;
            this.circuitBreakers = circuitBreakers;
            this.activeFailoverOperations = activeFailoverOperations;
        }
        
        public long getTotalFailovers() { return totalFailovers; }
        public long getSuccessfulFailovers() { return successfulFailovers; }
        public long getFailedFailovers() { return failedFailovers; }
        public int getTotalNodes() { return totalNodes; }
        public int getHealthyNodes() { return healthyNodes; }
        public int getCircuitBreakers() { return circuitBreakers; }
        public int getActiveFailoverOperations() { return activeFailoverOperations; }
        
        public double getFailoverSuccessRate() {
            return totalFailovers > 0 ? (double) successfulFailovers / totalFailovers : 0.0;
        }
        
        public double getNodeHealthRatio() {
            return totalNodes > 0 ? (double) healthyNodes / totalNodes : 0.0;
        }
    }
    
    public static class HighAvailabilityEvent {
        private final HighAvailabilityEventType type;
        private final Instant timestamp;
        private final Throwable error;
        private final String message;
        
        public HighAvailabilityEvent(HighAvailabilityEventType type, Instant timestamp,
                                   Throwable error, String message) {
            this.type = type;
            this.timestamp = timestamp;
            this.error = error;
            this.message = message;
        }
        
        public HighAvailabilityEventType getType() { return type; }
        public Instant getTimestamp() { return timestamp; }
        public Throwable getError() { return error; }
        public String getMessage() { return message; }
    }
    
    public interface HighAvailabilityEventListener {
        void onEvent(HighAvailabilityEvent event);
    }
    
    public enum HighAvailabilityEventType {
        STRATEGY_INITIALIZED,
        STRATEGY_INITIALIZATION_FAILED,
        STRATEGY_ENABLED,
        STRATEGY_DISABLED,
        FAILOVER_STARTED,
        FAILOVER_SUCCESS,
        FAILOVER_FAILED,
        CIRCUIT_BREAKER_ACTIVATED,
        CIRCUIT_BREAKER_DEACTIVATED,
        NODE_HEALTH_RESET,
        HEALTH_CHECK_PERFORMED
    }
    
    private enum FailoverType {
        MANUAL,
        CONNECTION_FAILURE,
        TIMEOUT,
        SESSION_EXPIRED,
        QUORUM_LOSS,
        UNKNOWN_ERROR
    }
}