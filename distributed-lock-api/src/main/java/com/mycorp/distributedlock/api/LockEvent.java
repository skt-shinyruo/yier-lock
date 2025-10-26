package com.mycorp.distributedlock.api;

import java.time.Instant;
import java.util.Optional;

/**
 * 锁事件封装类
 * 用于封装各种锁相关的状态变化事件
 * 
 * @param <T> 锁类型
 */
public class LockEvent<T extends DistributedLock> {
    
    private final LockEventListener.EventType type;
    private final T lock;
    private final String lockName;
    private final Instant timestamp;
    private final Throwable cause;
    private final LockEventListener.LockEventMetadata metadata;
    private final Instant newExpiryTime;
    private final long waitTime;
    private final String blockReason;
    private final Iterable<T> involvedLocks;
    final LockEventListener.DeadlockCycleInfo deadlockCycleInfo;
    final LockEventListener.HealthStatus healthStatus;
    private final String details;

    private LockEvent(Builder<T> builder) {
        this.type = builder.type;
        this.lock = builder.lock;
        this.lockName = builder.lockName;
        this.timestamp = builder.timestamp;
        this.cause = builder.cause;
        this.metadata = builder.metadata;
        this.newExpiryTime = builder.newExpiryTime;
        this.waitTime = builder.waitTime;
        this.blockReason = builder.blockReason;
        this.involvedLocks = builder.involvedLocks;
        this.deadlockCycleInfo = builder.deadlockCycleInfo;
        this.healthStatus = builder.healthStatus;
        this.details = builder.details;
    }

