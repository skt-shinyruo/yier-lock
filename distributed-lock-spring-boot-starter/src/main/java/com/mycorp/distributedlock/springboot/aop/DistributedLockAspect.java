package com.mycorp.distributedlock.springboot.aop;

import com.mycorp.distributedlock.api.LockExecutor;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
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

    private final LockExecutor lockExecutor;
    private final LockKeyResolver lockKeyResolver;
    private final DistributedLockProperties properties;

    public DistributedLockAspect(
        LockExecutor lockExecutor,
        LockKeyResolver lockKeyResolver,
        DistributedLockProperties properties
    ) {
        this.lockExecutor = Objects.requireNonNull(lockExecutor, "lockExecutor");
        this.lockKeyResolver = Objects.requireNonNull(lockKeyResolver, "lockKeyResolver");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        ensureSynchronousReturnType(joinPoint);
        LockRequest request = resolveRequest(joinPoint, distributedLock);
        return lockExecutor.withLock(request, () -> proceed(joinPoint));
    }

    private LockRequest resolveRequest(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) {
        String key = lockKeyResolver.resolveKey(joinPoint, distributedLock.key());
        return new LockRequest(
            new LockKey(key),
            resolveMode(distributedLock.mode()),
            resolveWaitPolicy(distributedLock)
        );
    }

    private LockMode resolveMode(DistributedLockMode mode) {
        return switch (mode) {
            case MUTEX -> LockMode.MUTEX;
            case READ -> LockMode.READ;
            case WRITE -> LockMode.WRITE;
        };
    }

    private WaitPolicy resolveWaitPolicy(DistributedLock distributedLock) {
        Duration waitTimeout = resolveWaitTimeout(distributedLock);
        if (waitTimeout == null) {
            return WaitPolicy.indefinite();
        }
        return WaitPolicy.timed(waitTimeout);
    }

    private Duration resolveWaitTimeout(DistributedLock distributedLock) {
        if (distributedLock.waitFor() != null && !distributedLock.waitFor().isBlank()) {
            return DurationStyle.detectAndParse(distributedLock.waitFor());
        }
        return properties.getSpring().getAnnotation().getDefaultTimeout();
    }

    private Object proceed(ProceedingJoinPoint joinPoint) throws Exception {
        try {
            return joinPoint.proceed();
        } catch (Exception exception) {
            throw exception;
        } catch (Error error) {
            throw error;
        } catch (Throwable throwable) {
            throw new IllegalStateException("Unexpected throwable from join point", throwable);
        }
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
