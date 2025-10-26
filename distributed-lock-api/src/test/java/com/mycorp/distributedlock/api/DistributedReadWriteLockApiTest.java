package com.mycorp.distributedlock.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分布式读写锁API接口的完整单元测试
 * 测试读写分离、锁升级降级、公平性控制等高级特性
 */
class DistributedReadWriteLockApiTest {

    private DistributedReadWriteLock readWriteLock;
    private static final String LOCK_NAME = "test-read-write-lock";

    @BeforeEach
    void setUp() {
        readWriteLock = createTestReadWriteLock();
    }

    private DistributedReadWriteLock createTestReadWriteLock() {
        return new DistributedReadWriteLock() {
            private volatile boolean readLocked = false;
            private volatile boolean writeLocked = false;
            private volatile int readLockCount = 0;
            private volatile String writeHolder = null;

            @Override
            public DistributedLock readLock() {
                return new DistributedLock() {
                    @Override
                    public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
                        synchronized (this) {
                            while (writeLocked) {
                                wait();
                            }
                            readLocked = true;
                            readLockCount++;
                        }
                    }

                    @Override
                    public void lock() throws InterruptedException {
                        lock(30, TimeUnit.SECONDS);
                    }

                    @Override
                    public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
                        synchronized (this) {
                            if (writeLocked) {
                                return false;
                            }
                            readLocked = true;
                            readLockCount++;
                            return true;
                        }
                    }

                    @Override
                    public boolean tryLock() throws InterruptedException {
                        return tryLock(0, 30, TimeUnit.SECONDS);
                    }

                    @Override
                    public void unlock() {
                        synchronized (this) {
                            if (readLockCount > 0) {
                                readLockCount--;
                                if (readLockCount == 0) {
                                    readLocked = false;
                                    notifyAll();
                                }
                            }
                        }
                    }

                    @Override
                    public CompletableFuture<Void> lockAsync(long leaseTime, TimeUnit unit) {
                        return CompletableFuture.runAsync(() -> {
                            try {
                                lock(leaseTime, unit);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
                    }

                    @Override
                    public CompletableFuture<Void> lockAsync() {
                        return lockAsync(30, TimeUnit.SECONDS);
                    }

                    @Override
                    public CompletableFuture<Boolean> tryLockAsync(long waitTime, long leaseTime, TimeUnit unit) {
                        return CompletableFuture.supplyAsync(() -> {
                            try {
                                return tryLock(waitTime, leaseTime, unit);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return false;
                            }
                        });
                    }

                    @Override
                    public CompletableFuture<Boolean> tryLockAsync() {
                        return tryLockAsync(0, 30, TimeUnit.SECONDS);
                    }

                    @Override
                    public CompletableFuture<Void> unlockAsync() {
                        return CompletableFuture.runAsync(this::unlock);
                    }

                    @Override
                    public boolean isLocked() {
                        synchronized (this) {
                            return readLocked && readLockCount > 0;
                        }
                    }

                    @Override
                    public boolean isHeldByCurrentThread() {
                        synchronized (this) {
                            return readLocked && readLockCount > 0;
                        }
                    }

                    @Override
                    public String getName() {
                        return LOCK_NAME + ":read";
                    }

                    @Override
                    public ScheduledFuture<?> scheduleAutoRenewal(long renewInterval, TimeUnit unit, Consumer<RenewalResult> renewalCallback) {
                        return null; // 不支持自动续期
                    }
                };
            }

            @Override
            public DistributedLock writeLock() {
                return new DistributedLock() {
                    @Override
                    public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
                        synchronized (this) {
                            while (writeLocked || readLockCount > 0) {
                                wait();
                            }
                            writeLocked = true;
                            writeHolder = Thread.currentThread().getName();
                        }
                    }

                    @Override
                    public void lock() throws InterruptedException {
                        lock(30, TimeUnit.SECONDS);
                    }

                    @Override
                    public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
                        synchronized (this) {
                            if (writeLocked || readLockCount > 0) {
                                return false;
                            }
                            writeLocked = true;
                            writeHolder = Thread.currentThread().getName();
                            return true;
                        }
                    }

                    @Override
                    public boolean tryLock() throws InterruptedException {
                        return tryLock(0, 30, TimeUnit.SECONDS);
                    }

                    @Override
                    public void unlock() {
                        synchronized (this) {
                            if (writeLocked) {
                                writeLocked = false;
                                writeHolder = null;
                                notifyAll();
                            }
                        }
                    }

                    @Override
                    public CompletableFuture<Void> lockAsync(long leaseTime, TimeUnit unit) {
                        return CompletableFuture.runAsync(() -> {
                            try {
                                lock(leaseTime, unit);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
                    }

                    @Override
                    public CompletableFuture<Void> lockAsync() {
                        return lockAsync(30, TimeUnit.SECONDS);
                    }

                    @Override
                    public CompletableFuture<Boolean> tryLockAsync(long waitTime, long leaseTime, TimeUnit unit) {
                        return CompletableFuture.supplyAsync(() -> {
                            try {
                                return tryLock(waitTime, leaseTime, unit);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return false;
                            }
                        });
                    }

                    @Override
                    public CompletableFuture<Boolean> tryLockAsync() {
                        return tryLockAsync(0, 30, TimeUnit.SECONDS);
                    }

                    @Override
                    public CompletableFuture<Void> unlockAsync() {
                        return CompletableFuture.runAsync(this::unlock);
                    }

                    @Override
                    public boolean isLocked() {
                        synchronized (this) {
                            return writeLocked;
                        }
                    }

                    @Override
                    public boolean isHeldByCurrentThread() {
                        synchronized (this) {
                            return writeLocked && writeHolder != null && 
                                   writeHolder.equals(Thread.currentThread().getName());
                        }
                    }

                    @Override
                    public String getName() {
                        return LOCK_NAME + ":write";
                    }

                    @Override
                    public ScheduledFuture<?> scheduleAutoRenewal(long renewInterval, TimeUnit unit, Consumer<RenewalResult> renewalCallback) {
                        return null; // 不支持自动续期
                    }
                };
            }

            @Override
            public String getName() {
                return LOCK_NAME;
            }

            @Override
            public boolean tryUpgradeToWriteLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
                // 默认实现：先释放读锁，再获取写锁
                readLock().unlock();
                return writeLock().tryLock(waitTime, leaseTime, unit);
            }

            @Override
            public CompletableFuture<Boolean> tryUpgradeToWriteLockAsync(long waitTime, long leaseTime, TimeUnit unit) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return tryUpgradeToWriteLock(waitTime, leaseTime, unit);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                });
            }

