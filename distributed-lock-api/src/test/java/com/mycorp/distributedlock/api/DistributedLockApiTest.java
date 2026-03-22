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

class DistributedLockApiTest {

    private TestDistributedLock lock;

    @BeforeEach
    void setUp() {
        lock = new TestDistributedLock("test-lock");
    }

    @Test
    void shouldAcquireAndReleaseLock() throws InterruptedException {
        lock.lock(1, TimeUnit.SECONDS);

        assertTrue(lock.isLocked());
        assertTrue(lock.isHeldByCurrentThread());

        lock.unlock();

        assertFalse(lock.isLocked());
    }

    @Test
    void shouldSupportAsyncTryLock() {
        assertTrue(lock.tryLockAsync(0, 1, TimeUnit.SECONDS).join());
        assertTrue(lock.isLocked());
    }

    @Test
    void shouldCancelAutoRenewalTask() {
        ScheduledFuture<?> task = lock.scheduleAutoRenewal(1, TimeUnit.SECONDS, result -> {});

        assertTrue(lock.cancelAutoRenewal(task));
        assertTrue(task.isCancelled());
    }

    @Test
    void shouldNotUnlockHeldLockDuringDefaultHealthCheck() throws InterruptedException {
        lock.lock(1, TimeUnit.SECONDS);

        DistributedLock.HealthCheckResult result = lock.healthCheck();

        assertTrue(result.isHealthy());
        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(0, lock.getUnlockCalls());
    }

    @Test
    void shouldFailSafeDefaultRenewLockWithoutUnlocking() throws InterruptedException {
        lock.lock(1, TimeUnit.SECONDS);

        boolean renewed = lock.renewLock(5, TimeUnit.SECONDS);

        assertFalse(renewed);
        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(0, lock.getUnlockCalls());
        assertEquals(1, lock.getLockCalls());
    }

    @Test
    void shouldCloseByUnlockingCurrentThreadLock() throws InterruptedException {
        lock.lock(1, TimeUnit.SECONDS);

        lock.close();

        assertFalse(lock.isLocked());
        assertEquals(1, lock.getUnlockCalls());
    }

    @Test
    void shouldExposeLockStateInfo() throws InterruptedException {
        lock.lock(2, TimeUnit.SECONDS);

        DistributedLock.LockStateInfo stateInfo = lock.getLockStateInfo();

        assertTrue(stateInfo.isLocked());
        assertTrue(stateInfo.isHeldByCurrentThread());
        assertEquals("main", stateInfo.getHolder());
        assertEquals(DistributedLock.LockType.MUTEX, stateInfo.getLockType());
        assertNotNull(stateInfo.getExpirationTime());
    }

    private static final class TestDistributedLock implements DistributedLock {
        private final String name;
        private boolean locked;
        private Thread owner;
        private Instant expirationTime;
        private int lockCalls;
        private int unlockCalls;

        private TestDistributedLock(String name) {
            this.name = name;
        }

        @Override
        public void lock(long leaseTime, TimeUnit unit) {
            locked = true;
            owner = Thread.currentThread();
            expirationTime = Instant.now().plusMillis(unit.toMillis(leaseTime));
            lockCalls++;
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
            unlockCalls++;
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
            return new CancelledScheduledFuture();
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
                    return TestDistributedLock.this.isHeldByCurrentThread();
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

        int getLockCalls() {
            return lockCalls;
        }

        int getUnlockCalls() {
            return unlockCalls;
        }
    }

    private static final class CancelledScheduledFuture implements ScheduledFuture<Object> {
        private boolean cancelled;

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
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
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
