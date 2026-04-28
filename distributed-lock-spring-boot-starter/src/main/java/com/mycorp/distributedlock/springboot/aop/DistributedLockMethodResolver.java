package com.mycorp.distributedlock.springboot.aop;

import com.mycorp.distributedlock.springboot.annotation.DistributedLock;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Async;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

final class DistributedLockMethodResolver {

    ResolvedLockMethod resolve(ProceedingJoinPoint joinPoint, DistributedLock pointcutAnnotation) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method proxiedMethod = signature.getMethod();
        Class<?> targetClass = joinPoint.getTarget() == null ? proxiedMethod.getDeclaringClass() : joinPoint.getTarget().getClass();
        return resolve(proxiedMethod, targetClass, pointcutAnnotation);
    }

    ResolvedLockMethod resolve(Method proxiedMethod, Class<?> targetClass, DistributedLock pointcutAnnotation) {
        Method specificMethod = BridgeMethodResolver.findBridgedMethod(AopUtils.getMostSpecificMethod(proxiedMethod, targetClass));
        DistributedLock annotation = AnnotatedElementUtils.findMergedAnnotation(specificMethod, DistributedLock.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(proxiedMethod, DistributedLock.class);
        }
        Optional<Method> interfaceMethod = findInterfaceMethod(targetClass, specificMethod);
        if (annotation == null && interfaceMethod.isPresent()) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(interfaceMethod.get(), DistributedLock.class);
        }
        if (annotation == null) {
            annotation = pointcutAnnotation;
        }
        return new ResolvedLockMethod(
            proxiedMethod,
            specificMethod,
            targetClass,
            annotation,
            hasAsync(proxiedMethod, specificMethod, targetClass, interfaceMethod.orElse(null))
        );
    }

    private Optional<Method> findInterfaceMethod(Class<?> targetClass, Method specificMethod) {
        for (Class<?> interfaceType : targetClass.getInterfaces()) {
            Optional<Method> method = findMethodOnInterface(interfaceType, specificMethod);
            if (method.isPresent()) {
                return method;
            }
        }
        Class<?> superclass = targetClass.getSuperclass();
        if (superclass == null || Object.class.equals(superclass)) {
            return Optional.empty();
        }
        return findInterfaceMethod(superclass, specificMethod);
    }

    private Optional<Method> findMethodOnInterface(Class<?> interfaceType, Method specificMethod) {
        for (Method candidate : interfaceType.getMethods()) {
            if (candidate.getName().equals(specificMethod.getName())
                && Arrays.equals(candidate.getParameterTypes(), specificMethod.getParameterTypes())) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private boolean hasAsync(Method proxiedMethod, Method specificMethod, Class<?> targetClass, Method interfaceMethod) {
        return AnnotatedElementUtils.hasAnnotation(proxiedMethod, Async.class)
            || AnnotatedElementUtils.hasAnnotation(proxiedMethod.getDeclaringClass(), Async.class)
            || AnnotatedElementUtils.hasAnnotation(specificMethod, Async.class)
            || AnnotatedElementUtils.hasAnnotation(targetClass, Async.class)
            || (interfaceMethod != null && (AnnotatedElementUtils.hasAnnotation(interfaceMethod, Async.class)
                || AnnotatedElementUtils.hasAnnotation(interfaceMethod.getDeclaringClass(), Async.class)));
    }

    record ResolvedLockMethod(
        Method proxiedMethod,
        Method specificMethod,
        Class<?> targetClass,
        DistributedLock distributedLock,
        boolean asyncAnnotated
    ) {
    }
}
