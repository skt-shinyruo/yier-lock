package com.mycorp.distributedlock.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 异步锁操作接口
 * 提供完整的异步分布式锁操作能力，包括自动续期、超时处理、错误恢复等高级特性
 * 
 * @param <T> 锁类型
 */
public interface AsyncLockOperations<T extends DistributedLock> extends AutoCloseable {

    /**
     * 异步获取锁
     * 
     * @param lockName 锁名称
     * @param leaseTime 租约时间
     * @param timeUnit 时间单位
     * @return 异步锁操作结果
     */
    CompletableFuture<AsyncLockResult<T>> acquireLockAsync(String lockName, long leaseTime, TimeUnit timeUnit);

    /**
     * 异步尝试获取锁
     * 
     * @param lockName 锁名称
     * @param waitTime 等待时间
     * @param leaseTime 租约时间
     * @param timeUnit 时间单位
     * @return 异步锁操作结果
     */
    CompletableFuture<AsyncLockResult<T>> tryAcquireLockAsync(String lockName, long waitTime, 
                                                             long leaseTime, TimeUnit timeUnit);

    /**
     * 异步释放锁
     * 
     * @param lock 锁实例
     * @return 异步释放结果
     */
    CompletableFuture<AsyncLockReleaseResult> releaseLockAsync(T lock);

    /**
     * 异步续期锁
     * 
     * @param lock 锁实例
     * @param newLeaseTime 新的租约时间
     * @param timeUnit 时间单位
     * @return 异步续期结果
     */
    CompletableFuture<AsyncLockRenewalResult> renewLockAsync(T lock, long newLeaseTime, TimeUnit timeUnit);

    /**
     * 异步获取锁信息
     * 
     * @param lockName 锁名称
     * @return 异步锁信息结果
     */
    CompletableFuture<AsyncLockInfoResult> getLockInfoAsync(String lockName);

    /**
     * 异步检查锁状态
     * 
     * @param lockName 锁名称
     * @return 异步锁状态结果
     */
    CompletableFuture<AsyncLockStateResult> checkLockStateAsync(String lockName);

    /**
     * 异步释放指定名称的锁
     * 
     * @param lockName 锁名称
     * @return 异步释放结果
     */
    CompletableFuture<AsyncLockReleaseResult> releaseLockByNameAsync(String lockName);

    /**
     * 异步等待锁释放
     * 
     * @param lockName 锁名称
     * @param timeout 超时时间
     * @param timeUnit 时间单位
     * @return 异步等待结果
     */
    CompletableFuture<AsyncWaitResult> waitForLockReleaseAsync(String lockName, long timeout, TimeUnit timeUnit);

    /**
     * 异步执行带锁的业务操作
     * 
     * @param lockName 锁名称
     * @param leaseTime 租约时间
     * @param timeUnit 时间单位
     * @param businessOperation 业务操作
     * @param <R> 业务结果类型
     * @return 异步业务执行结果
     */
    <R> CompletableFuture<AsyncBusinessOperationResult<R>> executeWithLockAsync(String lockName, 
                                                                               long leaseTime, TimeUnit timeUnit,
                                                                               AsyncBusinessOperation<R, T> businessOperation);

    /**
     * 异步执行带重试的锁操作
     * 
     * @param lockName 锁名称
     * @param leaseTime 租约时间
     * @param timeUnit 时间单位
     * @param maxRetries 最大重试次数
     * @param retryDelay 重试延迟
     * @param retryDelayTimeUnit 重试延迟时间单位
     * @param operation 锁操作
     * @param <R> 操作结果类型
     * @return 异步重试操作结果
     */
    <R> CompletableFuture<AsyncRetryOperationResult<R>> executeWithRetryAsync(String lockName, 
                                                                             long leaseTime, TimeUnit timeUnit,
                                                                             int maxRetries, long retryDelay, 
                                                                             TimeUnit retryDelayTimeUnit,
                                                                             AsyncRetryableLockOperation<R, T> operation);

    /**
     * 设置锁的自动续期
     * 
     * @param lock 锁实例
     * @param renewInterval 续期间隔
     * @param timeUnit 时间单位
     * @return 续期任务句柄
     */
    LockRenewalTask autoRenewLock(T lock, long renewInterval, TimeUnit timeUnit);

    /**
     * 取消自动续期
     * 
     * @param renewalTask 续期任务句柄
     */
    void cancelAutoRenew(LockRenewalTask renewalTask);

    /**
     * 获取异步操作配置
     * 
     * @return 异步操作配置
     */
    AsyncOperationConfiguration getConfiguration();

