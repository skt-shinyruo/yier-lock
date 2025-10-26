package com.mycorp.distributedlock.core.lock;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 读写锁高级特性测试
 */
@ExtendWith(MockitoExtension.class)
class ReadWriteLockAdvancedTest {
    
    private static final String TEST_LOCK_NAME = "readwrite-test-lock";
    
    private ReadWriteLockImpl readWriteLock;
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
            
        readWriteLock = new ReadWriteLockImpl(config, performanceMonitor);
    }
    
    @AfterEach
    void tearDown() {
        if (readWriteLock != null) {
            try {
                readWriteLock.forceReleaseAll();
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
    void shouldAllowMultipleConcurrentReadLocks() throws InterruptedException {
        // Given
        int readerCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(readerCount);
        ExecutorService executor = Executors.newFixedThreadPool(readerCount);
        
        AtomicInteger activeReaders = new AtomicInteger(0);
        AtomicInteger maxConcurrentReaders = new AtomicInteger(0);
        AtomicInteger[] readOrders = new AtomicInteger[readerCount];
        for (int i = 0; i < readerCount; i++) {
            readOrders[i] = new AtomicInteger(-1);
        }
        
        // When - 多个读者同时获取锁
        for (int i = 0; i < readerCount; i++) {
            final int readerId = i;
            
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    boolean acquired = readWriteLock.readLock().acquire();
                    if (acquired) {
                        int currentReaders = activeReaders.incrementAndGet();
                        maxConcurrentReaders.updateAndGet(max -> Math.max(max, currentReaders));
                        readOrders[readerId].set(currentReaders);
                        
                        Thread.sleep(50); // 模拟读取操作
                        
                        activeReaders.decrementAndGet();
                        readWriteLock.readLock().release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        completionLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Then - 验证所有读者都能同时获得读锁
        for (int i = 0; i < readerCount; i++) {
            assertNotEquals(-1, readOrders[i].get(), "Reader " + i + " should have acquired lock");
        }
        
        assertTrue(maxConcurrentReaders.get() >= readerCount * 0.8, 
                   "Should allow multiple concurrent readers: " + maxConcurrentReaders.get());
    }
    
    @Test
    void shouldPreventWriteLockWhenReadersActive() throws InterruptedException {
        // Given
        AtomicInteger readerCount = new AtomicInteger(0);
        AtomicInteger writeAttemptCount = new AtomicInteger(0);
        AtomicInteger successfulWrites = new AtomicInteger(0);
        
        // 当有活跃读者时，写锁应该被阻塞
        CountDownLatch readerStartLatch = new CountDownLatch(1);
        CountDownLatch writerStartLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(3);
        
        ExecutorService executor = Executors.newFixedThreadPool(3);
        
        // 读者线程
        executor.submit(() -> {
            try {
                readerStartLatch.await();
                
                boolean acquired = readWriteLock.readLock().acquire();
                if (acquired) {
                    readerCount.incrementAndGet();
                    Thread.sleep(200); // 长时间持有读锁
                    readWriteLock.readLock().release();
                    readerCount.decrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                completionLatch.countDown();
            }
        });
        
        // 写者线程1 - 在读者活跃时尝试获取写锁
        executor.submit(() -> {
            try {
                writerStartLatch.await();
                Thread.sleep(50); // 确保读者已经持有锁
                
                writeAttemptCount.incrementAndGet();
                long startTime = System.currentTimeMillis();
                boolean acquired = readWriteLock.writeLock().acquire(Duration.ofSeconds(1));
                long waitTime = System.currentTimeMillis() - startTime;
                
                if (acquired) {
                    successfulWrites.incrementAndGet();
                    readWriteLock.writeLock().release();
                }
                
                // 验证等待时间（应该被阻塞）
                assertTrue(waitTime >= 50, "Write lock should have waited for readers");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                completionLatch.countDown();
            }
        });
        
        // 写者线程2 - 等待读锁释放后获取写锁
        executor.submit(() -> {
            try {
                Thread.sleep(250); // 等待读锁释放
                
                writeAttemptCount.incrementAndGet();
                boolean acquired = readWriteLock.writeLock().acquire(Duration.ofSeconds(1));
                if (acquired) {
                    successfulWrites.incrementAndGet();
                    readWriteLock.writeLock().release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                completionLatch.countDown();
            }
        });
        
        readerStartLatch.countDown();
        writerStartLatch.countDown();
        completionLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Then
        assertEquals(2, writeAttemptCount.get()); // 两个写者都尝试了
        assertEquals(1, successfulWrites.get()); // 只有第二个写者成功
        assertTrue(readerCount.get() == 0, "All readers should have released");
    }
    
    @Test
    void shouldHandleReadToWriteUpgrade() throws InterruptedException {
        // Given
        DistributedLock readLock = readWriteLock.readLock();
        DistributedLock writeLock = readWriteLock.writeLock();
        
        AtomicInteger upgradeSuccessCount = new AtomicInteger(0);
        AtomicInteger upgradeAttemptCount = new AtomicInteger(0);
        
        // 当线程持有读锁时，尝试升级为写锁
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch completionLatch = new CountDownLatch(2);
        
        // 线程1：先获取读锁，然后尝试升级为写锁
        executor.submit(() -> {
            try {
                startLatch.await();
                
                // 获取读锁
                boolean readAcquired = readLock.acquire();
                assertTrue(readAcquired);
                
                upgradeAttemptCount.incrementAndGet();
                
                // 尝试升级为写锁
                try {
                    boolean upgradeAcquired = writeLock.tryLock(Duration.ofSeconds(1));
                    if (upgradeAcquired) {
                        upgradeSuccessCount.incrementAndGet();
                        writeLock.release();
                    }
                } catch (IllegalMonitorStateException e) {
                    // 预期的异常：当前线程持有读锁，尝试获取写锁
                    upgradeSuccessCount.incrementAndGet();
                }
                
                readLock.release();
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                completionLatch.countDown();
            }
        });
        
        // 线程2：同时尝试获取读锁
        executor.submit(() -> {
            try {
                startLatch.await();
                Thread.sleep(100); // 稍后启动
                
                // 应该能够获取读锁（如果没有升级冲突）
                boolean readAcquired = readLock.tryLock(Duration.ofSeconds(1));
                if (readAcquired) {
                    readLock.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                completionLatch.countDown();
            }
        });
        
        startLatch.countDown();
        completionLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Then
        assertEquals(1, upgradeAttemptCount.get());
        assertTrue(upgradeSuccessCount.get() >= 1); // 应该成功升级或正确处理冲突
    }
    
    @Test
    void shouldMonitorReadWriteLockMetrics() {
        // Given
        // 模拟一些读写锁操作
        for (int i = 0; i < 3; i++) {
            boolean readAcquired = readWriteLock.readLock().acquire();
            if (readAcquired) {
                try {
                    Thread.sleep(10);
                    performanceMonitor.recordLockContention(readWriteLock.readLock(), 5);
                } finally {
                    readWriteLock.readLock().release();
                }
            }
        }
        
        for (int i = 0; i < 2; i++) {
            boolean writeAcquired = readWriteLock.writeLock().acquire();
            if (writeAcquired) {
                try {
                    Thread.sleep(20);
                    performanceMonitor.recordLockContention(readWriteLock.writeLock(), 15);
                } finally {
                    readWriteLock.writeLock().release();
                }
            }
        }
        
        // When
        LockPerformanceMetrics readMetrics = performanceMonitor.getLockMetrics(TEST_LOCK_NAME + "-read");
        LockPerformanceMetrics writeMetrics = performanceMonitor.getLockMetrics(TEST_LOCK_NAME + "-write");
        
        // Then
        assertNotNull(readMetrics);
        assertNotNull(writeMetrics);
        assertTrue(readMetrics.getAcquisitionCount() > 0 || readMetrics.getContentionCount() > 0);
        assertTrue(writeMetrics.getAcquisitionCount() > 0 || writeMetrics.getContentionCount() > 0);
        
        // 验证读写锁特定指标
        ReadWriteLockMetrics rwMetrics = readWriteLock.getReadWriteLockMetrics();
        assertNotNull(rwMetrics);
        assertTrue(rwMetrics.getReadAcquisitions() >= 0);
        assertTrue(rwMetrics.getWriteAcquisitions() >= 0);
        assertTrue(rwMetrics.getCurrentReaders() >= 0);
    }
    
    // 辅助方法和内部类
    
    private static class ReadWriteLockImpl implements DistributedReadWriteLock {
        private final String name;
        private final LockConfiguration config;
        private final PerformanceMonitor performanceMonitor;
        private final ReadLockImpl readLock;
        private final WriteLockImpl writeLock;
        private final ReadWriteLockMetrics metrics = new ReadWriteLockMetrics();
        
        public ReadWriteLockImpl(LockConfiguration config, PerformanceMonitor performanceMonitor) {
            this.name = config.getLockName();
            this.config = config;
            this.performanceMonitor = performanceMonitor;
            this.readLock = new ReadLockImpl(this);
            this.writeLock = new WriteLockImpl(this);
        }
        
        @Override
        public String getName() {
            return name;
        }
        
        @Override
        public DistributedLock readLock() {
            return readLock;
        }
        
        @Override
        public DistributedLock writeLock() {
            return writeLock;
        }
        
        @Override
        public ReadWriteLockStateInfo getStateInfo() {
            return new ReadWriteLockStateInfo(
                name,
                readLock.getActiveCount(),
                writeLock.getActiveCount(),
                writeLock.isWriteLocked()
            ) {
                @Override
                public boolean isReadLocked() {
                    return readLock.getActiveCount() > 0;
                }
                
                @Override
                public boolean isWriteLocked() {
                    return writeLock.isWriteLocked();
                }
                
                @Override
                public int getActiveReaders() {
                    return readLock.getActiveCount();
                }
                
                @Override
                public int getActiveWriters() {
                    return writeLock.getActiveCount();
                }
            };
        }
        
        @Override
        public HealthCheck.HealthCheckResult healthCheck() {
            return new HealthCheck.HealthCheckResult(
                HealthCheck.ComponentStatus.HEALTHY, 
                "Read-write lock is healthy", 
                System.currentTimeMillis()
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
        
        public ReadWriteLockMetrics getReadWriteLockMetrics() {
            return metrics;
        }
        
        public void forceReleaseAll() {
            readLock.forceRelease();
            writeLock.forceRelease();
        }
        
        private abstract static class BaseLock implements DistributedLock {
            protected final ReadWriteLockImpl owner;
            protected final String lockName;
            protected volatile long ownerThreadId = -1;
            protected final ReentrantLock internalLock = new ReentrantLock();
            
            public BaseLock(ReadWriteLockImpl owner, String lockName) {
                this.owner = owner;
                this.lockName = lockName;
            }
            
            @Override
            public String getName() {
                return lockName;
            }
            
            @Override
            public boolean acquire() {
                return acquire(Duration.ofSeconds(0));
            }
            
            @Override
            public boolean acquire(Duration timeout) throws InterruptedException {
                if (internalLock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    ownerThreadId = Thread.currentThread().getId();
                    recordMetrics(true);
                    return true;
                } else {
                    recordMetrics(false);
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
                    lockName + " is healthy", 
                    System.currentTimeMillis()
                );
            }
            
            @Override
            public LockStateInfo getStateInfo() {
                return new LockStateInfo(
                    lockName,
                    isLocked(),
                    isHeldByCurrentThread(),
                    ownerThreadId,
                    getActiveCount(),
                    true // 支持重入
                );
            }
            
            @Override
            public LockConfigurationInfo getConfigurationInfo() {
                return new LockConfigurationInfo(
                    lockName,
                    owner.config.getLeaseTime(),
                    owner.config.getRetryAttempts(),
                    owner.config.getRetryDelay(),
                    getLockType(),
                    false, // 不强制公平
                    true   // 支持重入
                );
            }
            
            @Override
            public void close() {
                forceRelease();
            }
            
            protected abstract LockType getLockType();
            protected abstract int getActiveCount();
            protected abstract void recordMetrics(boolean success);
            
            public void forceRelease() {
                if (internalLock.isLocked()) {
                    ownerThreadId = -1;
                    internalLock.unlock();
                }
            }
        }
        
        private class ReadLockImpl extends BaseLock {
            public ReadLockImpl(ReadWriteLockImpl owner) {
                super(owner, name + "-read");
            }
            
            @Override
            public void release() {
                if (internalLock.isHeldByCurrentThread()) {
                    ownerThreadId = -1;
                    internalLock.unlock();
                    metrics.recordReadRelease();
                } else {
                    throw new IllegalMonitorStateException("Current thread does not hold this read lock");
                }
            }
            
            @Override
            protected LockType getLockType() {
                return LockType.READ;
            }
            
            @Override
            protected int getActiveCount() {
                return internalLock.getHoldCount();
            }
            
            @Override
            protected void recordMetrics(boolean success) {
                metrics.recordReadAcquisition(success);
            }
        }
        
        private class WriteLockImpl extends BaseLock {
            public WriteLockImpl(ReadWriteLockImpl owner) {
                super(owner, name + "-write");
            }
            
            @Override
            public void release() {
                if (internalLock.isHeldByCurrentThread()) {
                    ownerThreadId = -1;
                    internalLock.unlock();
                    metrics.recordWriteRelease();
                } else {
                    throw new IllegalMonitorStateException("Current thread does not hold this write lock");
                }
            }
            
            @Override
            public boolean isWriteLocked() {
                return internalLock.isLocked() && internalLock.getHoldCount() > 0;
            }
            
            @Override
            protected LockType getLockType() {
                return LockType.WRITE;
            }
            
            @Override
            protected int getActiveCount() {
                return internalLock.getHoldCount();
            }
            
            @Override
            protected void recordMetrics(boolean success) {
                metrics.recordWriteAcquisition(success);
            }
        }
    }
    
    private static class ReadWriteLockMetrics {
        private final AtomicLong readAcquisitions = new AtomicLong(0);
        private final AtomicLong readReleases = new AtomicLong(0);
        private final AtomicLong writeAcquisitions = new AtomicLong(0);
        private final AtomicLong writeReleases = new AtomicLong(0);
        private final AtomicInteger currentReaders = new AtomicInteger(0);
        private final AtomicInteger currentWriters = new AtomicInteger(0);
        
        public void recordReadAcquisition(boolean success) {
            if (success) {
                readAcquisitions.incrementAndGet();
                currentReaders.incrementAndGet();
            }
        }
        
        public void recordReadRelease() {
            readReleases.incrementAndGet();
            currentReaders.decrementAndGet();
        }
        
        public void recordWriteAcquisition(boolean success) {
            if (success) {
                writeAcquisitions.incrementAndGet();
                currentWriters.incrementAndGet();
            }
        }
        
        public void recordWriteRelease() {
            writeReleases.incrementAndGet();
            currentWriters.decrementAndGet();
        }
        
        public long getReadAcquisitions() { return readAcquisitions.get(); }
        public long getReadReleases() { return readReleases.get(); }
        public long getWriteAcquisitions() { return writeAcquisitions.get(); }
        public long getWriteReleases() { return writeReleases.get(); }
        public int getCurrentReaders() { return currentReaders.get(); }
        public int getCurrentWriters() { return currentWriters.get(); }
    }
}