            @Override
            public boolean tryDowngradeToReadLock(long leaseTime, TimeUnit unit) {
                try {
                    writeLock().unlock();
                    readLock().lock(leaseTime, unit);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public CompletableFuture<Boolean> tryDowngradeToReadLockAsync(long leaseTime, TimeUnit unit) {
                return CompletableFuture.supplyAsync(() -> tryDowngradeToReadLock(leaseTime, unit));
            }

            @Override
            public ReadWriteLockStateInfo getReadWriteLockStateInfo() {
                return new ReadWriteLockStateInfo() {
                    @Override
                    public boolean isReadLocked() {
                        synchronized (this) {
                            return readLocked && readLockCount > 0;
                        }
                    }

                    @Override
                    public boolean isWriteLocked() {
                        synchronized (this) {
                            return writeLocked;
                        }
                    }

                    @Override
                    public boolean isReadLockedByCurrentThread() {
                        synchronized (this) {
                            return readLocked && readLockCount > 0;
                        }
                    }

                    @Override
                    public boolean isWriteLockedByCurrentThread() {
                        synchronized (this) {
                            return writeLocked && writeHolder != null && 
                                   writeHolder.equals(Thread.currentThread().getName());
                        }
                    }

                    @Override
                    public int getReadLockCount() {
                        synchronized (this) {
                            return readLockCount;
                        }
                    }

                    @Override
                    public int getReadLockCountByCurrentThread() {
                        synchronized (this) {
                            return isReadLockedByCurrentThread() ? readLockCount : 0;
                        }
                    }

                    @Override
                    public String getWriteLockHolder() {
                        synchronized (this) {
                            return writeLocked ? writeHolder : null;
                        }
                    }

                    @Override
                    public String getReadLockHolders() {
                        synchronized (this) {
                            return isReadLocked() ? "current-thread" : "none";
                        }
                    }

                    @Override
                    public long getReadLockRemainingTime(TimeUnit unit) {
                        return isReadLocked() ? 30000 : 0;
                    }

                    @Override
                    public long getWriteLockRemainingTime(TimeUnit unit) {
                        return isWriteLocked() ? 30000 : 0;
                    }

                    @Override
                    public boolean isFairMode() {
                        return false;
                    }

                    @Override
                    public LockUpgradeMode getUpgradeMode() {
                        return LockUpgradeMode.DIRECT;
                    }

                    @Override
                    public ReadWriteLockType getLockType() {
                        return ReadWriteLockType.STANDARD;
                    }
                };
            }

            @Override
            public ReadWriteLockConfigurationInfo getConfigurationInfo() {
                return new ReadWriteLockConfigurationInfo() {
                    @Override
                    public boolean isFairLock() {
                        return false;
                    }

                    @Override
                    public boolean isReentrant() {
                        return true;
                    }

                    @Override
                    public long getDefaultReadLockLeaseTime(TimeUnit unit) {
                        return unit.convert(60, TimeUnit.SECONDS);
                    }

                    @Override
                    public long getDefaultWriteLockLeaseTime(TimeUnit unit) {
                        return unit.convert(30, TimeUnit.SECONDS);
                    }

                    @Override
                    public long getDefaultReadLockWaitTime(TimeUnit unit) {
                        return unit.convert(10, TimeUnit.SECONDS);
                    }

                    @Override
                    public long getDefaultWriteLockWaitTime(TimeUnit unit) {
                        return unit.convert(15, TimeUnit.SECONDS);
                    }

                    @Override
                    public boolean isUpgradeSupported() {
                        return true;
                    }

                    @Override
                    public boolean isDowngradeSupported() {
                        return true;
                    }

                    @Override
                    public LockUpgradeMode getUpgradeMode() {
                        return LockUpgradeMode.DIRECT;
                    }

                    @Override
                    public int getMaxReadLockCount() {
                        return Integer.MAX_VALUE;
                    }

                    @Override
                    public boolean isReadLockPreemptionEnabled() {
                        return false;
                    }

                    @Override
                    public boolean isWriteLockPriority() {
                        return true;
                    }
                };
            }

            @Override
            public ReadWriteLockHealthCheckResult healthCheck() {
                try {
                    // 检查读锁
                    boolean readLockHealthy = readLock().tryLock(0, 100, TimeUnit.MILLISECONDS);
                    if (readLockHealthy) {
                        readLock().unlock();
                    }

                    // 检查写锁
                    boolean writeLockHealthy = writeLock().tryLock(0, 100, TimeUnit.MILLISECONDS);
                    if (writeLockHealthy) {
                        writeLock().unlock();
                    }

                    boolean isHealthy = readLockHealthy && writeLockHealthy;
                    
                    return new ReadWriteLockHealthCheckResult() {
                        @Override
                        public boolean isHealthy() {
                            return isHealthy;
                        }

                        @Override
                        public boolean isReadLockHealthy() {
                            return readLockHealthy;
                        }

                        @Override
                        public boolean isWriteLockHealthy() {
                            return writeLockHealthy;
                        }

                        @Override
                        public String getDetails() {
                            if (isHealthy) {
                                return "Both read and write locks are accessible and working normally";
                            } else if (!readLockHealthy && !writeLockHealthy) {
                                return "Both read and write locks are not accessible";
                            } else if (!readLockHealthy) {
                                return "Read lock is not accessible";
                            } else {
                                return "Write lock is not accessible";
                            }
                        }

                        @Override
                        public long getCheckTime() {
                            return System.currentTimeMillis();
                        }
                    };
                } catch (Exception e) {
                    return new ReadWriteLockHealthCheckResult() {
                        @Override
                        public boolean isHealthy() {
                            return false;
                        }

                        @Override
                        public boolean isReadLockHealthy() {
                            return false;
                        }

                        @Override
                        public boolean isWriteLockHealthy() {
                            return false;
                        }

                        @Override
                        public String getDetails() {
                            return "Health check failed: " + e.getMessage();
                        }

                        @Override
                        public long getCheckTime() {
                            return System.currentTimeMillis();
                        }
                    };
                }
            }
        };
    }

