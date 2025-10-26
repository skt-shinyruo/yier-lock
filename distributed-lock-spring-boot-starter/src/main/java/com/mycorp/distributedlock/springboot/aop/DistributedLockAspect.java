package com.mycorp.distributedlock.springboot.aop;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.api.annotation.DistributedLock;
import com.mycorp.distributedlock.api.exception.LockAcquisitionException;
import com.mycorp.distributedlock.api.exception.LockReleaseException;
import com.mycorp.distributedlock.core.config.UnifiedLockConfiguration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 分布式锁AOP切面
 * 支持@DistributedLock注解的自动锁管理
 * 
 * @since Spring Boot 3.x
 */
@Aspect
@Component
@Order(100) // 设置切面优先级，确保在其他切面之前执行
public class DistributedLockAspect {

    private static final Logger logger = LoggerFactory.getLogger(DistributedLockAspect.class);

    private final DistributedLockFactory lockFactory;
    private final UnifiedLockConfiguration configuration;
    private final ConcurrentMap<String, Counter> lockOperationCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> lockOperationTimers = new ConcurrentHashMap<>();

    public DistributedLockAspect(DistributedLockFactory lockFactory, UnifiedLockConfiguration configuration) {
        this.lockFactory = lockFactory;
        this.configuration = configuration;
        logger.info("DistributedLockAspect initialized with lock factory: {}", lockFactory.getClass().getSimpleName());
    }

    /**
     * 环绕通知，处理@DistributedLock注解
     */
    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String lockKey = resolveLockKey(joinPoint, distributedLock);
        
        logger.debug("Acquiring distributed lock for method: {} with key: {}", 
                    method.getName(), lockKey);

        DistributedLock lock = null;
        boolean lockAcquired = false;
        Span span = null;
        Scope scope = null;
        Timer.Sample timerSample = Timer.start();
        
