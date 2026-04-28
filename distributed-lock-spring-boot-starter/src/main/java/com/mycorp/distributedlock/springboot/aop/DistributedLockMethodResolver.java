package com.mycorp.distributedlock.springboot.aop;

import com.mycorp.distributedlock.springboot.annotation.DistributedLock;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Async;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
        List<Method> interfaceMethods = findInterfaceMethods(targetClass, specificMethod);
        if (annotation == null) {
            annotation = findInterfaceAnnotation(interfaceMethods);
        }
        if (annotation == null) {
            annotation = pointcutAnnotation;
        }
        return new ResolvedLockMethod(
            proxiedMethod,
            specificMethod,
            targetClass,
            annotation,
            hasAsync(proxiedMethod, specificMethod, targetClass, interfaceMethods)
        );
    }

    private DistributedLock findInterfaceAnnotation(List<Method> interfaceMethods) {
        for (Method interfaceMethod : interfaceMethods) {
            DistributedLock annotation = AnnotatedElementUtils.findMergedAnnotation(interfaceMethod, DistributedLock.class);
            if (annotation != null) {
                return annotation;
            }
        }
        return null;
    }

    private List<Method> findInterfaceMethods(Class<?> targetClass, Method specificMethod) {
        List<Method> methods = new ArrayList<>();
        collectInterfaceMethods(targetClass, specificMethod, methods);
        return methods;
    }

    private void collectInterfaceMethods(Class<?> targetClass, Method specificMethod, List<Method> methods) {
        for (Class<?> interfaceType : targetClass.getInterfaces()) {
            collectMethodsOnInterface(interfaceType, specificMethod, methods);
        }
        Class<?> superclass = targetClass.getSuperclass();
        if (superclass != null && !Object.class.equals(superclass)) {
            collectInterfaceMethods(superclass, specificMethod, methods);
        }
    }

    private void collectMethodsOnInterface(Class<?> interfaceType, Method specificMethod, List<Method> methods) {
        for (Method candidate : interfaceType.getMethods()) {
            if (matches(candidate, specificMethod)) {
                methods.add(candidate);
            }
        }
    }

    private boolean matches(Method candidate, Method specificMethod) {
        if (!candidate.getName().equals(specificMethod.getName())) {
            return false;
        }
        Class<?>[] candidateTypes = candidate.getParameterTypes();
        Class<?>[] specificTypes = specificMethod.getParameterTypes();
        if (candidateTypes.length != specificTypes.length) {
            return false;
        }
        if (Arrays.equals(candidateTypes, specificTypes)) {
            return true;
        }
        for (int index = 0; index < candidateTypes.length; index++) {
            if (!candidateTypes[index].isAssignableFrom(specificTypes[index])) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAsync(Method proxiedMethod, Method specificMethod, Class<?> targetClass, List<Method> interfaceMethods) {
        return AnnotatedElementUtils.hasAnnotation(proxiedMethod, Async.class)
            || AnnotatedElementUtils.hasAnnotation(proxiedMethod.getDeclaringClass(), Async.class)
            || AnnotatedElementUtils.hasAnnotation(specificMethod, Async.class)
            || AnnotatedElementUtils.hasAnnotation(targetClass, Async.class)
            || hasInterfaceAsync(interfaceMethods);
    }

    private boolean hasInterfaceAsync(List<Method> interfaceMethods) {
        for (Method interfaceMethod : interfaceMethods) {
            if (AnnotatedElementUtils.hasAnnotation(interfaceMethod, Async.class)
                || AnnotatedElementUtils.hasAnnotation(interfaceMethod.getDeclaringClass(), Async.class)) {
                return true;
            }
        }
        return false;
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
