package com.mycorp.distributedlock.springboot.aop;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.springboot.annotation.DistributedLock;
import com.mycorp.distributedlock.springboot.annotation.DistributedLockMode;
import com.mycorp.distributedlock.springboot.config.DistributedLockProperties;
import com.mycorp.distributedlock.springboot.key.LockKeyResolver;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.boot.convert.DurationStyle;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;

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
        ensureSynchronousReturnType(joinPoint);
        String key = lockKeyResolver.resolveKey(joinPoint, distributedLock.key());
        MutexLock lock = resolveLock(key, distributedLock.mode());
        Duration waitTimeout = resolveWaitTimeout(distributedLock);

        if (waitTimeout == null) {
            lock.lock();
        } else if (!lock.tryLock(waitTimeout)) {
            throw new LockAcquisitionTimeoutException("Failed to acquire distributed lock for key " + key);
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

    private void ensureSynchronousReturnType(ProceedingJoinPoint joinPoint) {
        if (!(joinPoint.getSignature() instanceof MethodSignature methodSignature)) {
            return;
        }

        Method method = methodSignature.getMethod();
        Class<?> returnType = method.getReturnType();
        if (CompletionStage.class.isAssignableFrom(returnType)
            || Future.class.isAssignableFrom(returnType)
            || isReactivePublisherType(returnType)) {
            throw new LockConfigurationException(
                "@DistributedLock does not support async return types such as " + returnType.getSimpleName() + ": " + method
            );
        }
    }

    private boolean isReactivePublisherType(Class<?> returnType) {
        try {
            Class<?> publisherType = Class.forName("org.reactivestreams.Publisher", false, returnType.getClassLoader());
            return publisherType.isAssignableFrom(returnType);
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }
}
