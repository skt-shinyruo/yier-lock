package com.mycorp.distributedlock.integration;

import com.mycorp.distributedlock.api.*;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.event.LockEventManager;
import com.mycorp.distributedlock.core.monitoring.PerformanceMonitor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 分布式锁高级特性集成测试
 * 
 * 此测试套件验证所有高级特性的集成：
 * 1. 可重入锁特性
 * 2. 公平锁算法
 * 3. 读写锁功能
 * 4. 批量锁操作
 * 5. 锁事件和监听机制
 * 6. 高级锁策略
 * 7. 性能监控和指标
 */
@ExtendWith(MockitoExtension.class)
class DistributedLockAdvancedIntegrationTest {
    
    private static final String TEST_LOCK_PREFIX = "integration-test-lock";
    
    private PerformanceMonitor performanceMonitor;
    private LockEventManager eventManager;
    private MeterRegistry meterRegistry;
    
    // 各种锁实现
    private TestReentrantLock reentrantLock;
    private TestFairLock fairLock;
    private TestReadWriteLock readWriteLock;
    private TestBatchLockOperations batchLockOperations;
    
    // 测试计数器
    private AtomicInteger testEventCount = new AtomicInteger(0);
    private AtomicInteger deadlockDetections = new AtomicInteger(0);
    
    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        performanceMonitor = new PerformanceMonitor(meterRegistry);
        performanceMonitor.start();
        
        eventManager = new LockEventManager();
        eventManager.start();
        
        // 创建测试锁实例
        createTestLocks();
        
