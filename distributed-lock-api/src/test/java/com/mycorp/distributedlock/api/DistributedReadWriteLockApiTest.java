package com.mycorp.distributedlock.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class DistributedReadWriteLockApiTest {

    private TestReadWriteLock readWriteLock;

    @BeforeEach
    void setUp() {
        readWriteLock = new TestReadWriteLock("test-rw-lock");
    }

    @Test
    void shouldExposeReadAndWriteLocks() {
        assertEquals("test-rw-lock", readWriteLock.getName());
        assertEquals("test-rw-lock:read", readWriteLock.readLock().getName());
        assertEquals("test-rw-lock:write", readWriteLock.writeLock().getName());
    }

    @Test
    void shouldRejectUnsupportedDefaultUpgrade() {
        assertThrows(UnsupportedOperationException.class,
            () -> readWriteLock.tryUpgradeToWriteLock(1, 1, TimeUnit.SECONDS));
    }

    @Test
    void shouldDowngradeWriteLockToReadLock() throws InterruptedException {
        DistributedLock writeLock = readWriteLock.writeLock();
        DistributedLock readLock = readWriteLock.readLock();
        writeLock.lock(1, TimeUnit.SECONDS);

        boolean downgraded = readWriteLock.tryDowngradeToReadLock(1, TimeUnit.SECONDS);

        assertTrue(downgraded);
        assertFalse(writeLock.isLocked());
        assertTrue(readLock.isLocked());
        assertTrue(readLock.isHeldByCurrentThread());
    }

    @Test
    void shouldReportStateInfoFromDefaultImplementation() throws InterruptedException {
        readWriteLock.readLock().lock(1, TimeUnit.SECONDS);

        DistributedReadWriteLock.ReadWriteLockStateInfo stateInfo = readWriteLock.getReadWriteLockStateInfo();

        assertTrue(stateInfo.isReadLocked());
        assertFalse(stateInfo.isWriteLocked());
        assertTrue(stateInfo.isReadLockedByCurrentThread());
        assertEquals(DistributedReadWriteLock.ReadWriteLockType.STANDARD, stateInfo.getLockType());
        assertEquals(DistributedReadWriteLock.LockUpgradeMode.DIRECT, stateInfo.getUpgradeMode());
    }

    @Test
    void shouldPassDefaultHealthCheck() {
        DistributedReadWriteLock.ReadWriteLockHealthCheckResult result = readWriteLock.healthCheck();

        assertTrue(result.isHealthy());
        assertTrue(result.isReadLockHealthy());
        assertTrue(result.isWriteLockHealthy());
    }

    private static final class TestReadWriteLock implements DistributedReadWriteLock {
        private final String name;
        private final TestLock readLock;
        private final TestLock writeLock;

        private TestReadWriteLock(String name) {
            this.name = name;
            this.readLock = new TestLock(name + ":read");
            this.writeLock = new TestLock(name + ":write");
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
        public String getName() {
            return name;
        }
    }

    private static final class TestLock implements DistributedLock {
        private final String name;
        private boolean locked;
        private Thread owner;
        private Instant expirationTime;

        private TestLock(String name) {
            this.name = name;
        }

        @Override
        public void lock(long leaseTime, TimeUnit unit) {
            locked = true;
            owner = Thread.currentThread();
            expirationTime = Instant.now().plusMillis(unit.toMillis(leaseTime));
        }

        @Override
        public void lock() {
            lock(30, TimeUnit.SECONDS);
        }

        @Override
        public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) {
            if (locked && owner != Thread.currentThread()) {
                return false;
            }
            lock(leaseTime, unit);
            return true;
        }

        @Override
        public boolean tryLock() {
            return tryLock(0, 30, TimeUnit.SECONDS);
        }

        @Override
        public void unlock() {
            locked = false;
            owner = null;
            expirationTime = null;
        }

        @Override
        public CompletableFuture<Void> lockAsync(long leaseTime, TimeUnit unit) {
            return CompletableFuture.runAsync(() -> lock(leaseTime, unit));
        }

        @Override
        public CompletableFuture<Void> lockAsync() {
            return CompletableFuture.runAsync(this::lock);
        }

        @Override
        public CompletableFuture<Boolean> tryLockAsync(long waitTime, long leaseTime, TimeUnit unit) {
            return CompletableFuture.supplyAsync(() -> tryLock(waitTime, leaseTime, unit));
        }

        @Override
        public CompletableFuture<Boolean> tryLockAsync() {
            return CompletableFuture.supplyAsync(this::tryLock);
        }

        @Override
        public CompletableFuture<Void> unlockAsync() {
            return CompletableFuture.runAsync(this::unlock);
        }

        @Override
        public boolean isLocked() {
            return locked;
        }

        @Override
        public boolean isHeldByCurrentThread() {
            return locked && owner == Thread.currentThread();
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public ScheduledFuture<?> scheduleAutoRenewal(long renewInterval, TimeUnit unit,
                                                      Consumer<RenewalResult> renewalCallback) {
            return new NoopScheduledFuture();
        }

        @Override
        public LockStateInfo getLockStateInfo() {
            return new LockStateInfo() {
                @Override
                public boolean isLocked() {
                    return locked;
                }

                @Override
                public boolean isHeldByCurrentThread() {
                    return TestLock.this.isHeldByCurrentThread();
                }

                @Override
                public String getHolder() {
                    return owner != null ? owner.getName() : null;
                }

                @Override
                public long getRemainingTime(TimeUnit unit) {
                    if (expirationTime == null) {
                        return 0;
                    }
                    long remainingMillis = Math.max(0, expirationTime.toEpochMilli() - System.currentTimeMillis());
                    return unit.convert(remainingMillis, TimeUnit.MILLISECONDS);
                }

                @Override
                public int getReentrantCount() {
                    return isHeldByCurrentThread() ? 1 : 0;
                }

                @Override
                public Instant getCreationTime() {
                    return Instant.now();
                }

                @Override
                public Instant getExpirationTime() {
                    return expirationTime;
                }

                @Override
                public LockType getLockType() {
                    return LockType.MUTEX;
                }

                @Override
                public String getMetadata() {
                    return "{}";
                }
            };
        }
    }

    private static final class NoopScheduledFuture implements ScheduledFuture<Object> {
        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException {
            return null;
        }
    }
}
