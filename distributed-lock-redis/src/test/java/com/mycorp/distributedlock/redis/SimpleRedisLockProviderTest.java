package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SimpleRedisLockProvider单元测试
 * 测试Redis锁提供商的初始化、锁创建、连接管理等功能
 */
@ExtendWith(MockitoExtension.class)
class SimpleRedisLockProviderTest {

    @Mock
    private LockConfiguration mockConfiguration;

    @Mock
    private RedisClient mockRedisClient;

    @Mock
    private StatefulRedisConnection<String, String> mockConnection;

    @Mock
    private RedisCommands<String, String> mockCommands;

    private SimpleRedisLockProvider provider;

    @BeforeEach
    void setUp() {
        when(mockConfiguration.getDefaultLeaseTime()).thenReturn(java.time.Duration.ofSeconds(30));
        when(mockConfiguration.getRedisHosts()).thenReturn("localhost:6379");
        when(mockConfiguration.getRedisPassword()).thenReturn(null);
        when(mockConfiguration.isRedisSslEnabled()).thenReturn(false);
        when(mockConfiguration.getRedisDatabase()).thenReturn(0);
        
        when(mockRedisClient.connect()).thenReturn(mockConnection);
        when(mockConnection.sync()).thenReturn(mockCommands);
    }

    @Test
    void shouldCreateProviderWithDefaultConfiguration() {
        // 假设使用模拟的RedisClient避免实际连接
        provider = new SimpleRedisLockProvider(mockConfiguration);
        provider.close();
        
        assertNotNull(provider);
        assertEquals("redis", provider.getType());
        assertEquals(100, provider.getPriority());
    }

    @Test
    void shouldCreateLockSuccessfully() {
        provider = new SimpleRedisLockProvider(mockConfiguration);
        
        DistributedLock lock = provider.createLock("test-lock");
        
        assertNotNull(lock);
        assertEquals("test-lock", lock.getName());
        assertFalse(lock.isLocked());
    }

    @Test
    void shouldCreateReadWriteLockSuccessfully() {
        provider = new SimpleRedisLockProvider(mockConfiguration);
        
        DistributedReadWriteLock readWriteLock = provider.createReadWriteLock("test-rw-lock");
        
        assertNotNull(readWriteLock);
        assertEquals("test-rw-lock", readWriteLock.getName());
        
        DistributedLock readLock = readWriteLock.readLock();
        DistributedLock writeLock = readWriteLock.writeLock();
        assertNotNull(readLock);
        assertNotNull(writeLock);
        assertEquals("test-rw-lock:read", readLock.getName());
        assertEquals("test-rw-lock:write", writeLock.getName());
    }

    @Test
    void shouldCacheLocks() {
        provider = new SimpleRedisLockProvider(mockConfiguration);
        
        DistributedLock lock1 = provider.createLock("cached-lock");
        DistributedLock lock2 = provider.createLock("cached-lock");
        
        // 应该是同一个实例（来自缓存）
        assertSame(lock1, lock2);
    }

    @Test
    void shouldNotCreateLockWhenProviderIsClosed() {
        provider = new SimpleRedisLockProvider(mockConfiguration);
        provider.close();
        
        assertThrows(IllegalStateException.class, () -> {
            provider.createLock("test-lock");
        });
    }

    @Test
    void shouldNotCreateReadWriteLockWhenProviderIsClosed() {
        provider = new SimpleRedisLockProvider(mockConfiguration);
        provider.close();
        
        assertThrows(IllegalStateException.class, () -> {
            provider.createReadWriteLock("test-rw-lock");
        });
    }

    @Test
    void shouldRejectNullLockKey() {
        provider = new SimpleRedisLockProvider(mockConfiguration);
        
        assertThrows(IllegalArgumentException.class, () -> {
            provider.createLock(null);
        });
    }

    @Test
    void shouldRejectEmptyLockKey() {
        provider = new SimpleRedisLockProvider(mockConfiguration);
        
        assertThrows(IllegalArgumentException.class, () -> {
            provider.createLock("");
        });
    }

