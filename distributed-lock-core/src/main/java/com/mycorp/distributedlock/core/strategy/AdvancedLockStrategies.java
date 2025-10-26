package com.mycorp.distributedlock.core.strategy;

import com.mycorp.distributedlock.api.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 高级锁策略 - 简化版本
 */
public class AdvancedLockStrategies {
    
    /**
     * 限时锁 - 自动释放
     */
    public static class TimedLock implements DistributedLock {
        private final DistributedLock delegate;
        private final Duration timeout;
        private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        private volatile boolean autoReleaseScheduled = false;
        
        public TimedLock(DistributedLock delegate, Duration timeout) {
            this.delegate = delegate;
            this.timeout = timeout;
        }
        
        @Override
        public String getName() {
            return delegate.getName();
        }
        
        @Override
        public boolean acquire() throws InterruptedException {
            boolean acquired = delegate.acquire();
            if (acquired && !autoReleaseScheduled) {
                scheduleAutoRelease();
            }
            return acquired;
        }
        
        @Override
        public boolean acquire(Duration timeout) throws InterruptedException {
            boolean acquired = delegate.acquire(timeout);
            if (acquired && !autoReleaseScheduled) {
                scheduleAutoRelease();
            }
            return acquired;
        }
        
        @Override
        public boolean tryLock(Duration timeout) {
            try {
                return acquire(timeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        @Override
        public void release() {
            if (autoReleaseScheduled) {
                // 取消自动释放
                // 简化实现
                autoReleaseScheduled = false;
            }
            delegate.release();
        }
        
        @Override
        public boolean isLocked() {
            return delegate.isLocked();
        }
        
        @Override
        public boolean isHeldByCurrentThread() {
            return delegate.isHeldByCurrentThread();
        }
        
        @Override
        public RenewalResult renew() {
            return delegate.renew();
        }
        
        @Override
        public HealthCheck.HealthCheckResult healthCheck() {
            return delegate.healthCheck();
        }
        
        @Override
        public LockStateInfo getStateInfo() {
            return delegate.getStateInfo();
        }
        
        @Override
        public LockConfigurationInfo getConfigurationInfo() {
            return delegate.getConfigurationInfo();
        }
        
        @Override
        public void close() {
            scheduler.shutdown();
            delegate.close();
        }
        
        private void scheduleAutoRelease() {
            autoReleaseScheduled = true;
            scheduler.schedule(() -> {
                if (delegate.isHeldByCurrentThread()) {
                    delegate.release();
                }
                autoReleaseScheduled = false;
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
    
    /**
     * 条件锁 - 等待特定条件
     */
    public static class ConditionalLock implements DistributedLock {
        private final DistributedLock delegate;
        private final AtomicBoolean conditionMet = new AtomicBoolean(false);
        private final Lock conditionLock = new ReentrantLock();
        private final Condition condition = conditionLock.newCondition();
        
        public ConditionalLock(DistributedLock delegate) {
            this.delegate = delegate;
        }
        
        @Override
        public String getName() {
            return delegate.getName();
        }
        
        @Override
        public boolean acquire() throws InterruptedException {
            boolean acquired = delegate.acquire();
            if (acquired) {
                waitForCondition();
            }
            return acquired;
        }
        
        @Override
        public boolean acquire(Duration timeout) throws InterruptedException {
            boolean acquired = delegate.acquire(timeout);
            if (acquired) {
                waitForCondition(timeout);
            }
            return acquired;
        }
        
        @Override
        public boolean tryLock(Duration timeout) {
            try {
                return acquire(timeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        @Override
        public void release() {
            conditionMet.set(true);
            conditionLock.lock();
            try {
                condition.signalAll();
            } finally {
                conditionLock.unlock();
            }
            delegate.release();
        }
        
        @Override
        public boolean isLocked() {
            return delegate.isLocked();
        }
        
        @Override
        public boolean isHeldByCurrentThread() {
            return delegate.isHeldByCurrentThread();
        }
        
        @Override
        public RenewalResult renew() {
            return delegate.renew();
        }
        
        @Override
        public HealthCheck.HealthCheckResult healthCheck() {
            return delegate.healthCheck();
        }
        
        @Override
        public LockStateInfo getStateInfo() {
            return delegate.getStateInfo();
        }
        
        @Override
        public LockConfigurationInfo getConfigurationInfo() {
            return delegate.getConfigurationInfo();
        }
        
        @Override
        public void close() {
            delegate.close();
        }
        
        private void waitForCondition() throws InterruptedException {
            conditionLock.lock();
            try {
                while (!conditionMet.get()) {
                    condition.await();
                }
            } finally {
                conditionLock.unlock();
            }
        }
        
        private void waitForCondition(Duration timeout) throws InterruptedException {
            conditionLock.lock();
            try {
                long remaining = timeout.toMillis();
                long endTime = System.currentTimeMillis() + remaining;
                
                while (!conditionMet.get() && remaining > 0) {
                    long waitTime = Math.min(remaining, 100);
                    condition.await(waitTime, TimeUnit.MILLISECONDS);
                    remaining = endTime - System.currentTimeMillis();
                }
            } finally {
                conditionLock.unlock();
            }
        }
    }
    
    /**
     * 优先级锁 - 支持优先级调度
     */
    public static class PriorityLock implements DistributedLock {
        private final DistributedLock delegate;
        private final PriorityQueue<LockRequest> waitingQueue = new PriorityQueue<>(
            (a, b) -> Integer.compare(b.priority, a.priority)
        );
        
        public PriorityLock(DistributedLock delegate) {
            this.delegate = delegate;
        }
        
        @Override
        public String getName() {
            return delegate.getName();
        }
        
        @Override
        public boolean acquire() throws InterruptedException {
            return acquireWithPriority(0);
        }
        
        @Override
        public boolean acquire(Duration timeout) throws InterruptedException {
            return acquireWithPriority(0, timeout);
        }
        
        public boolean acquireWithPriority(int priority) throws InterruptedException {
            return acquireWithPriority(priority, Duration.ofSeconds(0));
        }
        
        public boolean acquireWithPriority(int priority, Duration timeout) throws InterruptedException {
            LockRequest request = new LockRequest(Thread.currentThread(), priority);
            
            if (delegate.tryLock(Duration.ofSeconds(0))) {
                return true;
            }
            
            waitingQueue.offer(request);
            
            try {
                return request.getLatch().await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } finally {
                waitingQueue.remove(request);
            }
        }
        
        @Override
        public boolean tryLock(Duration timeout) {
            try {
                return acquire(timeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        @Override
        public void release() {
            delegate.release();
            
            // 通知下一个等待者
            LockRequest nextRequest = waitingQueue.poll();
            if (nextRequest != null) {
                nextRequest.getLatch().countDown();
            }
        }
        
        @Override
        public boolean isLocked() {
            return delegate.isLocked();
        }
        
        @Override
        public boolean isHeldByCurrentThread() {
            return delegate.isHeldByCurrentThread();
        }
        
        @Override
        public RenewalResult renew() {
            return delegate.renew();
        }
        
        @Override
        public HealthCheck.HealthCheckResult healthCheck() {
            return delegate.healthCheck();
        }
        
        @Override
        public LockStateInfo getStateInfo() {
            return delegate.getStateInfo();
        }
        
        @Override
        public LockConfigurationInfo getConfigurationInfo() {
            return delegate.getConfigurationInfo();
        }
        
        @Override
        public void close() {
            delegate.close();
        }
        
        private static class LockRequest {
            private final Thread thread;
            private final int priority;
            private final CountDownLatch latch = new CountDownLatch(1);
            
            public LockRequest(Thread thread, int priority) {
                this.thread = thread;
                this.priority = priority;
            }
            
            public Thread getThread() { return thread; }
            public int getPriority() { return priority; }
            public CountDownLatch getLatch() { return latch; }
        }
    }
}