package com.mycorp.distributedlock.api;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * 锁事件监听器接口
 * 支持监听分布式锁的各种状态变化和事件
 * 
 * @param <T> 锁类型
 */
public interface LockEventListener<T extends DistributedLock> {

    /**
     * 锁获取成功事件
     * 
     * @param lock 锁实例
     * @param eventTime 事件时间
     * @param metadata 元数据信息
     */
    default void onLockAcquired(T lock, Instant eventTime, LockEventMetadata metadata) {
        // 默认空实现
    }

    /**
     * 锁获取失败事件
     * 
     * @param lockName 锁名称
     * @param eventTime 事件时间
     * @param cause 失败原因
     * @param metadata 元数据信息
     */
    default void onLockAcquisitionFailed(String lockName, Instant eventTime, 
                                       Throwable cause, LockEventMetadata metadata) {
        // 默认空实现
    }

    /**
     * 锁释放成功事件
     * 
     * @param lock 锁实例
     * @param eventTime 事件时间
     * @param metadata 元数据信息
     */
    default void onLockReleased(T lock, Instant eventTime, LockEventMetadata metadata) {
        // 默认空实现
    }

    /**
     * 锁续期成功事件
     * 
     * @param lock 锁实例
     * @param eventTime 事件时间
     * @param newExpiryTime 新的过期时间
     * @param metadata 元数据信息
     */
    default void onLockRenewed(T lock, Instant eventTime, Instant newExpiryTime, 
                              LockEventMetadata metadata) {
        // 默认空实现
    }

    /**
     * 锁过期事件
     * 
     * @param lock 锁实例
     * @param eventTime 事件时间
     * @param metadata 元数据信息
     */
    default void onLockExpired(T lock, Instant eventTime, LockEventMetadata metadata) {
        // 默认空实现
    }

    /**
     * 锁尝试获取超时事件
     * 
     * @param lockName 锁名称
     * @param eventTime 事件时间
     * @param waitTime 等待时间
     * @param metadata 元数据信息
     */
    default void onLockTimeout(String lockName, Instant eventTime, long waitTime, 
                              LockEventMetadata metadata) {
        // 默认空实现
    }

    /**
     * 锁阻塞事件
     * 
     * @param lockName 锁名称
     * @param eventTime 事件时间
     * @param blockReason 阻塞原因
     * @param metadata 元数据信息
     */
    default void onLockBlocked(String lockName, Instant eventTime, String blockReason, 
                              LockEventMetadata metadata) {
        // 默认空实现
    }

    /**
     * 锁死锁检测事件
     * 
     * @param involvedLocks 涉及的锁列表
     * @param eventTime 事件时间
     * @param cycleInfo 死锁循环信息
     * @param metadata 元数据信息
     */
    default void onDeadlockDetected(Iterable<T> involvedLocks, Instant eventTime, 
                                   DeadlockCycleInfo cycleInfo, LockEventMetadata metadata) {
        // 默认空实现
    }

    /**
     * 锁健康检查事件
     * 
     * @param lockName 锁名称
     * @param eventTime 事件时间
     * @param healthStatus 健康状态
     * @param details 详细信息
     * @param metadata 元数据信息
     */
    default void onHealthCheck(String lockName, Instant eventTime, HealthStatus healthStatus, 
                              String details, LockEventMetadata metadata) {
        // 默认空实现
    }

    /**
     * 异步锁事件处理
     * 
     * @param event 事件
     * @return 异步处理结果
     */
    default CompletableFuture<Void> onEventAsync(LockEvent<T> event) {
        return CompletableFuture.runAsync(() -> onEvent(event));
    }

