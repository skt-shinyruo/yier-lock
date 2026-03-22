package com.mycorp.distributedlock.api;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 增强的分布式锁接口
 * 提供完整的分布式锁功能，包括超时续期、事件回调、锁状态管理等高级特性
 */
public interface DistributedLock extends AutoCloseable {

    /**
     * 获取锁（同步）
     *
     * @param leaseTime 租约时间
     * @param unit 时间单位
     * @throws InterruptedException 当线程被中断时
     */
    void lock(long leaseTime, TimeUnit unit) throws InterruptedException;

    /**
     * 获取锁（同步，使用默认租约时间）
     *
     * @throws InterruptedException 当线程被中断时
     */
    void lock() throws InterruptedException;

    /**
     * 尝试获取锁（同步）
     *
     * @param waitTime 等待时间
     * @param leaseTime 租约时间
     * @param unit 时间单位
     * @return 是否获取成功
     * @throws InterruptedException 当线程被中断时
     */
    boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException;

    /**
     * 尝试获取锁（同步，使用默认时间）
     *
     * @return 是否获取成功
     * @throws InterruptedException 当线程被中断时
     */
    boolean tryLock() throws InterruptedException;

    /**
     * 释放锁（同步）
     */
    void unlock();

    /**
     * 异步获取锁
     *
     * @param leaseTime 租约时间
     * @param unit 时间单位
     * @return 异步结果
     */
    CompletableFuture<Void> lockAsync(long leaseTime, TimeUnit unit);

    /**
     * 异步获取锁（使用默认租约时间）
     *
     * @return 异步结果
     */
    CompletableFuture<Void> lockAsync();

    /**
     * 异步尝试获取锁
     *
     * @param waitTime 等待时间
     * @param leaseTime 租约时间
     * @param unit 时间单位
     * @return 异步结果
     */
    CompletableFuture<Boolean> tryLockAsync(long waitTime, long leaseTime, TimeUnit unit);

    /**
     * 异步尝试获取锁（使用默认时间）
     *
     * @return 异步结果
     */
    CompletableFuture<Boolean> tryLockAsync();

    /**
     * 异步释放锁
     *
     * @return 异步结果
     */
    CompletableFuture<Void> unlockAsync();

    /**
     * 检查锁是否被持有
     *
     * @return 是否被持有
     */
    boolean isLocked();

    /**
     * 检查锁是否被当前线程持有
     *
     * @return 是否被当前线程持有
     */
    boolean isHeldByCurrentThread();

    /**
     * 获取锁名称
     *
     * @return 锁名称
     */
    String getName();

    /**
     * 续期锁（同步）
     *
     * @param newLeaseTime 新的租约时间
     * @param unit 时间单位
     * @return 是否续期成功
     * @throws InterruptedException 当线程被中断时
     */
    default boolean renewLock(long newLeaseTime, TimeUnit unit) throws InterruptedException {
        // Generic renew is unsafe without backend-specific compare-and-renew support.
        // Implementations that can renew atomically should override this method.
        return false;
    }

