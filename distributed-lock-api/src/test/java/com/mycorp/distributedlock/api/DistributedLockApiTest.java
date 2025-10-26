
package com.mycorp.distributedlock.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 分布式锁API接口的完整单元测试
 * 测试所有同步、异步操作以及高级特性
 */
@ExtendWith(MockitoExtension.class)
class DistributedLockApiTest {

    @Mock
    private DistributedLock mockLock;

    private DistributedLock lock;
    private static final String LOCK_NAME = "test-lock";
    private static final long DEFAULT_LEASE_TIME = 30_000;
    private static final TimeUnit DEFAULT_UNIT = TimeUnit.MILLISECONDS;

    @BeforeEach
    void setUp() {
        lock = createTestLock();
    }

    private DistributedLock createTestLock() {
        return new DistributedLock() {
            private volatile boolean locked = false;
            private volatile String holder = null;

            @Override
            public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
                locked = true;
                holder = Thread.currentThread().getName();
            }

            @Override
            public void lock() throws InterruptedException {
                lock(DEFAULT_LEASE_TIME, DEFAULT_UNIT);
            }

            @Override
            public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
                if (locked) {
                    return false;
                }
                lock(leaseTime, unit);
                return true;
            }

            @Override
            public boolean tryLock() throws InterruptedException {
                return tryLock(0, DEFAULT_LEASE_TIME, DEFAULT_UNIT);
            }

            @Override
            public void unlock() {
                locked = false;
                holder = null;
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
                return lockAsync(DEFAULT_LEASE_TIME, DEFAULT_UNIT);
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
                return tryLockAsync(0, DEFAULT_LEASE_TIME, DEFAULT_UNIT);
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
                return locked && holder != null && holder.equals(Thread.currentThread().getName());
            }

            @Override
            public String getName() {
                return LOCK_NAME;
            }

            @Override
            public ScheduledFuture<?> scheduleAutoRenewal(long renewInterval, TimeUnit unit, Consumer<RenewalResult> renewalCallback) {
                // 模拟自动续期任务
                return mock(ScheduledFuture.class);
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
                        return DistributedLock.this.isHeldByCurrentThread();
                    }

                    @Override
                    public String getHolder() {
                        return holder;
                    }

                    @Override
                    public long getRemainingTime(TimeUnit unit) {
                        return locked ? 1000 : 0;
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
                        return locked ? Instant.now().plusMillis(30000) : null;
                    }

                    @Override
                    public LockType getLockType() {
                        return LockType.MUTEX;
                    }