    /**
     * 更新异步操作配置
     * 
     * @param configuration 新的配置
     */
    void updateConfiguration(AsyncOperationConfiguration configuration);

    /**
     * 异步锁操作结果
     */
    interface AsyncLockResult<T extends DistributedLock> {
        /**
         * 是否操作成功
         * 
         * @return 是否成功
         */
        boolean isSuccess();

        /**
         * 获取锁实例
         * 
         * @return 锁实例
         */
        T getLock();

        /**
         * 获取失败原因
         * 
         * @return 失败原因
         */
        Throwable getFailureCause();

        /**
         * 获取操作耗时
         * 
         * @return 耗时毫秒数
         */
        long getOperationTimeMs();

        /**
         * 获取获取时间
         * 
         * @return 获取时间戳
         */
        long getAcquisitionTime();

        /**
         * 获取过期时间
         * 
         * @return 过期时间戳
         */
        long getExpirationTime();
    }

    /**
     * 异步锁释放结果
     */
    interface AsyncLockReleaseResult {
        /**
         * 是否释放成功
         * 
         * @return 是否成功
         */
        boolean isSuccess();

        /**
         * 获取失败原因
         * 
         * @return 失败原因
         */
        Throwable getFailureCause();

        /**
         * 获取操作耗时
         * 
         * @return 耗时毫秒数
         */
        long getOperationTimeMs();
    }

    /**
     * 异步锁续期结果
     */
    interface AsyncLockRenewalResult {
        /**
         * 是否续期成功
         * 
         * @return 是否成功
         */
        boolean isSuccess();

        /**
         * 获取新的过期时间
         * 
         * @return 新的过期时间戳
         */
        long getNewExpirationTime();

        /**
         * 获取失败原因
         * 
         * @return 失败原因
         */
        Throwable getFailureCause();

        /**
         * 获取操作耗时
         * 
         * @return 耗时毫秒数
         */
        long getOperationTimeMs();
    }

    /**
     * 异步锁信息结果
     */
    interface AsyncLockInfoResult {
        /**
         * 是否获取成功
         * 
         * @return 是否成功
         */
        boolean isSuccess();

        /**
         * 获取锁信息
         * 
         * @return 锁信息
         */
        LockInfo getLockInfo();

        /**
         * 获取失败原因
         * 
         * @return 失败原因
         */
        Throwable getFailureCause();
    }

    /**
     * 异步锁状态结果
     */
    interface AsyncLockStateResult {
        /**
         * 是否检查成功
         * 
         * @return 是否成功
         */
        boolean isSuccess();

        /**
         * 获取锁状态
         * 
         * @return 锁状态
         */
        LockState getLockState();

        /**
         * 获取失败原因
         * 
         * @return 失败原因
         */
        Throwable getFailureCause();
    }

    /**
     * 异步等待结果
     */
    interface AsyncWaitResult {
        /**
         * 是否等待成功
         * 
         * @return 是否成功
         */
        boolean isSuccess();

        /**
         * 是否超时
         * 
         * @return 是否超时
         */
        boolean isTimeout();

        /**
         * 获取等待时间
         * 
         * @return 等待时间毫秒数
         */
        long getWaitTimeMs();
    }

    /**
     * 异步业务操作结果
     */
    interface AsyncBusinessOperationResult<R> {
        /**
         * 是否执行成功
         * 
         * @return 是否成功
         */
        boolean isSuccess();

        /**
         * 获取业务结果
         * 
         * @return 业务结果
         */
        R getResult();

        /**
         * 获取失败原因
         * 
         * @return 失败原因
         */
        Throwable getFailureCause();

        /**
         * 获取执行耗时
         * 
         * @return 执行耗时毫秒数
         */
        long getExecutionTimeMs();
    }

    /**
     * 异步重试操作结果
     */
    interface AsyncRetryOperationResult<R> {
        /**
         * 是否最终成功
         * 
         * @return 是否成功
         */
        boolean isSuccess();

        /**
         * 获取最终结果
         * 
         * @return 最终结果
         */
        R getResult();

        /**
         * 获取总重试次数
         * 
         * @return 总重试次数
         */
        int getTotalRetries();

        /**
         * 获取失败原因
         * 
         * @return 失败原因
         */
        Throwable getFailureCause();

        /**
         * 获取总执行时间
         * 
         * @return 总执行时间毫秒数
         */
        long getTotalExecutionTimeMs();
    }

    /**
     * 锁信息
     */
    interface LockInfo {
        /**
         * 获取锁名称
         * 
         * @return 锁名称
         */
        String getLockName();

        /**
         * 获取锁持有者
         * 
         * @return 持有者信息
         */
        String getHolder();