        try {
            // 创建OpenTelemetry Span
            span = io.opentelemetry.api.trace.Tracer.builder("distributed-lock")
                    .buildInstrument(io.opentelemetry.api.trace.Tracer.getDefault())
                    .spanBuilder("lock-operation")
                    .setAttribute("lock.key", lockKey)
                    .setAttribute("lock.type", distributedLock.lockType().name())
                    .setAttribute("method.name", method.getName())
                    .setAttribute("class.name", method.getDeclaringClass().getSimpleName())
                    .startSpan();

            scope = span.makeCurrent();
            span.setStatus(StatusCode.OK);

            // 获取分布式锁
            lock = acquireLock(lockKey, distributedLock);
            
            // 记录指标
            recordLockAcquisition(lockKey, distributedLock.lockType().name());

            timerSample.stop(getOrCreateTimer(lockKey, distributedLock.lockType().name()));

            // 执行目标方法
            Object result = joinPoint.proceed();
            
            logger.debug("Successfully executed method: {} with lock: {}", method.getName(), lockKey);
            
            // 记录成功指标
            recordSuccess(lockKey, distributedLock.lockType().name());
            
            return result;

        } catch (LockAcquisitionException e) {
            recordFailure(lockKey, distributedLock.lockType().name(), "acquisition-timeout");
            logger.warn("Failed to acquire lock for method: {} with key: {}", method.getName(), lockKey, e);
            
            if (span != null) {
                span.setStatus(StatusCode.ERROR, "Lock acquisition failed");
                span.recordException(e);
            }
            
            // 根据注解配置决定是否抛出异常
            if (distributedLock.throwExceptionOnFailure()) {
                throw e;
            }
            
            // 返回默认值或null
            return getDefaultReturnValue(method.getReturnType());
            
        } catch (Exception e) {
            recordFailure(lockKey, distributedLock.lockType().name(), "execution-error");
            logger.error("Error executing method: {} with lock: {}", method.getName(), lockKey, e);
            
            if (span != null) {
                span.setStatus(StatusCode.ERROR, "Execution error");
                span.recordException(e);
            }
            
            throw e;
            
        } finally {
            // 释放锁
            if (lock != null && lockAcquired) {
                try {
                    lock.unlock();
                    logger.debug("Released lock: {}", lockKey);
                } catch (LockReleaseException e) {
                    logger.warn("Failed to release lock: {}", lockKey, e);
                }
            }

            // 关闭Span和Scope
            if (scope != null) {
                scope.close();
            }
            if (span != null) {
                span.end();
            }

            // 记录总体执行时间
            recordExecutionTime(lockKey, distributedLock.lockType().name(), timerSample);
        }
    }

    /**
     * 获取分布式锁
     */
    private DistributedLock acquireLock(String lockKey, DistributedLock annotation) throws LockAcquisitionException {
        DistributedLock lock = lockFactory.getLock(lockKey);
        
        Duration leaseTime = annotation.leaseTime().isEmpty() ? 
            configuration.getDefaultLeaseTime() : 
            Duration.parse(annotation.leaseTime());
            
        Duration waitTime = annotation.waitTime().isEmpty() ? 
            configuration.getDefaultWaitTime() : 
            Duration.parse(annotation.waitTime());
        
        boolean acquired = lock.tryLock(waitTime, leaseTime);
        
        if (!acquired) {
            throw new LockAcquisitionException("Failed to acquire lock for key: " + lockKey + 
                " within wait time: " + waitTime);
        }
        
        return lock;
    }

    /**
     * 解析锁键
     */
    private String resolveLockKey(ProceedingJoinPoint joinPoint, DistributedLock annotation) {
        String key = annotation.key();
        
        if (!key.isEmpty()) {
            // 如果注解中指定了锁键，直接使用
            return key;
        }
        
        // 如果没有指定锁键，生成基于方法签名的默认锁键
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return generateDefaultLockKey(signature);
    }

    /**
     * 生成默认锁键
     */
    private String generateDefaultLockKey(MethodSignature signature) {
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(signature.getDeclaringType().getSimpleName());
        keyBuilder.append(".");
        keyBuilder.append(signature.getName());
        
        // 如果有参数，可以考虑将其包含在锁键中
        Object[] args = signature.getArgs();
        if (args.length > 0) {
            keyBuilder.append("(");
            for (int i = 0; i < args.length; i++) {
                if (i > 0) keyBuilder.append(",");
                keyBuilder.append(args[i] != null ? args[i].getClass().getSimpleName() : "null");
            }
            keyBuilder.append(")");
        }
        
        return keyBuilder.toString();
    }

    /**
     * 记录锁获取
     */
    private void recordLockAcquisition(String lockKey, String lockType) {
        String counterName = "distributed.lock.acquisition." + lockType;
        lockOperationCounters.computeIfAbsent(counterName, this::createCounter)
                .increment();
        
        logger.debug("Recorded lock acquisition for key: {}, type: {}", lockKey, lockType);
    }

    /**
     * 记录成功执行
     */
    private void recordSuccess(String lockKey, String lockType) {
        String counterName = "distributed.lock.success." + lockType;
        lockOperationCounters.computeIfAbsent(counterName, this::createCounter)
                .increment();
    }

    /**
     * 记录失败执行
     */
    private void recordFailure(String lockKey, String lockType, String reason) {
        String counterName = "distributed.lock.failure." + lockType + "." + reason;
        lockOperationCounters.computeIfAbsent(counterName, this::createCounter)
                .increment();
    }

    /**
     * 记录执行时间
     */
    private void recordExecutionTime(String lockKey, String lockType, Timer.Sample sample) {
        // 这里可以记录总体执行时间
        logger.debug("Recorded execution time for key: {}, type: {}", lockKey, lockType);
    }

    /**
     * 创建或获取计时器
     */
    private Timer getOrCreateTimer(String lockKey, String lockType) {
        String timerName = "distributed.lock.execution.time." + lockType;
        return lockOperationTimers.computeIfAbsent(timerName, this::createTimer);
    }

    /**
     * 创建计数器
     */
    private Counter createCounter(String name) {
        // 这里应该从Micrometer MeterRegistry创建计数器
        // 简化实现，返回虚拟计数器
        return new MockCounter(name);
    }

    /**
     * 创建计时器
     */
    private Timer createTimer(String name) {
        // 这里应该从Micrometer MeterRegistry创建计时器
        // 简化实现，返回虚拟计时器
        return new MockTimer(name);
    }

    /**
     * 模拟计数器实现
     */
    private static class MockCounter implements Counter {
        private final String name;

        public MockCounter(String name) {
            this.name = name;
        }

        @Override
        public void increment(double amount) {
            // 实际实现应该使用MeterRegistry
        }

        @Override
        public double count() {
            return 0;
        }

        @Override
        public Iterable<io.micrometer.core.instrument.Tag> getTags() {
            return java.util.Collections.emptyList();
        }

        @Override
        public io.micrometer.core.instrument.Meter.Id getId() {
            return new io.micrometer.core.instrument.Meter.Id(name,
                java.util.Collections.emptyList(), "", "", io.micrometer.core.instrument.Meter.Type.COUNTER);
        }
    }

    /**
     * 模拟计时器实现
     */
    private static class MockTimer implements Timer {
        private final String name;

        public MockTimer(String name) {
            this.name = name;
        }

        @Override
        public void record(Duration duration) {
            // 实际实现应该使用MeterRegistry
        }

        @Override
        public void record(long amount, java.util.concurrent.TimeUnit timeUnit) {
            // 实际实现应该使用MeterRegistry
        }

        @Override
        public double totalTime(java.util.concurrent.TimeUnit timeUnit) {
            return 0;
        }

        @Override
        public long count() {
            return 0;
        }

        @Override
        public double mean(java.util.concurrent.TimeUnit timeUnit) {
            return 0;
        }

        @Override
        public double max(java.util.concurrent.TimeUnit timeUnit) {
            return 0;
        }

        @Override
        public Iterable<io.micrometer.core.instrument.Tag> getTags() {
            return java.util.Collections.emptyList();
        }

        @Override
        public io.micrometer.core.instrument.Meter.Id getId() {
            return new io.micrometer.core.instrument.Meter.Id(name,
                java.util.Collections.emptyList(), "", "", io.micrometer.core.instrument.Meter.Type.TIMER);
        }

        @Override
        public Sample start() {
            return new Sample() {
                @Override
                public void stop(Timer timer) {
                    // 实际实现应该使用MeterRegistry
                }
            };
        }
    }

    /**
     * 获取默认返回值
     */
    private Object getDefaultReturnValue(Class<?> returnType) {
        if (returnType.isPrimitive()) {
            if (returnType == boolean.class) return false;
            if (returnType == int.class) return 0;
            if (returnType == long.class) return 0L;
            if (returnType == double.class) return 0.0;
            if (returnType == float.class) return 0.0f;
            if (returnType == char.class) return '\0';
            if (returnType == byte.class) return (byte) 0;
            if (returnType == short.class) return (short) 0;
        }
        return null;
    }
}