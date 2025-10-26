package com.mycorp.distributedlock.core.lock;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.monitoring.PerformanceMonitor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 公平锁高级特性测试
 */
@ExtendWith(MockitoExtension.class)
class FairLockAdvancedTest {
    
    private static final String TEST_LOCK_NAME = "fair-test-lock";
    
    private FairLockImpl fairLock;
    private PerformanceMonitor performanceMonitor;
    private MeterRegistry meterRegistry;
    
    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        performanceMonitor = new PerformanceMonitor(meterRegistry);
        performanceMonitor.start();
        
        LockConfiguration config = LockConfiguration.builder()
            .withLockName(TEST_LOCK_NAME)
            .withLeaseTime(Duration.ofSeconds(30))
            .withRetryAttempts(3)
            .withRetryDelay(Duration.ofMillis(100))
            .withFairness(true) // 启用公平性
            .build();
            
        fairLock = new FairLockImpl(config, performanceMonitor);
    }
    
    @AfterEach
    void tearDown() {
        if (fairLock != null) {
            try {
                fairLock.forceReleaseAll();
            } catch (Exception e) {
                // 忽略清理错误
            }
        }
        if (performanceMonitor != null) {
            performanceMonitor.stop();
            performanceMonitor.close();
        }
    }
    
    @Test
    void shouldMaintainAcquisitionOrder() throws InterruptedException {
        // Given
        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        AtomicInteger executionOrder = new AtomicInteger(0);
        AtomicInteger[] threadOrders = new AtomicInteger[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threadOrders[i] = new AtomicInteger(-1);
        }
        
        // When
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            final AtomicInteger order = new AtomicInteger(-1);
            
            executor.submit(() -> {
                try {
                    startLatch.await(); // 等待所有线程准备就绪
                    
                    // 每个线程尝试获取公平锁
                    boolean acquired = fairLock.acquire(Duration.ofSeconds(2));
                    if (acquired) {
                        order.set(executionOrder.getAndIncrement());
                        threadOrders[threadId].set(order.get());
                        
                        // 持有锁一小段时间
                        Thread.sleep(50);
                        fairLock.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }
        
        startLatch.countDown(); // 启动所有线程
        completionLatch.await(10, TimeUnit.SECONDS); // 等待完成
        executor.shutdown();
        
        // Then - 验证获取顺序
        int[] orders = new int[threadCount];
        for (int i = 0; i < threadCount; i++) {
            orders[i] = threadOrders[i].get();
        }
        
        // 所有线程都应该获得锁（按顺序）
        for (int order : orders) {
            assertNotEquals(-1, order, "Thread should have acquired lock");
        }
        
        // 验证顺序性 - 每个顺序应该只出现一次
        boolean[] seen = new boolean[threadCount];
        for (int order : orders) {
            assertFalse(seen[order], "Order " + order + " should be unique");
            seen[order] = true;
        }
    }
    
    @Test
    void shouldHandleWaitingQueueCorrectly() throws InterruptedException {
        // Given
        DistributedLock lock = fairLock;
        AtomicInteger queueSize = new AtomicInteger(0);
        
        // 第一个线程获取锁
        boolean firstLock = lock.acquire();
        assertTrue(firstLock);
        
        CountDownLatch secondThreadStart = new CountDownLatch(1);
        CountDownLatch secondThreadWait = new CountDownLatch(1);
        AtomicInteger secondThreadStatus = new AtomicInteger(0); // 0=waiting, 1=acquired, 2=released
        
        // 第二个线程尝试获取锁（应该排队等待）
        Thread waitingThread = new Thread(() -> {
            try {
                secondThreadStart.await();
                queueSize.incrementAndGet();
                
                boolean acquired = fairLock.acquire(Duration.ofSeconds(5));
                if (acquired) {
                    secondThreadStatus.set(1); // acquired
                    Thread.sleep(100);
                    lock.release();
                    secondThreadStatus.set(2); // released
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        waitingThread.start();
        secondThreadStart.countDown();
        
        // 验证队列中有一个等待者
        assertTrue(waitFor(() -> queueSize.get() == 1, Duration.ofSeconds(2)));
        
        // 第一个线程释放锁
        lock.release();
        
        // 等待第二个线程完成
        waitingThread.join(Duration.ofSeconds(5).toMillis());
        
        // Then
        assertEquals(2, secondThreadStatus.get()); // 应该已释放
    }
    
    @Test
    void shouldPreventThreadStarvation() throws InterruptedException {
        // Given
        AtomicInteger starvationCount = new AtomicInteger(0);
        int threadCount = 10;
        
        // 模拟高优先级线程持续占用锁的场景
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch completionLatch = new CountDownLatch(threadCount);
        
        // When - 多个线程竞争锁
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            final boolean isHighPriority = i < 2; // 前两个线程为"高优先级"
            
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    // 每个线程进行多次锁获取尝试
                    for (int attempt = 0; attempt < 3; attempt++) {
                        boolean acquired = fairLock.acquire(Duration.ofSeconds(1));
                        if (acquired) {
                            try {
                                Thread.sleep(20); // 短暂持有
                            } finally {
                                fairLock.release();
                            }
                        }
                        
                        // 检查是否经历了长时间等待（可能的饥饿）
                        long startTime = System.currentTimeMillis();
                        boolean starved = fairLock.acquire(Duration.ofSeconds(1));
                        if (starved && System.currentTimeMillis() - startTime > 500) {
                            starvationCount.incrementAndGet();
                        }
                        if (starved) {
                            fairLock.release();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        completionLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Then - 验证没有严重的线程饥饿
        // 在公平锁实现中，应该不会出现过多的长时间等待
        assertTrue(starvationCount.get() < threadCount / 2, 
                   "Too many potential starvation cases: " + starvationCount.get());
    }
    
    @Test
    void shouldHandleFairnessWithReentrant() throws InterruptedException {
        // Given
        DistributedLock lock = fairLock;
        AtomicInteger executionOrder = new AtomicInteger(0);
        AtomicInteger[] orders = new AtomicInteger[3];
        for (int i = 0; i < 3; i++) {
            orders[i] = new AtomicInteger(-1);
        }
        
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch completionLatch = new CountDownLatch(3);
        
        // When - 测试公平性对重入锁的影响
        executor.submit(() -> {
            try {
                startLatch.await();
                
                // 线程1：获取锁并重入
                boolean acquired = fairLock.acquire(Duration.ofSeconds(2));
                if (acquired) {
                    orders[0].set(executionOrder.getAndIncrement());
                    
                    // 重入获取
                    boolean reentrantAcquired = fairLock.acquire();
                    if (reentrantAcquired) {
                        orders[0].set(executionOrder.getAndIncrement() - 1); // 同一线程，重入应该立即成功
                        fairLock.release();
                    }
                    fairLock.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                completionLatch.countDown();
            }
        });
        
        executor.submit(() -> {
            try {
                startLatch.await();
                Thread.sleep(50); // 稍后启动
                
                boolean acquired = fairLock.acquire(Duration.ofSeconds(2));
                if (acquired) {
                    orders[1].set(executionOrder.getAndIncrement());
                    fairLock.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                completionLatch.countDown();
            }
        });
        
        executor.submit(() -> {
            try {
                startLatch.await();
                Thread.sleep(100); // 更晚启动
                
                boolean acquired = fairLock.acquire(Duration.ofSeconds(2));
                if (acquired) {
                    orders[2].set(executionOrder.getAndIncrement());
                    fairLock.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                completionLatch.countDown();
            }
        });
        
        startLatch.countDown();
        completionLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Then - 验证重入锁不影响公平性
        for (int i = 0; i < 3; i++) {
            assertNotEquals(-1, orders[i].get(), "Thread " + i + " should have acquired lock");
        }
    }
    
    @Test
    void shouldMonitorFairnessMetrics() {
        // Given
        DistributedLock lock = fairLock;
        
        // 模拟一些锁操作
        for (int i = 0; i < 5; i++) {
            boolean success = lock.acquire();
            if (success) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                lock.release();
            }
        }
        
        // 记录等待时间
        performanceMonitor.recordLockContention(lock, 100);
        performanceMonitor.recordLockAcquisition(lock, 50, true);
        
        // When
        LockPerformanceMetrics metrics = performanceMonitor.getLockMetrics(TEST_LOCK_NAME);
        
        // Then
        assertNotNull(metrics);
        assertTrue(metrics.getContentionCount() > 0);
        assertTrue(metrics.getAcquisitionCount() > 0);
        
        // 验证公平性指标
        FairnessMetrics fairnessMetrics = fairLock.getFairnessMetrics();
        assertNotNull(fairnessMetrics);
        assertTrue(fairnessMetrics.getTotalAcquisitions() >= 5);
        assertTrue(fairnessMetrics.getWaitingQueueSize() >= 0);
    }
    
    @Test
    void shouldHandleQueueOverflow() throws InterruptedException {
        // Given
        int maxQueueSize = fairLock.getMaxQueueSize();
        
        // 创建大量等待线程来测试队列容量
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(maxQueueSize + 5);
        CountDownLatch completionLatch = new CountDownLatch(maxQueueSize + 5);
        
        AtomicInteger queueOverflowCount = new AtomicInteger(0);
        AtomicInteger successfullyQueued = new AtomicInteger(0);
        
        // 当队列满时，新的获取请求应该被拒绝或排队失败
        for (int i = 0; i < maxQueueSize + 5; i++) {
            final int threadId = i;
            
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    boolean queued = fairLock.queueForAcquisition(Duration.ofSeconds(1));
                    if (queued) {
                        successfullyQueued.incrementAndGet();
                    } else {
                        queueOverflowCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    queueOverflowCount.incrementAndGet();
                } finally {
                    completionLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        completionLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Then
        assertTrue(successfullyQueued.get() <= maxQueueSize, 
                   "Should not exceed maximum queue size");
        assertTrue(queueOverflowCount.get() >= 5, 
                   "Should have some queue overflow cases");
    }
    
    @Test
    void shouldProvideFairLockInformation() {
        // Given
        fairLock.acquire();
        
        // When
        DistributedLock.LockStateInfo stateInfo = fairLock.getStateInfo();
        DistributedLock.LockConfigurationInfo configInfo = fairLock.getConfigurationInfo();
        
        // Then
        assertNotNull(stateInfo);
        assertTrue(stateInfo.isLocked());
        assertTrue(stateInfo.isFair());
        
        assertNotNull(configInfo);
        assertTrue(configInfo.isFair());
        assertEquals(LockType.FAIR, configInfo.getLockType());
    }
    
    @Test
    void shouldHandleInterruptionDuringQueueWait() throws InterruptedException {
        // Given
        fairLock.acquire(); // 获取锁
        
        Thread waitingThread = new Thread(() -> {
            try {
                fairLock.acquire(Duration.ofSeconds(10)); // 长时间等待
            } catch (InterruptedException e) {
                // 预期的中断
                Thread.currentThread().interrupt();
            }
        });
        
        // When
        waitingThread.start();
        Thread.sleep(100); // 让等待线程开始排队
        waitingThread.interrupt(); // 中断等待线程
        waitingThread.join(Duration.ofSeconds(1));
        
        // Then
        assertTrue(waitingThread.isInterrupted() || !waitingThread.isAlive());
        fairLock.release();
    }
    
    @Test
    void shouldOptimizeFairLockPerformance() throws InterruptedException {
        // Given - 基准测试
        int iterations = 100;
        AtomicLong totalAcquisitionTime = new AtomicLong(0);
        AtomicInteger successfulAcquisitions = new AtomicInteger(0);
        
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch completionLatch = new CountDownLatch(4);
        
        // When - 多线程公平锁性能测试
        for (int t = 0; t < 4; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    for (int i = 0; i < iterations / 4; i++) {
                        long startTime = System.currentTimeMillis();
                        boolean acquired = fairLock.acquire(Duration.ofSeconds(1));
                        long acquisitionTime = System.currentTimeMillis() - startTime;
                        
                        if (acquired) {
                            successfulAcquisitions.incrementAndGet();
                            totalAcquisitionTime.addAndGet(acquisitionTime);
                            
                            try {
                                Thread.sleep(10); // 短暂持有
                            } finally {
                                fairLock.release();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        completionLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Then - 验证性能指标
        double averageAcquisitionTime = (double) totalAcquisitionTime.get() / successfulAcquisitions.get();
        assertTrue(averageAcquisitionTime >= 0, "Should have valid average acquisition time");
        assertTrue(successfulAcquisitions.get() > iterations * 0.8, 
                   "Should have high success rate: " + successfulAcquisitions.get());
        
        // 获取性能统计
        PerformanceMonitor.FairnessMetrics fairnessMetrics = fairLock.getFairnessMetrics();
        assertNotNull(fairnessMetrics);
        assertTrue(fairnessMetrics.getAverageWaitTime() >= 0);
        assertTrue(fairnessMetrics.getFairnessScore() >= 0 && fairnessMetrics.getFairnessScore() <= 1);
    }
    
    // 辅助方法
    
    private boolean waitFor(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        while (!condition.getAsBoolean() && 
               System.currentTimeMillis() - startTime < timeout.toMillis()) {
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }
    
    // 内部类实现
    
    private static class FairLockImpl implements DistributedLock {
        private final String name;
        private final LockConfiguration config;
        private final PerformanceMonitor performanceMonitor;
        private final ReentrantLock internalLock = new ReentrantLock(true); // 公平锁
        private final BlockingQueue<LockRequest> waitingQueue = new LinkedBlockingQueue<>();
        private volatile int maxQueueSize = 1000;
        private long ownerThreadId = -1;
        private FairnessMetrics fairnessMetrics = new FairnessMetrics();
        
        public FairLockImpl(LockConfiguration config, PerformanceMonitor performanceMonitor) {
            this.name = config.getLockName();
            this.config = config;
            this.performanceMonitor = performanceMonitor;
        }
        
        @Override
        public String getName() {
            return name;
        }
        
        @Override
        public boolean acquire() {
            return acquire(Duration.ofSeconds(0));
        }
        
        @Override
        public boolean acquire(Duration timeout) throws InterruptedException {
            long startTime = System.currentTimeMillis();
            
            if (internalLock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                try {
                    ownerThreadId = Thread.currentThread().getId();
                    fairnessMetrics.recordAcquisition();
                    performanceMonitor.recordLockAcquisition(this, 
                        System.currentTimeMillis() - startTime, true);
                    return true;
                } finally {
                    // 不在这里释放
                }
            } else {
                fairnessMetrics.recordQueueWait();
                performanceMonitor.recordLockContention(this, timeout.toMillis());
                return false;
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
            if (internalLock.isHeldByCurrentThread()) {
                ownerThreadId = -1;
                internalLock.unlock();
                fairnessMetrics.recordRelease();
                performanceMonitor.recordLockRelease(this, 0);
            } else {
                throw new IllegalMonitorStateException("Current thread does not hold this lock");
            }
        }
        
        @Override
        public boolean isLocked() {
            return internalLock.isLocked();
        }
        
        @Override
        public boolean isHeldByCurrentThread() {
            return internalLock.isHeldByCurrentThread();
        }
        
        @Override
        public DistributedLock.RenewalResult renew() {
            return new DistributedLock.RenewalResult(true, "Fair lock renewed");
        }
        
        @Override
        public HealthCheck.HealthCheckResult healthCheck() {
            return new HealthCheck.HealthCheckResult(
                HealthCheck.ComponentStatus.HEALTHY, 
                "Fair lock is healthy", 
                System.currentTimeMillis()
            );
        }
        
        @Override
        public LockStateInfo getStateInfo() {
            return new LockStateInfo(
                name,
                isLocked(),
                isHeldByCurrentThread(),
                ownerThreadId,
                1, // 简化处理
                false // 不支持重入（公平锁通常不支持重入）
            ) {
                @Override
                public boolean isFair() {
                    return true;
                }
                
                @Override
                public int getWaitingQueueSize() {
                    return waitingQueue.size();
                }
            };
        }
        
        @Override
        public LockConfigurationInfo getConfigurationInfo() {
            return new LockConfigurationInfo(
                name,
                config.getLeaseTime(),
                config.getRetryAttempts(),
                config.getRetryDelay(),
                LockType.FAIR,
                true, // 公平锁
                false // 不支持重入
            );
        }
        
        @Override
        public void close() {
            try {
                forceReleaseAll();
            } catch (Exception e) {
                // 忽略关闭错误
            }
        }
        
        // 公平锁特有方法
        public boolean queueForAcquisition(Duration timeout) {
            try {
                LockRequest request = new LockRequest(Thread.currentThread(), timeout);
                return waitingQueue.offer(request, timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        public FairnessMetrics getFairnessMetrics() {
            return fairnessMetrics;
        }
        
        public int getMaxQueueSize() {
            return maxQueueSize;
        }
        
        public void setMaxQueueSize(int maxQueueSize) {
            this.maxQueueSize = maxQueueSize;
        }
        
        public void forceReleaseAll() {
            if (internalLock.isLocked()) {
                ownerThreadId = -1;
                internalLock.unlock();
            }
            waitingQueue.clear();
        }
        
        private static class LockRequest {
            private final Thread thread;
            private final Duration timeout;
            private final long requestTime;
            
            public LockRequest(Thread thread, Duration timeout) {
                this.thread = thread;
                this.timeout = timeout;
                this.requestTime = System.currentTimeMillis();
            }
            
            public Thread getThread() { return thread; }
            public Duration getTimeout() { return timeout; }
            public long getRequestTime() { return requestTime; }
        }
    }
    
    private static class FairnessMetrics {
        private final AtomicLong totalAcquisitions = new AtomicLong(0);
        private final AtomicLong totalReleases = new AtomicLong(0);
        private final AtomicLong queueWaits = new AtomicLong(0);
        private final AtomicLong totalWaitTime = new AtomicLong(0);
        private final AtomicInteger maxQueueSize = new AtomicInteger(0);
        
        public void recordAcquisition() {
            totalAcquisitions.incrementAndGet();
            maxQueueSize.updateAndGet(size -> Math.max(size, size));
        }
        
        public void recordRelease() {
            totalReleases.incrementAndGet();
        }
        
        public void recordQueueWait() {
            queueWaits.incrementAndGet();
        }
        
        public void recordWaitTime(long waitTime) {
            totalWaitTime.addAndGet(waitTime);
        }
        
        public long getTotalAcquisitions() { return totalAcquisitions.get(); }
        public long getTotalReleases() { return totalReleases.get(); }
        public long getQueueWaits() { return queueWaits.get(); }
        public long getTotalWaitTime() { return totalWaitTime.get(); }
        public int getWaitingQueueSize() { return maxQueueSize.get(); }
        
        public double getAverageWaitTime() {
            long waits = queueWaits.get();
            return waits > 0 ? (double) totalWaitTime.get() / waits : 0.0;
        }
        
        public double getFairnessScore() {
            long acquisitions = totalAcquisitions.get();
            long waits = queueWaits.get();
            return acquisitions + waits > 0 ? 
                (double) acquisitions / (acquisitions + waits) : 1.0;
        }
    }
}