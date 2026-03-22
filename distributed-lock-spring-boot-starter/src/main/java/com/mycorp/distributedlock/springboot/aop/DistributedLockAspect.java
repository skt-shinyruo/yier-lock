package com.mycorp.distributedlock.springboot.aop;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.springboot.annotation.DistributedLock;
import com.mycorp.distributedlock.springboot.annotation.DistributedLockMode;
import com.mycorp.distributedlock.springboot.config.DistributedLockProperties;
import com.mycorp.distributedlock.springboot.key.LockKeyResolver;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.convert.DurationStyle;

import java.time.Duration;
import java.util.Objects;

@Aspect
public final class DistributedLockAspect {

    private final LockManager lockManager;
    private final LockKeyResolver lockKeyResolver;
    private final DistributedLockProperties properties;

    public DistributedLockAspect(
        LockManager lockManager,
        LockKeyResolver lockKeyResolver,
        DistributedLockProperties properties
    ) {
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
        this.lockKeyResolver = Objects.requireNonNull(lockKeyResolver, "lockKeyResolver");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        String key = lockKeyResolver.resolveKey(joinPoint, distributedLock.key());
        MutexLock lock = resolveLock(key, distributedLock.mode());
        Duration waitTimeout = resolveWaitTimeout(distributedLock);

        if (waitTimeout == null) {
            lock.lock();
        } else if (!lock.tryLock(waitTimeout)) {
            throw new IllegalStateException("Failed to acquire distributed lock for key " + key);
        }

        try (lock) {
            return joinPoint.proceed();
        }
    }

    private MutexLock resolveLock(String key, DistributedLockMode mode) {
        return switch (mode) {
            case MUTEX -> lockManager.mutex(key);
            case READ -> lockManager.readWrite(key).readLock();
            case WRITE -> lockManager.readWrite(key).writeLock();
        };
    }

    private Duration resolveWaitTimeout(DistributedLock distributedLock) {
        if (distributedLock.waitFor() != null && !distributedLock.waitFor().isBlank()) {
            return DurationStyle.detectAndParse(distributedLock.waitFor());
        }
        return properties.getSpring().getAnnotation().getDefaultTimeout();
    }
}