    @Test
    void shouldAcquireReadLockSuccessfully() throws InterruptedException {
        DistributedLock readLock = readWriteLock.readLock();
        
        assertDoesNotThrow(() -> readLock.lock(1, TimeUnit.SECONDS));
        assertTrue(readLock.isLocked());
        assertTrue(readWriteLock.hasReadLocks());
        assertFalse(readWriteLock.hasWriteLocks());
    }

    @Test
    void shouldAcquireWriteLockSuccessfully() throws InterruptedException {
        DistributedLock writeLock = readWriteLock.writeLock();
        
        assertDoesNotThrow(() -> writeLock.lock(1, TimeUnit.SECONDS));
        assertTrue(writeLock.isLocked());
        assertFalse(readWriteLock.hasReadLocks());
        assertTrue(readWriteLock.hasWriteLocks());
    }

    @Test
    void shouldPreventWriteLockWhenReadLockHeld() throws InterruptedException {
        DistributedLock readLock = readWriteLock.readLock();
        readLock.lock(1, TimeUnit.SECONDS);
        
        DistributedLock writeLock = readWriteLock.writeLock();
        assertThrows(InterruptedException.class, () -> writeLock.tryLock(100, TimeUnit.MILLISECONDS));
        
        readLock.unlock();
        assertTrue(writeLock.tryLock(0, 1, TimeUnit.SECONDS));
    }

