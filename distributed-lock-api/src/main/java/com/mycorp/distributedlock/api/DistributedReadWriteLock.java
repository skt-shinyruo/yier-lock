package com.mycorp.distributedlock.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 增强的分布式读写锁接口
 * 提供读写分离、锁升级降级、公平性控制等高级特性
 */
public interface DistributedReadWriteLock extends AutoCloseable {
    
    /**
     * 获取读锁
     *
     * @return 读锁实例
     */
    DistributedLock readLock();
    
    /**
     * 获取写锁
     *
     * @return 写锁实例
     */
    DistributedLock writeLock();
    
    /**
     * 获取锁名称
     *
     * @return 锁名称
     */
    String getName();

    /**
     * 尝试锁升级（从读锁升级到写锁）
     *
     * @param waitTime 等待时间
     * @param leaseTime 租约时间
     * @param unit 时间单位
     * @return 是否升级成功
     * @throws InterruptedException 当线程被中断时
     */
    default boolean tryUpgradeToWriteLock(long waitTime, long leaseTime, TimeUnit unit)
            throws InterruptedException {
        // 默认实现，子类可重写
        throw new UnsupportedOperationException("Upgrade not supported by this implementation");
    }

    /**
     * 异步尝试锁升级
     *
     * @param waitTime 等待时间
     * @param leaseTime 租约时间
     * @param unit 时间单位
     * @return 异步升级结果
     */
    default CompletableFuture<Boolean> tryUpgradeToWriteLockAsync(long waitTime, long leaseTime, TimeUnit unit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return tryUpgradeToWriteLock(waitTime, leaseTime, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
    }

    /**
     * 尝试锁降级（从写锁降级到读锁）
     *
     * @param leaseTime 租约时间
     * @param unit 时间单位
     * @return 是否降级成功
     */
    default boolean tryDowngradeToReadLock(long leaseTime, TimeUnit unit) {
        // 默认实现，子类可重写
        try {
            writeLock().unlock();
            readLock().lock(leaseTime, unit);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 异步尝试锁降级
     *
     * @param leaseTime 租约时间
     * @param unit 时间单位
     * @return 异步降级结果
     */
    default CompletableFuture<Boolean> tryDowngradeToReadLockAsync(long leaseTime, TimeUnit unit) {
        return CompletableFuture.supplyAsync(() -> tryDowngradeToReadLock(leaseTime, unit));
    }

    /**
     * 获取锁状态信息
     *
     * @return 读写锁状态信息
     */
    default ReadWriteLockStateInfo getReadWriteLockStateInfo() {
        return new ReadWriteLockStateInfo() {
            @Override
            public boolean isReadLocked() {
                return readLock().isLocked();
            }

            @Override
            public boolean isWriteLocked() {
                return writeLock().isLocked();
            }

            @Override
            public boolean isReadLockedByCurrentThread() {
                return readLock().isHeldByCurrentThread();
            }

            @Override
            public boolean isWriteLockedByCurrentThread() {
                return writeLock().isHeldByCurrentThread();
            }

            @Override
            public int getReadLockCount() {
                // 默认实现，子类可重写
                return isReadLocked() ? 1 : 0;
            }

            @Override
            public int getReadLockCountByCurrentThread() {
                return isReadLockedByCurrentThread() ? 1 : 0;
            }

            @Override
            public String getWriteLockHolder() {
                return writeLock().isLocked() ? writeLock().getLockHolder() : null;
            }

            @Override
            public String getReadLockHolders() {
                // 默认实现，子类可重写
                return isReadLocked() ? "unknown" : "none";
            }

            @Override
            public long getReadLockRemainingTime(TimeUnit unit) {
                return readLock().getRemainingTime(unit);
            }

            @Override
            public long getWriteLockRemainingTime(TimeUnit unit) {
                return writeLock().getRemainingTime(unit);
            }

            @Override
            public boolean isFairMode() {
                return getConfigurationInfo().isFairLock();
            }

            @Override
            public LockUpgradeMode getUpgradeMode() {
                return getConfigurationInfo().getUpgradeMode();
            }

            @Override
            public ReadWriteLockType getLockType() {
                return ReadWriteLockType.STANDARD;
            }
        };
    }

    /**
     * 获取配置信息
     *
     * @return 配置信息
     */
    default ReadWriteLockConfigurationInfo getConfigurationInfo() {
        return new ReadWriteLockConfigurationInfo() {
            @Override
            public boolean isFairLock() {
                return false;
            }

            @Override
            public boolean isReentrant() {
                return true;
            }

            @Override
            public long getDefaultReadLockLeaseTime(TimeUnit unit) {
                return unit.convert(60, TimeUnit.SECONDS); // 读锁默认60秒
            }

            @Override
            public long getDefaultWriteLockLeaseTime(TimeUnit unit) {
                return unit.convert(30, TimeUnit.SECONDS); // 写锁默认30秒
            }

            @Override
            public long getDefaultReadLockWaitTime(TimeUnit unit) {
                return unit.convert(10, TimeUnit.SECONDS);
            }

            @Override
            public long getDefaultWriteLockWaitTime(TimeUnit unit) {
                return unit.convert(15, TimeUnit.SECONDS);
            }

            @Override
            public boolean isUpgradeSupported() {
                return false;
            }

            @Override
            public boolean isDowngradeSupported() {
                return true;
            }

            @Override
            public LockUpgradeMode getUpgradeMode() {
                return LockUpgradeMode.DIRECT;
            }

            @Override
            public int getMaxReadLockCount() {
                return Integer.MAX_VALUE;
            }

            @Override
            public boolean isReadLockPreemptionEnabled() {
                return false;
            }

            @Override
            public boolean isWriteLockPriority() {
                return true;
            }
        };
    }

    /**
     * 健康检查
     *
     * @return 健康检查结果
     */
    default ReadWriteLockHealthCheckResult healthCheck() {
        try {
            // 检查读锁
            boolean readLockHealthy = readLock().tryLock(0, 100, TimeUnit.MILLISECONDS);
            if (readLockHealthy) {
                readLock().unlock();
            }

            // 检查写锁
            boolean writeLockHealthy = writeLock().tryLock(0, 100, TimeUnit.MILLISECONDS);
            if (writeLockHealthy) {
                writeLock().unlock();
            }

            boolean isHealthy = readLockHealthy && writeLockHealthy;
            
            return new ReadWriteLockHealthCheckResult() {
                @Override
                public boolean isHealthy() {
                    return isHealthy;
                }

                @Override
                public boolean isReadLockHealthy() {
                    return readLockHealthy;
                }

                @Override
                public boolean isWriteLockHealthy() {
                    return writeLockHealthy;
                }

                @Override
                public String getDetails() {
                    if (isHealthy) {
                        return "Both read and write locks are accessible and working normally";
                    } else if (!readLockHealthy && !writeLockHealthy) {
                        return "Both read and write locks are not accessible";
                    } else if (!readLockHealthy) {
                        return "Read lock is not accessible";
                    } else {
                        return "Write lock is not accessible";
                    }
                }

                @Override
                public long getCheckTime() {
                    return System.currentTimeMillis();
                }
            };
        } catch (Exception e) {
            return new ReadWriteLockHealthCheckResult() {
                @Override
                public boolean isHealthy() {
                    return false;
                }

                @Override
                public boolean isReadLockHealthy() {
                    return false;
                }

                @Override
                public boolean isWriteLockHealthy() {
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
     * 获取读锁持有者数量
     *
     * @return 读锁持有者数量
     */
    default int getReadLockCount() {
        return getReadWriteLockStateInfo().getReadLockCount();
    }

    /**
     * 检查是否有读锁
     *
     * @return 是否有读锁
     */
    default boolean hasReadLocks() {
        return getReadWriteLockStateInfo().isReadLocked();
    }

    /**
     * 检查是否有写锁
     *
     * @return 是否有写锁
     */
    default boolean hasWriteLocks() {
        return getReadWriteLockStateInfo().isWriteLocked();
    }

    /**
     * 检查是否可升级
     *
     * @return 是否可升级
     */
    default boolean isUpgradeSupported() {
        return getConfigurationInfo().isUpgradeSupported();
    }

    /**
     * 检查是否可降级
     *
     * @return 是否可降级
     */
    default boolean isDowngradeSupported() {
        return getConfigurationInfo().isDowngradeSupported();
    }

    /**
     * 执行读写锁操作
     *
     * @param readOperation 读操作
     * @param writeOperation 写操作
     * @param <R> 操作结果类型
     * @return 操作结果
     * @throws Exception 操作异常
     */
    default <R> R executeWithAppropriateLock(java.util.concurrent.Callable<R> readOperation,
                                            java.util.concurrent.Callable<R> writeOperation) throws Exception {
        if (hasWriteLocks() || writeLock().isHeldByCurrentThread()) {
            return writeOperation.call();
        } else if (hasReadLocks() || readLock().isHeldByCurrentThread()) {
            return readOperation.call();
        } else {
            // 没有持有任何锁，默认执行读操作
            return readOperation.call();
        }
    }

    @Override
    default void close() {
        // 释放所有持有的锁
        if (readLock().isHeldByCurrentThread()) {
            readLock().unlock();
        }
        if (writeLock().isHeldByCurrentThread()) {
            writeLock().unlock();
        }
    }

    /**
     * 读写锁状态信息
     */
    interface ReadWriteLockStateInfo {
        /**
         * 是否有读锁
         */
        boolean isReadLocked();

        /**
         * 是否有写锁
         */
        boolean isWriteLocked();

        /**
         * 当前线程是否持有读锁
         */
        boolean isReadLockedByCurrentThread();

        /**
         * 当前线程是否持有写锁
         */
        boolean isWriteLockedByCurrentThread();

        /**
         * 获取读锁数量
         */
        int getReadLockCount();

        /**
         * 获取当前线程持有的读锁数量
         */
        int getReadLockCountByCurrentThread();

        /**
         * 获取写锁持有者
         */
        String getWriteLockHolder();

        /**
         * 获取读锁持有者列表
         */
        String getReadLockHolders();

        /**
         * 获取读锁剩余时间
         */
        long getReadLockRemainingTime(TimeUnit unit);

        /**
         * 获取写锁剩余时间
         */
        long getWriteLockRemainingTime(TimeUnit unit);

        /**
         * 是否公平模式
         */
        boolean isFairMode();

        /**
         * 获取锁升级模式
         */
        LockUpgradeMode getUpgradeMode();

        /**
         * 获取锁类型
         */
        ReadWriteLockType getLockType();
    }

    /**
     * 读写锁配置信息
     */
    interface ReadWriteLockConfigurationInfo {
        /**
         * 是否公平锁
         */
        boolean isFairLock();

        /**
         * 是否可重入
         */
        boolean isReentrant();

        /**
         * 获取默认读锁租约时间
         */
        long getDefaultReadLockLeaseTime(TimeUnit unit);

        /**
         * 获取默认写锁租约时间
         */
        long getDefaultWriteLockLeaseTime(TimeUnit unit);

        /**
         * 获取默认读锁等待时间
         */
        long getDefaultReadLockWaitTime(TimeUnit unit);

        /**
         * 获取默认写锁等待时间
         */
        long getDefaultWriteLockWaitTime(TimeUnit unit);

        /**
         * 是否支持锁升级
         */
        boolean isUpgradeSupported();

        /**
         * 是否支持锁降级
         */
        boolean isDowngradeSupported();

        /**
         * 获取锁升级模式
         */
        LockUpgradeMode getUpgradeMode();

        /**
         * 获取最大读锁数量
         */
        int getMaxReadLockCount();

        /**
         * 是否启用读锁抢占
         */
        boolean isReadLockPreemptionEnabled();

        /**
         * 是否写锁优先
         */
        boolean isWriteLockPriority();
    }

    /**
     * 读写锁健康检查结果
     */
    interface ReadWriteLockHealthCheckResult {
        /**
         * 是否整体健康
         */
        boolean isHealthy();

        /**
         * 读锁是否健康
         */
        boolean isReadLockHealthy();

        /**
         * 写锁是否健康
         */
        boolean isWriteLockHealthy();

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
     * 锁升级模式
     */
    enum LockUpgradeMode {
        /** 直接升级 */
        DIRECT,
        /** 排队升级 */
        QUEUED,
        /** 条件升级 */
        CONDITIONAL,
        /** 不支持升级 */
        NOT_SUPPORTED
    }

    /**
     * 读写锁类型
     */
    enum ReadWriteLockType {
        /** 标准读写锁 */
        STANDARD,
        /** 公平读写锁 */
        FAIR,
        /** 可重入读写锁 */
        REENTRANT,
        /** 优先级读写锁 */
        PRIORITY
    }
}