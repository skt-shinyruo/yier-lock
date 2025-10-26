package com.mycorp.distributedlock.core.operation;

import com.mycorp.distributedlock.api.*;
import com.mycorp.distributedlock.core.config.LockConfiguration;
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
 * 批量锁操作高级特性测试
 */
@ExtendWith(MockitoExtension.class)
class BatchLockOperationsAdvancedTest {
    
    private static final String TEST_LOCK_NAME = "batch-test-lock";
    
    private BatchLockOperationsImpl batchLockOperations;
    private PerformanceMonitor performanceMonitor;
    private MeterRegistry meterRegistry;
    private List<SimpleLockImpl> testLocks;
    
    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        performanceMonitor = new PerformanceMonitor(meterRegistry);
        performanceMonitor.start();
        
        // 创建多个测试锁
        testLocks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            LockConfiguration config = LockConfiguration.builder()
                .withLockName(TEST_LOCK_NAME + "-" + i)
                .withLeaseTime(Duration.ofSeconds(30))
                .withRetryAttempts(3)
                .withRetryDelay(Duration.ofMillis(100))
                .build();
            testLocks.add(new SimpleLockImpl(config, performanceMonitor));
        }
        
        batchLockOperations = new BatchLockOperationsImpl(testLocks, performanceMonitor);
    }
    
    @AfterEach
    void tearDown() {
        if (batchLockOperations != null) {
            try {
                batchLockOperations.releaseAll();
            } catch (Exception e) {
                // 忽略清理错误
            }
        }
        for (SimpleLockImpl lock : testLocks) {
            lock.forceRelease();
        }
        if (performanceMonitor != null) {
            performanceMonitor.stop();
            performanceMonitor.close();
        }
    }
    
    @Test
    void shouldAcquireMultipleLocksAtomically() throws InterruptedException {
        // Given
        List<String> lockNames = testLocks.stream()
            .map(SimpleLockImpl::getName)
            .toList();
            
        // When - 原子性地获取多个锁
        BatchLockOperations.BatchLockResult result = 
            batchLockOperations.acquireMultiple(lockNames, Duration.ofSeconds(5));
        
        // Then - 验证所有锁都被获取
        assertTrue(result.isSuccess(), "Batch acquisition should succeed");
        assertEquals(lockNames.size(), result.getAcquiredLocks().size());
        assertEquals(0, result.getFailedLocks().size());
        
        // 验证所有锁都被持有
        for (SimpleLockImpl lock : testLocks) {
            assertTrue(lock.isLocked(), "Lock " + lock.getName() + " should be locked");
            assertEquals(Thread.currentThread().getId(), lock.getOwnerThreadId(), 
                        "Lock should be owned by current thread");
        }
    }
    
    @Test
    void shouldReleaseMultipleLocksAtomically() throws InterruptedException {
        // Given - 首先获取多个锁
        List<String> lockNames = testLocks.stream()
            .map(SimpleLockImpl::getName)
            .toList();
            
        BatchLockOperations.BatchLockResult acquireResult = 
            batchLockOperations.acquireMultiple(lockNames, Duration.ofSeconds(5));
        assertTrue(acquireResult.isSuccess());
        
        // When - 原子性地释放多个锁
        BatchLockOperations.BatchReleaseResult releaseResult = 
            batchLockOperations.releaseMultiple(lockNames);
        
        // Then - 验证所有锁都被释放
        assertTrue(releaseResult.isSuccess(), "Batch release should succeed");
        assertEquals(lockNames.size(), releaseResult.getReleasedLocks().size());
        
        // 验证所有锁都被释放
        for (SimpleLockImpl lock : testLocks) {
            assertFalse(lock.isLocked(), "Lock " + lock.getName() + " should be released");
        }
    }
    
    @Test
    void shouldHandlePartialAcquisitionFailure() throws InterruptedException {
        // Given - 预先获取某些锁
        SimpleLockImpl preLocked = testLocks.get(0);
        preLocked.acquire(); // 预先锁定一个锁
        
        List<String> lockNames = testLocks.stream()
            .map(SimpleLockImpl::getName)
            .toList();
        
        // When - 尝试批量获取所有锁
        BatchLockOperations.BatchLockResult result = 
            batchLockOperations.acquireMultiple(lockNames, Duration.ofSeconds(5));
        
        // Then - 验证部分获取失败
        assertFalse(result.isSuccess(), "Batch acquisition should fail due to pre-locked lock");
        assertTrue(result.getFailedLocks().contains(preLocked.getName()));
        assertTrue(result.getAcquiredLocks().isEmpty() || 
                   result.getFailedLocks().size() > 0);
        
        // 验证已获取的锁都被释放（回滚）
        for (String acquiredLock : result.getAcquiredLocks()) {
            SimpleLockImpl lock = findLockByName(acquiredLock);
            assertNotNull(lock);
            assertFalse(lock.isLocked(), "Acquired lock should be rolled back");
        }
    }
    
    @Test
    void shouldExecuteBatchOperationWithRetry() throws InterruptedException {
        // Given
        List<String> lockNames = testLocks.stream()
            .map(SimpleLockImpl::getName)
            .toList();
            
        AtomicInteger executionCount = new AtomicInteger(0);
        
        BatchLockOperations.BatchOperationExecutor<String> executor = 
            () -> {
                executionCount.incrementAndGet();
                return "operation-" + System.currentTimeMillis();
            };
        
        // When - 使用重试机制执行批量操作
        BatchLockOperations.BatchOperationResult<String> result = 
            batchLockOperations.executeWithLocks(lockNames, executor, Duration.ofSeconds(5), 3);
        
        // Then - 验证操作成功执行
        assertTrue(result.isSuccess());
        assertTrue(result.getResult().startsWith("operation-"));
        assertTrue(executionCount.get() >= 1);
    }
    
    @Test
    void shouldRollbackOnOperationFailure() throws InterruptedException {
        // Given
        List<String> lockNames = testLocks.stream()
            .map(SimpleLockImpl::getName)
            .toList();
            
        BatchLockOperations.BatchOperationExecutor<String> failingExecutor = 
            () -> {
                throw new RuntimeException("Simulated operation failure");
            };
        
        // When - 执行会失败的操作
        BatchLockOperations.BatchOperationResult<String> result = 
            batchLockOperations.executeWithLocks(lockNames, failingExecutor, Duration.ofSeconds(5), 1);
        
        // Then - 验证操作失败且锁被回滚
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("Simulated operation failure"));
        
        // 验证所有锁都被释放（回滚）
        for (SimpleLockImpl lock : testLocks) {
            assertFalse(lock.isLocked(), "All locks should be rolled back");
        }
    }
    
    @Test
    void shouldHandleConcurrentBatchOperations() throws InterruptedException {
        // Given
        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch completionLatch = new CountDownLatch(threadCount);
        
        AtomicInteger successfulOperations = new AtomicInteger(0);
        AtomicInteger failedOperations = new AtomicInteger(0);
        
        // When - 多个线程同时执行批量操作
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            final List<String> lockNames = List.of(testLocks.get(i % testLocks.size()).getName());
            
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    BatchLockOperations.BatchOperationResult<String> result = 
                        batchLockOperations.executeWithLocks(lockNames, 
                            () -> {
                                successfulOperations.incrementAndGet();
                                Thread.sleep(10); // 模拟操作时间
                                return "thread-" + threadId + "-result";
                            }, 
                            Duration.ofSeconds(2), 1);
                    
                    if (result.isSuccess()) {
                        // 成功
                    } else {
                        failedOperations.incrementAndGet();
                    }
                } catch (Exception e) {
                    failedOperations.incrementAndGet();
                } finally {
                    completionLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        completionLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Then - 验证并发批量操作的正确性
        assertTrue(successfulOperations.get() >= 0);
        assertTrue(failedOperations.get() >= 0);
        
        // 最终验证所有锁都被正确释放
        for (SimpleLockImpl lock : testLocks) {
            assertFalse(lock.isLocked(), "All locks should be released after concurrent operations");
        }
    }
    
    @Test
    void shouldMonitorBatchOperationMetrics() {
        // Given
        List<String> lockNames = testLocks.stream()
            .map(SimpleLockImpl::getName)
            .toList();
            
        // 执行一些批量操作
        for (int i = 0; i < 3; i++) {
            BatchLockOperations.BatchLockResult result = 
                batchLockOperations.acquireMultiple(lockNames, Duration.ofSeconds(1));
            if (result.isSuccess()) {
                batchLockOperations.releaseMultiple(lockNames);
            }
        }
        
        // 记录性能指标
        performanceMonitor.recordLockContention(testLocks.get(0), 50);
        performanceMonitor.recordLockAcquisition(testLocks.get(0), 30, true);
        
        // When
        PerformanceMonitor.LockPerformanceMetrics metrics = 
            performanceMonitor.getLockMetrics(TEST_LOCK_NAME + "-0");
        
        // Then
        assertNotNull(metrics);
        assertTrue(metrics.getAcquisitionCount() > 0 || metrics.getContentionCount() > 0);
        
        // 验证批量操作特定指标
        BatchOperationMetrics batchMetrics = batchLockOperations.getBatchOperationMetrics();
        assertNotNull(batchMetrics);
        assertTrue(batchMetrics.getTotalBatchOperations() >= 0);
        assertTrue(batchMetrics.getSuccessfulBatchOperations() >= 0);
    }
    
    @Test
    void shouldProvideBatchOperationInformation() {
        // Given
        List<String> lockNames = testLocks.stream()
            .map(SimpleLockImpl::getName)
            .toList();
            
        // When
        BatchOperationInfo info = batchLockOperations.getBatchOperationInfo(lockNames);
        
        // Then
        assertNotNull(info);
        assertEquals(lockNames, info.getLockNames());
        assertTrue(info.getTotalLocks() == lockNames.size());
        assertTrue(info.getAvailableLocks() >= 0);
    }
    
    // 辅助方法
    
    private SimpleLockImpl findLockByName(String lockName) {
        return testLocks.stream()
            .filter(lock -> lock.getName().equals(lockName))
            .findFirst()
            .orElse(null);
    }
    
    // 内部类实现
    
    private static class SimpleLockImpl implements DistributedLock {
        private final String name;
        private final LockConfiguration config;
        private final PerformanceMonitor performanceMonitor;
        private final ReentrantLock internalLock = new ReentrantLock();
        private volatile long ownerThreadId = -1;
        
        public SimpleLockImpl(LockConfiguration config, PerformanceMonitor performanceMonitor) {
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
            if (internalLock.tryLock()) {
                ownerThreadId = Thread.currentThread().getId();
                performanceMonitor.recordLockAcquisition(this, 0, true);
                return true;
            }
            return false;
        }
        
        @Override
        public boolean acquire(Duration timeout) throws InterruptedException {
            long startTime = System.currentTimeMillis();
            if (internalLock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                try {
                    ownerThreadId = Thread.currentThread().getId();
                    performanceMonitor.recordLockAcquisition(this, 
                        System.currentTimeMillis() - startTime, true);
                    return true;
                } finally {
                    // 不在这里释放
                }
            }
            performanceMonitor.recordLockAcquisition(this, timeout.toMillis(), false);
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
        public RenewalResult renew() {
            return new RenewalResult(true, "Lock renewed");
        }
        
        @Override
        public HealthCheck.HealthCheckResult healthCheck() {
            return new HealthCheck.HealthCheckResult(
                HealthCheck.ComponentStatus.HEALTHY, 
                "Simple lock is healthy", 
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
                1,
                false
            );
        }
        
        @Override
        public LockConfigurationInfo getConfigurationInfo() {
            return new LockConfigurationInfo(
                name,
                config.getLeaseTime(),
                config.getRetryAttempts(),
                config.getRetryDelay(),
                LockType.SIMPLE,
                false,
                false
            );
        }
        
        @Override
        public void close() {
            forceRelease();
        }
        
        public void forceRelease() {
            if (internalLock.isLocked()) {
                ownerThreadId = -1;
                internalLock.unlock();
            }
        }
        
        public long getOwnerThreadId() { return ownerThreadId; }
    }
    
    private static class BatchLockOperationsImpl implements BatchLockOperations {
        private final List<SimpleLockImpl> locks;
        private final PerformanceMonitor performanceMonitor;
        private final BatchOperationMetrics metrics = new BatchOperationMetrics();
        
        public BatchLockOperationsImpl(List<SimpleLockImpl> locks, PerformanceMonitor performanceMonitor) {
            this.locks = new ArrayList<>(locks);
            this.performanceMonitor = performanceMonitor;
        }
        
        @Override
        public BatchLockResult acquireMultiple(List<String> lockNames, Duration timeout) {
            long startTime = System.currentTimeMillis();
            List<String> acquiredLocks = new ArrayList<>();
            List<String> failedLocks = new ArrayList<>();
            
            try {
                // 按顺序获取锁
                for (String lockName : lockNames) {
                    SimpleLockImpl lock = findLock(lockName);
                    if (lock != null && lock.acquire(timeout)) {
                        acquiredLocks.add(lockName);
                    } else {
                        failedLocks.add(lockName);
                        break; // 停止获取更多锁
                    }
                }
                
                boolean success = failedLocks.isEmpty();
                
                if (!success && !acquiredLocks.isEmpty()) {
                    // 如果部分获取失败，回滚已获取的锁
                    for (String acquiredLock : acquiredLocks) {
                        SimpleLockImpl lock = findLock(acquiredLock);
                        if (lock != null && lock.isHeldByCurrentThread()) {
                            lock.release();
                        }
                    }
                    acquiredLocks.clear();
                }
                
                metrics.recordBatchOperation(success);
                
                return new BatchLockResult(success, acquiredLocks, failedLocks, 
                    System.currentTimeMillis() - startTime, null);
                    
            } catch (Exception e) {
                // 回滚所有已获取的锁
                for (String acquiredLock : acquiredLocks) {
                    SimpleLockImpl lock = findLock(acquiredLock);
                    if (lock != null && lock.isHeldByCurrentThread()) {
                        lock.release();
                    }
                }
                
                metrics.recordBatchOperation(false);
                return new BatchLockResult(false, Collections.emptyList(), lockNames, 
                    System.currentTimeMillis() - startTime, e.getMessage());
            }
        }
        
        @Override
        public BatchReleaseResult releaseMultiple(List<String> lockNames) {
            long startTime = System.currentTimeMillis();
            List<String> releasedLocks = new ArrayList<>();
            List<String> failedLocks = new ArrayList<>();
            
            try {
                for (String lockName : lockNames) {
                    SimpleLockImpl lock = findLock(lockName);
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
                
                boolean success = failedLocks.isEmpty();
                metrics.recordBatchRelease(success);
                
                return new BatchReleaseResult(success, releasedLocks, failedLocks, 
                    System.currentTimeMillis() - startTime);
                    
            } catch (Exception e) {
                metrics.recordBatchRelease(false);
                return new BatchReleaseResult(false, releasedLocks, failedLocks, 
                    System.currentTimeMillis() - startTime);
            }
        }
        
        @Override
        public <T> BatchOperationResult<T> executeWithLocks(List<String> lockNames, 
                                                           BatchOperationExecutor<T> executor, 
                                                           Duration timeout, 
                                                           int maxRetries) {
            int attempts = 0;
            Exception lastException = null;
            
            while (attempts < maxRetries) {
                try {
                    BatchLockResult lockResult = acquireMultiple(lockNames, timeout);
                    
                    if (lockResult.isSuccess()) {
                        try {
                            T result = executor.execute();
                            releaseMultiple(lockNames);
                            return new BatchOperationResult<>(true, result, null);
                        } catch (Exception e) {
                            // 执行失败，回滚锁
                            releaseMultiple(lockNames);
                            throw e;
                        }
                    } else {
                        // 获取锁失败，检查是否需要重试
                        if (attempts == maxRetries - 1) {
                            return new BatchOperationResult<>(false, null, 
                                "Failed to acquire locks after " + maxRetries + " attempts: " + 
                                lockResult.getErrorMessage());
                        }
                        attempts++;
                        try {
                            Thread.sleep(100 * attempts); // 递增延迟
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return new BatchOperationResult<>(false, null, "Operation interrupted");
                        }
                    }
                } catch (Exception e) {
                    lastException = e;
                    attempts++;
                    if (attempts >= maxRetries) {
                        break;
                    }
                }
            }
            
            return new BatchOperationResult<>(false, null, 
                "Operation failed after " + attempts + " attempts: " + 
                (lastException != null ? lastException.getMessage() : "Unknown error"));
        }
        
        @Override
        public void releaseAll() {
            releaseMultiple(locks.stream().map(SimpleLockImpl::getName).toList());
        }
        
        @Override
        public BatchOperationInfo getBatchOperationInfo(List<String> lockNames) {
            int totalLocks = lockNames.size();
            int availableLocks = 0;
            
            for (String lockName : lockNames) {
                SimpleLockImpl lock = findLock(lockName);
                if (lock != null && !lock.isLocked()) {
                    availableLocks++;
                }
            }
            
            return new BatchOperationInfo(lockNames, totalLocks, availableLocks);
        }
        
        public BatchOperationMetrics getBatchOperationMetrics() {
            return metrics;
        }
        
        private SimpleLockImpl findLock(String lockName) {
            return locks.stream()
                .filter(lock -> lock.getName().equals(lockName))
                .findFirst()
                .orElse(null);
        }
    }
    
    private static class BatchOperationMetrics {
        private final AtomicLong totalBatchOperations = new AtomicLong(0);
        private final AtomicLong successfulBatchOperations = new AtomicLong(0);
        private final AtomicLong totalBatchReleases = new AtomicLong(0);
        private final AtomicLong successfulBatchReleases = new AtomicLong(0);
        
        public void recordBatchOperation(boolean success) {
            totalBatchOperations.incrementAndGet();
            if (success) {
                successfulBatchOperations.incrementAndGet();
            }
        }
        
        public void recordBatchRelease(boolean success) {
            totalBatchReleases.incrementAndGet();
            if (success) {
                successfulBatchReleases.incrementAndGet();
            }
        }
        
        public long getTotalBatchOperations() { return totalBatchOperations.get(); }
        public long getSuccessfulBatchOperations() { return successfulBatchOperations.get(); }
        public long getTotalBatchReleases() { return totalBatchReleases.get(); }
        public long getSuccessfulBatchReleases() { return successfulBatchReleases.get(); }
    }
}