package com.mycorp.distributedlock.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 高可用策略接口
 * 定义分布式锁的高可用性策略，包括故障转移、负载均衡、重试机制等
 * 
 * @param <T> 锁类型
 */
public interface HighAvailabilityStrategy<T extends DistributedLock> {

    /**
     * 执行故障转移操作
     * 
     * @param originalLock 原始锁实例
     * @param failureReason 失败原因
     * @return 故障转移后的锁实例
     * @throws Exception 故障转移失败时抛出
     */
    T failover(T originalLock, Throwable failureReason) throws Exception;

    /**
     * 异步执行故障转移操作
     * 
     * @param originalLock 原始锁实例
     * @param failureReason 失败原因
     * @return 异步故障转移结果
     */
    CompletableFuture<T> failoverAsync(T originalLock, Throwable failureReason);

    /**
     * 获取健康检查结果
     * 
     * @param lockName 锁名称
     * @return 健康状态信息
     */
    HealthCheckResult performHealthCheck(String lockName);

    /**
     * 异步执行健康检查
     * 
     * @param lockName 锁名称
     * @return 异步健康检查结果
     */
    CompletableFuture<HealthCheckResult> performHealthCheckAsync(String lockName);

    /**
     * 检查节点是否可用
     * 
     * @param nodeId 节点ID
     * @return 是否可用
     */
    boolean isNodeAvailable(String nodeId);

    /**
     * 获取备用节点列表
     * 
     * @param primaryNodeId 主节点ID
     * @return 备用节点列表
     */
    List<String> getBackupNodes(String primaryNodeId);

    /**
     * 选择最优节点
     * 
     * @param availableNodes 可用节点列表
     * @param lockName 锁名称
     * @return 最优节点ID
     */
    String selectOptimalNode(List<String> availableNodes, String lockName);

    /**
     * 获取策略名称
     * 
     * @return 策略名称
     */
    String getStrategyName();

    /**
     * 获取策略配置
     * 
     * @return 策略配置
     */
    StrategyConfiguration getConfiguration();

    /**
     * 更新策略配置
     * 
     * @param configuration 新的配置
     */
    void updateConfiguration(StrategyConfiguration configuration);

    /**
     * 检查策略是否启用
     * 
     * @return 是否启用
     */
    boolean isEnabled();

    /**
     * 设置策略启用状态
     * 
     * @param enabled 是否启用
     */
    void setEnabled(boolean enabled);

    /**
     * 优雅关闭策略
     */
    default void shutdown() {
        // 默认实现，子类可重写
    }

    /**
     * 健康检查结果
     */
    interface HealthCheckResult {
        /**
         * 是否健康
         * 
         * @return 是否健康
         */
        boolean isHealthy();

        /**
         * 获取健康状态
         * 
         * @return 健康状态
         */
        HealthStatus getStatus();

        /**
         * 获取检查时间
         * 
         * @return 检查时间（毫秒）
         */
        long getCheckTimeMs();

        /**
         * 获取详细信息
         * 
         * @return 详细信息
         */
        String getDetails();

        /**
         * 获取响应时间
         * 
         * @return 响应时间（毫秒）
         */
        long getResponseTimeMs();

        /**
         * 获取错误信息
         * 
         * @return 错误信息
         */
        String getErrorMessage();
    }

    /**
     * 策略配置
     */
    interface StrategyConfiguration {
        /**
         * 获取重试次数
         * 
         * @return 重试次数
         */
        int getRetryCount();

        /**
         * 获取重试间隔
         * 
         * @return 重试间隔（毫秒）
         */
        long getRetryIntervalMs();

        /**
         * 获取故障转移超时时间
         * 
         * @return 超时时间（毫秒）
         */
        long getFailoverTimeoutMs();

        /**
         * 获取健康检查间隔
         * 
         * @return 健康检查间隔（毫秒）
         */
        long getHealthCheckIntervalMs();

        /**
         * 获取最大故障转移次数
         * 
         * @return 最大故障转移次数
         */
        int getMaxFailoverCount();

        /**
         * 获取负载均衡策略
         * 
         * @return 负载均衡策略
         */
        LoadBalancingStrategy getLoadBalancingStrategy();

        /**
         * 获取节点选择策略
         * 
         * @return 节点选择策略
         */
        NodeSelectionStrategy getNodeSelectionStrategy();

        /**
         * 是否启用自动故障转移
         * 
         * @return 是否启用
         */
        boolean isAutoFailoverEnabled();

        /**
         * 是否启用健康检查
         * 
         * @return 是否启用
         */
        boolean isHealthCheckEnabled();

        /**
         * 设置重试次数
         * 
         * @param retryCount 重试次数
         * @return 配置实例
         */
        StrategyConfiguration setRetryCount(int retryCount);

        /**
         * 设置重试间隔
         * 
         * @param retryIntervalMs 重试间隔（毫秒）
         * @return 配置实例
         */
        StrategyConfiguration setRetryIntervalMs(long retryIntervalMs);

        /**
         * 设置故障转移超时时间
         * 
         * @param failoverTimeoutMs 超时时间（毫秒）
         * @return 配置实例
         */
        StrategyConfiguration setFailoverTimeoutMs(long failoverTimeoutMs);

        /**
         * 设置健康检查间隔
         * 
         * @param healthCheckIntervalMs 健康检查间隔（毫秒）
         * @return 配置实例
         */
        StrategyConfiguration setHealthCheckIntervalMs(long healthCheckIntervalMs);