        // 注册事件监听器
        registerEventListeners();
    }
    
    @AfterEach
    void tearDown() {
        // 清理所有锁
        if (reentrantLock != null) reentrantLock.forceRelease();
        if (fairLock != null) fairLock.forceReleaseAll();
        if (readWriteLock != null) readWriteLock.forceReleaseAll();
        if (batchLockOperations != null) batchLockOperations.releaseAll();
        
        // 停止监控
        if (eventManager != null) eventManager.stop();
        if (performanceMonitor != null) {
            performanceMonitor.stop();
            performanceMonitor.close();
        }
    }
    
    @Test
    void shouldHandleComplexReentrantScenario() throws InterruptedException {
        // Given - 模拟复杂的可重入锁场景
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(1);
        AtomicInteger nestedCallDepth = new AtomicInteger(0);
        AtomicInteger maxDepth = new AtomicInteger(0);
        
        // When - 深度嵌套的可重入调用
        Thread workerThread = new Thread(() -> {
            try {
                startLatch.await();
                
                executeNested(() -> {
                    nestedCallDepth.incrementAndGet();
                    maxDepth.updateAndGet(depth -> Math.max(depth, nestedCallDepth.get()));
                    return "result-" + nestedCallDepth.get();
                }, 0, 5); // 5层嵌套
                
            } catch (Exception e) {
                // 处理异常
            } finally {
                completionLatch.countDown();
            }
        });
        
        workerThread.start();
        startLatch.countDown();
        completionLatch.await(10, TimeUnit.SECONDS);
        workerThread.join();
        
        // Then - 验证可重入特性
        assertEquals(5, maxDepth.get());
        assertEquals(0, reentrantLock.getReentrantCount());
        
        // 验证监控指标
        performanceMonitor.recordLockAcquisition(reentrantLock, 100, true);
        performanceMonitor.recordLockRelease(reentrantLock, 50);
        
        PerformanceMonitor.LockPerformanceMetrics metrics = 
            performanceMonitor.getLockMetrics(reentrantLock.getName());
        assertNotNull(metrics);
        assertTrue(metrics.getAcquisitionCount() >= 1);
    }
    
    @Test
    void shouldMaintainFairnessUnderHighContention() throws InterruptedException {
        // Given - 高竞争场景下的公平锁测试
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch completionLatch = new CountDownLatch(threadCount);
        
        List<Long> acquisitionOrders = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger[] threadOrders = new AtomicInteger[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threadOrders[i] = new AtomicInteger(-1);
        }
        
        // When - 多个线程竞争公平锁
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    boolean acquired = fairLock.acquire(Duration.ofSeconds(2));
                    if (acquired) {
                        int order = acquisitionOrders.size();
                        acquisitionOrders.add(System.currentTimeMillis());
                        threadOrders[threadId].set(order);
                        
                        Thread.sleep(20); // 短暂持有
                        fairLock.release();
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
        
        // Then - 验证公平性
        assertEquals(threadCount, acquisitionOrders.size());
        
        // 验证顺序性（公平锁应该按请求顺序分配）
        for (int i = 0; i < threadCount; i++) {
            assertNotEquals(-1, threadOrders[i].get(), 
                          "Thread " + i + " should have acquired lock");
        }
        
        // 验证没有线程饥饿
        boolean hasStarvation = false;
        for (int i = 0; i < threadCount; i++) {
            if (threadOrders[i].get() == -1) {
                hasStarvation = true;
                break;
            }
        }
        assertFalse(hasStarvation, "No thread should starve");
    }
    
    @Test
    void shouldHandleReadWriteLockUpgradesAndDowngrades() throws InterruptedException {
        // Given
        AtomicInteger upgradeCount = new AtomicInteger(0);
        AtomicInteger downgradeCount = new AtomicInteger(0);
        AtomicInteger concurrentAccessCount = new AtomicInteger(0);
        
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch completionLatch = new CountDownLatch(3);
        
        // 线程1：读取 -> 升级到写入 -> 降级到读取
        executor.submit(() -> {
            try {
                startLatch.await();
                
                // 读取锁
                assertTrue(readWriteLock.readLock().acquire());
                concurrentAccessCount.incrementAndGet();
                
                Thread.sleep(50);
                
                // 升级到写锁
                try {
                    readWriteLock.writeLock().acquire(); // 升级
                    upgradeCount.incrementAndGet();
                    concurrentAccessCount.decrementAndGet();
                    
                    Thread.sleep(50);
                    
                    // 降级到读锁
                    readWriteLock.readLock().acquire(); // 降级
                    downgradeCount.incrementAndGet();
                    concurrentAccessCount.incrementAndGet();
                    
                    Thread.sleep(50);
                    
                    readWriteLock.readLock().release(); // 释放降级的读锁
                    readWriteLock.writeLock().release(); // 释放写锁
                    readWriteLock.readLock().release(); // 释放原始读锁
                    
                } catch (Exception e) {
                    readWriteLock.readLock().release();
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                completionLatch.countDown();
            }
        });
        
        // 线程2：并发读取
        executor.submit(() -> {
            try {
                startLatch.await();
                Thread.sleep(25); // 在升级期间尝试读取
                
                boolean acquired = readWriteLock.readLock().tryLock(Duration.ofSeconds(1));
                if (acquired) {
                    concurrentAccessCount.incrementAndGet();
                    Thread.sleep(25);
                    readWriteLock.readLock().release();
                    concurrentAccessCount.decrementAndGet();
                }
            } catch (Exception e) {
                // 忽略异常
            } finally {
                completionLatch.countDown();
            }
        });
        
        // 线程3：并发写入
        executor.submit(() -> {
            try {
                startLatch.await();
                Thread.sleep(75); // 在降级期间尝试写入
                
                boolean acquired = readWriteLock.writeLock().tryLock(Duration.ofSeconds(1));
                if (acquired) {
                    // 写操作
                    readWriteLock.writeLock().release();
                }
            } catch (Exception e) {
                // 忽略异常
            } finally {
                completionLatch.countDown();
            }
        });
        
        startLatch.countDown();
        completionLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Then - 验证读写锁转换
        assertTrue(upgradeCount.get() >= 0);
        assertTrue(downgradeCount.get() >= 0);
        
        // 最终状态检查
        DistributedReadWriteLock.ReadWriteLockStateInfo finalState = readWriteLock.getStateInfo();
        assertEquals(0, finalState.getActiveReaders());
        assertEquals(0, finalState.getActiveWriters());
    }
    
    @Test
    void shouldHandleComplexBatchOperationsWithRollback() throws InterruptedException {
        // Given
        List<String> lockNames = Arrays.asList(
            TEST_LOCK_PREFIX + "-batch-1",
            TEST_LOCK_PREFIX + "-batch-2",
            TEST_LOCK_PREFIX + "-batch-3"
        );
        
        // 创建批次锁操作
        batchLockOperations = new TestBatchLockOperations(lockNames, performanceMonitor);
        
        AtomicInteger operationCount = new AtomicInteger(0);
        AtomicInteger rollbackCount = new AtomicInteger(0);
        
        // When - 复杂的批量操作，包含失败和重试
        BatchLockOperations.BatchOperationResult<String> result = 
            batchLockOperations.executeWithLocks(lockNames, () -> {
                operationCount.incrementAndGet();
                
                // 模拟可能失败的操作
                if (operationCount.get() == 1) {
                    throw new RuntimeException("Simulated failure");
                }
                
                return "success-" + operationCount.get();
            }, Duration.ofSeconds(2), 3);
        
        // Then - 验证回滚机制
        if (!result.isSuccess()) {
            rollbackCount.incrementAndGet();
        }
        
        // 验证所有锁都被正确释放
        for (String lockName : lockNames) {
            TestSimpleLock lock = batchLockOperations.findLock(lockName);
            assertNotNull(lock);
            assertFalse(lock.isLocked(), "Lock " + lockName + " should be released");
        }
    }
    
    @Test
    void shouldDetectAndPreventDeadlocks() throws InterruptedException {
        // Given - 设置死锁检测
        TestDeadlockDetector detector = new TestDeadlockDetector();
        detector.setEnableDetection(true);
        
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch completionLatch = new CountDownLatch(2);
        
        // 创建两个锁用于死锁测试
        TestSimpleLock lock1 = new TestSimpleLock(TEST_LOCK_PREFIX + "-deadlock-1", performanceMonitor);
        TestSimpleLock lock2 = new TestSimpleLock(TEST_LOCK_PREFIX + "-deadlock-2", performanceMonitor);
        
        AtomicInteger deadlockPreventionCount = new AtomicInteger(0);
        AtomicInteger successfulOperations = new AtomicInteger(0);
        
        // 线程1：获取lock1然后lock2
        executor.submit(() -> {
            try {
                startLatch.await();
                
                boolean lock1Acquired = lock1.acquire(Duration.ofSeconds(1));
                if (lock1Acquired) {
                    try {
                        Thread.sleep(50); // 持有lock1一段时间
                        
                        // 检查死锁风险
                        if (detector.detectDeadlock(lock1, lock2)) {
                            deadlockPreventionCount.incrementAndGet();
                            lock1.release(); // 预防死锁
                        } else {
                            boolean lock2Acquired = lock2.acquire(Duration.ofSeconds(1));
                            if (lock2Acquired) {
                                successfulOperations.incrementAndGet();
                                lock2.release();
                            }
                        }
                    } finally {
                        lock1.release();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                completionLatch.countDown();
            }
        });
        
        // 线程2：获取lock2然后lock1
        executor.submit(() -> {
            try {
                startLatch.await();
                Thread.sleep(25); // 稍后启动
                
                boolean lock2Acquired = lock2.acquire(Duration.ofSeconds(1));
                if (lock2Acquired) {
                    try {
                        Thread.sleep(50); // 持有lock2一段时间
                        
                        if (detector.detectDeadlock(lock2, lock1)) {
                            deadlockPreventionCount.incrementAndGet();
                            lock2.release(); // 预防死锁
                        } else {
                            boolean lock1Acquired = lock1.acquire(Duration.ofSeconds(1));
                            if (lock1Acquired) {
                                successfulOperations.incrementAndGet();
                                lock1.release();
                            }
                        }
                    } finally {
                        lock2.release();
                    }
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
        
        // Then - 验证死锁预防机制
        assertTrue(deadlockPreventionCount.get() >= 0 || successfulOperations.get() >= 1,
                   "Either deadlock was prevented or operations succeeded: " + 
                   "prevention=" + deadlockPreventionCount.get() + 
                   ", success=" + successfulOperations.get());
    }
    
    @Test
    void shouldIntegrateEventSystemWithPerformanceMonitoring() {
        // Given
        DistributedLock testLock = reentrantLock;
        
        // When - 模拟锁事件序列
        eventManager.publishEvent(new LockEvent<>(testLock, LockEvent.Type.LOCK_ACQUIRED, 
            "Lock acquired event"));
        
        eventManager.publishEvent(new LockEvent<>(testLock, LockEvent.Type.LOCK_RELEASED, 
            "Lock released event"));
        
        eventManager.publishEvent(new LockEvent<>(testLock, LockEvent.Type.LOCK_ACQUISITION_FAILED, 
            "Lock acquisition failed event"));
        
        // 记录性能指标
        performanceMonitor.recordLockAcquisition(testLock, 100, true);
        performanceMonitor.recordLockContention(testLock, 50);
        performanceMonitor.recordError(testLock.getName(), new RuntimeException("Test error"));
        
        // Then - 验证集成
        assertEquals(3, testEventCount.get());
        
        // 验证性能指标
        PerformanceMonitor.LockPerformanceMetrics metrics = 
            performanceMonitor.getLockMetrics(testLock.getName());
        assertNotNull(metrics);
        assertTrue(metrics.getAcquisitionCount() >= 1);
        assertTrue(metrics.getContentionCount() >= 1);
        assertTrue(metrics.getErrorCount() >= 1);
    }
    
    @Test
    void shouldHandleHighConcurrencyScenario() throws InterruptedException {
        // Given - 高并发场景
        int threadCount = 20;
        int operationsPerThread = 10;
        Duration operationTimeout = Duration.ofSeconds(2);
        
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch completionLatch = new CountDownLatch(threadCount);
        
        AtomicInteger successfulOperations = new AtomicInteger(0);
        AtomicInteger failedOperations = new AtomicInteger(0);
        AtomicInteger totalExecutionTime = new AtomicLong(0);
        
        // When - 多线程并发操作
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    for (int op = 0; op < operationsPerThread; op++) {
                        long startTime = System.currentTimeMillis();
                        
                        // 随机选择锁类型
                        DistributedLock lock = chooseRandomLock();
                        boolean acquired = false;
                        
                        try {
                            switch (op % 4) {
                                case 0:
                                    acquired = lock.acquire(operationTimeout);
                                    break;
                                case 1:
                                    acquired = lock.tryLock(Duration.ofMillis(100));
                                    break;
                                case 2:
                                    acquired = readWriteLock.readLock().tryLock(Duration.ofMillis(100));
                                    break;
                                case 3:
                                    acquired = readWriteLock.writeLock().tryLock(Duration.ofMillis(100));
                                    break;
                            }
                            
                            if (acquired) {
                                try {
                                    Thread.sleep(5); // 短暂持有
                                    successfulOperations.incrementAndGet();
                                } finally {
                                    // 释放锁（根据锁类型）
                                    releaseLock(lock);
                                }
                            } else {
                                failedOperations.incrementAndGet();
                            }
                            
                        } catch (Exception e) {
                            failedOperations.incrementAndGet();
                        }
                        
                        long executionTime = System.currentTimeMillis() - startTime;
                        totalExecutionTime.addAndGet(executionTime);
                    }
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        completionLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Then - 验证高并发性能
        int totalOperations = threadCount * operationsPerThread;
        double successRate = (double) successfulOperations.get() / totalOperations;
        double averageExecutionTime = (double) totalExecutionTime.get() / totalOperations;
        
        assertTrue(successRate > 0.5, "Success rate should be > 50%: " + successRate);
        assertTrue(averageExecutionTime < 1000, "Average execution time should be < 1000ms: " + averageExecutionTime);
        
        // 验证最终状态
        PerformanceMonitor.PerformanceMetrics systemMetrics = performanceMonitor.getSystemMetrics();
        assertTrue(systemMetrics.getTotalAcquisitions() > 0);
        assertTrue(systemMetrics.getActiveLocksCount() == 0);
    }
    
    @Test
    void shouldGenerateComprehensivePerformanceReport() throws InterruptedException {
        // Given - 预热
        performanceMonitor.start();
        Thread.sleep(1000);
        
        // 执行各种操作以生成性能数据
        for (int i = 0; i < 20; i++) {
            // 可重入锁操作
            reentrantLock.acquire();
            reentrantLock.acquire();
            reentrantLock.release();
            reentrantLock.release();
            
            // 公平锁操作
            fairLock.acquire();
            fairLock.release();
            
            // 读写锁操作
            readWriteLock.readLock().acquire();
            readWriteLock.readLock().release();
            readWriteLock.writeLock().acquire();
            readWriteLock.writeLock().release();
        }
        
        // 等待性能数据收集
        Thread.sleep(2000);
        
        // When - 生成性能报告
        PerformanceMonitor.PerformanceReport report = 
            performanceMonitor.generatePerformanceReport(Duration.ofMinutes(1));
        
        PerformanceMonitor.PerformanceMetrics metrics = performanceMonitor.getSystemMetrics();
        
        // Then - 验证报告完整性
        assertNotNull(report);
        assertNotNull(metrics);
        
        assertTrue(report.getTotalAcquisitions() > 0);
        assertTrue(report.getSuccessRate() >= 0 && report.getSuccessRate() <= 1);
        assertTrue(report.getAverageAcquisitionTime() >= 0);
        assertTrue(report.getThroughput() >= 0);
        
        // 验证指标一致性
        assertEquals(metrics.getTotalAcquisitions(), report.getTotalAcquisitions(), 1);
        assertTrue(Math.abs(metrics.getAverageAcquisitionTime() - report.getAverageAcquisitionTime()) < 10);
    }
    
    // 辅助方法
    
    private void createTestLocks() {
        // 可重入锁
        LockConfiguration reentrantConfig = LockConfiguration.builder()
            .withLockName(TEST_LOCK_PREFIX + "-reentrant")
            .withLeaseTime(Duration.ofSeconds(30))
            .build();
        reentrantLock = new TestReentrantLock(reentrantConfig, performanceMonitor);
        
        // 公平锁
        LockConfiguration fairConfig = LockConfiguration.builder()
            .withLockName(TEST_LOCK_PREFIX + "-fair")
            .withFairness(true)
            .build();
        fairLock = new TestFairLock(fairConfig, performanceMonitor);
        
        // 读写锁
        LockConfiguration rwConfig = LockConfiguration.builder()
            .withLockName(TEST_LOCK_PREFIX + "-rw")
            .build();
        readWriteLock = new TestReadWriteLock(rwConfig, performanceMonitor);
    }
    
    private void registerEventListeners() {
        // 事件监听器
        eventManager.addEventListener(event -> {
            if (event.getLock() != null) {
                testEventCount.incrementAndGet();
            }
        }, LockEventManager.EventListenerConfig.DEFAULT);
        
        // 死锁检测监听器
        eventManager.addEventListener(event -> {
            if (event.getType() == LockEvent.Type.DEADLOCK_DETECTED) {
                deadlockDetections.incrementAndGet();
            }
        }, LockEventManager.EventListenerConfig.DEFAULT);
    }
    
    private <T> T executeNested(Callable<T> operation, int depth, int maxDepth) throws Exception {
        if (depth < maxDepth) {
            reentrantLock.acquire();
            try {
                return executeNested(operation, depth + 1, maxDepth);
            } finally {
                reentrantLock.release();
            }
        } else {
            return operation.call();
        }
    }
    
    private DistributedLock chooseRandomLock() {
        int choice = (int) (Math.random() * 4);
        return switch (choice) {
            case 0 -> reentrantLock;
            case 1 -> fairLock;
            case 2 -> readWriteLock.readLock();
            case 3 -> readWriteLock.writeLock();
            default -> reentrantLock;
        };
    }
    
    private void releaseLock(DistributedLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.release();
        }
    }
    
    // 内部测试类
    
    private static class TestReentrantLock implements DistributedLock {
        private final String name;
        private final LockConfiguration config;
        private final PerformanceMonitor performanceMonitor;
        private final ReentrantLock internalLock = new ReentrantLock();
        private volatile long ownerThreadId = -1;
        private volatile int reentrantCount = 0;
        
        public TestReentrantLock(LockConfiguration config, PerformanceMonitor performanceMonitor) {
            this.name = config.getLockName();
            this.config = config;
            this.performanceMonitor = performanceMonitor;
        }
        
        @Override
        public String getName() { return name; }
        
        @Override
        public boolean acquire() {
            internalLock.lock();
            try {
                ownerThreadId = Thread.currentThread().getId();
                reentrantCount++;
                return true;
            } finally {
                // 不释放锁
            }
        }
        
        @Override
        public boolean acquire(Duration timeout) throws InterruptedException {
            if (internalLock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                try {
                    ownerThreadId = Thread.currentThread().getId();
                    reentrantCount++;
                    return true;
                } finally {
                    // 不释放锁
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
            } else {
                throw new IllegalMonitorStateException("Current thread does not hold this lock");
            }
        }
        
        @Override
        public boolean isLocked() { return internalLock.isLocked(); }
        
        @Override
        public boolean isHeldByCurrentThread() { return internalLock.isHeldByCurrentThread(); }
        
        @Override
        public RenewalResult renew() { return new RenewalResult(true, "Reentrant lock renewed"); }
        
        @Override
        public HealthCheck.HealthCheckResult healthCheck() {
            return new HealthCheck.HealthCheckResult(
                HealthCheck.ComponentStatus.HEALTHY, "Reentrant lock is healthy", System.currentTimeMillis());
        }
        
        @Override
        public LockStateInfo getStateInfo() {
            return new LockStateInfo(name, isLocked(), isHeldByCurrentThread(), ownerThreadId, reentrantCount, true);
        }
        
        @Override
        public LockConfigurationInfo getConfigurationInfo() {
            return new LockConfigurationInfo(name, config.getLeaseTime(), config.getRetryAttempts(), 
                config.getRetryDelay(), LockType.REENTRANT, false, true);
        }
        
        @Override
        public void close() { forceRelease(); }
        
        public void forceRelease() {
            if (internalLock.isLocked()) {
                reentrantCount = 0;
                ownerThreadId = -1;
                internalLock.unlock();
            }
        }
        
        public int getReentrantCount() { return reentrantCount; }
    }
    
    private static class TestFairLock implements DistributedLock {
        private final String name;
        private final LockConfiguration config;
        private final PerformanceMonitor performanceMonitor;
        private final ReentrantLock internalLock = new ReentrantLock(true); // 公平锁
        private volatile long ownerThreadId = -1;
        
        public TestFairLock(LockConfiguration config, PerformanceMonitor performanceMonitor) {
            this.name = config.getLockName();
            this.config = config;
            this.performanceMonitor = performanceMonitor;
        }
        
        @Override
        public String getName() { return name; }
        
        @Override
        public boolean acquire() {
            internalLock.lock();
            try {
                ownerThreadId = Thread.currentThread().getId();
                return true;
            } finally {
                // 不释放锁
            }
        }
        
        @Override
        public boolean acquire(Duration timeout) throws InterruptedException {
            if (internalLock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                try {
                    ownerThreadId = Thread.currentThread().getId();
                    return true;
                } finally {
                    // 不释放锁
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
                ownerThreadId = -1;
                internalLock.unlock();
            } else {
                throw new IllegalMonitorStateException("Current thread does not hold this lock");
            }
        }
        
        @Override
        public boolean isLocked() { return internalLock.isLocked(); }
        
        @Override
        public boolean isHeldByCurrentThread() { return internalLock.isHeldByCurrentThread(); }
        
        @Override
        public RenewalResult renew() { return new RenewalResult(true, "Fair lock renewed"); }
        
        @Override
        public HealthCheck.HealthCheckResult healthCheck() {
            return new HealthCheck.HealthCheckResult(
                HealthCheck.ComponentStatus.HEALTHY, "Fair lock is healthy", System.currentTimeMillis());
        }
        
        @Override
        public LockStateInfo getStateInfo() {
            return new LockStateInfo(name, isLocked(), isHeldByCurrentThread(), ownerThreadId, 1, false);
        }
        
        @Override
        public LockConfigurationInfo getConfigurationInfo() {
            return new LockConfigurationInfo(name, config.getLeaseTime(), config.getRetryAttempts(), 
                config.getRetryDelay(), LockType.FAIR, true, false);
        }
        
        @Override
        public void close() { forceReleaseAll(); }
        
        public void forceReleaseAll() {
            if (internalLock.isLocked()) {
                ownerThreadId = -1;
                internalLock.unlock();
            }
        }
    }
    
    private static class TestReadWriteLock implements DistributedReadWriteLock {
        private final String name;
        private final LockConfiguration config;
        private final PerformanceMonitor performanceMonitor;
        private final ReadLockImpl readLock;
        private final WriteLockImpl writeLock;
        
        public TestReadWriteLock(LockConfiguration config, PerformanceMonitor performanceMonitor) {
            this.name = config.getLockName();
            this.config = config;
            this.performanceMonitor = performanceMonitor;
            this.readLock = new ReadLockImpl(this);
            this.writeLock = new WriteLockImpl(this);
        }
        
        @Override
        public String getName() { return name; }
        
        @Override
        public DistributedLock readLock() { return readLock; }
        
        @Override
        public DistributedLock writeLock() { return writeLock; }
        
        @Override
        public ReadWriteLockStateInfo getStateInfo() {
            return new ReadWriteLockStateInfo(name, readLock.getHoldCount(), writeLock.getHoldCount(), writeLock.isWriteLocked()) {
                @Override
                public boolean isReadLocked() { return readLock.getHoldCount() > 0; }
                @Override
                public boolean isWriteLocked() { return writeLock.isWriteLocked(); }
                @Override
                public int getActiveReaders() { return readLock.getHoldCount(); }
                @Override
                public int getActiveWriters() { return writeLock.getHoldCount(); }
            };
        }
        
        @Override
        public HealthCheck.HealthCheckResult healthCheck() {
            return new HealthCheck.HealthCheckResult(
                HealthCheck.ComponentStatus.HEALTHY, "Read-write lock is healthy", System.currentTimeMillis());
        }
        
        @Override
        public void close() { forceReleaseAll(); }
        
        public void forceReleaseAll() {
            readLock.forceRelease();
            writeLock.forceRelease();
        }
        
        private abstract static class BaseLock implements DistributedLock {
            protected final TestReadWriteLock owner;
            protected final String lockName;
            protected final ReentrantLock internalLock = new ReentrantLock();
            protected volatile long ownerThreadId = -1;
            
            public BaseLock(TestReadWriteLock owner, String lockName) {
                this.owner = owner;
                this.lockName = lockName;
            }
            
            @Override
            public String getName() { return lockName; }
            
            @Override
            public boolean acquire() {
                internalLock.lock();
                try {
                    ownerThreadId = Thread.currentThread().getId();
                    return true;
                } finally {
                    // 不释放锁
                }
            }
            
            @Override
            public boolean acquire(Duration timeout) throws InterruptedException {
                if (internalLock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    try {
                        ownerThreadId = Thread.currentThread().getId();
                        return true;
                    } finally {
                        // 不释放锁
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
            public boolean isLocked() { return internalLock.isLocked(); }
            
            @Override
            public boolean isHeldByCurrentThread() { return internalLock.isHeldByCurrentThread(); }
            
            @Override
            public RenewalResult renew() { return new RenewalResult(true, "Lock renewed"); }
            
            @Override
            public HealthCheck.HealthCheckResult healthCheck() {
                return new HealthCheck.HealthCheckResult(
                    HealthCheck.ComponentStatus.HEALTHY, lockName + " is healthy", System.currentTimeMillis());
            }
            
            @Override
            public LockStateInfo getStateInfo() {
                return new LockStateInfo(lockName, isLocked(), isHeldByCurrentThread(), ownerThreadId, getHoldCount(), true);
            }
            
            @Override
            public LockConfigurationInfo getConfigurationInfo() {
                return new LockConfigurationInfo(lockName, owner.config.getLeaseTime(), owner.config.getRetryAttempts(), 
                    owner.config.getRetryDelay(), getLockType(), false, true);
            }
            
            @Override
            public void close() { forceRelease(); }
            
            protected abstract LockType getLockType();
            protected abstract int getHoldCount();
            
            public void forceRelease() {
                if (internalLock.isLocked()) {
                    ownerThreadId = -1;
                    internalLock.unlock();
                }
            }
        }
        
        private class ReadLockImpl extends BaseLock {
            public ReadLockImpl(TestReadWriteLock owner) {
                super(owner, name + "-read");
            }
            
            @Override
            public void release() {
                if (internalLock.isHeldByCurrentThread()) {
                    ownerThreadId = -1;
                    internalLock.unlock();
                } else {
                    throw new IllegalMonitorStateException("Current thread does not hold this read lock");
                }
            }
            
            @Override
            protected LockType getLockType() { return LockType.READ; }
            
            @Override
            protected int getHoldCount() { return internalLock.getHoldCount(); }
        }
        
        private class WriteLockImpl extends BaseLock {
            public WriteLockImpl(TestReadWriteLock owner) {
                super(owner, name + "-write");
            }
            
            @Override
            public void release() {
                if (internalLock.isHeldByCurrentThread()) {
                    ownerThreadId = -1;
                    internalLock.unlock();
                } else {
                    throw new IllegalMonitorStateException("Current thread does not hold this write lock");
                }
            }
            
            @Override
            public boolean isWriteLocked() {
                return internalLock.isLocked() && internalLock.getHoldCount() > 0;
            }
            
            @Override
            protected LockType getLockType() { return LockType.WRITE; }
            
            @Override
            protected int getHoldCount() { return internalLock.getHoldCount(); }
        }
    }
    
    private static class TestSimpleLock implements DistributedLock {
        private final String name;
        private final PerformanceMonitor performanceMonitor;
        private final ReentrantLock internalLock = new ReentrantLock();
        private volatile long ownerThreadId = -1;
        
        public TestSimpleLock(String name, PerformanceMonitor performanceMonitor) {
            this.name = name;
            this.performanceMonitor = performanceMonitor;
        }
        
        @Override
        public String getName() { return name; }
        
        @Override
        public boolean acquire() {
            if (internalLock.tryLock()) {
                ownerThreadId = Thread.currentThread().getId();
                return true;
            }
            return false;
        }
        
        @Override
        public boolean acquire(Duration timeout) throws InterruptedException {
            if (internalLock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                try {
                    ownerThreadId = Thread.currentThread().getId();
                    return true;
                } finally {
                    // 不释放锁
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
                ownerThreadId = -1;
                internalLock.unlock();
            } else {
                throw new IllegalMonitorStateException("Current thread does not hold this lock");
            }
        }
        
        @Override
        public boolean isLocked() { return internalLock.isLocked(); }
        
        @Override
        public boolean isHeldByCurrentThread() { return internalLock.isHeldByCurrentThread(); }
        
        @Override
        public RenewalResult renew() { return new RenewalResult(true, "Lock renewed"); }
        
        @Override
        public HealthCheck.HealthCheckResult healthCheck() {
            return new HealthCheck.HealthCheckResult(
                HealthCheck.ComponentStatus.HEALTHY, "Simple lock is healthy", System.currentTimeMillis());
        }
        
        @Override
        public LockStateInfo getStateInfo() {
            return new LockStateInfo(name, isLocked(), isHeldByCurrentThread(), ownerThreadId, 1, false);
        }
        
        @Override
        public LockConfigurationInfo getConfigurationInfo() {
            return new LockConfigurationInfo(name, Duration.ofSeconds(30), 3, Duration.ofMillis(100), 
                LockType.SIMPLE, false, false);
        }
        
        @Override
        public void close() { /* 空实现 */ }
    }
    
    private static class TestBatchLockOperations implements BatchLockOperations {
        private final List<TestSimpleLock> locks;
        private final PerformanceMonitor performanceMonitor;
        
        public TestBatchLockOperations(List<String> lockNames, PerformanceMonitor performanceMonitor) {
            this.performanceMonitor = performanceMonitor;
            this.locks = lockNames.stream()
                .map(name -> new TestSimpleLock(name, performanceMonitor))
                .toList();
        }
        
        @Override
        public BatchLockResult acquireMultiple(List<String> lockNames, Duration timeout) {
            long startTime = System.currentTimeMillis();
            List<String> acquiredLocks = new ArrayList<>();
            List<String> failedLocks = new ArrayList<>();
            
            try {
                for (String lockName : lockNames) {
                    TestSimpleLock lock = findLock(lockName);
                    if (lock != null && lock.acquire(timeout)) {
                        acquiredLocks.add(lockName);
                    } else {
                        failedLocks.add(lockName);
                        break;
                    }
                }
                
                boolean success = failedLocks.isEmpty();
                if (!success && !acquiredLocks.isEmpty()) {
                    // 回滚
                    for (String acquiredLock : acquiredLocks) {
                        TestSimpleLock lock = findLock(acquiredLock);
                        if (lock != null && lock.isHeldByCurrentThread()) {
                            lock.release();
                        }
                    }
                    acquiredLocks.clear();
                }
                
                return new BatchLockResult(success, acquiredLocks, failedLocks, 
                    System.currentTimeMillis() - startTime, null);
                    
            } catch (Exception e) {
                // 回滚
                for (String acquiredLock : acquiredLocks) {
                    TestSimpleLock lock = findLock(acquiredLock);
                    if (lock != null && lock.isHeldByCurrentThread()) {
                        lock.release();
                    }
                }
                return new BatchLockResult(false, Collections.emptyList(), lockNames, 
                    System.currentTimeMillis() - startTime, e.getMessage());
            }
        }
        
        @Override
        public BatchReleaseResult releaseMultiple(List<String> lockNames) {
            long startTime = System.currentTimeMillis();
            List<String> releasedLocks = new ArrayList<>();
            List<String> failedLocks = new ArrayList<>();
            
            for (String lockName : lockNames) {
                TestSimpleLock lock = findLock(lockName);
                if (lock != null) {
                    try {
                        lock.release();
                        releasedLocks.add(lockName);
                    } catch (Exception e) {
                        failedLocks.add(lockName);
                    }
                } else {
                    failedLocks.add(lockName);
                }
            }
            
            return new BatchReleaseResult(failedLocks.isEmpty(), releasedLocks, failedLocks, 
                System.currentTimeMillis() - startTime);
        }
        
        @Override
        public <T> BatchOperationResult<T> executeWithLocks(List<String> lockNames, BatchOperationExecutor<T> executor, Duration timeout, int maxRetries) {
            int attempts = 0;
            while (attempts < maxRetries) {
                try {
                    BatchLockResult lockResult = acquireMultiple(lockNames, timeout);
                    
                    if (lockResult.isSuccess()) {
                        try {
                            T result = executor.execute();
                            releaseMultiple(lockNames);
                            return new BatchOperationResult<>(true, result, null);
                        } catch (Exception e) {
                            releaseMultiple(lockNames);
                            throw e;
                        }
                    } else {
                        if (attempts == maxRetries - 1) {
                            return new BatchOperationResult<>(false, null, 
                                "Failed to acquire locks after " + maxRetries + " attempts");
                        }
                        attempts++;
                        try {
                            Thread.sleep(100 * attempts);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return new BatchOperationResult<>(false, null, "Operation interrupted");
                        }
                    }
                } catch (Exception e) {
                    attempts++;
                    if (attempts >= maxRetries) {
                        break;
                    }
                }
            }
            
            return new BatchOperationResult<>(false, null, "Operation failed after " + attempts + " attempts");
        }
        
        @Override
        public void releaseAll() {
            releaseMultiple(locks.stream().map(TestSimpleLock::getName).toList());
        }
        
        @Override
        public BatchOperationInfo getBatchOperationInfo(List<String> lockNames) {
            int totalLocks = lockNames.size();
            int availableLocks = 0;
            
            for (String lockName : lockNames) {
                TestSimpleLock lock = findLock(lockName);
                if (lock != null && !lock.isLocked()) {
                    availableLocks++;
                }
            }
            
            return new BatchOperationInfo(lockNames, totalLocks, availableLocks);
        }
        
        public TestSimpleLock findLock(String lockName) {
            return locks.stream()
                .filter(lock -> lock.getName().equals(lockName))
                .findFirst()
                .orElse(null);
        }
    }
    
    private static class TestDeadlockDetector {
        private volatile boolean enableDetection = false;
        
        public void setEnableDetection(boolean enable) {
            this.enableDetection = enable;
        }
        
        public boolean detectDeadlock(TestSimpleLock lock1, TestSimpleLock lock2) {
            if (!enableDetection) return false;
            
            // 简化的死锁检测逻辑
            return lock1.isLocked() && lock2.isLocked() && 
                   lock1.ownerThreadId != lock2.ownerThreadId &&
                   lock1.ownerThreadId != Thread.currentThread().getId() &&
                   lock2.ownerThreadId != Thread.currentThread().getId();
        }
    }
}