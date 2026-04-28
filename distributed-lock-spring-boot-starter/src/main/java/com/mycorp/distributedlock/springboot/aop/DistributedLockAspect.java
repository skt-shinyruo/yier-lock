package com.mycorp.distributedlock.springboot.aop;

import com.mycorp.distributedlock.api.LeasePolicy;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.springboot.annotation.DistributedLock;
import com.mycorp.distributedlock.springboot.annotation.DistributedLockMode;
import com.mycorp.distributedlock.springboot.key.LockKeyResolver;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;

@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public final class DistributedLockAspect {

    private final SynchronousLockExecutor lockExecutor;
    private final LockKeyResolver lockKeyResolver;
    private final DistributedLockMethodResolver methodResolver = new DistributedLockMethodResolver();

    public DistributedLockAspect(
        SynchronousLockExecutor lockExecutor,
        LockKeyResolver lockKeyResolver
    ) {
        this.lockExecutor = Objects.requireNonNull(lockExecutor, "lockExecutor");
        this.lockKeyResolver = Objects.requireNonNull(lockKeyResolver, "lockKeyResolver");
    }

    @Around("execution(* *(..))"
        + " && !within(org.springframework..*)"
        + " && !@within(org.springframework.context.annotation.Configuration)"
        + " && !within(com.mycorp.distributedlock.springboot.aop..*)"
        + " && !within(com.mycorp.distributedlock.springboot.config..*)"
        + " && !within(com.mycorp.distributedlock.springboot.key..*)"
        + " && !within(com.mycorp.distributedlock.springboot.integration.DistributedLockAspectIntegrationTest$CapturingBackend)"
        + " && !within(com.mycorp.distributedlock.springboot.integration.DistributedLockAspectIntegrationTest$CapturingBackendModule)"
        + " && !within(com.mycorp.distributedlock.api..*)"
        + " && !within(com.mycorp.distributedlock.core..*)"
        + " && !within(com.mycorp.distributedlock.runtime..*)"
        + " && !within(com.mycorp.distributedlock.testkit..*)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        DistributedLockMethodResolver.ResolvedLockMethod resolved = methodResolver.resolve(joinPoint, null);
        if (resolved.distributedLock() == null) {
            return joinPoint.proceed();
        }
        ensureSynchronous(resolved);
        LockRequest request = resolveRequest(joinPoint, resolved.distributedLock());
        return lockExecutor.withLock(request, lease -> proceed(joinPoint));
    }

    private LockRequest resolveRequest(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) {
        String key = lockKeyResolver.resolveKey(joinPoint, distributedLock.key());
        return new LockRequest(
            new LockKey(key),
            resolveMode(distributedLock.mode()),
            resolveWaitPolicy(distributedLock),
            resolveLeasePolicy(distributedLock)
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
        if (waitTimeout.isZero()) {
            return WaitPolicy.tryOnce();
        }
        return WaitPolicy.timed(waitTimeout);
    }

    private Duration resolveWaitTimeout(DistributedLock distributedLock) {
        if (distributedLock.waitFor() != null && !distributedLock.waitFor().isBlank()) {
            return DurationStyle.detectAndParse(distributedLock.waitFor());
        }
        return null;
    }

    private LeasePolicy resolveLeasePolicy(DistributedLock distributedLock) {
        if (distributedLock.leaseFor() == null || distributedLock.leaseFor().isBlank()) {
            return LeasePolicy.backendDefault();
        }
        Duration leaseDuration = DurationStyle.detectAndParse(distributedLock.leaseFor());
        return LeasePolicy.fixed(leaseDuration);
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

    private void ensureSynchronous(DistributedLockMethodResolver.ResolvedLockMethod resolved) {
        if (resolved.asyncAnnotated()) {
            throw new LockConfigurationException(
                "@DistributedLock does not support @Async methods: " + resolved.specificMethod()
            );
        }

        ensureSynchronousReturnType(resolved.proxiedMethod());
        if (!resolved.specificMethod().equals(resolved.proxiedMethod())) {
            ensureSynchronousReturnType(resolved.specificMethod());
        }
    }

    private void ensureSynchronousReturnType(Method method) {
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
