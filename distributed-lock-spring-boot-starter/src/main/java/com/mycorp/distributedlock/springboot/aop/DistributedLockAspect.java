package com.mycorp.distributedlock.springboot.aop;

import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.api.LockConfigurationBuilder;
import com.mycorp.distributedlock.api.exception.LockAcquisitionException;
import com.mycorp.distributedlock.springboot.config.DistributedLockProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁AOP切面
 * 支持@DistributedLock注解的自动锁管理
 */
@Aspect
@Component
@Order(100)
public class DistributedLockAspect {

    private static final Logger logger = LoggerFactory.getLogger(DistributedLockAspect.class);

    private final DistributedLockFactory lockFactory;
    private final DistributedLockProperties properties;

    public DistributedLockAspect(DistributedLockFactory lockFactory, DistributedLockProperties properties) {
        this.lockFactory = lockFactory;
        this.properties = properties;
        logger.info("DistributedLockAspect initialized with lock factory: {}", lockFactory.getClass().getSimpleName());
    }

    @Around("@annotation(distributedLock)")
    public Object around(
        ProceedingJoinPoint joinPoint,
        com.mycorp.distributedlock.api.annotation.DistributedLock distributedLock
    ) throws Throwable {
        String lockKey = resolveLockKey(joinPoint, distributedLock);
        long timeoutSeconds = resolveTimeoutSeconds(distributedLock);
        com.mycorp.distributedlock.api.DistributedLock lock = acquireLock(lockKey, distributedLock, timeoutSeconds);

        try {
            return joinPoint.proceed();
        } finally {
            releaseLock(lock, lockKey);
        }
    }

    private com.mycorp.distributedlock.api.DistributedLock acquireLock(
        String lockKey,
        com.mycorp.distributedlock.api.annotation.DistributedLock annotation,
        long timeoutSeconds
    ) {
        com.mycorp.distributedlock.api.DistributedLock lock = annotation.fair()
            ? lockFactory.getConfiguredLock(lockKey, new AnnotationLockConfiguration(lockKey, timeoutSeconds, true))
            : lockFactory.getLock(lockKey);

        boolean acquired;
        try {
            acquired = lock.tryLock(timeoutSeconds, timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException(
                "Interrupted while acquiring lock for key: " + lockKey,
                lockKey,
                exception,
                LockAcquisitionException.LockAcquisitionFailureReason.GENERAL_FAILURE,
                timeoutSeconds,
                timeoutSeconds
            );
        }

        if (!acquired) {
            throw new LockAcquisitionException(
                "Failed to acquire lock for key: " + lockKey,
                lockKey,
                null,
                LockAcquisitionException.LockAcquisitionFailureReason.WAIT_TIMEOUT,
                timeoutSeconds,
                timeoutSeconds
            );
        }
        return lock;
    }

    private void releaseLock(com.mycorp.distributedlock.api.DistributedLock lock, String lockKey) {
        if (lock == null) {
            return;
        }
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException exception) {
            logger.warn("Failed to release lock {}", lockKey, exception);
        }
    }

    private long resolveTimeoutSeconds(com.mycorp.distributedlock.api.annotation.DistributedLock annotation) {
        if (annotation.timeoutSeconds() > 0) {
            return annotation.timeoutSeconds();
        }

        if (properties.getAspect() != null && properties.getAspect().getDefaultTimeout() != null) {
            return Math.max(1, properties.getAspect().getDefaultTimeout().toSeconds());
        }
        return 30L;
    }

    private String resolveLockKey(
        ProceedingJoinPoint joinPoint,
        com.mycorp.distributedlock.api.annotation.DistributedLock annotation
    ) {
        if (!annotation.value().isBlank()) {
            return annotation.value();
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return signature.getDeclaringType().getSimpleName() + "." + signature.getName();
    }

    private static final class AnnotationLockConfiguration implements LockConfigurationBuilder.LockConfiguration {

        private final String name;
        private final long timeoutSeconds;
        private final boolean fairLock;

        private AnnotationLockConfiguration(String name, long timeoutSeconds, boolean fairLock) {
            this.name = name;
            this.timeoutSeconds = timeoutSeconds;
            this.fairLock = fairLock;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public LockConfigurationBuilder.LockType getLockType() {
            return fairLock ? LockConfigurationBuilder.LockType.FAIR : LockConfigurationBuilder.LockType.MUTEX;
        }

        @Override
        public long getLeaseTime(TimeUnit timeUnit) {
            return timeUnit.convert(timeoutSeconds, TimeUnit.SECONDS);
        }

        @Override
        public long getWaitTime(TimeUnit timeUnit) {
            return timeUnit.convert(timeoutSeconds, TimeUnit.SECONDS);
        }

        @Override
        public boolean isFairLock() {
            return fairLock;
        }

        @Override
        public boolean isRetryEnabled() {
            return false;
        }

        @Override
        public int getRetryCount() {
            return 0;
        }

        @Override
        public long getRetryInterval(TimeUnit timeUnit) {
            return 0;
        }

        @Override
        public boolean isAutoRenew() {
            return false;
        }

        @Override
        public long getRenewInterval(TimeUnit timeUnit) {
            return 0;
        }

        @Override
        public double getRenewRatio() {
            return 0;
        }

        @Override
        public LockConfigurationBuilder.TimeoutStrategy getTimeoutStrategy() {
            return LockConfigurationBuilder.TimeoutStrategy.BLOCK_UNTIL_TIMEOUT;
        }

        @Override
        public boolean isDeadlockDetectionEnabled() {
            return false;
        }

        @Override
        public long getDeadlockDetectionTimeout(TimeUnit timeUnit) {
            return 0;
        }

        @Override
        public boolean isEventListeningEnabled() {
            return false;
        }

        @Override
        public Map<String, String> getProperties() {
            return Map.of();
        }
    }
}