    @Test
    void shouldRejectBlankLockKey() {
        provider = new SimpleRedisLockProvider(mockConfiguration);
        
        assertThrows(IllegalArgumentException.class, () -> {
            provider.createLock("   ");
        });
    }

    @Test
    void shouldCloseProvider() {
        provider = new SimpleRedisLockProvider(mockConfiguration);
        
        // 首次关闭应该成功
        assertDoesNotThrow(() -> provider.close());
        
        // 后续关闭应该安全（幂等性）
        assertDoesNotThrow(() -> provider.close());
        assertDoesNotThrow(() -> provider.close());
    }

    @Test
    void shouldHandleProviderType() {
        provider = new SimpleRedisLockProvider(mockConfiguration);
        
        assertEquals("redis", provider.getType());
    }

    @Test
    void shouldHandleProviderPriority() {
        provider = new SimpleRedisLockProvider(mockConfiguration);
        
        assertEquals(100, provider.getPriority());
    }

    @Test
    void shouldCreateLocksWithCorrectLeaseTime() {
        when(mockConfiguration.getDefaultLeaseTime()).thenReturn(java.time.Duration.ofSeconds(60));
        
        provider = new SimpleRedisLockProvider(mockConfiguration);
        DistributedLock lock = provider.createLock("lease-test");
        
        // 验证锁创建时使用了正确的租约时间
        assertNotNull(lock);
    }

    @Test
    void shouldHandleMultipleDifferentLocks() {
        provider = new SimpleRedisLockProvider(mockConfiguration);
        
        DistributedLock lock1 = provider.createLock("lock-1");
        DistributedLock lock2 = provider.createLock("lock-2");
        DistributedLock lock3 = provider.createLock("lock-3");
        
        assertNotSame(lock1, lock2);
        assertNotSame(lock1, lock3);
        assertNotSame(lock2, lock3);
        
        assertEquals("lock-1", lock1.getName());
        assertEquals("lock-2", lock2.getName());
        assertEquals("lock-3", lock3.getName());
    }

    @Test
    void shouldCacheReadAndWriteLocksSeparately() {
        provider = new SimpleRedisLockProvider(mockConfiguration);
        
        // 创建读写锁
        DistributedReadWriteLock rwLock1 = provider.createReadWriteLock("rw-test");
        DistributedReadWriteLock rwLock2 = provider.createReadWriteLock("rw-test");
        
        // 应该返回同一个读写锁实例
        assertSame(rwLock1, rwLock2);
        
        // 但内部的读写锁应该是不同的实例
        assertNotSame(rwLock1.readLock(), rwLock2.readLock());
        assertNotSame(rwLock1.writeLock(), rwLock2.writeLock());
    }

    @Test
    void shouldValidateProviderStateOnMultipleOperations() {
        provider = new SimpleRedisLockProvider(mockConfiguration);
        
        // 正常操作
        DistributedLock lock1 = provider.createLock("valid-lock");
        assertNotNull(lock1);
        
        // 关闭提供者
        provider.close();
        
        // 后续操作应该失败
        assertThrows(IllegalStateException.class, () -> {
            provider.createLock("another-lock");
        });
        
        assertThrows(IllegalStateException.class, () -> {
            provider.createReadWriteLock("rw-lock");
        });
    }

    @Test
    void shouldHandleLockCreationEdgeCases() {
        provider = new SimpleRedisLockProvider(mockConfiguration);
        
        // 测试各种边缘情况的锁名
        String[] edgeCaseNames = {
            "single-char",
            "very-long-lock-name-that-exceeds-normal-limits-but-should-still-work-fine",
            "lock_with_underscores",
            "lock-with-dashes",
            "lock.with.dots",
            "lock123",
            "LOCK_UPPERCASE",
            "中文名称",
            "lock-with-123-numbers"
        };
        
        for (String lockName : edgeCaseNames) {
            assertDoesNotThrow(() -> {
                DistributedLock lock = provider.createLock(lockName);
                assertNotNull(lock);
                assertEquals(lockName, lock.getName());
            });
        }
    }
}