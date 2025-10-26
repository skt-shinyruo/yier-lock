package com.mycorp.distributedlock.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 批量锁操作接口，提供高效的批量锁获取和释放能力
 * 支持事务性操作，确保批量操作的一致性
 * 
 * @param <T> 锁类型
 */
public interface BatchLockOperations<T extends DistributedLock> extends AutoCloseable {

    /**
     * 批量获取锁
     * 
     * @param lockNames 锁名称列表
     * @param leaseTime 租约时间
     * @param unit 时间单位
     * @return 批量锁操作结果
     * @throws InterruptedException 当线程被中断时
     */
    BatchLockResult<T> batchLock(List<String> lockNames, long leaseTime, TimeUnit unit) 
            throws InterruptedException;

    /**
     * 异步批量获取锁
     * 
     * @param lockNames 锁名称列表
     * @param leaseTime 租约时间
     * @param unit 时间单位
     * @return 异步批量锁操作结果
     */
    CompletableFuture<BatchLockResult<T>> batchLockAsync(List<String> lockNames, long leaseTime, TimeUnit unit);

    /**
     * 批量释放锁
     * 
     * @param locks 要释放的锁列表
     * @return 是否全部成功释放
     */
    boolean batchUnlock(List<T> locks);

    /**
     * 异步批量释放锁
     * 
     * @param locks 要释放的锁列表
     * @return 异步释放结果
     */
    CompletableFuture<Boolean> batchUnlockAsync(List<T> locks);

    /**
     * 事务性批量锁操作
     * 
     * @param lockNames 锁名称列表
     * @param leaseTime 租约时间
     * @param unit 时间单位
     * @param transactionCallback 事务回调
     * @param <R> 事务结果类型
     * @return 事务执行结果
     * @throws Exception 事务执行异常
     */
    <R> R executeInTransaction(List<String> lockNames, long leaseTime, TimeUnit unit,
                              TransactionalLockCallback<R, T> transactionCallback) throws Exception;

    /**
     * 异步事务性批量锁操作
     * 
     * @param lockNames 锁名称列表
     * @param leaseTime 租约时间
     * @param unit 时间单位
     * @param transactionCallback 事务回调
     * @param <R> 事务结果类型
     * @return 异步事务执行结果
     */
    <R> CompletableFuture<R> executeInTransactionAsync(List<String> lockNames, long leaseTime, TimeUnit unit,
                                                      TransactionalLockCallback<R, T> transactionCallback);

    /**
     * 获取指定锁
     * 
     * @param name 锁名称
     * @return 锁实例
     */
    T getLock(String name);

    /**
     * 锁获取策略
     */
    enum LockStrategy {
        /** 全或无策略 - 必须获取所有锁才成功 */
        ALL_OR_NOTHING,
        /** 部分获取策略 - 可以获取部分锁 */
        PARTIAL_ACCEPT,
        /** 按优先级获取策略 - 按指定顺序获取 */
        PRIORITY_ORDER
    }

    @Override
    default void close() {
        // 默认实现，子类可重写
    }

    /**
     * 批量锁操作结果
     */
    interface BatchLockResult<T extends DistributedLock> {
        /**
         * 获取成功的锁列表
         * 
         * @return 成功的锁列表
         */
        List<T> getSuccessfulLocks();

        /**
         * 获取失败的锁名称列表
         * 
         * @return 失败的锁名称列表
         */
        List<String> getFailedLockNames();

        /**
         * 是否所有锁都获取成功
         * 
         * @return 是否全部成功
         */
        boolean isAllSuccessful();

        /**
         * 获取操作耗时
         * 
         * @return 耗时毫秒数
         */
        long getOperationTimeMs();
    }

    /**
     * 事务性锁操作回调接口
     */
    @FunctionalInterface
    interface TransactionalLockCallback<R, T extends DistributedLock> {
        /**
         * 执行事务操作
         * 
         * @param locks 已获取的锁列表
         * @return 事务结果
         * @throws Exception 事务执行异常
         */
        R execute(List<T> locks) throws Exception;
    }

    /**
     * 批量操作执行器
     * 
     * @param <R> 执行结果类型
     */
    @FunctionalInterface
    interface BatchOperationExecutor<R> {
        /**
         * 执行批量操作
         * 
         * @param locks 锁列表
         * @return 执行结果
         * @throws Exception 执行异常
         */
        R execute(List<DistributedLock> locks) throws Exception;
    }
}