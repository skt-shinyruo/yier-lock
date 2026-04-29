package com.mycorp.distributedlock.springboot.aop;

import com.mycorp.distributedlock.api.LeasePolicy;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.springboot.annotation.DistributedLock;
import com.mycorp.distributedlock.springboot.config.DistributedLockProperties;
import com.mycorp.distributedlock.springboot.key.LockKeyResolver;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.aspectj.lang.reflect.SourceLocation;
import org.aspectj.runtime.internal.AroundClosure;
import org.springframework.aop.support.StaticMethodMatcherPointcutAdvisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.Ordered;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;

public final class DistributedLockAspect extends StaticMethodMatcherPointcutAdvisor implements MethodInterceptor {

    private static final long serialVersionUID = 1L;

    private final ObjectProvider<SynchronousLockExecutor> lockExecutorProvider;
    private final ObjectProvider<LockKeyResolver> lockKeyResolverProvider;
    private final DistributedLockProperties properties;
    private final DistributedLockMethodResolver methodResolver = new DistributedLockMethodResolver();

    public DistributedLockAspect(
        ObjectProvider<SynchronousLockExecutor> lockExecutorProvider,
        ObjectProvider<LockKeyResolver> lockKeyResolverProvider,
        DistributedLockProperties properties
    ) {
        this.lockExecutorProvider = Objects.requireNonNull(lockExecutorProvider, "lockExecutorProvider");
        this.lockKeyResolverProvider = Objects.requireNonNull(lockKeyResolverProvider, "lockKeyResolverProvider");
        this.properties = Objects.requireNonNull(properties, "properties");
        setAdvice(this);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        return methodResolver.resolve(method, targetClass, null).distributedLock() != null;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Class<?> targetClass = invocation.getThis() == null ? invocation.getMethod().getDeclaringClass() : invocation.getThis().getClass();
        DistributedLockMethodResolver.ResolvedLockMethod resolved = methodResolver.resolve(invocation.getMethod(), targetClass, null);
        ensureSynchronous(resolved);
        LockRequest request = resolveRequest(new MethodInvocationProceedingJoinPoint(invocation), resolved.specificMethod(), resolved.distributedLock());
        return lockExecutorProvider.getObject().withLock(request, lease -> proceed(invocation));
    }

    private LockRequest resolveRequest(ProceedingJoinPoint joinPoint, Method method, DistributedLock distributedLock) {
        String key = lockKeyResolverProvider.getObject().resolveKey(joinPoint, distributedLock.key());
        return new LockRequest(
            new LockKey(key),
            distributedLock.mode(),
            resolveWaitPolicy(method, distributedLock),
            resolveLeasePolicy(method, distributedLock)
        );
    }

    private WaitPolicy resolveWaitPolicy(Method method, DistributedLock distributedLock) {
        Duration waitTimeout = resolveWaitTimeout(method, distributedLock);
        if (waitTimeout == null) {
            return WaitPolicy.indefinite();
        }
        if (waitTimeout.isZero()) {
            return WaitPolicy.tryOnce();
        }
        return WaitPolicy.timed(waitTimeout);
    }

    private Duration resolveWaitTimeout(Method method, DistributedLock distributedLock) {
        if (distributedLock.waitFor() != null && !distributedLock.waitFor().isBlank()) {
            return parseDuration(method, "waitFor", distributedLock.waitFor());
        }
        return properties.getSpring().getAnnotation().getDefaultTimeout();
    }

    private LeasePolicy resolveLeasePolicy(Method method, DistributedLock distributedLock) {
        if (distributedLock.leaseFor() == null || distributedLock.leaseFor().isBlank()) {
            return LeasePolicy.backendDefault();
        }
        Duration leaseDuration = parseDuration(method, "leaseFor", distributedLock.leaseFor());
        return LeasePolicy.fixed(leaseDuration);
    }

    private Duration parseDuration(Method method, String attributeName, String value) {
        try {
            return DurationStyle.detectAndParse(value);
        } catch (RuntimeException exception) {
            throw new LockConfigurationException(
                "Invalid @DistributedLock " + attributeName + " value '" + value + "' on " + method,
                exception
            );
        }
    }

    private Object proceed(MethodInvocation invocation) throws Exception {
        try {
            return invocation.proceed();
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
        if (isKotlinCoroutineMethod(method)) {
            throw new LockConfigurationException(
                "@DistributedLock does not support Kotlin coroutine methods: " + method
            );
        }
        if (Object.class.equals(returnType) && !properties.getSpring().getAnnotation().isAllowDynamicReturnType()) {
            throw new LockConfigurationException(
                "@DistributedLock does not support Object return type by default because async results cannot be rejected before invocation: "
                    + method
            );
        }
        if (CompletionStage.class.isAssignableFrom(returnType)
            || Future.class.isAssignableFrom(returnType)
            || isReactivePublisherType(returnType)) {
            throw new LockConfigurationException(
                "@DistributedLock does not support async return types such as " + returnType.getSimpleName() + ": " + method
            );
        }
    }

    private boolean isKotlinCoroutineMethod(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length > 0
            && "kotlin.coroutines.Continuation".equals(parameterTypes[parameterTypes.length - 1].getName());
    }

    private boolean isReactivePublisherType(Class<?> returnType) {
        try {
            Class<?> publisherType = Class.forName("org.reactivestreams.Publisher", false, returnType.getClassLoader());
            return publisherType.isAssignableFrom(returnType);
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }

    private static final class MethodInvocationProceedingJoinPoint implements ProceedingJoinPoint {

        private final MethodInvocation invocation;

        private MethodInvocationProceedingJoinPoint(MethodInvocation invocation) {
            this.invocation = invocation;
        }

        @Override
        public Object proceed() throws Throwable {
            return invocation.proceed();
        }

        @Override
        public Object proceed(Object[] args) {
            throw new UnsupportedOperationException("Proceeding with replacement arguments is not supported");
        }

        @Override
        public Object getThis() {
            return invocation.getThis();
        }

        @Override
        public Object getTarget() {
            return invocation.getThis();
        }

        @Override
        public Object[] getArgs() {
            return invocation.getArguments();
        }

        @Override
        public Signature getSignature() {
            return new InvocationMethodSignature(invocation.getMethod());
        }

        @Override
        public String toShortString() {
            return getSignature().toShortString();
        }

        @Override
        public String toLongString() {
            return getSignature().toLongString();
        }

        @Override
        public void set$AroundClosure(AroundClosure arc) {
        }

        @Override
        public SourceLocation getSourceLocation() {
            return null;
        }

        @Override
        public String getKind() {
            return ProceedingJoinPoint.METHOD_EXECUTION;
        }

        @Override
        public StaticPart getStaticPart() {
            return null;
        }
    }

    private record InvocationMethodSignature(Method method) implements MethodSignature {

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public Class<?> getReturnType() {
            return method.getReturnType();
        }

        @Override
        public Class<?>[] getParameterTypes() {
            return method.getParameterTypes();
        }

        @Override
        public String[] getParameterNames() {
            return null;
        }

        @Override
        public Class<?>[] getExceptionTypes() {
            return method.getExceptionTypes();
        }

        @Override
        public String toShortString() {
            return method.toGenericString();
        }

        @Override
        public String toLongString() {
            return method.toGenericString();
        }

        @Override
        public String getName() {
            return method.getName();
        }

        @Override
        public int getModifiers() {
            return method.getModifiers();
        }

        @Override
        public Class<?> getDeclaringType() {
            return method.getDeclaringClass();
        }

        @Override
        public String getDeclaringTypeName() {
            return method.getDeclaringClass().getName();
        }
    }
}
