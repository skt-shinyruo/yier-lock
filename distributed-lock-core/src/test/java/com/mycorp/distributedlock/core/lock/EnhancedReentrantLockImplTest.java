package com.mycorp.distributedlock.core.lock;

import com.mycorp.distributedlock.api.DistributedLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EnhancedReentrantLockImplTest {

    private EnhancedReentrantLockImpl reentrantLock;

    @BeforeEach
    void setUp() {
        reentrantLock = new EnhancedReentrantLockImpl("test-reentrant-lock");
    }

    @Test
    void shouldSupportSingleArgumentConstructor() {
        assertEquals("test-reentrant-lock", reentrantLock.getName());
    }

    @Test
    void shouldTrackReentrantCount() throws InterruptedException {
        assertTrue(reentrantLock.tryLock(0, 1, TimeUnit.SECONDS));
        assertEquals(1, reentrantLock.getReentrantCount());

        assertTrue(reentrantLock.tryLock(0, 1, TimeUnit.SECONDS));
        assertEquals(2, reentrantLock.getReentrantCount());
    }

    @Test
    void shouldKeepLockHeldUntilFinalUnlock() throws InterruptedException {
        reentrantLock.tryLock(0, 1, TimeUnit.SECONDS);
        reentrantLock.tryLock(0, 1, TimeUnit.SECONDS);

        reentrantLock.unlock();

        assertTrue(reentrantLock.isLocked());
        assertTrue(reentrantLock.isHeldByCurrentThread());
        assertEquals(1, reentrantLock.getReentrantCount());

        reentrantLock.unlock();

        assertFalse(reentrantLock.isLocked());
        assertEquals(0, reentrantLock.getReentrantCount());
    }

    @Test
    void shouldRenewHeldLockByExtendingExpiration() throws Exception {
        reentrantLock.tryLock(0, 50, TimeUnit.MILLISECONDS);
        long beforeRenew = reentrantLock.getRemainingTime(TimeUnit.MILLISECONDS);

        Thread.sleep(20);
        assertTrue(reentrantLock.renewLock(200, TimeUnit.MILLISECONDS));

        long afterRenew = reentrantLock.getRemainingTime(TimeUnit.MILLISECONDS);
        assertTrue(afterRenew > beforeRenew);
    }

    @Test
    void shouldReturnFalseWhenRenewingUnlockedLock() {
        assertFalse(reentrantLock.renewLock(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void shouldExposeLockStateInfo() throws InterruptedException {
        reentrantLock.tryLock(0, 1, TimeUnit.SECONDS);

        DistributedLock.LockStateInfo stateInfo = reentrantLock.getLockStateInfo();

        assertTrue(stateInfo.isLocked());
        assertTrue(stateInfo.isHeldByCurrentThread());
        assertEquals(Thread.currentThread().getName(), stateInfo.getHolder());
        assertEquals(DistributedLock.LockType.REENTRANT, stateInfo.getLockType());
        assertNotNull(stateInfo.getExpirationTime());
    }

    @Test
    void shouldScheduleAutoRenewal() throws Exception {
        reentrantLock.tryLock(0, 50, TimeUnit.MILLISECONDS);

        ScheduledFuture<?> renewalTask = reentrantLock.scheduleAutoRenewal(10, TimeUnit.MILLISECONDS);
        Thread.sleep(80);

        assertFalse(reentrantLock.isExpired());
        assertTrue(reentrantLock.cancelAutoRenewal(renewalTask));
    }
}