        /**
         * 获取创建时间
         * 
         * @return 创建时间戳
         */
        long getCreationTime();

        /**
         * 获取过期时间
         * 
         * @return 过期时间戳
         */
        long getExpirationTime();

        /**
         * 获取剩余时间
         * 
         * @return 剩余时间毫秒数
         */
        long getRemainingTime();

        /**
         * 是否已过期
         * 
         * @return 是否已过期
         */
        boolean isExpired();
    }

    /**
     * 锁状态
     */
    enum LockState {
        /** 可用 */
        AVAILABLE,
        /** 已被占用 */
        OCCUPIED,
        /** 过期 */
        EXPIRED,
        /** 释放中 */
        RELEASING,
        /** 续期中 */
        RENEWING,
        /** 未知 */
        UNKNOWN
    }

    /**
     * 异步业务操作接口
     * 
     * @param <R> 结果类型
     * @param <T> 锁类型
     */
    @FunctionalInterface
    interface AsyncBusinessOperation<R, T extends DistributedLock> {
        /**
         * 执行业务操作
         * 
         * @param lock 锁实例
         * @return 业务结果
         * @throws Exception 业务异常
         */
        CompletableFuture<R> execute(T lock) throws Exception;
    }

    /**
     * 异步可重试锁操作接口
     * 
     * @param <R> 结果类型
     * @param <T> 锁类型
     */
    @FunctionalInterface
    interface AsyncRetryableLockOperation<R, T extends DistributedLock> {
        /**
         * 执行锁操作
         * 
         * @param lock 锁实例
         * @param attemptNumber 尝试次数（从1开始）
         * @return 操作结果
         * @throws Exception 操作异常
         */
        CompletableFuture<R> execute(T lock, int attemptNumber) throws Exception;
    }

    /**
     * 续期任务句柄
     */
    interface LockRenewalTask {
        /**
         * 是否正在运行
         * 
         * @return 是否运行
         */
        boolean isRunning();

        /**
         * 获取续期次数
         * 
         * @return 续期次数
         */
        long getRenewalCount();

        /**
         * 获取最后一次续期时间
         * 
         * @return 最后一次续期时间戳
         */
        long getLastRenewalTime();

        /**
         * 取消续期任务
         */
        void cancel();

        /**
         * 获取关联的锁
         * 
         * @return 锁实例
         */
        DistributedLock getLock();
    }

    /**
     * 异步操作配置
     */
    interface AsyncOperationConfiguration {
        /**
         * 获取默认超时时间
         * 
         * @return 默认超时时间（毫秒）
         */
        long getDefaultTimeoutMs();

        /**
         * 获取默认重试次数
         * 
         * @return 默认重试次数
         */
        int getDefaultRetryCount();

        /**
         * 获取默认重试间隔
         * 
         * @return 默认重试间隔（毫秒）
         */
        long getDefaultRetryDelayMs();

        /**
         * 获取续期间隔比例
         * 
         * @return 续期间隔比例
         */
        double getRenewalIntervalRatio();

        /**
         * 获取最大并发数
         * 
         * @return 最大并发数
         */
        int getMaxConcurrency();

        /**
         * 是否启用自动续期
         * 
         * @return 是否启用
         */
        boolean isAutoRenewalEnabled();

        /**
         * 设置默认超时时间
         * 
         * @param timeoutMs 超时时间（毫秒）
         * @return 配置实例
         */
        AsyncOperationConfiguration setDefaultTimeoutMs(long timeoutMs);

        /**
         * 设置默认重试次数
         * 
         * @param retryCount 重试次数
         * @return 配置实例
         */
        AsyncOperationConfiguration setDefaultRetryCount(int retryCount);

        /**
         * 设置默认重试间隔
         * 
         * @param retryDelayMs 重试间隔（毫秒）
         * @return 配置实例
         */
        AsyncOperationConfiguration setDefaultRetryDelayMs(long retryDelayMs);

        /**
         * 设置续期间隔比例
         * 
         * @param ratio 续期间隔比例
         * @return 配置实例
         */
        AsyncOperationConfiguration setRenewalIntervalRatio(double ratio);

        /**
         * 设置最大并发数
         * 
         * @param maxConcurrency 最大并发数
         * @return 配置实例
         */
        AsyncOperationConfiguration setMaxConcurrency(int maxConcurrency);

        /**
         * 设置是否启用自动续期
         * 
         * @param autoRenewalEnabled 是否启用
         * @return 配置实例
         */
        AsyncOperationConfiguration setAutoRenewalEnabled(boolean autoRenewalEnabled);
    }

    @Override
    default void close() {
        // 默认实现，子类可重写
    }
}