        /**
         * 设置最大故障转移次数
         * 
         * @param maxFailoverCount 最大故障转移次数
         * @return 配置实例
         */
        StrategyConfiguration setMaxFailoverCount(int maxFailoverCount);

        /**
         * 设置负载均衡策略
         * 
         * @param strategy 负载均衡策略
         * @return 配置实例
         */
        StrategyConfiguration setLoadBalancingStrategy(LoadBalancingStrategy strategy);

        /**
         * 设置节点选择策略
         * 
         * @param strategy 节点选择策略
         * @return 配置实例
         */
        StrategyConfiguration setNodeSelectionStrategy(NodeSelectionStrategy strategy);

        /**
         * 设置是否启用自动故障转移
         * 
         * @param autoFailoverEnabled 是否启用
         * @return 配置实例
         */
        StrategyConfiguration setAutoFailoverEnabled(boolean autoFailoverEnabled);

        /**
         * 设置是否启用健康检查
         * 
         * @param healthCheckEnabled 是否启用
         * @return 配置实例
         */
        StrategyConfiguration setHealthCheckEnabled(boolean healthCheckEnabled);
    }

    /**
     * 负载均衡策略
     */
    enum LoadBalancingStrategy {
        /** 轮询 */
        ROUND_ROBIN,
        /** 加权轮询 */
        WEIGHTED_ROUND_ROBIN,
        /** 最少连接 */
        LEAST_CONNECTIONS,
        /** 最快响应 */
        FASTEST_RESPONSE,
        /** 一致性哈希 */
        CONSISTENT_HASHING,
        /** 随机 */
        RANDOM
    }

    /**
     * 节点选择策略
     */
    enum NodeSelectionStrategy {
        /** 选择最优节点 */
        OPTIMAL,
        /** 选择第一个可用节点 */
        FIRST_AVAILABLE,
        /** 选择最近的节点 */
        NEAREST,
        /** 选择负载最低的节点 */
        LOWEST_LOAD
    }

    /**
     * 健康状态
     */
    enum HealthStatus {
        /** 健康 */
        HEALTHY,
        /** 不健康 */
        UNHEALTHY,
        /** 部分可用 */
        DEGRADED,
        /** 未知 */
        UNKNOWN
    }

    /**
     * 默认策略配置实现
     */
    abstract class DefaultStrategyConfiguration implements StrategyConfiguration {
        private int retryCount = 3;
        private long retryIntervalMs = 1000;
        private long failoverTimeoutMs = 5000;
        private long healthCheckIntervalMs = 30000;
        private int maxFailoverCount = 3;
        private LoadBalancingStrategy loadBalancingStrategy = LoadBalancingStrategy.ROUND_ROBIN;
        private NodeSelectionStrategy nodeSelectionStrategy = NodeSelectionStrategy.OPTIMAL;
        private boolean autoFailoverEnabled = true;
        private boolean healthCheckEnabled = true;

        public DefaultStrategyConfiguration() {
            // 默认构造函数
        }

        @Override
        public int getRetryCount() {
            return retryCount;
        }

        @Override
        public long getRetryIntervalMs() {
            return retryIntervalMs;
        }

        @Override
        public long getFailoverTimeoutMs() {
            return failoverTimeoutMs;
        }

        @Override
        public long getHealthCheckIntervalMs() {
            return healthCheckIntervalMs;
        }

        @Override
        public int getMaxFailoverCount() {
            return maxFailoverCount;
        }

        @Override
        public LoadBalancingStrategy getLoadBalancingStrategy() {
            return loadBalancingStrategy;
        }

        @Override
        public NodeSelectionStrategy getNodeSelectionStrategy() {
            return nodeSelectionStrategy;
        }

        @Override
        public boolean isAutoFailoverEnabled() {
            return autoFailoverEnabled;
        }

        @Override
        public boolean isHealthCheckEnabled() {
            return healthCheckEnabled;
        }

        @Override
        public StrategyConfiguration setRetryCount(int retryCount) {
            this.retryCount = Math.max(0, retryCount);
            return this;
        }

        @Override
        public StrategyConfiguration setRetryIntervalMs(long retryIntervalMs) {
            this.retryIntervalMs = Math.max(0, retryIntervalMs);
            return this;
        }

        @Override
        public StrategyConfiguration setFailoverTimeoutMs(long failoverTimeoutMs) {
            this.failoverTimeoutMs = Math.max(0, failoverTimeoutMs);
            return this;
        }

        @Override
        public StrategyConfiguration setHealthCheckIntervalMs(long healthCheckIntervalMs) {
            this.healthCheckIntervalMs = Math.max(1000, healthCheckIntervalMs);
            return this;
        }

        @Override
        public StrategyConfiguration setMaxFailoverCount(int maxFailoverCount) {
            this.maxFailoverCount = Math.max(0, maxFailoverCount);
            return this;
        }

        @Override
        public StrategyConfiguration setLoadBalancingStrategy(LoadBalancingStrategy strategy) {
            this.loadBalancingStrategy = strategy;
            return this;
        }

        @Override
        public StrategyConfiguration setNodeSelectionStrategy(NodeSelectionStrategy strategy) {
            this.nodeSelectionStrategy = strategy;
            return this;
        }

        @Override
        public StrategyConfiguration setAutoFailoverEnabled(boolean autoFailoverEnabled) {
            this.autoFailoverEnabled = autoFailoverEnabled;
            return this;
        }

        @Override
        public StrategyConfiguration setHealthCheckEnabled(boolean healthCheckEnabled) {
            this.healthCheckEnabled = healthCheckEnabled;
            return this;
        }
    }
}