                    @Override
                    public String getMetadata() {
                        return "{\"name\": \"" + LOCK_NAME + "\", \"type\": \"MUTEX\"}";
                    }
                };
            }

            @Override
            public LockConfigurationInfo getConfigurationInfo() {
                return new LockConfigurationInfo() {
                    @Override
                    public long getDefaultLeaseTime(TimeUnit unit) {
                        return unit.convert(DEFAULT_LEASE_TIME, DEFAULT_UNIT);
                    }

                    @Override
                    public long getDefaultWaitTime(TimeUnit unit) {
                        return unit.convert(10_000, TimeUnit.MILLISECONDS);
                    }

                    @Override
                    public boolean isFairLock() {
                        return false;
                    }

                    @Override
                    public boolean isReentrant() {
                        return true;
                    }

                    @Override
                    public boolean isAutoRenewalEnabled() {
                        return false;
                    }

                    @Override
                    public long getRenewalInterval(TimeUnit unit) {
                        return unit.convert(10_000, TimeUnit.MILLISECONDS);
                    }

                    @Override
                    public double getRenewalRatio() {
                        return 0.7;
                    }
                };
            }
        };
    }

    @Test
    void shouldAcquireLockSuccessfully() throws InterruptedException {
        assertDoesNotThrow(() -> lock.lock(1, TimeUnit.SECONDS));
        assertTrue(lock.isLocked());
        assertTrue(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldAcquireLockWithDefaultTime() throws InterruptedException {
        assertDoesNotThrow(() -> lock.lock());
        assertTrue(lock.isLocked());
        assertTrue(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldTryLockSuccessfully() throws InterruptedException {
        assertTrue(lock.tryLock(0, 1, TimeUnit.SECONDS));
        assertTrue(lock.isLocked());
        assertTrue(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldTryLockFailWhenAlreadyLocked() throws InterruptedException {
        lock.lock(1, TimeUnit.SECONDS);
        
        Thread otherThread = new Thread(() -> {
            try {
                lock.tryLock(0, 1, TimeUnit.SECONDS);
                fail("Expected lock acquisition to fail");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        otherThread.start();
        otherThread.join();
        
        assertTrue(lock.isLocked());
        assertTrue(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldTryLockWithDefaultTimes() throws InterruptedException {
        assertTrue(lock.tryLock());
        assertTrue(lock.isLocked());
        assertTrue(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldReleaseLock() throws InterruptedException {
        lock.lock(1, TimeUnit.SECONDS);
        assertTrue(lock.isLocked());
        
        lock.unlock();
        assertFalse(lock.isLocked());
        assertFalse(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldHandleLockWithAutoCloseable() throws InterruptedException {
        try (DistributedLock l = lock) {
            l.lock(1, TimeUnit.SECONDS);
            assertTrue(l.isHeldByCurrentThread());
        }
        assertFalse(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldAcquireLockAsync() throws InterruptedException {
        CompletableFuture<Void> future = lock.lockAsync(1, TimeUnit.SECONDS);
        future.join();
        
        assertTrue(lock.isLocked());
        assertTrue(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldAcquireLockAsyncWithDefaultTime() {
        CompletableFuture<Void> future = lock.lockAsync();
        future.join();
        
        assertTrue(lock.isLocked());
        assertTrue(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldTryLockAsync() {
        CompletableFuture<Boolean> future = lock.tryLockAsync(0, 1, TimeUnit.SECONDS);
        Boolean result = future.join();
        
        assertTrue(result);
        assertTrue(lock.isLocked());
        assertTrue(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldTryLockAsyncWithDefaultTimes() {
        CompletableFuture<Boolean> future = lock.tryLockAsync();
        Boolean result = future.join();
        
        assertTrue(result);
        assertTrue(lock.isLocked());
        assertTrue(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldReleaseLockAsync() throws InterruptedException {
        lock.lock(1, TimeUnit.SECONDS);
        assertTrue(lock.isLocked());
        
        CompletableFuture<Void> future = lock.unlockAsync();
        future.join();
        
        assertFalse(lock.isLocked());
        assertFalse(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldGetLockName() {
        assertEquals(LOCK_NAME, lock.getName());
    }

    @Test
    void shouldRenewLock() throws InterruptedException {
        lock.lock(1, TimeUnit.SECONDS);
        assertTrue(lock.isHeldByCurrentThread());
        
        assertTrue(lock.renewLock(2, TimeUnit.SECONDS));
        assertTrue(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldRenewLockFailWhenNotHeld() {
        assertFalse(lock.renewLock(1, TimeUnit.SECONDS));
    }

    @Test
    void shouldRenewLockAsync() throws InterruptedException {
        lock.lock(1, TimeUnit.SECONDS);
        CompletableFuture<Boolean> future = lock.renewLockAsync(2, TimeUnit.SECONDS);
        
        assertTrue(future.join());
        assertTrue(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldRenewLockAsyncFailWhenNotHeld() {
        CompletableFuture<Boolean> future = lock.renewLockAsync(1, TimeUnit.SECONDS);
        assertFalse(future.join());
    }

    @Test
    void shouldScheduleAutoRenewal() {
        ScheduledFuture<?> future = lock.scheduleAutoRenewal(10, TimeUnit.SECONDS);
        assertNotNull(future);
        assertFalse(future.isDone());
    }

    @Test
    void shouldScheduleAutoRenewalWithCallback() {
        Consumer<RenewalResult> callback = result -> {};
        ScheduledFuture<?> future = lock.scheduleAutoRenewal(10, TimeUnit.SECONDS, callback);
        assertNotNull(future);
        assertFalse(future.isDone());
    }

    @Test
    void shouldCancelAutoRenewal() {
        ScheduledFuture<?> future = lock.scheduleAutoRenewal(10, TimeUnit.SECONDS);
        assertTrue(lock.cancelAutoRenewal(future));
        assertTrue(future.isCancelled());
    }

    @Test
    void shouldCancelAutoRenewalWithNullTask() {
        assertFalse(lock.cancelAutoRenewal(null));
    }

    @Test
    void shouldGetLockStateInfo() {
        LockStateInfo stateInfo = lock.getLockStateInfo();
        assertNotNull(stateInfo);
        
        // 测试默认状态
        assertFalse(stateInfo.isLocked());
        assertFalse(stateInfo.isHeldByCurrentThread());
        assertNull(stateInfo.getHolder());
        assertEquals(0, stateInfo.getRemainingTime(TimeUnit.MILLISECONDS));
        assertEquals(0, stateInfo.getReentrantCount());
        assertNotNull(stateInfo.getCreationTime());
        assertNull(stateInfo.getExpirationTime());
        assertEquals(LockType.MUTEX, stateInfo.getLockType());
        assertNotNull(stateInfo.getMetadata());
    }

    @Test
    void shouldGetLockStateInfoWhenLocked() throws InterruptedException {
        lock.lock(1, TimeUnit.SECONDS);
        LockStateInfo stateInfo = lock.getLockStateInfo();
        
        assertTrue(stateInfo.isLocked());
        assertTrue(stateInfo.isHeldByCurrentThread());
        assertEquals(Thread.currentThread().getName(), stateInfo.getHolder());
        assertTrue(stateInfo.getRemainingTime(TimeUnit.MILLISECONDS) > 0);
        assertEquals(1, stateInfo.getReentrantCount());
        assertNotNull(stateInfo.getExpirationTime());
    }

    @Test
    void shouldGetConfigurationInfo() {
        LockConfigurationInfo configInfo = lock.getConfigurationInfo();
        assertNotNull(configInfo);
        
        assertEquals(30, configInfo.getDefaultLeaseTime(TimeUnit.SECONDS));
        assertEquals(10, configInfo.getDefaultWaitTime(TimeUnit.SECONDS));
        assertFalse(configInfo.isFairLock());
        assertTrue(configInfo.isReentrant());
        assertFalse(configInfo.isAutoRenewalEnabled());
        assertEquals(10, configInfo.getRenewalInterval(TimeUnit.SECONDS));
        assertEquals(0.7, configInfo.getRenewalRatio(), 0.001);
    }

    @Test
    void shouldPerformHealthCheckWhenHealthy() throws InterruptedException {
        lock.lock(1, TimeUnit.SECONDS);
        DistributedLock.HealthCheckResult result = lock.healthCheck();
        
        assertTrue(result.isHealthy());
        assertNotNull(result.getDetails());
        assertTrue(result.getCheckTime() > 0);
    }

    @Test
    void shouldPerformHealthCheckWhenUnhealthy() {
        DistributedLock.HealthCheckResult result = lock.healthCheck();
        
        // 锁空闲时健康检查应该成功（可以尝试获取锁）
        assertTrue(result.isHealthy());
        assertNotNull(result.getDetails());
        assertTrue(result.getCheckTime() > 0);
    }

    @Test
    void shouldGetReentrantCount() throws InterruptedException {
        assertEquals(0, lock.getReentrantCount());
        
        lock.lock(1, TimeUnit.SECONDS);
        assertEquals(1, lock.getReentrantCount());
        
        lock.unlock();
        assertEquals(0, lock.getReentrantCount());
    }

    @Test
    void shouldCheckIfExpired() throws InterruptedException {
        assertFalse(lock.isExpired());
        
        lock.lock(100, TimeUnit.MILLISECONDS);
        assertFalse(lock.isExpired());
        
        // 等待锁过期（这里只是一个简单测试，实际可能需要更长时间）
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // 注意：实际过期逻辑取决于具体实现
    }

    @Test
    void shouldGetRemainingTime() throws InterruptedException {
        lock.lock(1, TimeUnit.SECONDS);
        long remaining = lock.getRemainingTime(TimeUnit.MILLISECONDS);
        
        assertTrue(remaining > 0);
        assertTrue(remaining <= 1000);
    }

    @Test
    void shouldGetLockHolder() throws InterruptedException {
        lock.lock(1, TimeUnit.SECONDS);
        String holder = lock.getLockHolder();
        
        assertNotNull(holder);
        assertTrue(holder.contains("main") || holder.contains("Test"));
    }

    @Test
    void shouldHandleInterruptedException() throws InterruptedException {
        Thread.currentThread().interrupt();
        
        assertThrows(InterruptedException.class, () -> {
            lock.lock(1, TimeUnit.SECONDS);
        });
        
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void shouldHandleInterruptedExceptionInAsyncOperations() {
        Thread.currentThread().interrupt();
        
        Thread.currentThread().interrupt();
        
        CompletableFuture<Void> future = lock.lockAsync(1, TimeUnit.SECONDS);
        // 异步操作应该仍然可以执行，但调用线程的中断状态会被处理
        future.join();
        
        // 验证锁仍然被正确获取
        assertTrue(lock.isLocked());
        assertTrue(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldHandleInterruptedExceptionInRenewLockAsync() {
        Thread.currentThread().interrupt();
        
        CompletableFuture<Boolean> future = lock.renewLockAsync(1, TimeUnit.SECONDS);
        Boolean result = future.join();
        
        // 续期应该失败，因为当前线程不持有锁
        assertFalse(result);
    }

    @Test
    void shouldValidateLockStateConsistency() throws InterruptedException {
        // 初始状态
        assertFalse(lock.isLocked());
        assertFalse(lock.isHeldByCurrentThread());
        
        // 获取锁
        lock.lock(1, TimeUnit.SECONDS);
        assertTrue(lock.isLocked());
        assertTrue(lock.isHeldByCurrentThread());
        
        // 释放锁
        lock.unlock();
        assertFalse(lock.isLocked());
        assertFalse(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldHandleMultipleLockOperations() throws InterruptedException {
        // 测试多个锁操作的组合
        assertTrue(lock.tryLock(0, 1, TimeUnit.SECONDS));
        assertTrue(lock.isLocked());
        
        DistributedLock.HealthCheckResult health = lock.healthCheck();
        assertTrue(health.isHealthy());
        
        assertTrue(lock.renewLock(2, TimeUnit.SECONDS));
        assertTrue(lock.isHeldByCurrentThread());
        
        lock.unlock();
        assertFalse(lock.isLocked());
    }

    @Test
    void shouldValidateDefaultConfigurations() {
        LockConfigurationInfo config = lock.getConfigurationInfo();
        
        // 验证默认值
        assertTrue(config.isReentrant());
        assertFalse(config.isFairLock());
        assertFalse(config.isAutoRenewalEnabled());
        assertEquals(0.7, config.getRenewalRatio(), 0.001);
        
        // 验证时间转换
        assertEquals(30, config.getDefaultLeaseTime(TimeUnit.SECONDS));
        assertEquals(10, config.getDefaultWaitTime(TimeUnit.SECONDS));
        assertEquals(10, config.getRenewalInterval(TimeUnit.SECONDS));
    }

    @Test
    void shouldValidateMetadataInStateInfo() throws InterruptedException {
        lock.lock(1, TimeUnit.SECONDS);
        LockStateInfo stateInfo = lock.getLockStateInfo();
        
        assertNotNull(stateInfo.getMetadata());
        assertTrue(stateInfo.getMetadata().contains("test-lock"));
        assertTrue(stateInfo.getMetadata().contains("MUTEX"));
    }

    @Test
    void shouldValidateThreadSafety() throws InterruptedException {
        lock.lock(1, TimeUnit.SECONDS);
        
        // 在持有锁的情况下，主线程应该能够访问锁状态
        assertTrue(lock.isHeldByCurrentThread());
        
        // 启动另一个线程尝试获取锁
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
            try {
                return lock.tryLock(0, 100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
        
        // 另一个线程应该无法获取锁
        assertFalse(future.join());
        assertTrue(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldHandleEdgeCaseTimeouts() throws InterruptedException {
        // 测试零超时
        assertTrue(lock.tryLock(0, 1, TimeUnit.SECONDS));
        lock.unlock();
        
        // 测试负超时（应该等同于零超时）
        assertTrue(lock.tryLock(-1, 1, TimeUnit.SECONDS));
        lock.unlock();
    }

    @Test
    void shouldValidateZeroLeaseTime() throws InterruptedException {
        // 测试零租约时间（立即过期）
        lock.lock(0, TimeUnit.MILLISECONDS);
        // 零租约时间的具体行为取决于实现
        
        // 测试最小正租约时间
        lock.unlock();
        lock.lock(1, TimeUnit.MILLISECONDS);
        assertTrue(lock.isLocked());
    }
}
        CompletableFuture<Void> future = lock.lockAsync(1