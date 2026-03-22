package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.typesafe.config.ConfigFactory;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SimpleRedisLockProviderTest {

    @Mock
    private RedisClient mockRedisClient;

    @Mock
    private StatefulRedisConnection<String, String> mockConnection;

    @Mock
    private RedisCommands<String, String> mockCommands;

    @Test
    void shouldCreateDistinctLockInstancesForSameKey() {
        SimpleRedisLockProvider provider = newProvider(mockCommands);

        DistributedLock first = provider.createLock("shared-lock");
        DistributedLock second = provider.createLock("shared-lock");

        assertNotSame(first, second);
        assertEquals("shared-lock", first.getName());
        assertEquals("shared-lock", second.getName());
    }

    @Test
    void shouldCreateReadWriteLockWithExpectedNames() {
        SimpleRedisLockProvider provider = newProvider(mockCommands);

        DistributedReadWriteLock readWriteLock = provider.createReadWriteLock("resource");

        assertEquals("resource", readWriteLock.getName());
        assertEquals("resource:read", readWriteLock.readLock().getName());
        assertEquals("resource:write", readWriteLock.writeLock().getName());
    }

    @Test
    void shouldBlockWriterWhileReaderIsHeld() throws InterruptedException {
        RedisCommands<String, String> commands = new StatefulRedisCommands().commandView();
        SimpleRedisLockProvider provider = newProvider(commands);
        DistributedReadWriteLock readWriteLock = provider.createReadWriteLock("rw-resource");

        DistributedLock readLock = readWriteLock.readLock();
        DistributedLock writeLock = readWriteLock.writeLock();

        assertTrue(readLock.tryLock(0, 30, TimeUnit.SECONDS));
        assertFalse(writeLock.tryLock(20, 30, TimeUnit.MILLISECONDS));

        readLock.unlock();

        assertTrue(writeLock.tryLock(20, 30, TimeUnit.MILLISECONDS));
        writeLock.unlock();
    }

    @Test
    void shouldRejectCreationWhenClosedAndReleaseResources() {
        SimpleRedisLockProvider provider = newProvider(mockCommands);

        provider.close();

        assertThrows(IllegalStateException.class, () -> provider.createLock("test-lock"));
        assertThrows(IllegalStateException.class, () -> provider.createReadWriteLock("rw-lock"));
        verify(mockConnection).close();
        verify(mockRedisClient).shutdown();
    }

    @Test
    void shouldRejectNullAndBlankKeys() {
        SimpleRedisLockProvider provider = newProvider(mockCommands);

        assertThrows(IllegalArgumentException.class, () -> provider.createLock(null));
        assertThrows(IllegalArgumentException.class, () -> provider.createLock(""));
        assertThrows(IllegalArgumentException.class, () -> provider.createReadWriteLock("   "));
    }

    private SimpleRedisLockProvider newProvider(RedisCommands<String, String> commands) {
        return new SimpleRedisLockProvider(newConfiguration(), mockRedisClient, mockConnection, commands);
    }

    private LockConfiguration newConfiguration() {
        return new LockConfiguration(ConfigFactory.parseString("""
                distributed-lock {
                  default-lease-time = 30s
                  redis {
                    hosts = "localhost:6379"
                    database = 0
                    ssl = false
                  }
                }
                """));
    }

    private static final class StatefulRedisCommands {
        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final Map<String, Map<String, String>> hashes = new ConcurrentHashMap<>();
        private final Map<String, Long> ttlSeconds = new ConcurrentHashMap<>();

        private final RedisCommands<String, String> commands = mock(RedisCommands.class, this::handle);

        private RedisCommands<String, String> commandView() {
            return commands;
        }

        private Object handle(InvocationOnMock invocation) {
            String methodName = invocation.getMethod().getName();
            if ("eval".equals(methodName)) {
                return handleEval(invocation);
            }
            if ("ttl".equals(methodName)) {
                return ttlSeconds.getOrDefault(invocation.getArgument(0, String.class), -1L);
            }
            return defaultValue(invocation.getMethod().getReturnType());
        }

        private Long handleEval(InvocationOnMock invocation) {
            String script = invocation.getArgument(0, String.class);
            String[] keys = invocation.getArgument(2, String[].class);
            Object[] args = invocation.getArguments();

            if (script.contains("SIMPLE_RW_READ_ACQUIRE")) {
                String writerKey = keys[0];
                String readersKey = keys[1];
                String token = (String) args[3];
                long leaseSeconds = Long.parseLong((String) args[4]);
                if (values.containsKey(writerKey)) {
                    return 0L;
                }
                hashes.computeIfAbsent(readersKey, ignored -> new ConcurrentHashMap<>()).put(token, "1");
                ttlSeconds.put(readersKey, leaseSeconds);
                return 1L;
            }

            if (script.contains("SIMPLE_RW_READ_RELEASE")) {
                String readersKey = keys[0];
                String token = (String) args[3];
                Map<String, String> readers = hashes.get(readersKey);
                if (readers == null || readers.remove(token) == null) {
                    return 0L;
                }
                if (readers.isEmpty()) {
                    hashes.remove(readersKey);
                    ttlSeconds.remove(readersKey);
                }
                return 1L;
            }

            if (script.contains("SIMPLE_RW_READ_RENEW")) {
                String readersKey = keys[0];
                String token = (String) args[3];
                long leaseSeconds = Long.parseLong((String) args[4]);
                Map<String, String> readers = hashes.get(readersKey);
                if (readers == null || !readers.containsKey(token)) {
                    return 0L;
                }
                ttlSeconds.put(readersKey, leaseSeconds);
                return 1L;
            }

            if (script.contains("SIMPLE_RW_WRITE_ACQUIRE")) {
                String writerKey = keys[0];
                String readersKey = keys[1];
                String token = (String) args[3];
                long leaseSeconds = Long.parseLong((String) args[4]);
                if (values.containsKey(writerKey)) {
                    return 0L;
                }
                if (!hashes.getOrDefault(readersKey, Map.of()).isEmpty()) {
                    return 0L;
                }
                values.put(writerKey, token);
                ttlSeconds.put(writerKey, leaseSeconds);
                return 1L;
            }

            if (script.contains("SIMPLE_RW_WRITE_RELEASE")) {
                String writerKey = keys[0];
                String token = (String) args[3];
                if (!token.equals(values.get(writerKey))) {
                    return 0L;
                }
                values.remove(writerKey);
                ttlSeconds.remove(writerKey);
                return 1L;
            }

            if (script.contains("SIMPLE_RW_WRITE_RENEW")) {
                String writerKey = keys[0];
                String token = (String) args[3];
                long leaseSeconds = Long.parseLong((String) args[4]);
                if (!token.equals(values.get(writerKey))) {
                    return 0L;
                }
                ttlSeconds.put(writerKey, leaseSeconds);
                return 1L;
            }

            return 0L;
        }

        private Object defaultValue(Class<?> returnType) {
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class || returnType == Long.class) {
                return 0L;
            }
            return null;
        }
    }
}
