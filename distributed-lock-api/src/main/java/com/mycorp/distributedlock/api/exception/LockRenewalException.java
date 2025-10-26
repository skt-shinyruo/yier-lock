package com.mycorp.distributedlock.api.exception;

import java.util.Map;

/**
 * 锁续期异常
 * 用于处理锁续期操作失败的场景
 */
public class LockRenewalException extends DistributedLockException {

    private final LockRenewalFailureReason failureReason;
    private final long remainingTime;
    private final long requestedTime;

    // 保持向后兼容性的基础构造函数
    public LockRenewalException(String message) {
        this(message, (Throwable) null);
    }

    public LockRenewalException(String message, Throwable cause) {
        super(message, cause, null, ErrorCode.LOCK_RENEWAL_FAILED, Map.of(
                "failureReason", LockRenewalFailureReason.GENERAL_FAILURE,
                "remainingTime", 0,
                "requestedTime", 0
        ), null);
        this.failureReason = LockRenewalFailureReason.GENERAL_FAILURE;
        this.remainingTime = 0;
        this.requestedTime = 0;
    }

    // 增强的构造函数
    public LockRenewalException(String message, String lockName) {
        this(message, lockName, (Throwable) null);
    }

    public LockRenewalException(String message, String lockName, Throwable cause) {
        this(message, lockName, cause, LockRenewalFailureReason.GENERAL_FAILURE);
    }

    public LockRenewalException(String message, String lockName, Throwable cause,
                              LockRenewalFailureReason failureReason) {
        this(message, lockName, cause, failureReason, 0, 0);
    }

    public LockRenewalException(String message, String lockName, Throwable cause,
                              LockRenewalFailureReason failureReason,
                              long remainingTime, long requestedTime) {
        this(message, lockName, cause, failureReason, remainingTime, requestedTime, null);
    }

    public LockRenewalException(String message, String lockName, Throwable cause,
                              LockRenewalFailureReason failureReason,
                              long remainingTime, long requestedTime,
                              String recommendation) {
        super(message, cause, lockName, ErrorCode.LOCK_RENEWAL_FAILED, Map.of(
                "failureReason", failureReason,
                "remainingTime", remainingTime,
                "requestedTime", requestedTime
        ), recommendation);
        this.failureReason = failureReason;
        this.remainingTime = remainingTime;
        this.requestedTime = requestedTime;
    }

    /**
     * 获取续期失败原因
     */
    public LockRenewalFailureReason getFailureReason() {
        return failureReason;
    }

    /**
     * 获取剩余时间
     */
    public long getRemainingTime() {
        return remainingTime;
    }

    /**
     * 获取请求的续期时间
     */
    public long getRequestedTime() {
        return requestedTime;
    }

    /**
     * 锁续期失败原因枚举
     */
    public enum LockRenewalFailureReason {
        /** 通用失败 */
        GENERAL_FAILURE,
        /** 锁未被持有 */
        LOCK_NOT_HELD,
        /** 锁已过期 */
        LOCK_ALREADY_EXPIRED,
        /** 续期时间不足 */
        INSUFFICIENT_RENEWAL_TIME,
        /** 网络连接失败 */
        NETWORK_FAILURE,
        /** 锁服务不可用 */
        LOCK_SERVICE_UNAVAILABLE,
        /** 权限不足 */
        INSUFFICIENT_PERMISSIONS,
        /** 续期间隔过短 */
        RENEWAL_INTERVAL_TOO_SHORT,
        /** 续期次数超限 */
        MAX_RENEWAL_COUNT_EXCEEDED,
        /** 异步续期失败 */
        ASYNC_RENEWAL_FAILED,
        /** 批量续期失败 */
        BATCH_RENEWAL_FAILED
    }
}