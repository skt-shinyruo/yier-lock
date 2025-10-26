package com.mycorp.distributedlock.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 增强的分布式锁工厂接口
 * 提供工厂管理、健康检查、批量操作、状态监控等企业级功能
 */
public interface DistributedLockFactory extends AutoCloseable {

    /**
     * 获取分布式锁
     *
     * @param name 锁名称
     * @return 分布式锁实例
     */
    DistributedLock getLock(String name);

    /**
     * 获取分布式读写锁
     *
     * @param name 锁名称
     * @return 分布式读写锁实例
     */
    DistributedReadWriteLock getReadWriteLock(String name);

    /**
     * 获取配置锁（带配置参数）
     *
     * @param name 锁名称
     * @param configuration 锁配置
     * @return 分布式锁实例
     */
    DistributedLock getConfiguredLock(String name, LockConfigurationBuilder.LockConfiguration configuration);

    /**
     * 获取配置读写锁（带配置参数）
     *
     * @param name 锁名称
     * @param configuration 锁配置
     * @return 分布式读写锁实例
     */
    DistributedReadWriteLock getConfiguredReadWriteLock(String name, LockConfigurationBuilder.LockConfiguration configuration);

    /**
     * 批量获取锁
     *
     * @param lockNames 锁名称列表
     * @return 锁实例映射
     */
    Map<String, DistributedLock> getLocks(List<String> lockNames);

    /**
     * 批量获取读写锁
     *
     * @param lockNames 锁名称列表
     * @return 读写锁实例映射
     */
    Map<String, DistributedReadWriteLock> getReadWriteLocks(List<String> lockNames);

    /**
     * 异步获取锁
     *
     * @param name 锁名称
     * @return 异步锁实例
     */
    CompletableFuture<DistributedLock> getLockAsync(String name);

    /**
     * 异步获取读写锁
     *
     * @param name 锁名称
     * @return 异步读写锁实例
     */
    CompletableFuture<DistributedReadWriteLock> getReadWriteLockAsync(String name);

    /**
     * 执行批量锁操作
     *
     * @param lockNames 锁名称列表
     * @param operation 锁操作
     * @param <R> 操作结果类型
     * @return 批量操作结果
     */
    <R> BatchLockOperations<DistributedLock> createBatchLockOperations(List<String> lockNames,
                                                                      BatchLockOperations.BatchOperationExecutor<R> operation);

    /**
     * 创建异步锁操作器
     *
     * @return 异步锁操作器
     */
    AsyncLockOperations<DistributedLock> createAsyncLockOperations();

    /**
     * 获取工厂健康状态
     *
     * @return 健康检查结果
     */
    FactoryHealthStatus healthCheck();

    /**
     * 异步执行健康检查
     *
     * @return 异步健康检查结果
     */
    CompletableFuture<FactoryHealthStatus> healthCheckAsync();

    /**
     * 检查锁是否可用
     *
     * @param name 锁名称
     * @return 是否可用
     */
    boolean isLockAvailable(String name);

    /**
     * 异步检查锁是否可用
     *
     * @param name 锁名称
     * @return 异步可用性检查结果
     */
    CompletableFuture<Boolean> isLockAvailableAsync(String name);

    /**
     * 获取工厂统计信息
     *
     * @return 工厂统计信息
     */
    FactoryStatistics getStatistics();

    /**
     * 重置统计信息
     */
    default void resetStatistics() {
        // 默认空实现，子类可重写
    }

    /**
     * 获取所有活跃锁
     *
     * @return 活跃锁列表
     */
    List<String> getActiveLocks();