    @Test
    void shouldPreventReadLockWhenWriteLockHeld() throws InterruptedException {
        DistributedLock writeLock = readWriteLock.writeLock();
        writeLock.lock(1, TimeUnit.SECONDS);
        
        DistributedLock readLock = readWriteLock.readLock();
        assertThrows(InterruptedException.class, () -> readLock.tryLock(100, TimeUnit.MILLISECONDS));
        
        writeLock.unlock();
        assertTrue(readLock.tryLock(0, 1, TimeUnit.SECONDS));
    }

    @Test
    void shouldAllowMultipleReadLocks() throws InterruptedException {
        DistributedLock readLock1 = readWriteLock.readLock();
        DistributedLock readLock2 = readWriteLock.readLock();
        
        readLock1.lock(1, TimeUnit.SECONDS);
        assertTrue(readLock1.isLocked());
        
        readLock2.lock(1, TimeUnit.SECONDS);
        assertTrue(readLock2.isLocked());
        
        assertTrue(readWriteLock.hasReadLocks());
        assertFalse(readWriteLock.hasWriteLocks());
        assertTrue(readWriteLock.getReadLockCount() >= 2);
    }

    @Test
    void shouldTryReadLock() throws InterruptedException {
        DistributedLock readLock = readWriteLock.readLock();
        
        assertTrue(readLock.tryLock(0, 1, TimeUnit.SECONDS));
        assertTrue(readLock.isLocked());
        
        readLock.unlock();
        assertFalse(readLock.isLocked());
    }

    @Test
    void shouldTryWriteLock() throws InterruptedException {
        DistributedLock writeLock = readWriteLock.writeLock();
        
        assertTrue(writeLock.tryLock(0, 1, TimeUnit.SECONDS));
        assertTrue(writeLock.isLocked());
        
        writeLock.unlock();
        assertFalse(writeLock.isLocked());
    }

    @Test
    void shouldTryWriteLockFailWhenReadLockHeld() throws InterruptedException {
        DistributedLock readLock = readWriteLock.readLock();
        readLock.lock(1, TimeUnit.SECONDS);
        
        DistributedLock writeLock = readWriteLock.writeLock();
        assertFalse(writeLock.tryLock(0, 100, TimeUnit.MILLISECONDS));
        
        readLock.unlock();
    }

    @Test
    void shouldTryReadLockFailWhenWriteLockHeld() throws InterruptedException {
        DistributedLock writeLock = readWriteLock.writeLock();
        writeLock.lock(1, TimeUnit.SECONDS);
        
        DistributedLock readLock = readWriteLock.readLock();
        assertFalse(readLock.tryLock(0, 100, TimeUnit.MILLISECONDS));
        
        writeLock.unlock();
    }

    @Test
    void shouldUpgradeFromReadToWriteLock() throws InterruptedException {
        DistributedLock readLock = readWriteLock.readLock();
        readLock.lock(1, TimeUnit.SECONDS);
        
        assertTrue(readWriteLock.isUpgradeSupported());
        assertTrue(readWriteLock.tryUpgradeToWriteLock(0, 1, TimeUnit.SECONDS));
        
        assertFalse(readWriteLock.hasReadLocks());
        assertTrue(readWriteLock.hasWriteLocks());
    }

    @Test
    void shouldDowngradeFromWriteToReadLock() {
        assertTrue(readWriteLock.isDowngradeSupported());
        
        boolean result = readWriteLock.tryDowngradeToReadLock(1, TimeUnit.SECONDS);
        assertTrue(result);
    }

    @Test
    void shouldPerformHealthCheck() {
        ReadWriteLockHealthCheckResult result = readWriteLock.healthCheck();
        
        assertTrue(result.isHealthy());
        assertTrue(result.isReadLockHealthy());
        assertTrue(result.isWriteLockHealthy());
        assertNotNull(result.getDetails());
        assertTrue(result.getCheckTime() > 0);
    }

