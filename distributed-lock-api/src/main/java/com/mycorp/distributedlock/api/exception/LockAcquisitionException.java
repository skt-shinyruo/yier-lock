package com.mycorp.distributedlock.api.exception;

import java.util.Map;

/**
 * 增强的锁获取异常
 * 提供详细的锁获取失败原因和上下文信息
 */
public class LockAcquisitionException extends DistributedLockException {

    private final LockAcquisitionFailureReason failureReason;
    private final long waitTime;
    private final long leaseTime;
    private final int retryCount;

    // 保持向后兼容性的基础构造函数
    public LockAcquisitionException(String message) {
        this(message, (Throwable) null);
    }

    public LockAcquisitionException(String message, Throwable cause) {
        super(message, cause, null, ErrorCode.LOCK_ACQUISITION_FAILED, Map.of(
                "failureReason", LockAcquisitionFailureReason.GENERAL_FAILURE,
                "waitTime", 0,
                "leaseTime", 0,
                "retryCount", 0
        ), null);
        this.failureReason = LockAcquisitionFailureReason.GENERAL_FAILURE;
        this.waitTime = 0;
        this.leaseTime = 0;
        this.retryCount = 0;
    }

    // 增强的构造函数
    public LockAcquisitionException(String message, String lockName) {
        this(message, lockName, (Throwable) null);
    }

    public LockAcquisitionException(String message, String lockName, Throwable cause) {
        this(message, lockName, cause, LockAcquisitionFailureReason.GENERAL_FAILURE);
    }

    public LockAcquisitionException(String message, String lockName, Throwable cause,
                                  LockAcquisitionFailureReason failureReason) {
        this(message, lockName, cause, failureReason, 0, 0);
    }

    public LockAcquisitionException(String message, String lockName, Throwable cause,
                                  LockAcquisitionFailureReason failureReason,
                                  long waitTime, long leaseTime) {
        this(message, lockName, cause, failureReason, waitTime, leaseTime, 0);
    }

    public LockAcquisitionException(String message, String lockName, Throwable cause,
                                  LockAcquisitionFailureReason failureReason,
                                  long waitTime, long leaseTime, int retryCount) {
        this(message, lockName, cause, failureReason, waitTime, leaseTime, retryCount, null);
    }

    public LockAcquisitionException(String message, String lockName, Throwable cause,
                                  LockAcquisitionFailureReason failureReason,
                                  long waitTime, long leaseTime, int retryCount,
                                  String recommendation) {
        super(message, cause, lockName, ErrorCode.LOCK_ACQUISITION_FAILED, Map.of(
                "failureReason", failureReason,
                "waitTime", waitTime,
                "leaseTime", leaseTime,
                "retryCount", retryCount
        ), recommendation);
        this.failureReason = failureReason;
        this.waitTime = waitTime;
        this.leaseTime = leaseTime;
        this.retryCount = retryCount;
    }

    /**
     * 获取锁获取失败原因
     */
    public LockAcquisitionFailureReason getFailureReason() {
        return failureReason;
    }

    /**
     * 获取等待时间
     */
    public long getWaitTime() {
        return waitTime;
    }

    /**
     * 获取租约时间
     */
    public long getLeaseTime() {
        return leaseTime;
    }

    /**
     * 获取重试次数
     */
    public int getRetryCount() {
        return retryCount;
    }

    /**
     * 锁获取失败原因枚举
     */
    public enum LockAcquisitionFailureReason {
        /** 通用失败 */
        GENERAL_FAILURE,
        /** 锁已被其他线程占用 */
        LOCK_ALREADY_HELD,
        /** 等待超时 */
        WAIT_TIMEOUT,
        /** 网络连接失败 */
        NETWORK_FAILURE,
        /** 锁服务不可用 */
        LOCK_SERVICE_UNAVAILABLE,
        /** 锁已过期 */
        LOCK_EXPIRED,
        /** 锁重入失败 */
        REENTRANT_FAILURE,
        /** 权限不足 */
        INSUFFICIENT_PERMISSIONS,
        /** 资源不足 */
        RESOURCE_EXHAUSTED,
        /** 配置错误 */
        CONFIGURATION_ERROR,
        /** 死锁检测 */
        DEADLOCK_DETECTED,
        /** 批量操作失败 */
        BATCH_OPERATION_FAILED,
        /** 异步操作失败 */
        ASYNC_OPERATION_FAILED,
        /** 健康检查失败 */
        HEALTH_CHECK_FAILED
    }
}