package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.DistributedLock;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SimpleRedisLock单元测试
 * 测试Redis分布式锁的所有功能
 */
@ExtendWith(MockitoExtension.class)
class SimpleRedisLockTest {

    @Mock
    private RedisCommands<String, String> mockCommands;

    private SimpleRedisLock lock;
    private static final String LOCK_KEY = "test-lock";
    private static final long DEFAULT_LEASE_TIME = 30L;
    private static final String EXPECTED_LOCK_VALUE = "1:"; // 线程ID + 冒号 + 时间戳前缀

    @BeforeEach
    void setUp() {
        lock = new SimpleRedisLock(LOCK_KEY, mockCommands, DEFAULT_LEASE_TIME);
    }

    @Test
    void shouldCreateLockSuccessfully() {
        assertNotNull(lock);
        assertEquals(LOCK_KEY, lock.getName());
        assertFalse(lock.isLocked());
        assertFalse(lock.isHeldByCurrentThread());
        assertEquals(0, lock.getReentrantCount());
    }

    @Test
    void shouldAcquireLockWithTryLock() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");

        boolean acquired = lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS);

        assertTrue(acquired);
        assertTrue(lock.isLocked());
        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(1, lock.getReentrantCount());
        verify(mockCommands).set(eq(LOCK_KEY), anyString(), any());
    }

    @Test
    void shouldFailToAcquireLockWhenRedisReturnsNull() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn(null);

        boolean acquired = lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS);

        assertFalse(acquired);
        assertFalse(lock.isLocked());
        assertFalse(lock.isHeldByCurrentThread());
        assertEquals(0, lock.getReentrantCount());
        verify(mockCommands).set(eq(LOCK_KEY), anyString(), any());
    }

    @Test
    void shouldHandleRedisException() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenThrow(new RuntimeException("Redis connection error"));

        boolean acquired = lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS);

        assertFalse(acquired);
        assertFalse(lock.isLocked());
        verify(mockCommands).set(eq(LOCK_KEY), anyString(), any());
    }

    @Test
    void shouldHandleReentrantLock() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");

        // 第一次获取
        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));
        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(1, lock.getReentrantCount());

        // 第二次重入获取
        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));
        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(2, lock.getReentrantCount());

        verify(mockCommands, times(1)).set(eq(LOCK_KEY), anyString(), any()); // 只调用一次，因为第二次是重入
    }

    @Test
    void shouldHandleReentrantUnlock() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");
        when(mockCommands.eval(anyString(), any(), any())).thenReturn(1L);

        // 获取锁并重入
        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));
        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));
        assertEquals(2, lock.getReentrantCount());

        // 第一次解锁（应该不释放锁）
        lock.unlock();
        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(1, lock.getReentrantCount());
        verify(mockCommands, never()).eval(anyString(), any(), any()); // 还没完全释放

        // 第二次解锁（应该释放锁）
        lock.unlock();
        assertFalse(lock.isHeldByCurrentThread());
        assertEquals(0, lock.getReentrantCount());
        verify(mockCommands).eval(anyString(), any(), any()); // 释放锁
    }

    @Test
    void shouldThrowExceptionWhenUnlockWithoutHoldingLock() {
        assertThrows(IllegalMonitorStateException.class, () -> {
            lock.unlock();
        });
    }

    @Test
    void shouldHandleLockOperation() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");
        when(mockCommands.eval(anyString(), any(), any())).thenReturn(1L);

        // 使用lock()方法
        lock.lock(DEFAULT_LEASE_TIME, TimeUnit.SECONDS);
        assertTrue(lock.isHeldByCurrentThread());

        lock.unlock();
        assertFalse(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldHandleLockWithDefaultTime() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");
        when(mockCommands.eval(anyString(), any(), any())).thenReturn(1L);

        lock.lock();
        assertTrue(lock.isHeldByCurrentThread());

        lock.unlock();
        assertFalse(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldHandleAsyncLockOperations() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");
        when(mockCommands.eval(anyString(), any(), any())).thenReturn(1L);

        // 异步锁获取
        CompletableFuture<Void> lockFuture = lock.lockAsync(DEFAULT_LEASE_TIME, TimeUnit.SECONDS);
        lockFuture.join();
        assertTrue(lock.isHeldByCurrentThread());

        // 异步解锁
        CompletableFuture<Void> unlockFuture = lock.unlockAsync();
        unlockFuture.join();
        assertFalse(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldHandleAsyncTryLock() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");

        CompletableFuture<Boolean> tryLockFuture = lock.tryLockAsync(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS);
        Boolean acquired = tryLockFuture.join();

        assertTrue(acquired);
        assertTrue(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldHandleAutoRenewal() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any(io.lettuce.core.SetArgs.Builder.xx().ex(DEFAULT_LEASE_TIME)))).thenReturn("OK");

        // 获取锁
        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));

        // 安排自动续期
        ScheduledFuture<?> renewalTask = lock.scheduleAutoRenewal(5, TimeUnit.SECONDS);
        assertNotNull(renewalTask);
        assertFalse(renewalTask.isDone());

        // 等待续期任务执行一次
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 验证续期逻辑被调用
        verify(mockCommands).set(eq(LOCK_KEY), anyString(), any(io.lettuce.core.SetArgs.Builder.xx().ex(DEFAULT_LEASE_TIME)));

        lock.unlock();
    }

    @Test
    void shouldHandleAutoRenewalCancellation() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");

        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));

        ScheduledFuture<?> renewalTask = lock.scheduleAutoRenewal(5, TimeUnit.SECONDS);
        assertTrue(lock.cancelAutoRenewal(renewalTask));

        lock.unlock();
    }

    @Test
    void shouldNotScheduleAutoRenewalWhenNotHoldingLock() {
        assertThrows(IllegalMonitorStateException.class, () -> {
            lock.scheduleAutoRenewal(5, TimeUnit.SECONDS);
        });
    }

    @Test
    void shouldNotScheduleAutoRenewalWithNegativeInterval() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");

        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));

        assertThrows(IllegalArgumentException.class, () -> {
            lock.scheduleAutoRenewal(-1, TimeUnit.SECONDS);
        });

        lock.unlock();
    }

    @Test
    void shouldCheckExpiration() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");
        when(mockCommands.ttl(LOCK_KEY)).thenReturn(25L);

        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));
        assertFalse(lock.isExpired()); // 还有25秒剩余

        when(mockCommands.ttl(LOCK_KEY)).thenReturn(-1L);
        assertTrue(lock.isExpired()); // 已过期

        lock.unlock();
    }

    @Test
    void shouldHandleExpirationCheckWithException() {
        when(mockCommands.ttl(LOCK_KEY)).thenThrow(new RuntimeException("Redis error"));

        // 应该不抛出异常，返回false
        assertFalse(lock.isExpired());
    }

    @Test
    void shouldGetRemainingTime() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");
        when(mockCommands.ttl(LOCK_KEY)).thenReturn(25L);

        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));

        long remainingSeconds = lock.getRemainingTime(TimeUnit.SECONDS);
        assertEquals(25, remainingSeconds);

        long remainingMillis = lock.getRemainingTime(TimeUnit.MILLISECONDS);
        assertEquals(25000, remainingMillis);

        lock.unlock();
    }

    @Test
    void shouldHandleGetRemainingTimeWhenExpired() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");
        when(mockCommands.ttl(LOCK_KEY)).thenReturn(-1L);

        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));

        long remaining = lock.getRemainingTime(TimeUnit.SECONDS);
        assertEquals(0, remaining);

        lock.unlock();
    }

    @Test
    void shouldGetLockHolder() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");
        when(mockCommands.eval(anyString(), any(), any())).thenReturn(1L);

        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));

        String holder = lock.getLockHolder();
        assertNotNull(holder);
        assertTrue(holder.contains("main") || holder.contains("Test"));

        lock.unlock();
    }

    @Test
    void shouldReturnNullForLockHolderWhenNotHeld() {
        String holder = lock.getLockHolder();
        assertNull(holder);
    }

    @Test
    void shouldHandleLockWithTimeout() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");

        boolean acquired = lock.tryLock(100, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);

        assertTrue(acquired);
        verify(mockCommands).set(eq(LOCK_KEY), anyString(), any());

        lock.unlock();
    }

    @Test
    void shouldHandleLockTimeoutExpiration() throws InterruptedException {
        // 模拟超时情况（第二次调用返回null）
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any()))
            .thenReturn(null); // 模拟锁已被占用

        long startTime = System.currentTimeMillis();
        boolean acquired = lock.tryLock(100, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        long elapsedTime = System.currentTimeMillis() - startTime;

        assertFalse(acquired);
        assertTrue(elapsedTime >= 100); // 应该等待了约100ms

        // 验证重试逻辑执行了多次
        verify(mockCommands, atLeast(1)).set(eq(LOCK_KEY), anyString(), any());
    }

    @Test
    void shouldHandleConcurrentLockAttempts() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");

        // 当前线程获取锁
        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));

        // 在另一个线程中尝试获取锁
        AtomicInteger threadResult = new AtomicInteger(0);
        Thread otherThread = new Thread(() -> {
            try {
                SimpleRedisLock otherLock = new SimpleRedisLock(LOCK_KEY, mockCommands, DEFAULT_LEASE_TIME);
                if (!otherLock.tryLock(0, 100, TimeUnit.MILLISECONDS)) {
                    threadResult.set(1); // 获取失败
                } else {
                    threadResult.set(2); // 获取成功
                    otherLock.unlock();
                }
            } catch (InterruptedException e) {
                threadResult.set(3);
                Thread.currentThread().interrupt();
            }
        });

        otherThread.start();
        otherThread.join();

        assertEquals(1, threadResult.get()); // 其他线程应该无法获取锁
        assertTrue(lock.isHeldByCurrentThread());

        lock.unlock();
    }

    @Test
    void shouldHandleLockRenewalOnSchedule() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any(io.lettuce.core.SetArgs.Builder.xx().ex(DEFAULT_LEASE_TIME)))).thenReturn("OK");

        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));

        // 安排续期任务
        ScheduledFuture<?> renewalTask = lock.scheduleAutoRenewal(1, TimeUnit.SECONDS);

        // 等待续期任务执行
        Thread.sleep(1500);

        // 验证续期被调用
        verify(mockCommands).set(eq(LOCK_KEY), anyString(), any(io.lettuce.core.SetArgs.Builder.xx().ex(DEFAULT_LEASE_TIME)));

        renewalTask.cancel(false);
        lock.unlock();
    }

    @Test
    void shouldHandleRenewalFailure() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any(io.lettuce.core.SetArgs.Builder.xx().ex(DEFAULT_LEASE_TIME)))).thenReturn(null); // 续期失败

        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));

        // 测试续期回调
        AtomicInteger callbackInvoked = new AtomicInteger(0);
        DistributedLock.RenewalResult[] lastResult = new DistributedLock.RenewalResult[1];

        ScheduledFuture<?> renewalTask = lock.scheduleAutoRenewal(1, TimeUnit.SECONDS, result -> {
            callbackInvoked.incrementAndGet();
            lastResult[0] = result;
        });

        Thread.sleep(1500);

        // 验证续期失败回调被调用
        assertTrue(callbackInvoked.get() > 0);
        assertNotNull(lastResult[0]);
        assertFalse(lastResult[0].isSuccess());
        assertNotNull(lastResult[0].getFailureCause());

        renewalTask.cancel(false);
        lock.unlock();
    }

    @Test
    void shouldCancelRenewalWhenLockReleased() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any(io.lettuce.core.SetArgs.Builder.xx().ex(DEFAULT_LEASE_TIME)))).thenReturn("OK");

        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));

        ScheduledFuture<?> renewalTask = lock.scheduleAutoRenewal(1, TimeUnit.SECONDS);

        // 释放锁应该自动取消续期任务
        lock.unlock();
        assertTrue(renewalTask.isCancelled());
    }

    @Test
    void shouldHandleAutoCloseable() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");
        when(mockCommands.eval(anyString(), any(), any())).thenReturn(1L);

        try (SimpleRedisLock autoCloseableLock = new SimpleRedisLock(LOCK_KEY, mockCommands, DEFAULT_LEASE_TIME)) {
            autoCloseableLock.lock(DEFAULT_LEASE_TIME, TimeUnit.SECONDS);
            assertTrue(autoCloseableLock.isHeldByCurrentThread());
        } // 自动调用unlock()

        assertFalse(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldValidateLockStateConsistency() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");
        when(mockCommands.eval(anyString(), any(), any())).thenReturn(1L);

        // 初始状态
        assertFalse(lock.isLocked());
        assertFalse(lock.isHeldByCurrentThread());

        // 获取锁后
        assertTrue(lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));
        assertTrue(lock.isLocked());
        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(1, lock.getReentrantCount());

        // 释放锁后
        lock.unlock();
        assertFalse(lock.isLocked());
        assertFalse(lock.isHeldByCurrentThread());
        assertEquals(0, lock.getReentrantCount());
    }

    @Test
    void shouldHandleZeroTimeout() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");

        boolean acquired = lock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS);

        assertTrue(acquired);
        lock.unlock();
    }

    @Test
    void shouldHandleNegativeTimeout() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenReturn("OK");

        // 负超时应该被视为0超时
        boolean acquired = lock.tryLock(-1, DEFAULT_LEASE_TIME, TimeUnit.SECONDS);

        assertTrue(acquired);
        lock.unlock();
    }

    @Test
    void shouldHandleInterruptedExceptionInLock() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenThrow(new InterruptedException("Thread interrupted"));

        assertThrows(InterruptedException.class, () -> {
            lock.tryLock(100, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        });

        assertFalse(lock.isLocked());
    }

    @Test
    void shouldHandleInterruptedExceptionInAsyncOperations() throws InterruptedException {
        when(mockCommands.set(eq(LOCK_KEY), anyString(), any())).thenThrow(new InterruptedException("Thread interrupted"));

        CompletableFuture<Void> future = lock.lockAsync(DEFAULT_LEASE_TIME, TimeUnit.SECONDS);

        // 异步操作应该处理中断异常
        assertDoesNotThrow(() -> {
            try {
                future.join();
            } catch (Exception e) {
                // 预期的异常
                assertTrue(e.getCause() instanceof RuntimeException);
                assertTrue(e.getCause().getMessage().contains("interrupted"));
            }
        });
    }

    @Test
    void shouldValidateLockName() {
        assertEquals(LOCK_KEY, lock.getName());
    }

    @Test
    void shouldHandleMultipleLockInstances() throws InterruptedException {
        when(mockCommands.set(anyString(), anyString(), any())).thenReturn("OK");
        when(mockCommands.eval(anyString(), any(), any())).thenReturn(1L);

        // 创建多个锁实例（不同的key）
        SimpleRedisLock lock1 = new SimpleRedisLock("lock-1", mockCommands, DEFAULT_LEASE_TIME);
        SimpleRedisLock lock2 = new SimpleRedisLock("lock-2", mockCommands, DEFAULT_LEASE_TIME);

        assertTrue(lock1.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));
        assertTrue(lock2.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.SECONDS));

        assertTrue(lock1.isHeldByCurrentThread());
        assertTrue(lock2.isHeldByCurrentThread());

        lock1.unlock();
        lock2.unlock();
    }
}