    /**
     * 释放指定的锁
     *
     * @param name 锁名称
     * @return 是否成功释放
     */
    default boolean releaseLock(String name) {
        DistributedLock lock = getLock(name);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
            return true;
        }
        return false;
    }

    /**
     * 异步释放指定的锁
     *
     * @param name 锁名称
     * @return 异步释放结果
     */
    default CompletableFuture<Boolean> releaseLockAsync(String name) {
        return CompletableFuture.supplyAsync(() -> releaseLock(name));
    }

    /**
     * 清理过期的锁
     *
     * @return 清理的锁数量
     */
    default int cleanupExpiredLocks() {
        // 默认实现，子类可重写
        return 0;
    }

    /**
     * 异步清理过期的锁
     *
     * @return 异步清理结果
     */
    default CompletableFuture<Integer> cleanupExpiredLocksAsync() {
        return CompletableFuture.supplyAsync(this::cleanupExpiredLocks);
    }

    /**
     * 获取工厂配置
     *
     * @return 工厂配置
     */
    FactoryConfiguration getConfiguration();

    /**
     * 更新工厂配置
     *
     * @param configuration 新配置
     */
    default void updateConfiguration(FactoryConfiguration configuration) {
        // 默认实现，子类可重写
    }

    /**
     * 获取工厂状态
     *
     * @return 工厂状态
     */
    FactoryState getState();

    /**
     * 设置工厂状态
     *
     * @param state 新状态
     */
    default void setState(FactoryState state) {
        // 默认实现，子类可重写
    }

    /**
     * 注册事件监听器
     *
     * @param listener 事件监听器
     */
    default <L extends DistributedLock> void registerEventListener(LockEventListener<L> listener) {
        // 默认实现，子类可重写
    }

    /**
     * 注销事件监听器
     *
     * @param listener 事件监听器
     */
    default <L extends DistributedLock> void unregisterEventListener(LockEventListener<L> listener) {
        // 默认实现，子类可重写
    }

    /**
     * 获取支持的高可用策略
     *
     * @return 高可用策略列表
     */
    List<HighAvailabilityStrategy<?>> getSupportedHighAvailabilityStrategies();

    /**
     * 设置高可用策略
     *
     * @param strategy 高可用策略
     */
    default void setHighAvailabilityStrategy(HighAvailabilityStrategy<?> strategy) {
        // 默认实现，子类可重写
    }

    /**
     * 获取执行器服务
     *
     * @return 执行器服务
     */
    default ScheduledExecutorService getScheduledExecutorService() {
        // 默认实现，子类可重写
        return null;
    }

    /**
     * 设置执行器服务
     *
     * @param executor 执行器服务
     */
    default void setScheduledExecutorService(ScheduledExecutorService executor) {
        // 默认实现，子类可重写
    }

    /**
     * 获取工厂名称
     *
     * @return 工厂名称
     */
    default String getFactoryName() {
        return this.getClass().getSimpleName();
    }

    /**
     * 获取工厂版本
     *
     * @return 工厂版本
     */
    default String getFactoryVersion() {
        return "1.0.0";
    }

    /**
     * 优雅关闭
     *
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 是否成功关闭
     */
    default boolean gracefulShutdown(long timeout, java.util.concurrent.TimeUnit unit) {
        // 默认实现，子类可重写
        shutdown();
        return true;
    }

    /**
     * 是否正在关闭
     *
     * @return 是否正在关闭
     */
    default boolean isShutdown() {
        return getState() == FactoryState.SHUTDOWN || getState() == FactoryState.TERMINATED;
    }

    /**
     * 是否已终止
     *
     * @return 是否已终止
     */
    default boolean isTerminated() {
        return getState() == FactoryState.TERMINATED;
    }

    /**
     * 等待终止
     *
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 是否在超时前终止
     * @throws InterruptedException 当线程被中断时
     */
    default boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
        // 默认实现，子类可重写
        Thread.sleep(unit.toMillis(timeout));
        return isTerminated();
    }

    void shutdown();

    /**
     * 工厂健康状态
     */
    interface FactoryHealthStatus {
        /**
         * 是否健康
         */
        boolean isHealthy();

        /**
         * 获取状态详情
         */
        String getDetails();

        /**
         * 获取检查时间
         */
        long getCheckTime();

        /**
         * 获取性能指标
         */
        PerformanceMetrics getPerformanceMetrics();

        /**
         * 获取错误信息
         */
        String getErrorMessage();

        /**
         * 性能指标
         */
        interface PerformanceMetrics {
            /**
             * 获取响应时间
             */
            long getResponseTimeMs();

            /**
             * 获取吞吐量
             */
            double getThroughput();

            /**
             * 获取错误率
             */
            double getErrorRate();

            /**
             * 获取活跃连接数
             */
            int getActiveConnections();
        }
    }

    /**
     * 工厂统计信息
     */
    interface FactoryStatistics {
        /**
         * 获取总锁数量
         */
        long getTotalLocks();

        /**
         * 获取活跃锁数量
         */
        long getActiveLocks();

        /**
         * 获取获取锁的总数
         */
        long getTotalLockAcquisitions();

        /**
         * 获取获取锁失败总数
         */
        long getFailedLockAcquisitions();

        /**
         * 获取释放锁的总数
         */
        long getTotalLockReleases();

        /**
         * 获取平均获取锁时间
         */
        double getAverageLockAcquisitionTime();

        /**
         * 获取峰值并发数
         */
        int getPeakConcurrency();

        /**
         * 获取运行时长
         */
        long getUptime();

        /**
         * 获取内存使用情况
         */
        MemoryUsage getMemoryUsage();

        /**
         * 内存使用情况
         */
        interface MemoryUsage {
            /**
             * 获取已使用内存
             */
            long getUsedMemory();

            /**
             * 获取总内存
             */
            long getTotalMemory();

            /**
             * 获取可用内存
             */
            long getAvailableMemory();

            /**
             * 获取内存使用率
             */
            double getUsageRate();
        }
    }

    /**
     * 工厂配置
     */
    interface FactoryConfiguration {
        /**
         * 获取最大锁数量
         */
        int getMaxLocks();

        /**
         * 获取连接超时时间
         */
        long getConnectionTimeout(java.util.concurrent.TimeUnit unit);

        /**
         * 获取操作超时时间
         */
        long getOperationTimeout(java.util.concurrent.TimeUnit unit);

        /**
         * 获取重试次数
         */
        int getRetryCount();

        /**
         * 获取重试间隔
         */
        long getRetryInterval(java.util.concurrent.TimeUnit unit);

        /**
         * 是否启用缓存
         */
        boolean isCacheEnabled();

        /**
         * 是否启用监控
         */
        boolean isMonitoringEnabled();

        /**
         * 是否启用性能指标收集
         */
        boolean isMetricsEnabled();

        /**
         * 设置最大锁数量
         */
        FactoryConfiguration setMaxLocks(int maxLocks);

        /**
         * 设置连接超时时间
         */
        FactoryConfiguration setConnectionTimeout(long timeout, java.util.concurrent.TimeUnit unit);

        /**
         * 设置操作超时时间
         */
        FactoryConfiguration setOperationTimeout(long timeout, java.util.concurrent.TimeUnit unit);

        /**
         * 设置重试次数
         */
        FactoryConfiguration setRetryCount(int retryCount);

        /**
         * 设置重试间隔
         */
        FactoryConfiguration setRetryInterval(long interval, java.util.concurrent.TimeUnit unit);

        /**
         * 设置是否启用缓存
         */
        FactoryConfiguration setCacheEnabled(boolean enabled);

        /**
         * 设置是否启用监控
         */
        FactoryConfiguration setMonitoringEnabled(boolean enabled);

        /**
         * 设置是否启用性能指标收集
         */
        FactoryConfiguration setMetricsEnabled(boolean enabled);
    }

    /**
     * 工厂状态枚举
     */
    enum FactoryState {
        /** 创建中 */
        CREATING,
        /** 运行中 */
        RUNNING,
        /** 暂停中 */
        PAUSED,
        /** 关闭中 */
        SHUTDOWN,
        /** 已终止 */
        TERMINATED,
        /** 错误状态 */
        ERROR
    }
}