    /**
     * 通用事件处理方法
     * 
     * @param event 事件
     */
    default void onEvent(LockEvent<T> event) {
        switch (event.getType()) {
            case LOCK_ACQUIRED:
                onLockAcquired(event.getLock(), event.getTimestamp(), event.getMetadata());
                break;
            case LOCK_ACQUISITION_FAILED:
                onLockAcquisitionFailed(event.getLockName(), event.getTimestamp(), 
                                      event.getCause(), event.getMetadata());
                break;
            case LOCK_RELEASED:
                onLockReleased(event.getLock(), event.getTimestamp(), event.getMetadata());
                break;
            case LOCK_RENEWED:
                onLockRenewed(event.getLock(), event.getTimestamp(), 
                            event.getNewExpiryTime(), event.getMetadata());
                break;
            case LOCK_EXPIRED:
                onLockExpired(event.getLock(), event.getTimestamp(), event.getMetadata());
                break;
            case LOCK_TIMEOUT:
                onLockTimeout(event.getLockName(), event.getTimestamp(), 
                            event.getWaitTime(), event.getMetadata());
                break;
            case LOCK_BLOCKED:
                onLockBlocked(event.getLockName(), event.getTimestamp(), 
                            event.getBlockReason(), event.getMetadata());
                break;
            case DEADLOCK_DETECTED:
                onDeadlockDetected(event.getInvolvedLocks(), event.getTimestamp(), 
                                 event.getDeadlockCycleInfo(), event.getMetadata());
                break;
            case HEALTH_CHECK:
                onHealthCheck(event.getLockName(), event.getTimestamp(), 
                            event.getHealthStatus(), event.getDetails(), event.getMetadata());
                break;
        }
    }

    /**
     * 事件监听器优先级
     * 
     * @return 优先级，数值越小优先级越高
     */
    default int getPriority() {
        return 0;
    }

    /**
     * 是否启用异步事件处理
     * 
     * @return 是否启用异步处理
     */
    default boolean isAsyncEnabled() {
        return false;
    }

    /**
     * 事件过滤条件
     * 
     * @param event 事件
     * @return 是否应该处理该事件
     */
    default boolean shouldHandleEvent(LockEvent<T> event) {
        return true;
    }

    /**
     * 锁事件类型枚举
     */
    enum EventType {
        LOCK_ACQUIRED,
        LOCK_ACQUISITION_FAILED,
        LOCK_RELEASED,
        LOCK_RENEWED,
        LOCK_EXPIRED,
        LOCK_TIMEOUT,
        LOCK_BLOCKED,
        DEADLOCK_DETECTED,
        HEALTH_CHECK
    }

    /**
     * 健康状态枚举
     */
    enum HealthStatus {
        HEALTHY,
        DEGRADED,
        UNHEALTHY,
        UNKNOWN
    }

    /**
     * 锁事件元数据
     */
    interface LockEventMetadata {
        /**
         * 获取线程ID
         * 
         * @return 线程ID
         */
        default long getThreadId() {
            return Thread.currentThread().getId();
        }

        /**
         * 获取线程名称
         * 
         * @return 线程名称
         */
        default String getThreadName() {
            return Thread.currentThread().getName();
        }

        /**
         * 获取调用栈信息
         * 
         * @return 调用栈
         */
        default String getCallStack() {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            if (stack.length > 2) {
                return stack[2].toString();
            }
            return "";
        }

        /**
         * 获取自定义元数据
         * 
         * @return 自定义元数据
         */
        default Object getCustomData() {
            return null;
        }

        /**
         * 获取会话ID
         * 
         * @return 会话ID
         */
        default String getSessionId() {
            return null;
        }

        /**
         * 获取应用名称
         * 
         * @return 应用名称
         */
        default String getApplicationName() {
            return null;
        }
    }

    /**
     * 死锁循环信息
     */
    interface DeadlockCycleInfo {
        /**
         * 获取死锁涉及的线程集合
         * 
         * @return 线程集合
         */
        Iterable<String> getInvolvedThreads();

        /**
         * 获取死锁锁集合
         * 
         * @return 锁集合
         */
        Iterable<String> getInvolvedLocks();

        /**
         * 获取循环描述
         * 
         * @return 循环描述
         */
        String getCycleDescription();
    }
}