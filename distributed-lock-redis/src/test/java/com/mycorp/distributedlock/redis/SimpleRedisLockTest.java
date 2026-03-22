package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.DistributedLock;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimpleRedisLockTest {

    private static final String LOCK_KEY = "test-lock";
    private static final long DEFAULT_LEASE_TIME = 30L;

    @Mock
    private RedisCommands<String, String> mockCommands;

    private SimpleRedisLock lock;

    @BeforeEach
    void setUp() {
        lock = new SimpleRedisLock(LOCK_KEY, mockCommands, DEFAULT_LEASE_TIME);
    }

    @Test
    void shouldAcquireAndReleaseLock() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");
        when(mockCommands.eval(anyString(), any(), any(String[].class), anyString())).thenReturn(1L);

        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));
        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(1, lock.getReentrantCount());

        lock.unlock();

        assertFalse(lock.isHeldByCurrentThread());
        assertFalse(lock.isLocked());
    }

    @Test
    void shouldGenerateNewTokenForEachSuccessfulAcquisitionLifecycle() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK", "OK");
        when(mockCommands.eval(anyString(), any(), any(String[].class), anyString())).thenReturn(1L, 1L);

        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));
        lock.unlock();
        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockCommands, times(2)).set(eq(LOCK_KEY), tokenCaptor.capture(), any());

        List<String> tokens = tokenCaptor.getAllValues();
        assertEquals(2, tokens.size());
        assertNotEquals(tokens.get(0), tokens.get(1));

        lock.unlock();
    }

    @Test
    void shouldRejectUnlockFromDifferentThread() throws Exception {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");
        when(mockCommands.eval(anyString(), any(), any(String[].class), anyString())).thenReturn(1L);

        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread otherThread = new Thread(() -> {
            try {
                lock.unlock();
            } catch (Throwable throwable) {
                thrown.set(throwable);
            }
        });

        otherThread.start();
        otherThread.join();

        assertNotNull(thrown.get());
        assertInstanceOf(IllegalMonitorStateException.class, thrown.get());
        assertTrue(lock.isHeldByCurrentThread());

        lock.unlock();
    }

    @Test
    void shouldRejectUnlockWhenRedisOwnerTokenDoesNotMatch() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");
        when(mockCommands.eval(anyString(), any(), any(String[].class), anyString())).thenReturn(0L);

        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));

        assertThrows(IllegalMonitorStateException.class, lock::unlock);
        assertFalse(lock.isHeldByCurrentThread());
        assertFalse(lock.isLocked());
    }

    @Test
    void shouldReportLockedWhenRedisKeyExistsForAnotherOwner() {
        when(mockCommands.exists(LOCK_KEY)).thenReturn(1L);

        assertTrue(lock.isLocked());
        assertFalse(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldTreatRemoteOwnerAsHealthyDuringHealthCheck() {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn(null);
        when(mockCommands.exists(LOCK_KEY)).thenReturn(1L);

        DistributedLock.HealthCheckResult result = lock.healthCheck();

        assertTrue(result.isHealthy());
        assertTrue(result.getDetails().contains("another owner"));
    }

    @Test
    void shouldFailRenewalWhenRedisOwnerTokenDoesNotMatchWithoutReacquiring() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK", "OK");
        when(mockCommands.eval(anyString(), any(), any(String[].class), anyString(), eq("45"))).thenReturn(0L);

        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));

        assertFalse(lock.renewLock(45, TimeUnit.SECONDS));
        assertFalse(lock.isHeldByCurrentThread());
        assertFalse(lock.isLocked());
        verify(mockCommands, times(1)).set(eq(LOCK_KEY), anyString(), any());
    }

    @Test
    void shouldAutoRenewUsingOwnerTokenFromSchedulerThread() throws Exception {
        CountDownLatch renewed = new CountDownLatch(1);

        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");
        when(mockCommands.eval(anyString(), any(), any(String[].class), anyString(), eq(String.valueOf(DEFAULT_LEASE_TIME))))
                .thenReturn(1L);
        when(mockCommands.eval(anyString(), any(), any(String[].class), anyString())).thenReturn(1L);

        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));

        ScheduledFuture<?> renewalTask = lock.scheduleAutoRenewal(5, TimeUnit.MILLISECONDS, result -> {
            if (result.isSuccess()) {
                renewed.countDown();
            }
        });

        try {
            assertTrue(renewed.await(500, TimeUnit.MILLISECONDS));
        } finally {
            renewalTask.cancel(false);
            lock.unlock();
        }
    }
}