    @Test
    void shouldGetReadWriteLockStateInfo() {
        ReadWriteLockStateInfo stateInfo = readWriteLock.getReadWriteLockStateInfo();
        
        assertNotNull(stateInfo);
        assertFalse(stateInfo.isReadLocked());
        assertFalse(stateInfo.isWriteLocked());
        assertFalse(stateInfo.isReadLockedByCurrentThread());
        assertFalse(stateInfo.isWriteLockedByCurrentThread());
        assertEquals(0, stateInfo.getReadLockCount());
        assertEquals(0, stateInfo.getReadLockCountByCurrentThread());
        assertNull(stateInfo.getWriteLockHolder());
        assertEquals("none", stateInfo.getReadLockHolders());
        assertEquals(0, stateInfo.getReadLockRemainingTime(TimeUnit.MILLISECONDS));
        assertEquals(0, stateInfo.getWriteLockRemainingTime(TimeUnit.MILLISECONDS));
        assertFalse(stateInfo.isFairMode());
        assertEquals(LockUpgradeMode.DIRECT, stateInfo.getUpgradeMode());
        assertEquals(ReadWriteLockType.STANDARD, stateInfo.getLockType());
    }

    @Test
    void shouldGetReadWriteLockStateInfoWhenLocked() throws InterruptedException {
        DistributedLock readLock = readWriteLock.readLock();
        readLock.lock(1, TimeUnit.SECONDS);
        
        ReadWriteLockStateInfo stateInfo = readWriteLock.getReadWriteLockStateInfo();
        
        assertTrue(stateInfo.isReadLocked());
        assertFalse(stateInfo.isWriteLocked());
        assertTrue(stateInfo.isReadLockedByCurrentThread());
        assertFalse(stateInfo.isWriteLockedByCurrentThread());
        assertTrue(stateInfo.getReadLockCount() > 0);
        assertTrue(stateInfo.getReadLockCountByCurrentThread() > 0);
        assertNull(stateInfo.getWriteLockHolder());
        assertEquals("current-thread", stateInfo.getReadLockHolders());
        assertTrue(stateInfo.getReadLockRemainingTime(TimeUnit.MILLISECONDS) > 0);
    }

    @Test
    void shouldGetReadWriteLockConfigurationInfo() {
        ReadWriteLockConfigurationInfo configInfo = readWriteLock.getConfigurationInfo();
        
        assertNotNull(configInfo);
        assertFalse(configInfo.isFairLock());
        assertTrue(configInfo.isReentrant());
        assertEquals(60, configInfo.getDefaultReadLockLeaseTime(TimeUnit.SECONDS));
        assertEquals(30, configInfo.getDefaultWriteLockLeaseTime(TimeUnit.SECONDS));
        assertEquals(10, configInfo.getDefaultReadLockWaitTime(TimeUnit.SECONDS));
        assertEquals(15, configInfo.getDefaultWriteLockWaitTime(TimeUnit.SECONDS));
        assertTrue(configInfo.isUpgradeSupported());
        assertTrue(configInfo.isDowngradeSupported());
        assertEquals(LockUpgradeMode.DIRECT, configInfo.getUpgradeMode());
        assertEquals(Integer.MAX_VALUE, configInfo.getMaxReadLockCount());
        assertFalse(configInfo.isReadLockPreemptionEnabled());
        assertTrue(configInfo.isWriteLockPriority());
    }

    @Test
    void shouldGetReadLockCount() throws InterruptedException {
        assertEquals(0, readWriteLock.getReadLockCount());
        
        DistributedLock readLock = readWriteLock.readLock();
        readLock.lock(1, TimeUnit.SECONDS);
        
        assertTrue(readWriteLock.getReadLockCount() > 0);
    }

    @Test
    void shouldCheckIfHasReadLocks() throws InterruptedException {
        assertFalse(readWriteLock.hasReadLocks());
        
        DistributedLock readLock = readWriteLock.readLock();
        readLock.lock(1, TimeUnit.SECONDS);
        
        assertTrue(readWriteLock.hasReadLocks());
    }

    @Test
    void shouldCheckIfHasWriteLocks() throws InterruptedException {
        assertFalse(readWriteLock.hasWriteLocks());
        
        DistributedLock writeLock = readWriteLock.writeLock();
        writeLock.lock(1, TimeUnit.SECONDS);
        
        assertTrue(readWriteLock.hasWriteLocks());
    }

