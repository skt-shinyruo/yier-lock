package com.mycorp.distributedlock.api.exception;

import java.util.Map;

/**
 * 增强的锁释放异常
 * 提供详细的锁释放失败原因和上下文信息
 */
public class LockReleaseException extends DistributedLockException {

    private final LockReleaseFailureReason failureReason;
    private final boolean forceRelease;
    private final long holdTime;
    private final String holder;

    // 保持向后兼容性的基础构造函数
    public LockReleaseException(String message) {
        this(message, (Throwable) null);
    }

    public LockReleaseException(String message, Throwable cause) {
        super(message, cause, null, ErrorCode.LOCK_RELEASE_FAILED, Map.of(
                "failureReason", LockReleaseFailureReason.GENERAL_FAILURE,
                "forceRelease", false,
                "holdTime", 0,
                "holder", null
        ), null);
        this.failureReason = LockReleaseFailureReason.GENERAL_FAILURE;
        this.forceRelease = false;
        this.holdTime = 0;
        this.holder = null;
    }

    // 增强的构造函数
    public LockReleaseException(String message, String lockName) {
        this(message, lockName, (Throwable) null);
    }

    public LockReleaseException(String message, String lockName, Throwable cause) {
        this(message, lockName, cause, LockReleaseFailureReason.GENERAL_FAILURE);
    }

    public LockReleaseException(String message, String lockName, Throwable cause,
                              LockReleaseFailureReason failureReason) {
        this(message, lockName, cause, failureReason, false);
    }

    public LockReleaseException(String message, String lockName, Throwable cause,
                              LockReleaseFailureReason failureReason, boolean forceRelease) {
        this(message, lockName, cause, failureReason, forceRelease, 0);
    }

    public LockReleaseException(String message, String lockName, Throwable cause,
                              LockReleaseFailureReason failureReason, boolean forceRelease,
                              long holdTime) {
        this(message, lockName, cause, failureReason, forceRelease, holdTime, null);
    }

    public LockReleaseException(String message, String lockName, Throwable cause,
                              LockReleaseFailureReason failureReason, boolean forceRelease,
                              long holdTime, String holder) {
        this(message, lockName, cause, failureReason, forceRelease, holdTime, holder, null);
    }

    public LockReleaseException(String message, String lockName, Throwable cause,
                              LockReleaseFailureReason failureReason, boolean forceRelease,
                              long holdTime, String holder, String recommendation) {
        super(message, cause, lockName, ErrorCode.LOCK_RELEASE_FAILED, Map.of(
                "failureReason", failureReason,
                "forceRelease", forceRelease,
                "holdTime", holdTime,
                "holder", holder
        ), recommendation);
        this.failureReason = failureReason;
        this.forceRelease = forceRelease;
        this.holdTime = holdTime;
        this.holder = holder;
    }

    /**
     * 获取锁释放失败原因
     */
    public LockReleaseFailureReason getFailureReason() {
        return failureReason;
    }

    /**
     * 是否为强制释放
     */
    public boolean isForceRelease() {
        return forceRelease;
    }

    /**
     * 获取持有时间
     */
    public long getHoldTime() {
        return holdTime;
    }

    /**
     * 获取锁持有者
     */
    public String getHolder() {
        return holder;
    }

    /**

    /**
     * 锁释放失败原因枚举
     */
    public enum LockReleaseFailureReason {
        /** 通用失败 */
        GENERAL_FAILURE,
        /** 锁未被当前线程持有 */
        LOCK_NOT_HELD_BY_CURRENT_THREAD,
        /** 锁已被其他线程释放 */
        LOCK_ALREADY_RELEASED,
        /** 强制释放失败 */
        FORCE_RELEASE_FAILED,
        /** 网络连接失败 */
        NETWORK_FAILURE,
        /** 锁服务不可用 */
        LOCK_SERVICE_UNAVAILABLE,
        /** 权限不足 */
        INSUFFICIENT_PERMISSIONS,
        /** 事务回滚 */
        TRANSACTION_ROLLBACK,
        /** 分布式锁状态不一致 */
        INCONSISTENT_LOCK_STATE,
        /** 锁重入次数不匹配 */
        REENTRANT_COUNT_MISMATCH,
        /** 锁已过期 */
        LOCK_EXPIRED,
        /** 批量操作失败 */
        BATCH_OPERATION_FAILED,
        /** 异步操作失败 */
        ASYNC_OPERATION_FAILED,
        /** 优雅关闭失败 */
        GRACEFUL_SHUTDOWN_FAILED
    }
}