    /**
     * 创建锁获取成功事件
     * 
     * @param lock 锁实例
     * @param metadata 元数据
     * @param <T> 锁类型
     * @return 锁事件
     */
    public static <T extends DistributedLock> LockEvent<T> ofLockAcquired(T lock, 
                                                                          LockEventListener.LockEventMetadata metadata) {
        return new Builder<T>()
                .type(LockEventListener.EventType.LOCK_ACQUIRED)
                .lock(lock)
                .metadata(metadata)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * 创建锁获取失败事件
     * 
     * @param lockName 锁名称
     * @param cause 失败原因
     * @param metadata 元数据
     * @return 锁事件
     */
    public static LockEvent<?> ofLockAcquisitionFailed(String lockName, Throwable cause, 
                                                      LockEventListener.LockEventMetadata metadata) {
        return new Builder<>()
                .type(LockEventListener.EventType.LOCK_ACQUISITION_FAILED)
                .lockName(lockName)
                .cause(cause)
                .metadata(metadata)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * 创建锁释放成功事件
     * 
     * @param lock 锁实例
     * @param metadata 元数据
     * @param <T> 锁类型
     * @return 锁事件
     */
    public static <T extends DistributedLock> LockEvent<T> ofLockReleased(T lock, 
                                                                          LockEventListener.LockEventMetadata metadata) {
        return new Builder<T>()
                .type(LockEventListener.EventType.LOCK_RELEASED)
                .lock(lock)
                .metadata(metadata)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * 创建锁续期成功事件
     * 
     * @param lock 锁实例
     * @param newExpiryTime 新的过期时间
     * @param metadata 元数据
     * @param <T> 锁类型
     * @return 锁事件
     */
    public static <T extends DistributedLock> LockEvent<T> ofLockRenewed(T lock, Instant newExpiryTime, 
                                                                        LockEventListener.LockEventMetadata metadata) {
        return new Builder<T>()
                .type(LockEventListener.EventType.LOCK_RENEWED)
                .lock(lock)
                .newExpiryTime(newExpiryTime)
                .metadata(metadata)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * 创建锁过期事件
     * 
     * @param lock 锁实例
     * @param metadata 元数据
     * @param <T> 锁类型
     * @return 锁事件
     */
    public static <T extends DistributedLock> LockEvent<T> ofLockExpired(T lock, 
                                                                        LockEventListener.LockEventMetadata metadata) {
        return new Builder<T>()
                .type(LockEventListener.EventType.LOCK_EXPIRED)
                .lock(lock)
                .metadata(metadata)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * 创建锁超时事件
     * 
     * @param lockName 锁名称
     * @param waitTime 等待时间
     * @param metadata 元数据
     * @return 锁事件
     */
    public static LockEvent<?> ofLockTimeout(String lockName, long waitTime, 
                                            LockEventListener.LockEventMetadata metadata) {
        return new Builder<>()
                .type(LockEventListener.EventType.LOCK_TIMEOUT)
                .lockName(lockName)
                .waitTime(waitTime)
                .metadata(metadata)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * 创建锁阻塞事件
     * 
     * @param lockName 锁名称
     * @param blockReason 阻塞原因
     * @param metadata 元数据
     * @return 锁事件
     */
    public static LockEvent<?> ofLockBlocked(String lockName, String blockReason, 
                                            LockEventListener.LockEventMetadata metadata) {
        return new Builder<>()
                .type(LockEventListener.EventType.LOCK_BLOCKED)
                .lockName(lockName)
                .blockReason(blockReason)
                .metadata(metadata)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * 创建死锁检测事件
     * 
     * @param involvedLocks 涉及的锁列表
     * @param deadlockCycleInfo 死锁循环信息
     * @param metadata 元数据
     * @param <T> 锁类型
     * @return 锁事件
     */
    public static <T extends DistributedLock> LockEvent<T> ofDeadlockDetected(Iterable<T> involvedLocks,
                                                                              LockEventListener.DeadlockCycleInfo deadlockCycleInfo,
                                                                              LockEventListener.LockEventMetadata metadata) {
        return new Builder<T>()
                .type(LockEventListener.EventType.DEADLOCK_DETECTED)
                .involvedLocks(involvedLocks)
                .deadlockCycleInfo(deadlockCycleInfo)
                .metadata(metadata)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * 创建健康检查事件
     * 
     * @param lockName 锁名称
     * @param healthStatus 健康状态
     * @param details 详细信息
     * @param metadata 元数据
     * @return 锁事件
     */
    public static LockEvent<?> ofHealthCheck(String lockName, LockEventListener.HealthStatus healthStatus, 
                                            String details, LockEventListener.LockEventMetadata metadata) {
        return new Builder<>()
                .type(LockEventListener.EventType.HEALTH_CHECK)
                .lockName(lockName)
                .healthStatus(healthStatus)
                .details(details)
                .metadata(metadata)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * 获取事件类型
     * 
     * @return 事件类型
     */
    public LockEventListener.EventType getType() {
        return type;
    }

    /**
     * 获取锁实例
     * 
     * @return 锁实例
     */
    public T getLock() {
        return lock;
    }

    /**
     * 获取锁名称
     * 
     * @return 锁名称
     */
    public String getLockName() {
        return Optional.ofNullable(lock)
                .map(DistributedLock::getName)
                .orElse(lockName);
    }

    /**
     * 获取事件时间戳
     * 
     * @return 时间戳
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * 获取异常原因
     * 
     * @return 异常原因
     */
    public Throwable getCause() {
        return cause;
    }

    /**
     * 获取元数据
     * 
     * @return 元数据
     */
    public LockEventListener.LockEventMetadata getMetadata() {
        return metadata;
    }

    /**
     * 获取新的过期时间
     * 
     * @return 新的过期时间
     */
    public Instant getNewExpiryTime() {
        return newExpiryTime;
    }

    /**
     * 获取等待时间
     * 
     * @return 等待时间
     */
    public long getWaitTime() {
        return waitTime;
    }

    /**
     * 获取阻塞原因
     * 
     * @return 阻塞原因
     */
    public String getBlockReason() {
        return blockReason;
    }

    /**
     * 获取涉及的锁列表
     * 
     * @return 涉及的锁列表
     */
    public Iterable<T> getInvolvedLocks() {
        return involvedLocks;
    }

    /**
     * 获取死锁循环信息
     * 
     * @return 死锁循环信息
     */
    public LockEventListener.DeadlockCycleInfo getDeadlockCycleInfo() {
        return deadlockCycleInfo;
    }

    /**
     * 获取健康状态
     * 
     * @return 健康状态
     */
    public LockEventListener.HealthStatus getHealthStatus() {
        return healthStatus;
    }

    /**
     * 获取详细信息
     * 
     * @return 详细信息
     */
    public String getDetails() {
        return details;
    }

    /**
     * 事件构建器
     * 
     * @param <T> 锁类型
     */
    public static class Builder<T extends DistributedLock> {
        private LockEventListener.EventType type;
        private T lock;
        private String lockName;
        private Instant timestamp = Instant.now();
        private Throwable cause;
        private LockEventListener.LockEventMetadata metadata;
        private Instant newExpiryTime;
        private long waitTime;
        private String blockReason;
        private Iterable<T> involvedLocks;
        private LockEventListener.DeadlockCycleInfo deadlockCycleInfo;
        private LockEventListener.HealthStatus healthStatus;
        private String details;

        public Builder<T> type(LockEventListener.EventType type) {
            this.type = type;
            return this;
        }

        public Builder<T> lock(T lock) {
            this.lock = lock;
            return this;
        }

        public Builder<T> lockName(String lockName) {
            this.lockName = lockName;
            return this;
        }

        public Builder<T> timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder<T> cause(Throwable cause) {
            this.cause = cause;
            return this;
        }

        public Builder<T> metadata(LockEventListener.LockEventMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder<T> newExpiryTime(Instant newExpiryTime) {
            this.newExpiryTime = newExpiryTime;
            return this;
        }

        public Builder<T> waitTime(long waitTime) {
            this.waitTime = waitTime;
            return this;
        }

        public Builder<T> blockReason(String blockReason) {
            this.blockReason = blockReason;
            return this;
        }

        public Builder<T> involvedLocks(Iterable<T> involvedLocks) {
            this.involvedLocks = involvedLocks;
            return this;
        }

        public Builder<T> deadlockCycleInfo(LockEventListener.DeadlockCycleInfo deadlockCycleInfo) {
            this.deadlockCycleInfo = deadlockCycleInfo;
            return this;
        }

        public Builder<T> healthStatus(LockEventListener.HealthStatus healthStatus) {
            this.healthStatus = healthStatus;
            return this;
        }

        public Builder<T> details(String details) {
            this.details = details;
            return this;
        }

        public LockEvent<T> build() {
            return new LockEvent<>(this);
        }
    }

    @Override
    public String toString() {
        return "LockEvent{" +
                "type=" + type +
                ", lockName=" + getLockName() +
                ", timestamp=" + timestamp +
                ", details='" + details + '\'' +
                '}';
    }
}