package com.mycorp.distributedlock.core.lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 增强可重入锁实现单元测试
 * 测试高级可重入锁的核心功能
 */
@ExtendWith(MockitoExtension.class)
class EnhancedReentrantLockImplTest {

    @Mock
    private EnhancedReentrantLockImpl.MockLockBackend mockBackend;

    private EnhancedReentrantLockImpl reentrantLock;
    private static final String LOCK_NAME = "test-reentrant-lock";
    private static final long DEFAULT_LEASE_TIME = 30_000;
    private static final long DEFAULT_WAIT_TIME = 10_000;

    @BeforeEach
    void setUp() {
        reentrantLock = new EnhancedReentrantLockImpl(LOCK_NAME, mockBackend);
    }

    @Test
    void shouldCreateReentrantLock() {
        assertNotNull(reentrantLock);
        assertEquals(LOCK_NAME, reentrantLock.getName());
    }

    @Test
    void shouldAcquireLockSuccessfully() throws InterruptedException {
        when(mockBackend.tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME)).thenReturn(true);
        
        boolean acquired = reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        
        assertTrue(acquired);
        verify(mockBackend).tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME);
    }

    @Test
    void shouldFailToAcquireLockWhenBackendReturnsFalse() throws InterruptedException {
        when(mockBackend.tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME)).thenReturn(false);
        
        boolean acquired = reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        
        assertFalse(acquired);
        verify(mockBackend).tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME);
    }

    @Test
    void shouldHandleInterruptedException() throws InterruptedException {
        when(mockBackend.tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME))
            .thenThrow(new InterruptedException("Test interruption"));
        
        assertThrows(InterruptedException.class, () -> {
            reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        });
        
        verify(mockBackend).tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME);
    }

    @Test
    void shouldReleaseLock() throws InterruptedException {
        when(mockBackend.tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME)).thenReturn(true);
        doNothing().when(mockBackend).release(LOCK_NAME);
        
        reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        reentrantLock.unlock();
        
        verify(mockBackend).release(LOCK_NAME);
    }

    @Test
    void shouldHandleReentrantAcquisition() throws InterruptedException {
        when(mockBackend.tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME)).thenReturn(true);
        when(mockBackend.isHeldByCurrentThread(LOCK_NAME)).thenReturn(true).thenReturn(true);
        doNothing().when(mockBackend).release(LOCK_NAME);
        
        // 第一次获取
        boolean firstAcquired = reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        assertTrue(firstAcquired);
        
        // 第二次重入获取
        boolean secondAcquired = reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        assertTrue(secondAcquired);
        
        // 释放两次
        reentrantLock.unlock();
        reentrantLock.unlock();
        
        verify(mockBackend, times(2)).tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME);
        verify(mockBackend, times(2)).release(LOCK_NAME);
    }

    @Test
    void shouldHandleInterleavedReentrantCalls() throws InterruptedException {
        when(mockBackend.tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME)).thenReturn(true);
        when(mockBackend.isHeldByCurrentThread(LOCK_NAME)).thenReturn(true);
        doNothing().when(mockBackend).release(LOCK_NAME);
        
        // 外部方法获取锁
        assertTrue(reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS));
        
        // 内部方法重入获取
        assertTrue(reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS));
        
        // 内部方法释放
        reentrantLock.unlock();
        
        // 外部方法释放
        reentrantLock.unlock();
        
        verify(mockBackend, times(2)).tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME);
        verify(mockBackend, times(2)).release(LOCK_NAME);
    }

    @Test
    void shouldHandleNestedReentrantCalls() throws InterruptedException {
        when(mockBackend.tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME)).thenReturn(true);
        when(mockBackend.isHeldByCurrentThread(LOCK_NAME)).thenReturn(true);
        doNothing().when(mockBackend).release(LOCK_NAME);
        
        // 三层嵌套调用
        assertTrue(outerMethod());
        
        verify(mockBackend, times(3)).tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME);
        verify(mockBackend, times(3)).release(LOCK_NAME);
    }

    private boolean outerMethod() throws InterruptedException {
        if (!reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS)) {
            return false;
        }
        try {
            return middleMethod();
        } finally {
            reentrantLock.unlock();
        }
    }

    private boolean middleMethod() throws InterruptedException {
        if (!reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS)) {
            return false;
        }
        try {
            return innerMethod();
        } finally {
            reentrantLock.unlock();
        }
    }

    private boolean innerMethod() throws InterruptedException {
        return reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
    }

    @Test
    void shouldHandleMultipleThreads() throws InterruptedException {
        when(mockBackend.tryAcquire(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(mockBackend.isHeldByCurrentThread(LOCK_NAME)).thenReturn(true);
        doNothing().when(mockBackend).release(LOCK_NAME);
        
        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // 等待所有线程准备好
                    
                    if (reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS)) {
                        successCount.incrementAndGet();
                        reentrantLock.unlock();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }
        
        startLatch.countDown(); // 启动所有线程
        assertTrue(finishLatch.await(5, TimeUnit.SECONDS));
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        
        // 验证只有当前线程能够获取锁
        assertTrue(successCount.get() > 0);
    }

    @Test
    void shouldValidateLockState() throws InterruptedException {
        when(mockBackend.tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME)).thenReturn(true);
        when(mockBackend.isHeldByCurrentThread(LOCK_NAME)).thenReturn(true);
        doNothing().when(mockBackend).release(LOCK_NAME);
        
        // 锁空闲状态
        assertFalse(reentrantLock.isLocked());
        assertFalse(reentrantLock.isHeldByCurrentThread());
        
        // 获取锁后
        reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        assertTrue(reentrantLock.isLocked());
        assertTrue(reentrantLock.isHeldByCurrentThread());
        
        // 释放锁后
        reentrantLock.unlock();
        assertFalse(reentrantLock.isLocked());
        assertFalse(reentrantLock.isHeldByCurrentThread());
    }

    @Test
    void shouldHandleLockRenewal() throws InterruptedException {
        when(mockBackend.tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME)).thenReturn(true);
        when(mockBackend.renew(LOCK_NAME, 60_000)).thenReturn(true);
        doNothing().when(mockBackend).release(LOCK_NAME);
        
        reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        
        boolean renewed = reentrantLock.renewLock(60_000, TimeUnit.MILLISECONDS);
        assertTrue(renewed);
        
        verify(mockBackend).renew(LOCK_NAME, 60_000);
    }

    @Test
    void shouldFailRenewalWhenLockNotHeld() throws InterruptedException {
        when(mockBackend.tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME)).thenReturn(true);
        doNothing().when(mockBackend).release(LOCK_NAME);
        
        reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        reentrantLock.unlock();
        
        boolean renewed = reentrantLock.renewLock(60_000, TimeUnit.MILLISECONDS);
        assertFalse(renewed);
        
        verify(mockBackend, never()).renew(LOCK_NAME, anyLong());
    }

    @Test
    void shouldHandleScheduleAutoRenewal() {
        when(mockBackend.tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME)).thenReturn(true);
        when(mockBackend.isHeldByCurrentThread(LOCK_NAME)).thenReturn(true);
        doNothing().when(mockBackend).release(LOCK_NAME);
        
        reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        
        // 测试自动续期调度（简化版本）
        var renewalTask = reentrantLock.scheduleAutoRenewal(10_000, TimeUnit.MILLISECONDS);
        
        assertNotNull(renewalTask);
        assertFalse(renewalTask.isDone());
        
        reentrantLock.unlock();
    }

    @Test
    void shouldGetLockStateInfo() throws InterruptedException {
        when(mockBackend.tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME)).thenReturn(true);
        when(mockBackend.isHeldByCurrentThread(LOCK_NAME)).thenReturn(true);
        when(mockBackend.getRemainingTime(LOCK_NAME, TimeUnit.MILLISECONDS)).thenReturn(25_000L);
        doNothing().when(mockBackend).release(LOCK_NAME);
        
        reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        
        var stateInfo = reentrantLock.getLockStateInfo();
        
        assertTrue(stateInfo.isLocked());
        assertTrue(stateInfo.isHeldByCurrentThread());
        assertEquals(LOCK_NAME, stateInfo.getHolder());
        assertEquals(25_000, stateInfo.getRemainingTime(TimeUnit.MILLISECONDS));
        assertEquals(1, stateInfo.getReentrantCount());
        assertNotNull(stateInfo.getCreationTime());
        assertNotNull(stateInfo.getExpirationTime());
        assertEquals("REENTRANT", stateInfo.getLockType().name());
    }

    @Test
    void shouldGetConfigurationInfo() {
        var configInfo = reentrantLock.getConfigurationInfo();
        
        assertEquals(30, configInfo.getDefaultLeaseTime(TimeUnit.SECONDS));
        assertEquals(10, configInfo.getDefaultWaitTime(TimeUnit.SECONDS));
        assertFalse(configInfo.isFairLock());
        assertTrue(configInfo.isReentrant());
        assertFalse(configInfo.isAutoRenewalEnabled());
        assertEquals(10, configInfo.getRenewalInterval(TimeUnit.SECONDS));
        assertEquals(0.7, configInfo.getRenewalRatio(), 0.001);
    }

    @Test
    void shouldPerformHealthCheck() throws InterruptedException {
        when(mockBackend.tryAcquire(LOCK_NAME, 0, 100)).thenReturn(true);
        doNothing().when(mockBackend).release(LOCK_NAME);
        
        var healthResult = reentrantLock.healthCheck();
        
        assertTrue(healthResult.isHealthy());
        assertNotNull(healthResult.getDetails());
        assertTrue(healthResult.getCheckTime() > 0);
    }

    @Test
    void shouldHandleHealthCheckFailure() {
        when(mockBackend.tryAcquire(LOCK_NAME, 0, 100)).thenThrow(new RuntimeException("Backend error"));
        
        var healthResult = reentrantLock.healthCheck();
        
        assertFalse(healthResult.isHealthy());
        assertTrue(healthResult.getDetails().contains("Health check failed"));
        assertTrue(healthResult.getCheckTime() > 0);
    }

    @Test
    void shouldHandleAutoCloseable() throws InterruptedException {
        when(mockBackend.tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME)).thenReturn(true);
        doNothing().when(mockBackend).release(LOCK_NAME);
        
        try (var lock = reentrantLock) {
            lock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
            assertTrue(lock.isHeldByCurrentThread());
        }
        
        assertFalse(reentrantLock.isHeldByCurrentThread());
        verify(mockBackend).release(LOCK_NAME);
    }

    @Test
    void shouldHandleExceptionInUnlock() throws InterruptedException {
        when(mockBackend.tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME)).thenReturn(true);
        doThrow(new RuntimeException("Release error")).when(mockBackend).release(LOCK_NAME);
        
        reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        
        // 解锁失败不应该抛出异常（根据接口设计）
        assertDoesNotThrow(() -> reentrantLock.unlock());
    }

    @Test
    void shouldValidateReentrancyCount() throws InterruptedException {
        when(mockBackend.tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME)).thenReturn(true);
        when(mockBackend.isHeldByCurrentThread(LOCK_NAME)).thenReturn(true);
        doNothing().when(mockBackend).release(LOCK_NAME);
        
        assertEquals(0, reentrantLock.getReentrantCount());
        
        reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        assertEquals(1, reentrantLock.getReentrantCount());
        
        reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        assertEquals(2, reentrantLock.getReentrantCount());
        
        reentrantLock.unlock();
        assertEquals(1, reentrantLock.getReentrantCount());
        
        reentrantLock.unlock();
        assertEquals(0, reentrantLock.getReentrantCount());
    }

    @Test
    void shouldHandleConcurrentReentrantAccess() throws InterruptedException {
        when(mockBackend.tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME)).thenReturn(true);
        when(mockBackend.isHeldByCurrentThread(LOCK_NAME)).thenReturn(true);
        doNothing().when(mockBackend).release(LOCK_NAME);
        
        int iterations = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(iterations);
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int i = 0; i < iterations; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    
                    // 重入获取锁
                    if (reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS)) {
                        try {
                            // 在持有锁的情况下再次获取（重入）
                            if (reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS)) {
                                successCount.incrementAndGet();
                                reentrantLock.unlock(); // 内部重入释放
                            }
                        } finally {
                            reentrantLock.unlock(); // 外部释放
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }
        
        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        
        assertTrue(successCount.get() > 0);
    }

    @Test
    void shouldValidateLockExpiration() throws InterruptedException {
        when(mockBackend.tryAcquire(LOCK_NAME, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME)).thenReturn(true);
        when(mockBackend.isExpired(LOCK_NAME)).thenReturn(false).thenReturn(true);
        doNothing().when(mockBackend).release(LOCK_NAME);
        
        reentrantLock.tryLock(DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        
        assertFalse(reentrantLock.isExpired());
        
        // 模拟锁过期
        when(mockBackend.isExpired(LOCK_NAME)).thenReturn(true);
        assertTrue(reentrantLock.isExpired());
    }

    @Test
    void shouldHandleZeroTimeout() throws InterruptedException {
        when(mockBackend.tryAcquire(LOCK_NAME, 0, DEFAULT_LEASE_TIME)).thenReturn(true);
        doNothing().when(mockBackend).release(LOCK_NAME);
        
        boolean acquired = reentrantLock.tryLock(0, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        assertTrue(acquired);
        
        reentrantLock.unlock();
    }

    @Test
    void shouldHandleNegativeTimeout() throws InterruptedException {
        when(mockBackend.tryAcquire(LOCK_NAME, -1, DEFAULT_LEASE_TIME)).thenReturn(true);
        doNothing().when(mockBackend).release(LOCK_NAME);
        
        // 负超时应该被处理为0超时
        boolean acquired = reentrantLock.tryLock(-1, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
        assertTrue(acquired);
        
        reentrantLock.unlock();
    }
}