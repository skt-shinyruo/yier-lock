package com.mycorp.distributedlock.zookeeper.cluster;

import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import com.mycorp.distributedlock.zookeeper.connection.ZooKeeperConnectionManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.utils.CloseableUtils;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Zookeeper集群高可用管理器
 * 
 * 主要功能：
 * - 集群节点管理和健康检查
 * - Leader选举和Follower切换
 * - 自动故障检测和转移
 * - 负载均衡和节点选择
 * - 集群状态监控
 * - 会话管理和恢复
 */
public class ZooKeeperClusterManager implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperClusterManager.class);
    
    // 集群配置
    private static final String CLUSTER_BASE_PATH = "/distributed-lock/cluster";
    private static final String LEADER_ELECTION_PATH = CLUSTER_BASE_PATH + "/leader-election";
    private static final String NODE_HEALTH_PATH = CLUSTER_BASE_PATH + "/health";
    private static final String CLUSTER_CONFIG_PATH = CLUSTER_BASE_PATH + "/config";
    
    // Leader选举相关
    private static final String LEADER_NODE_PREFIX = "leader-";
    private static final Duration DEFAULT_LEADER_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration LEADER_ELECTION_INTERVAL = Duration.ofSeconds(5);
    
    // 健康检查配置
    private static final Duration HEALTH_CHECK_INTERVAL = Duration.ofSeconds(10);
    private static final Duration NODE_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_FAILED_HEALTH_CHECKS = 3;
    
    // 负载均衡配置
    private static final int CONNECTION_WEIGHT = 10;
    private static final int LATENCY_WEIGHT = 5;
    private static final int HEALTH_WEIGHT = 20;
    
    // 状态管理
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isLeader = new AtomicBoolean(false);
    private final AtomicLong lastLeaderHeartbeat = new AtomicLong(0);
    
    // 集群管理
    private final ZooKeeperConnectionManager connectionManager;
    private final LockConfiguration configuration;
    private final LockMetrics metrics;
    private final LockTracing tracing;
    private final String nodeId;
    private final String nodeAddress;
    private final Instant startTime;
    
    // 集群节点状态
    private final Map<String, ClusterNodeInfo> clusterNodes = new ConcurrentHashMap<>();
    private final ReadWriteLock clusterNodesLock = new ReentrantReadWriteLock();
    
    // Leader选举
    private final LeaderElectionManager leaderElectionManager;
    
    // 线程池
    private final ScheduledExecutorService clusterHealthExecutor;
    private final ScheduledExecutorService leaderElectionExecutor;
    private final ScheduledExecutorService failoverExecutor;
    private final ExecutorService asyncOperationExecutor;
    
    // 指标收集
    private final Timer nodeHealthCheckTimer;
    private final Timer leaderElectionTimer;
    private final Counter failoverTriggerCounter;
    private final Counter leaderElectionCounter;
    private final Gauge healthyNodesGauge;
    private final Gauge totalNodesGauge;
    private final Gauge leaderNodeGauge;
    
    // 事件监听
    private final List<ClusterEventListener> eventListeners = new CopyOnWriteArrayList<>();
    
    /**
     * 构造函数
     * 
     * @param connectionManager 连接管理器
     * @param configuration 配置
     * @param meterRegistry 指标注册器
     * @param openTelemetry 分布式追踪
     * @param nodeId 节点ID
     * @param nodeAddress 节点地址
     */
    public ZooKeeperClusterManager(ZooKeeperConnectionManager connectionManager,
                                 LockConfiguration configuration,
                                 io.micrometer.core.instrument.MeterRegistry meterRegistry,
                                 OpenTelemetry openTelemetry,
                                 String nodeId,
                                 String nodeAddress) {
        this.connectionManager = connectionManager;
        this.configuration = configuration;
        this.metrics = new LockMetrics(meterRegistry, configuration.isMetricsEnabled());
        this.tracing = new LockTracing(openTelemetry, configuration.isTracingEnabled());
        this.nodeId = nodeId;
        this.nodeAddress = nodeAddress;
        this.startTime = Instant.now();
        
        // 初始化线程池
        this.clusterHealthExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "zookeeper-cluster-health");
            t.setDaemon(true);
            return t;
        });
        
        this.leaderElectionExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "zookeeper-leader-election");
            t.setDaemon(true);
            return t;
        });
        
        this.failoverExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zookeeper-failover");
            t.setDaemon(true);
            return t;
        });
        
        this.asyncOperationExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(), r -> {
                Thread t = new Thread(r, "zookeeper-cluster-async");
                t.setDaemon(true);
                return t;
            }
        );
        
        // 初始化指标
        this.nodeHealthCheckTimer = metrics.createTimer("zookeeper.cluster.node.health.check.time");
        this.leaderElectionTimer = metrics.createTimer("zookeeper.cluster.leader.election.time");
        this.failoverTriggerCounter = metrics.createCounter("zookeeper.cluster.failover.trigger.count");
        this.leaderElectionCounter = metrics.createCounter("zookeeper.cluster.leader.election.count");
        this.healthyNodesGauge = metrics.createGauge("zookeeper.cluster.healthy.nodes.count", this::getHealthyNodesCount);
        this.totalNodesGauge = metrics.createGauge("zookeeper.cluster.total.nodes.count", this::getTotalNodesCount);
        this.leaderNodeGauge = metrics.createGauge("zookeeper.cluster.leader.node.flag", () -> isLeader.get() ? 1 : 0);
        
        // 初始化Leader选举管理器
        this.leaderElectionManager = new LeaderElectionManager();
        
        // 添加连接状态监听器
        connectionManager.addConnectionStateListener((client, newState) -> {
            handleConnectionStateChanged(newState);
        });
        
        logger.info("ZooKeeperClusterManager initialized for node: {} at address: {}", nodeId, nodeAddress);
    }
    
    /**
     * 初始化集群管理器
     * 
     * @throws Exception 初始化失败
     */
    public synchronized void initialize() throws Exception {
        if (isInitialized.getAndSet(true)) {
            return;
        }
        
        logger.info("Initializing ZooKeeperClusterManager");
        
        try {
            // 创建集群基础路径
            createClusterPaths();
            
            // 注册当前节点
            registerCurrentNode();
            
            // 启动Leader选举
            startLeaderElection();
            
            // 启动集群健康检查
            startClusterHealthMonitoring();
            
            // 通知初始化完成
            notifyEvent(ClusterEventType.INITIALIZED, null, "Cluster manager initialized successfully");
            
            logger.info("ZooKeeperClusterManager initialized successfully");
        } catch (Exception e) {
            isInitialized.set(false);
            notifyEvent(ClusterEventType.INITIALIZATION_FAILED, e, "Failed to initialize cluster manager");
            throw e;
        }
    }
    
    /**
     * 获取当前集群状态
     * 
     * @return 集群状态
     */
    public ClusterState getClusterState() {
        Instant now = Instant.now();
        Duration uptime = Duration.between(startTime, now);
        
        int totalNodes = clusterNodes.size();
        int healthyNodes = (int) clusterNodes.values().stream()
            .filter(node -> node.isHealthy())
            .count();
        
        int leaderNodeCount = (int) clusterNodes.values().stream()
            .filter(node -> node.isLeader())
            .count();
        
        return new ClusterState(
            nodeId,
            isLeader.get(),
            totalNodes,
            healthyNodes,
            leaderNodeCount,
            uptime,
            now,
            getClusterHealthScore()
        );
    }
    
    /**
     * 获取所有集群节点信息
     * 
     * @return 节点信息列表
     */
    public List<ClusterNodeInfo> getAllClusterNodes() {
        clusterNodesLock.readLock().lock();
        try {
            return new ArrayList<>(clusterNodes.values());
        } finally {
            clusterNodesLock.readLock().unlock();
        }
    }
    
    /**
     * 获取健康的集群节点
     * 
     * @return 健康节点列表
     */
    public List<ClusterNodeInfo> getHealthyClusterNodes() {
        clusterNodesLock.readLock().lock();
        try {
            return clusterNodes.values().stream()
                .filter(ClusterNodeInfo::isHealthy)
                .collect(Collectors.toList());
        } finally {
            clusterNodesLock.readLock().unlock();
        }
    }
    
    /**
     * 获取最优节点进行连接
     * 
     * @param excludeNodeId 排除的节点ID（可选）
     * @return 最优节点信息
     */
    public Optional<ClusterNodeInfo> getOptimalNode(String excludeNodeId) {
        List<ClusterNodeInfo> healthyNodes = getHealthyClusterNodes();
        
        if (excludeNodeId != null) {
            healthyNodes = healthyNodes.stream()
                .filter(node -> !node.getNodeId().equals(excludeNodeId))
                .collect(Collectors.toList());
        }
        
        if (healthyNodes.isEmpty()) {
            return Optional.empty();
        }
        
        // 根据负载均衡算法选择最优节点
        return healthyNodes.stream()
            .max(Comparator.comparingDouble(this::calculateNodeScore))
            .map(Optional::of)
            .orElse(Optional.empty());
    }
    
    /**
     * 执行故障转移
     * 
     * @param failedNodeId 失败的节点ID
     * @return 是否转移成功
     */
    public boolean executeFailover(String failedNodeId) {
        logger.info("Executing failover for failed node: {}", failedNodeId);
        
        if (!isInitialized.get()) {
            logger.warn("Cluster manager not initialized, cannot execute failover");
            return false;
        }
        
        failoverTriggerCounter.increment();
        
        try {
            // 标记失败节点
            markNodeAsFailed(failedNodeId);
            
            // 如果失败的节点是Leader，需要重新选举
            if (isNodeLeader(failedNodeId)) {
                logger.info("Failed node was leader, triggering new leader election");
                triggerNewLeaderElection();
            }
            
            // 重新平衡负载
            rebalanceClusterLoad();
            
            // 通知故障转移事件
            notifyEvent(ClusterEventType.FAILOVER_EXECUTED, null, 
                       "Failover executed for node: " + failedNodeId);
            
            return true;
        } catch (Exception e) {
            logger.error("Failed to execute failover for node: {}", failedNodeId, e);
            notifyEvent(ClusterEventType.FAILOVER_FAILED, e, 
                       "Failover failed for node: " + failedNodeId);
            return false;
        }
    }
    
    /**
     * 添加集群事件监听器
     * 
     * @param listener 监听器
     */
    public void addClusterEventListener(ClusterEventListener listener) {
        if (listener != null) {
            eventListeners.add(listener);
        }
    }
    
    /**
     * 移除集群事件监听器
     * 
     * @param listener 监听器
     */
    public void removeClusterEventListener(ClusterEventListener listener) {
        eventListeners.remove(listener);
    }
    
    /**
     * 获取当前节点是否是Leader
     * 
     * @return 是否是Leader
     */
    public boolean isCurrentNodeLeader() {
        return isLeader.get();
    }
    
    /**
     * 获取当前节点的Leader超时时间
     * 
     * @return Leader超时时间
     */
    public Duration getLeaderTimeout() {
        return getConfigurationDuration("leader-timeout", DEFAULT_LEADER_TIMEOUT);
    }
    
    /**
     * 手动触发Leader重新选举
     * 
     * @return 是否成功触发
     */
    public boolean triggerNewLeaderElection() {
        logger.info("Manually triggering new leader election");
        leaderElectionCounter.increment();
        
        try {
            leaderElectionManager.startElection();
            return true;
        } catch (Exception e) {
            logger.error("Failed to trigger leader election", e);
            return false;
        }
    }
    
    /**
     * 强制成为Leader（仅用于测试或特殊场景）
     * 
     * @return 是否成功
     */
    public boolean forceBecomeLeader() {
        if (!isInitialized.get()) {
            return false;
        }
        
        logger.info("Forcing current node to become leader");
        return leaderElectionManager.forceLeadership();
    }
    
    /**
     * 获取集群配置信息
     * 
     * @return 集群配置
     */
    public ClusterConfiguration getClusterConfiguration() {
        return new ClusterConfiguration(
            getConfigurationInt("max-nodes", 100),
            getConfigurationInt("min-healthy-nodes", 1),
            getConfigurationDuration("health-check-interval", HEALTH_CHECK_INTERVAL),
            getConfigurationDuration("node-timeout", NODE_TIMEOUT),
            getConfigurationInt("max-failed-health-checks", MAX_FAILED_HEALTH_CHECKS)
        );
    }
    
    @Override
    public void close() {
        if (isShutdown.getAndSet(true)) {
            return;
        }
        
        logger.info("Shutting down ZooKeeperClusterManager");
        
        try {
            // 停止Leader选举
            leaderElectionManager.stopElection();
            
            // 关闭线程池
            shutdownExecutor(clusterHealthExecutor, "cluster-health");
            shutdownExecutor(leaderElectionExecutor, "leader-election");
            shutdownExecutor(failoverExecutor, "failover");
            shutdownExecutor(asyncOperationExecutor, "cluster-async");
            
            // 清理集群节点状态
            cleanupClusterState();
            
            logger.info("ZooKeeperClusterManager shutdown completed");
        } catch (Exception e) {
            logger.error("Error during shutdown", e);
        }
    }
    
    // 私有方法区域
    
    private void createClusterPaths() throws Exception {
        CuratorFramework connection = connectionManager.getConnection();
        
        // 创建集群基础路径
        createPathIfNotExists(connection, CLUSTER_BASE_PATH);
        createPathIfNotExists(connection, LEADER_ELECTION_PATH);
        createPathIfNotExists(connection, NODE_HEALTH_PATH);
        createPathIfNotExists(connection, CLUSTER_CONFIG_PATH);
    }
    
    private void createPathIfNotExists(CuratorFramework connection, String path) throws Exception {
        try {
            connection.checkExists().forPath(path);
        } catch (Exception e) {
            connection.create().creatingParentsIfNeeded().forPath(path);
        }
    }
    
    private void registerCurrentNode() throws Exception {
        ClusterNodeInfo currentNode = new ClusterNodeInfo(
            nodeId,
            nodeAddress,
            Instant.now(),
            NodeStatus.HEALTHY,
            true, // 是Leader候选者
            false, // 不是Leader
            0, // 没有连接数
            0 // 没有延迟
        );
        
        clusterNodesLock.writeLock().lock();
        try {
            clusterNodes.put(nodeId, currentNode);
        } finally {
            clusterNodesLock.writeLock().unlock();
        }
        
        logger.info("Registered current node: {} at address: {}", nodeId, nodeAddress);
    }
    
    private void startLeaderElection() throws Exception {
        leaderElectionManager.startElection();
    }
    
    private void startClusterHealthMonitoring() {
        clusterHealthExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!isShutdown.get()) {
                    performClusterHealthCheck();
                }
            } catch (Exception e) {
                logger.debug("Cluster health check error", e);
            }
        }, 5, 5, TimeUnit.SECONDS);
    }
    
    private void performClusterHealthCheck() {
        Timer.Sample sample = metrics.startTimer(nodeHealthCheckTimer);
        
        try {
            List<String> currentNodeIds = discoverCurrentNodes();
            Instant now = Instant.now();
            
            clusterNodesLock.writeLock().lock();
            try {
                // 更新已知节点状态
                for (String nodeId : currentNodeIds) {
                    ClusterNodeInfo nodeInfo = clusterNodes.get(nodeId);
                    if (nodeInfo == null) {
                        // 新发现的节点
                        nodeInfo = new ClusterNodeInfo(
                            nodeId, "unknown", now, NodeStatus.HEALTHY, 
                            true, false, 0, 0
                        );
                        clusterNodes.put(nodeId, nodeInfo);
                        logger.info("Discovered new cluster node: {}", nodeId);
                    } else {
                        // 更新心跳时间
                        nodeInfo.updateHeartbeat(now);
                    }
                }
                
                // 检查超时节点
                List<String> timeoutNodes = new ArrayList<>();
                for (ClusterNodeInfo nodeInfo : clusterNodes.values()) {
                    if (nodeInfo.isTimedOut(NODE_TIMEOUT) && !nodeInfo.getNodeId().equals(this.nodeId)) {
                        timeoutNodes.add(nodeInfo.getNodeId());
                    }
                }
                
                // 处理超时节点
                for (String timeoutNodeId : timeoutNodes) {
                    ClusterNodeInfo timeoutNode = clusterNodes.get(timeoutNodeId);
                    if (timeoutNode != null) {
                        timeoutNode.setStatus(NodeStatus.TIMEOUT);
                        logger.warn("Node {} marked as timeout", timeoutNodeId);
                        
                        // 执行故障转移
                        executeFailover(timeoutNodeId);
                    }
                }
                
                // 清理过期的节点（可选）
                cleanupExpiredNodes();
                
            } finally {
                clusterNodesLock.writeLock().unlock();
            }
            
            // 更新指标
            updateClusterMetrics();
            
        } finally {
            sample.stop(nodeHealthCheckTimer);
        }
    }
    
    private List<String> discoverCurrentNodes() {
        // 这里应该通过Zookeeper来发现集群节点
        // 目前简化实现，返回已知节点
        clusterNodesLock.readLock().lock();
        try {
            return new ArrayList<>(clusterNodes.keySet());
        } finally {
            clusterNodesLock.readLock().unlock();
        }
    }
    
    private void cleanupExpiredNodes() {
        Instant now = Instant.now();
        List<String> expiredNodes = new ArrayList<>();
        
        for (ClusterNodeInfo nodeInfo : clusterNodes.values()) {
            Duration nodeAge = Duration.between(nodeInfo.getLastHeartbeat(), now);
            if (nodeAge.toMinutes() > 60) { // 超过1小时的节点认为是过期的
                expiredNodes.add(nodeInfo.getNodeId());
            }
        }
        
        for (String expiredNodeId : expiredNodes) {
            clusterNodes.remove(expiredNodeId);
            logger.info("Removed expired cluster node: {}", expiredNodeId);
        }
    }
    
    private void updateClusterMetrics() {
        // 健康节点数已在Gauge中自动更新
        // 总节点数已在Gauge中自动更新
    }
    
    private void handleConnectionStateChanged(ConnectionState newState) {
        logger.info("Cluster manager connection state changed: {}", newState);
        
        switch (newState) {
            case CONNECTED:
                // 重新连接后，需要重新初始化集群状态
                if (!isInitialized.get()) {
                    try {
                        initialize();
                    } catch (Exception e) {
                        logger.error("Failed to reinitialize cluster manager", e);
                    }
                }
                break;
                
            case LOST:
                // 连接丢失，标记为非Leader
                if (isLeader.getAndSet(false)) {
                    notifyEvent(ClusterEventType.LEADERSHIP_LOST, null, "Connection lost, leadership relinquished");
                }
                break;
                
            case RECONNECTED:
                // 重新连接后，重新开始Leader选举
                if (isInitialized.get()) {
                    triggerNewLeaderElection();
                }
                break;
        }
    }
    
    private void markNodeAsFailed(String nodeId) {
        clusterNodesLock.writeLock().lock();
        try {
            ClusterNodeInfo node = clusterNodes.get(nodeId);
            if (node != null) {
                node.setStatus(NodeStatus.FAILED);
                logger.warn("Marked node {} as failed", nodeId);
            }
        } finally {
            clusterNodesLock.writeLock().unlock();
        }
    }
    
    private boolean isNodeLeader(String nodeId) {
        clusterNodesLock.readLock().lock();
        try {
            ClusterNodeInfo node = clusterNodes.get(nodeId);
            return node != null && node.isLeader();
        } finally {
            clusterNodesLock.readLock().unlock();
        }
    }
    
    private void rebalanceClusterLoad() {
        logger.info("Rebalancing cluster load after failover");
        // 简单的负载重平衡逻辑，可以根据需要进行扩展
        // 例如：重新分配Leader节点、平衡连接数等
    }
    
    private double calculateNodeScore(ClusterNodeInfo nodeInfo) {
        if (!nodeInfo.isHealthy()) {
            return 0.0;
        }
        
        // 基于连接数、延迟、健康状态的综合评分
        double connectionScore = Math.max(0, 100 - nodeInfo.getConnectionCount() * CONNECTION_WEIGHT);
        double latencyScore = Math.max(0, 100 - nodeInfo.getLatencyMs() * LATENCY_WEIGHT);
        double healthScore = HEALTH_WEIGHT * (nodeInfo.isHealthy() ? 1.0 : 0.0);
        
        return (connectionScore + latencyScore + healthScore) / 3.0;
    }
    
    private double getClusterHealthScore() {
        List<ClusterNodeInfo> nodes = getAllClusterNodes();
        if (nodes.isEmpty()) {
            return 0.0;
        }
        
        double totalScore = nodes.stream()
            .mapToDouble(this::calculateNodeScore)
            .sum();
        
        return totalScore / nodes.size();
    }
    
    private int getHealthyNodesCount() {
        return (int) clusterNodes.values().stream()
            .filter(ClusterNodeInfo::isHealthy)
            .count();
    }
    
    private int getTotalNodesCount() {
        return clusterNodes.size();
    }
    
    private void notifyEvent(ClusterEventType eventType, Throwable error, String message) {
        ClusterEvent event = new ClusterEvent(eventType, nodeId, Instant.now(), error, message);
        
        for (ClusterEventListener listener : eventListeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                logger.warn("Error in cluster event listener", e);
            }
        }
    }
    
    private void cleanupClusterState() {
        clusterNodesLock.writeLock().lock();
        try {
            clusterNodes.clear();
        } finally {
            clusterNodesLock.writeLock().unlock();
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
    
    private Duration getConfigurationDuration(String key, Duration defaultValue) {
        return configuration.getConfig().hasPath("distributed-lock.zookeeper.cluster." + key) ?
            Duration.ofMillis(configuration.getConfig().getLong("distributed-lock.zookeeper.cluster." + key)) :
            defaultValue;
    }
    
    private int getConfigurationInt(String key, int defaultValue) {
        return configuration.getConfig().hasPath("distributed-lock.zookeeper.cluster." + key) ?
            configuration.getConfig().getInt("distributed-lock.zookeeper.cluster." + key) :
            defaultValue;
    }
    
    // 内部类定义
    
    private class LeaderElectionManager {
        private final AtomicBoolean isRunning = new AtomicBoolean(false);
        private final ScheduledFuture<?> electionTask;
        
        public LeaderElectionManager() {
            this.electionTask = null;
        }
        
        public void startElection() throws Exception {
            if (isRunning.getAndSet(true)) {
                return;
            }
            
            logger.info("Starting leader election process");
            
            leaderElectionExecutor.scheduleAtFixedRate(() -> {
                try {
                    if (!isShutdown.get()) {
                        performLeaderElection();
                    }
                } catch (Exception e) {
                    logger.debug("Leader election error", e);
                }
            }, 0, (long) LEADER_ELECTION_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        }
        
        public void stopElection() {
            if (isRunning.getAndSet(false)) {
                logger.info("Stopping leader election process");
            }
        }
        
        public boolean forceLeadership() {
            logger.info("Forcing current node to take leadership");
            return becomeLeader();
        }
        
        private void performLeaderElection() {
            Timer.Sample sample = metrics.startTimer(leaderElectionTimer);
            
            try {
                Instant now = Instant.now();
                long currentTime = now.toEpochMilli();
                
                // 检查当前Leader是否超时
                boolean leaderTimeout = (currentTime - lastLeaderHeartbeat.get()) > getLeaderTimeout().toMillis();
                
                if (!isLeader.get() && (leaderTimeout || getAllClusterNodes().isEmpty())) {
                    // 尝试成为Leader
                    if (tryBecomeLeader()) {
                        logger.info("Successfully became cluster leader");
                        leaderElectionCounter.increment();
                    }
                } else if (isLeader.get()) {
                    // 更新Leader心跳
                    lastLeaderHeartbeat.set(currentTime);
                    
                    // 更新当前节点的Leader状态
                    clusterNodesLock.writeLock().lock();
                    try {
                        ClusterNodeInfo currentNode = clusterNodes.get(nodeId);
                        if (currentNode != null) {
                            currentNode.setLeader(true);
                        }
                    } finally {
                        clusterNodesLock.writeLock().unlock();
                    }
                }
                
            } catch (Exception e) {
                logger.debug("Leader election cycle error", e);
            } finally {
                sample.stop(leaderElectionTimer);
            }
        }
        
        private boolean tryBecomeLeader() {
            try {
                return becomeLeader();
            } catch (Exception e) {
                logger.debug("Failed to become leader", e);
                return false;
            }
        }
        
        private boolean becomeLeader() {
            clusterNodesLock.writeLock().lock();
            try {
                ClusterNodeInfo currentNode = clusterNodes.get(nodeId);
                if (currentNode != null && currentNode.isHealthy()) {
                    // 标记所有节点为非Leader
                    for (ClusterNodeInfo node : clusterNodes.values()) {
                        node.setLeader(false);
                    }
                    
                    // 标记当前节点为Leader
                    currentNode.setLeader(true);
                    isLeader.set(true);
                    lastLeaderHeartbeat.set(System.currentTimeMillis());
                    
                    notifyEvent(ClusterEventType.LEADERSHIP_ACQUIRED, null, 
                               "Current node became cluster leader");
                    
                    return true;
                }
            } finally {
                clusterNodesLock.writeLock().unlock();
            }
            
            return false;
        }
    }
    
    // 数据类定义
    
    public static class ClusterState {
        private final String currentNodeId;
        private final boolean isLeader;
        private final int totalNodes;
        private final int healthyNodes;
        private final int leaderNodeCount;
        private final Duration uptime;
        private final Instant timestamp;
        private final double healthScore;
        
        public ClusterState(String currentNodeId, boolean isLeader, int totalNodes, 
                          int healthyNodes, int leaderNodeCount, Duration uptime,
                          Instant timestamp, double healthScore) {
            this.currentNodeId = currentNodeId;
            this.isLeader = isLeader;
            this.totalNodes = totalNodes;
            this.healthyNodes = healthyNodes;
            this.leaderNodeCount = leaderNodeCount;
            this.uptime = uptime;
            this.timestamp = timestamp;
            this.healthScore = healthScore;
        }
        
        public String getCurrentNodeId() { return currentNodeId; }
        public boolean isLeader() { return isLeader; }
        public int getTotalNodes() { return totalNodes; }
        public int getHealthyNodes() { return healthyNodes; }
        public int getLeaderNodeCount() { return leaderNodeCount; }
        public Duration getUptime() { return uptime; }
        public Instant getTimestamp() { return timestamp; }
        public double getHealthScore() { return healthScore; }
    }
    
    public static class ClusterNodeInfo {
        private final String nodeId;
        private final String address;
        private final Instant registrationTime;
        private volatile Instant lastHeartbeat;
        private volatile NodeStatus status;
        private volatile boolean isLeaderCandidate;
        private volatile boolean isLeader;
        private volatile int connectionCount;
        private volatile long latencyMs;
        
        public ClusterNodeInfo(String nodeId, String address, Instant registrationTime,
                             NodeStatus status, boolean isLeaderCandidate, boolean isLeader,
                             int connectionCount, long latencyMs) {
            this.nodeId = nodeId;
            this.address = address;
            this.registrationTime = registrationTime;
            this.lastHeartbeat = registrationTime;
            this.status = status;
            this.isLeaderCandidate = isLeaderCandidate;
            this.isLeader = isLeader;
            this.connectionCount = connectionCount;
            this.latencyMs = latencyMs;
        }
        
        public void updateHeartbeat(Instant now) {
            this.lastHeartbeat = now;
            if (status == NodeStatus.TIMEOUT) {
                this.status = NodeStatus.HEALTHY;
            }
        }
        
        public boolean isTimedOut(Duration timeout) {
            return Duration.between(lastHeartbeat, Instant.now()).compareTo(timeout) > 0;
        }
        
        public boolean isHealthy() {
            return status == NodeStatus.HEALTHY;
        }
        
        // Getters and Setters
        public String getNodeId() { return nodeId; }
        public String getAddress() { return address; }
        public Instant getRegistrationTime() { return registrationTime; }
        public Instant getLastHeartbeat() { return lastHeartbeat; }
        public NodeStatus getStatus() { return status; }
        public void setStatus(NodeStatus status) { this.status = status; }
        public boolean isLeaderCandidate() { return isLeaderCandidate; }
        public void setLeaderCandidate(boolean leaderCandidate) { this.isLeaderCandidate = leaderCandidate; }
        public boolean isLeader() { return isLeader; }
        public void setLeader(boolean leader) { this.isLeader = leader; }
        public int getConnectionCount() { return connectionCount; }
        public void setConnectionCount(int connectionCount) { this.connectionCount = connectionCount; }
        public long getLatencyMs() { return latencyMs; }
        public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
    }
    
    public static class ClusterConfiguration {
        private final int maxNodes;
        private final int minHealthyNodes;
        private final Duration healthCheckInterval;
        private final Duration nodeTimeout;
        private final int maxFailedHealthChecks;
        
        public ClusterConfiguration(int maxNodes, int minHealthyNodes, Duration healthCheckInterval,
                                  Duration nodeTimeout, int maxFailedHealthChecks) {
            this.maxNodes = maxNodes;
            this.minHealthyNodes = minHealthyNodes;
            this.healthCheckInterval = healthCheckInterval;
            this.nodeTimeout = nodeTimeout;
            this.maxFailedHealthChecks = maxFailedHealthChecks;
        }
        
        public int getMaxNodes() { return maxNodes; }
        public int getMinHealthyNodes() { return minHealthyNodes; }
        public Duration getHealthCheckInterval() { return healthCheckInterval; }
        public Duration getNodeTimeout() { return nodeTimeout; }
        public int getMaxFailedHealthChecks() { return maxFailedHealthChecks; }
    }
    
    public static class ClusterEvent {
        private final ClusterEventType type;
        private final String nodeId;
        private final Instant timestamp;
        private final Throwable error;
        private final String message;
        
        public ClusterEvent(ClusterEventType type, String nodeId, Instant timestamp,
                          Throwable error, String message) {
            this.type = type;
            this.nodeId = nodeId;
            this.timestamp = timestamp;
            this.error = error;
            this.message = message;
        }
        
        public ClusterEventType getType() { return type; }
        public String getNodeId() { return nodeId; }
        public Instant getTimestamp() { return timestamp; }
        public Throwable getError() { return error; }
        public String getMessage() { return message; }
    }
    
    public interface ClusterEventListener {
        void onEvent(ClusterEvent event);
    }
    
    public enum ClusterEventType {
        INITIALIZED,
        INITIALIZATION_FAILED,
        LEADERSHIP_ACQUIRED,
        LEADERSHIP_LOST,
        FAILOVER_EXECUTED,
        FAILOVER_FAILED,
        NODE_DISCOVERED,
        NODE_TIMEOUT,
        NODE_RECOVERED
    }
    
    public enum NodeStatus {
        HEALTHY,
        DEGRADED,
        FAILED,
        TIMEOUT,
        MAINTENANCE
    }
}