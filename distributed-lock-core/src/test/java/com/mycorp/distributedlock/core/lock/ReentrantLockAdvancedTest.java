package com.mycorp.distributedlock.core.lock;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.monitoring.PerformanceMonitor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 可重入锁高级特性测试
 */
@ExtendWith(MockitoExtension.class)
class ReentrantLockAdvancedTest {
    
    private static final String TEST_LOCK_NAME = "reentrant-test-lock";
    
    private ReentrantLockImpl reentrantLock;
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
            .build();
            
        reentrantLock = new ReentrantLockImpl(config, performanceMonitor);
    }
    
    @AfterEach
    void tearDown() {
        if (reentrantLock != null) {
            try {
                reentrantLock.forceRelease();
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
    void shouldAllowReentrantAcquisition() {
        // Given
        DistributedLock lock = reentrantLock;
        
        // When
        boolean firstLock = lock.acquire();
        boolean secondLock = lock.acquire();
        boolean thirdLock = lock.acquire();
        
        // Then
        assertTrue(firstLock);
        assertTrue(secondLock);
        assertTrue(thirdLock);
        assertEquals(3, reentrantLock.getReentrantCount());
    }
    
    @Test
    void shouldMaintainReentrantCountOnRelease() {
        // Given
        DistributedLock lock = reentrantLock;
        lock.acquire();
        lock.acquire();
        lock.acquire();
        
        // When
        lock.release(); // 第一次释放
        int countAfterFirstRelease = reentrantLock.getReentrantCount();
        
        lock.release(); // 第二次释放
        int countAfterSecondRelease = reentrantLock.getReentrantCount();
        
        lock.release(); // 最终释放
        int countAfterFinalRelease = reentrantLock.getReentrantCount();
        
        // Then
        assertEquals(2, countAfterFirstRelease);
        assertEquals(1, countAfterSecondRelease);
        assertEquals(0, countAfterFinalRelease);
        assertFalse(lock.isLocked());
    }
    
    @Test
    void shouldTrackThreadOwnerCorrectly() {
        // Given
        DistributedLock lock = reentrantLock;
        long currentThreadId = Thread.currentThread().getId();
        
        // When
        lock.acquire();
        lock.acquire();
        
        // Then
        assertEquals(currentThreadId, reentrantLock.getOwnerThreadId());
        assertTrue(reentrantLock.isHeldByCurrentThread());
        
        lock.release();
        assertEquals(currentThreadId, reentrantLock.getOwnerThreadId());
        
        lock.release();
        assertEquals(-1, reentrantLock.getOwnerThreadId());
    }
    
    @Test
    void shouldPreventOtherThreadFromReleasing() {
        // Given
        DistributedLock lock = reentrantLock;
        lock.acquire();
        lock.acquire();
        
        AtomicInteger releaseCount = new AtomicInteger(0);
        
        // When - 另一个线程尝试释放
        boolean releasedByOtherThread = ThreadContextSimulator.executeInOtherThread(() -> {
            try {
                lock.release();
                releaseCount.incrementAndGet();
                return true;
            } catch (IllegalMonitorStateException e) {
                return false;
            }
        });
        
        // Then
        assertFalse(releasedByOtherThread);
        assertEquals(0, releaseCount.get());
        assertEquals(2, reentrantLock.getReentrantCount()); // 计数未改变
    }
    
    @Test
    void shouldHandleNestedReentrantCalls() {
        // Given
        AtomicInteger executionCount = new AtomicInteger(0);
        
        // When
        executeNestedOperation(() -> {
            executionCount.incrementAndGet();
            return null;
        }, 0);
        
        // Then
        assertEquals(3, executionCount.get()); // 3层嵌套
        assertEquals(0, reentrantLock.getReentrantCount());
    }
    
    @Test
    void shouldHandleConcurrentReentrantAccess() throws InterruptedException {
        // Given
        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        AtomicInteger totalOperations = new AtomicInteger(0);
        AtomicInteger maxConcurrentCount = new AtomicInteger(0);
        
        // When
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // 等待所有线程准备就绪
                    
                    // 每个线程进行3次重入获取
                    boolean lock1 = reentrantLock.acquire();
                    if (lock1) {
                        maxConcurrentCount.updateAndGet(c -> Math.max(c, reentrantLock.getReentrantCount()));
                        totalOperations.incrementAndGet();
                        
                        boolean lock2 = reentrantLock.acquire();
                        if (lock2) {
                            maxConcurrentCount.updateAndGet(c -> Math.max(c, reentrantLock.getReentrantCount()));
                            totalOperations.incrementAndGet();
                            
                            boolean lock3 = reentrantLock.acquire();
                            if (lock3) {
                                maxConcurrentCount.updateAndGet(c -> Math.max(c, reentrantLock.getReentrantCount()));
                                totalOperations.incrementAndGet();
                                reentrantLock.release();
                                reentrantLock.release();
                                reentrantLock.release();
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
        
        startLatch.countDown(); // 启动所有线程
        completionLatch.await(10, TimeUnit.SECONDS); // 等待完成
        executor.shutdown();
        
        // Then
        assertTrue(maxConcurrentCount.get() <= threadCount * 3); // 不应该超过理论最大值
        assertEquals(threadCount * 3, totalOperations.get()); // 所有操作都应该成功
    }
    
    @Test
    void shouldMonitorReentrantOperations() {
        // Given
        performanceMonitor.recordLockAcquisition(reentrantLock, 50, true);
        performanceMonitor.recordLockAcquisition(reentrantLock, 30, true);
        performanceMonitor.recordLockRelease(reentrantLock, 100);
        
        // When
        LockPerformanceMetrics metrics = performanceMonitor.getLockMetrics(TEST_LOCK_NAME);
        
        // Then
        assertNotNull(metrics);
        assertTrue(metrics.getAcquisitionCount() >= 2);
        assertTrue(metrics.getReleaseCount() >= 1);
    }
    
    @Test
    void shouldHandleReentrantLockUpgrade() {
        // Given - 模拟读写锁升级场景
        DistributedLock lock = reentrantLock;
        
        // 当获取锁时模拟不同的模式
        reentrantLock.setLockMode(LockMode.READ);
        
        // When - 尝试"升级"
        boolean readLock1 = lock.acquire();
        boolean readLock2 = lock.acquire();
        
        reentrantLock.setLockMode(LockMode.WRITE); // 模拟升级模式
        boolean writeLock = lock.acquire();
        
        // Then
        assertTrue(readLock1);
        assertTrue(readLock2);
        assertTrue(writeLock);
        assertEquals(3, reentrantLock.getReentrantCount());
    }
    
    @Test
    void shouldHandleReentrantDeadlockPrevention() throws InterruptedException {
        // Given - 模拟可能导致死锁的场景
        ReentrantLockImpl lock1 = new ReentrantLockImpl(
            LockConfiguration.builder().withLockName("lock-1").build(),
            performanceMonitor
        );
        ReentrantLockImpl lock2 = new ReentrantLockImpl(
            LockConfiguration.builder().withLockName("lock-2").build(),
            performanceMonitor
        );
        
        AtomicInteger deadlockPreventionCount = new AtomicInteger(0);
        
        // 当死锁预防机制启用时，模拟避免死锁
        DeadlockDetector detector = new DeadlockDetector();
        detector.setEnableDeadlockPrevention(true);
        reentrantLock.setDeadlockDetector(detector);
        
        // When - 两个线程尝试获取锁的相反顺序
        CountDownLatch threadStart = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        
        Future<?> thread1 = executor.submit(() -> {
            try {
                threadStart.await();
                reentrantLock.acquire(); // 获取第一个锁
                Thread.sleep(100); // 模拟工作
                
                // 检查死锁风险
                if (detector.detectDeadlockRisk(lock1, lock2)) {
                    deadlockPreventionCount.incrementAndGet();
                    // 释放当前锁以避免死锁
                    reentrantLock.release();
                } else {
                    lock2.acquire();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        Future<?> thread2 = executor.submit(() -> {
            try {
                threadStart.await();
                Thread.sleep(50); // 确保第二个线程稍后启动
                lock2.acquire(); // 获取第二个锁
                Thread.sleep(100);
                
                if (detector.detectDeadlockRisk(lock2, lock1)) {
                    deadlockPreventionCount.incrementAndGet();
                    lock2.release();
                } else {
                    reentrantLock.acquire();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        threadStart.countDown();
        thread1.get(5, TimeUnit.SECONDS);
        thread2.get(5, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Then - 死锁预防应该被触发
        assertTrue(deadlockPreventionCount.get() >= 1);
    }
    
    @Test
    void shouldProvideReentrantLockInformation() {
        // Given
        reentrantLock.acquire();
        reentrantLock.acquire();
        
        // When
        DistributedLock.LockStateInfo stateInfo = reentrantLock.getStateInfo();
        
        // Then
        assertNotNull(stateInfo);
        assertTrue(stateInfo.isLocked());
        assertTrue(stateInfo.isReentrant());
        assertEquals(2, stateInfo.getReentrantCount());
        assertEquals(Thread.currentThread().getId(), stateInfo.getOwnerThreadId());
    }
    
    @Test
    void shouldHandleTimeoutWithReentrant() {
        // Given
        DistributedLock lock = reentrantLock;
        
        // When
        boolean quickLock = lock.tryLock(Duration.ofSeconds(1));
        boolean timeoutLock = lock.tryLock(Duration.ofMillis(100));
        
        // Then
        assertTrue(quickLock);
        assertTrue(timeoutLock); // 对于同一线程，应该立即成功
        assertEquals(2, reentrantLock.getReentrantCount());
    }
    
    @Test
    void shouldHandleInterruptDuringReentrantOperation() throws InterruptedException {
        // Given
        DistributedLock lock = reentrantLock;
        lock.acquire();
        
        Thread interruptThread = new Thread(() -> {
            try {
                Thread.sleep(50);
                Thread.currentThread().interrupt(); // 中断自己
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        // When
        interruptThread.start();
        boolean interruptedLock = lock.tryLock(Duration.ofSeconds(2));
        
        // Then
        assertTrue(interruptedLock);
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.currentThread().interrupt(); // 恢复中断状态
    }
    
    // 辅助方法
    
    private void executeNestedOperation(Runnable operation, int depth) {
        if (depth < 3) {
            reentrantLock.acquire();
            try {
                executeNestedOperation(operation, depth + 1);
            } finally {
                reentrantLock.release();
            }
        } else {
            operation.run();
        }
    }
    
    private Object executeNestedOperation(Callable<Object> operation, int depth) {
        if (depth < 3) {
            reentrantLock.acquire();
            try {
                return executeNestedOperation(operation, depth + 1);
            } finally {
                reentrantLock.release();
            }
        } else {
            return operation.call();
        }
    }
    
    // 内部类模拟
    
    private static class ThreadContextSimulator {
        public static boolean executeInOtherThread(BooleanSupplier operation) {
            try {
                ExecutorService executor = Executors.newSingleThreadExecutor();
                Future<Boolean> future = executor.submit(() -> operation.getAsBoolean());
                Boolean result = future.get(1, TimeUnit.SECONDS);
                executor.shutdown();
                return result;
            } catch (Exception e) {
                return false;
            }
        }
    }
    
    private enum LockMode {
        READ, WRITE
    }
    
    private static class ReentrantLockImpl implements DistributedLock {
        private final String name;
        private final LockConfiguration config;
        private final PerformanceMonitor performanceMonitor;
        private final ReentrantLock internalLock = new ReentrantLock();
        private volatile long ownerThreadId = -1;
        private volatile int reentrantCount = 0;
        private LockMode lockMode = LockMode.WRITE;
        private DeadlockDetector deadlockDetector;
        
        public ReentrantLockImpl(LockConfiguration config, PerformanceMonitor performanceMonitor) {
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
            internalLock.lock();
            try {
                ownerThreadId = Thread.currentThread().getId();
                reentrantCount++;
                performanceMonitor.recordLockAcquisition(this, 0, true);
                return true;
            } finally {
                // 不在这里释放，释放时手动处理
            }
        }
        
        @Override
        public boolean acquire(Duration timeout) throws InterruptedException {
            long startTime = System.currentTimeMillis();
            if (internalLock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                try {
                    ownerThreadId = Thread.currentThread().getId();
                    reentrantCount++;
                    performanceMonitor.recordLockAcquisition(this, 
                        System.currentTimeMillis() - startTime, true);
                    return true;
                } finally {
                    // 不在这里释放，释放时手动处理
                }
            }
            return false;
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
                reentrantCount--;
                if (reentrantCount == 0) {
                    ownerThreadId = -1;
                    internalLock.unlock();
                }
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
            return new DistributedLock.RenewalResult(true, "Reentrant lock renewed");
        }
        
        @Override
        public HealthCheck.HealthCheckResult healthCheck() {
            return new HealthCheck.HealthCheckResult(
                HealthCheck.ComponentStatus.HEALTHY, 
                "Reentrant lock is healthy", 
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
                reentrantCount,
                reentrantCount > 0
            );
        }
        
        @Override
        public LockConfigurationInfo getConfigurationInfo() {
            return new LockConfigurationInfo(
                name,
                config.getLeaseTime(),
                config.getRetryAttempts(),
                config.getRetryDelay(),
                LockType.REENTRANT,
                false, // 不是公平锁
                true   // 支持重入
            );
        }
        
        @Override
        public void close() {
            try {
                forceRelease();
            } catch (Exception e) {
                // 忽略关闭错误
            }
        }
        
        // Getter方法
        public int getReentrantCount() { return reentrantCount; }
        public long getOwnerThreadId() { return ownerThreadId; }
        public void setLockMode(LockMode mode) { this.lockMode = mode; }
        public void setDeadlockDetector(DeadlockDetector detector) { this.deadlockDetector = detector; }
        
        // 内部方法
        public void forceRelease() {
            if (internalLock.isLocked()) {
                reentrantCount = 0;
                ownerThreadId = -1;
                internalLock.unlock();
            }
        }
    }
    
    private static class DeadlockDetector {
        private volatile boolean enableDeadlockPrevention = false;
        
        public void setEnableDeadlockPrevention(boolean enable) {
            this.enableDeadlockPrevention = enable;
        }
        
        public boolean detectDeadlockRisk(ReentrantLockImpl lock1, ReentrantLockImpl lock2) {
            return enableDeadlockPrevention && 
                   lock1.isLocked() && lock2.isLocked() &&
                   lock1.getOwnerThreadId() != lock2.getOwnerThreadId();
        }
    }
}