    @Test
    void shouldCheckIfUpgradeSupported() {
        assertTrue(readWriteLock.isUpgradeSupported());
    }

    @Test
    void shouldCheckIfDowngradeSupported() {
        assertTrue(readWriteLock.isDowngradeSupported());
    }

    @Test
    void shouldExecuteWithAppropriateLock() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        
        // 测试在没有锁的情况下执行读操作
        Integer result1 = readWriteLock.executeWithAppropriateLock(
            () -> {
                counter.incrementAndGet();
                return 1;
            },
            () -> {
                counter.addAndGet(10);
                return 10;
            }
        );
        assertEquals(1, result1);
        
        // 测试在读锁下执行
        DistributedLock readLock = readWriteLock.readLock();
        readLock.lock(1, TimeUnit.SECONDS);
        
        Integer result2 = readWriteLock.executeWithAppropriateLock(
            () -> {
                counter.incrementAndGet();
                return 2;
            },
            () -> {
                counter.addAndGet(20);
                return 20;
            }
        );
        assertEquals(2, result2);
    }

    @Test
    void shouldHandleAutoCloseable() throws InterruptedException {
        try (DistributedReadWriteLock rwLock = readWriteLock) {
            DistributedLock readLock = rwLock.readLock();
            readLock.lock(1, TimeUnit.SECONDS);
            assertTrue(readLock.isHeldByCurrentThread());
        }
        // 所有锁应该被自动释放
        assertFalse(readWriteLock.hasReadLocks());
        assertFalse(readWriteLock.hasWriteLocks());
    }

    @Test
    void shouldHandleReadLockAsync() throws InterruptedException {
        DistributedLock readLock = readWriteLock.readLock();
        CompletableFuture<Void> future = readLock.lockAsync(1, TimeUnit.SECONDS);
        
        future.join();
        assertTrue(readLock.isLocked());
        assertTrue(readWriteLock.hasReadLocks());
    }

    @Test
    void shouldHandleWriteLockAsync() throws InterruptedException {
        DistributedLock writeLock = readWriteLock.writeLock();
        CompletableFuture<Void> future = writeLock.lockAsync(1, TimeUnit.SECONDS);
        
        future.join();
        assertTrue(writeLock.isLocked());
        assertTrue(readWriteLock.hasWriteLocks());
    }

    @Test
    void shouldHandleTryReadLockAsync() {
        DistributedLock readLock = readWriteLock.readLock();
        CompletableFuture<Boolean> future = readLock.tryLockAsync(0, 1, TimeUnit.SECONDS);
        
        Boolean result = future.join();
        assertTrue(result);
        assertTrue(readLock.isLocked());
    }

    @Test
    void shouldHandleTryWriteLockAsync() {
        DistributedLock writeLock = readWriteLock.writeLock();
        CompletableFuture<Boolean> future = writeLock.tryLockAsync(0, 1, TimeUnit.SECONDS);
        
        Boolean result = future.join();
        assertTrue(result);
        assertTrue(writeLock.isLocked());
    }

    @Test
    void shouldHandleUnlockAsync() throws InterruptedException {
        DistributedLock readLock = readWriteLock.readLock();
        readLock.lock(1, TimeUnit.SECONDS);
        
        CompletableFuture<Void> future = readLock.unlockAsync();
        future.join();
        
        assertFalse(readLock.isLocked());
        assertFalse(readWriteLock.hasReadLocks());
    }

    @Test
    void shouldValidateLockUpgradeAsync() throws InterruptedException {
        DistributedLock readLock = readWriteLock.readLock();
        readLock.lock(1, TimeUnit.SECONDS);
        
        CompletableFuture<Boolean> upgradeFuture = 
            readWriteLock.tryUpgradeToWriteLockAsync(0, 1, TimeUnit.SECONDS);
        
        Boolean upgraded = upgradeFuture.join();
        assertTrue(upgraded);
        assertFalse(readWriteLock.hasReadLocks());
        assertTrue(readWriteLock.hasWriteLocks());
    }

    @Test
    void shouldValidateLockDowngradeAsync() {
        CompletableFuture<Boolean> downgradeFuture = 
            readWriteLock.tryDowngradeToReadLockAsync(1, TimeUnit.SECONDS);
        
        Boolean downgraded = downgradeFuture.join();
        assertTrue(downgraded);
    }
}