    /**
     * 异步续期锁
     *
     * @param newLeaseTime 新的租约时间
     * @param unit 时间单位
     * @return 异步续期结果
     */
    default CompletableFuture<Boolean> renewLockAsync(long newLeaseTime, TimeUnit unit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return renewLock(newLeaseTime, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
    }

    /**
     * Legacy compatibility alias used by older benchmarks/examples.
     */
    default boolean renewLease() {
        try {
            return renewLock(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 设置自动续期任务
     *
     * @param renewInterval 续期间隔
     * @param unit 时间单位
     * @return 续期任务句柄
     */
    default ScheduledFuture<?> scheduleAutoRenewal(long renewInterval, TimeUnit unit) {
        return scheduleAutoRenewal(renewInterval, unit, null);
    }

    /**
     * 设置自动续期任务
     *
     * @param renewInterval 续期间隔
     * @param unit 时间单位
     * @param renewalCallback 续期回调
     * @return 续期任务句柄
     */
    ScheduledFuture<?> scheduleAutoRenewal(long renewInterval, TimeUnit unit,
                                          Consumer<RenewalResult> renewalCallback);

    /**
     * 取消自动续期任务
     *
     * @param renewalTask 续期任务句柄
     * @return 是否成功取消
     */
    default boolean cancelAutoRenewal(ScheduledFuture<?> renewalTask) {
        return renewalTask != null && renewalTask.cancel(false);
    }

    /**
     * 获取锁状态信息
     *
     * @return 锁状态信息
     */
    default LockStateInfo getLockStateInfo() {
        return new LockStateInfo() {
            @Override
            public boolean isLocked() {
                return DistributedLock.this.isLocked();
            }

            @Override
            public boolean isHeldByCurrentThread() {
                return DistributedLock.this.isHeldByCurrentThread();
            }

            @Override
            public String getHolder() {
                // 默认实现，子类可重写
                return null;
            }

            @Override
            public long getRemainingTime(TimeUnit unit) {
                // 默认实现，子类可重写
                return 0;
            }

            @Override
            public int getReentrantCount() {
                // 默认实现，子类可重写
                return isHeldByCurrentThread() ? 1 : 0;
            }

            @Override
            public Instant getCreationTime() {
                // 默认实现，子类可重写
                return null;
            }

            @Override
            public Instant getExpirationTime() {
                // 默认实现，子类可重写
                return null;
            }

            @Override
            public LockType getLockType() {
                return LockType.MUTEX;
            }

            @Override
            public String getMetadata() {
                // 默认实现，子类可重写
                return "{}";
            }
        };
    }

    /**
     * 获取锁配置信息
     *
     * @return 锁配置信息
     */
    default LockConfigurationInfo getConfigurationInfo() {
        return new LockConfigurationInfo() {
            @Override
            public long getDefaultLeaseTime(TimeUnit unit) {
                return unit.convert(30, TimeUnit.SECONDS); // 默认30秒
            }

            @Override
            public long getDefaultWaitTime(TimeUnit unit) {
                return unit.convert(10, TimeUnit.SECONDS); // 默认10秒
            }

            @Override
            public boolean isFairLock() {
                return false;
            }

            @Override
            public boolean isReentrant() {
                return true;
            }

            @Override
            public boolean isAutoRenewalEnabled() {
                return false;
            }

            @Override
            public long getRenewalInterval(TimeUnit unit) {
                return unit.convert(10, TimeUnit.SECONDS); // 默认10秒
            }

            @Override
            public double getRenewalRatio() {
                return 0.7; // 默认70%
            }
        };
    }

    /**
     * 健康检查
     *
     * @return 健康检查结果
     */
    default HealthCheckResult healthCheck() {
        try {
            final boolean alreadyHeldByCurrentThread = isHeldByCurrentThread();
            final boolean acquiredForCheck;
            final boolean isHealthy;
            final String details;

            if (alreadyHeldByCurrentThread) {
                acquiredForCheck = false;
                isHealthy = true;
                details = "Lock is held by current thread and working normally";
            } else {
                acquiredForCheck = tryLock(0, 100, TimeUnit.MILLISECONDS);
                if (acquiredForCheck) {
                    isHealthy = true;
                    details = "Lock is accessible and working normally";
                } else if (isLocked()) {
                    isHealthy = true;
                    details = "Lock is currently held by another owner but appears reachable";
                } else {
                    isHealthy = false;
                    details = "Lock is not accessible";
                }
            }

            if (acquiredForCheck) {
                unlock();
            }
            return new HealthCheckResult() {
                @Override
                public boolean isHealthy() {
                    return isHealthy;
                }

                @Override
                public String getDetails() {
                    return details;
                }

                @Override
                public long getCheckTime() {
                    return System.currentTimeMillis();
                }
            };
        } catch (Exception e) {
            return new HealthCheckResult() {
                @Override
                public boolean isHealthy() {
                    return false;
                }

                @Override
                public String getDetails() {
                    return "Health check failed: " + e.getMessage();
                }

                @Override
                public long getCheckTime() {
                    return System.currentTimeMillis();
                }
            };
        }
    }

    /**
     * 获取重入次数
     *
     * @return 重入次数
     */
    default int getReentrantCount() {
        return isHeldByCurrentThread() ? 1 : 0;
    }

    /**
     * 检查锁是否过期
     *
     * @return 是否过期
     */
    default boolean isExpired() {
        LockStateInfo stateInfo = getLockStateInfo();
        Instant expirationTime = stateInfo.getExpirationTime();
        return expirationTime != null && Instant.now().isAfter(expirationTime);
    }

    /**
     * 获取剩余时间
     *
     * @param unit 时间单位
     * @return 剩余时间
     */
    default long getRemainingTime(TimeUnit unit) {
        return getLockStateInfo().getRemainingTime(unit);
    }

    /**
     * 获取锁持有者信息
     *
     * @return 持有者信息
     */
    default String getLockHolder() {
        return getLockStateInfo().getHolder();
    }

    @Override
    default void close() {
        if (isHeldByCurrentThread()) {
            unlock();
        }
    }

    /**
     * 锁状态信息
     */
    interface LockStateInfo {
        /**
         * 是否被持有
         */
        boolean isLocked();

        /**
         * 是否被当前线程持有
         */
        boolean isHeldByCurrentThread();

        /**
         * 获取锁持有者
         */
        String getHolder();

        /**
         * 获取剩余时间
         *
         * @param unit 时间单位
         * @return 剩余时间
         */
        long getRemainingTime(TimeUnit unit);

        /**
         * 获取重入次数
         */
        int getReentrantCount();

        /**
         * 获取创建时间
         */
        Instant getCreationTime();

        /**
         * 获取过期时间
         */
        Instant getExpirationTime();

        /**
         * 获取锁类型
         */
        LockType getLockType();

        /**
         * 获取元数据
         */
        String getMetadata();
    }

    /**
     * 锁配置信息
     */
    interface LockConfigurationInfo {
        /**
         * 获取默认租约时间
         *
         * @param unit 时间单位
         * @return 默认租约时间
         */
        long getDefaultLeaseTime(TimeUnit unit);

        /**
         * 获取默认等待时间
         *
         * @param unit 时间单位
         * @return 默认等待时间
         */
        long getDefaultWaitTime(TimeUnit unit);

        /**
         * 是否公平锁
         */
        boolean isFairLock();

        /**
         * 是否可重入
         */
        boolean isReentrant();

        /**
         * 是否启用自动续期
         */
        boolean isAutoRenewalEnabled();

        /**
         * 获取续期间隔
         *
         * @param unit 时间单位
         * @return 续期间隔
         */
        long getRenewalInterval(TimeUnit unit);

        /**
         * 获取续期比例
         */
        double getRenewalRatio();
    }

    /**
     * 健康检查结果
     */
    interface HealthCheckResult {
        /**
         * 是否健康
         */
        boolean isHealthy();

        /**
         * 获取详细信息
         */
        String getDetails();

        /**
         * 获取检查时间
         */
        long getCheckTime();
    }

    /**
     * 续期结果
     */
    interface RenewalResult {
        /**
         * 是否续期成功
         */
        boolean isSuccess();

        /**
         * 获取失败原因
         */
        Throwable getFailureCause();

        /**
         * 获取续期时间
         */
        long getRenewalTime();

        /**
         * 获取新的过期时间
         */
        long getNewExpirationTime();
    }

    /**
     * 锁类型枚举
     */
    enum LockType {
        /** 互斥锁 */
        MUTEX,
        /** 可重入锁 */
        REENTRANT,
        /** 读写锁 */
        READ_WRITE,
        /** 公平锁 */
        FAIR,
        /** 信号量 */
        SEMAPHORE
    }
}
