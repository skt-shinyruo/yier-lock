package com.mycorp.distributedlock.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisLockBackendLifecycleTest {

    private static final String REDIS_URI = "redis://localhost:6379";

    @Test
    void constructorShouldShutdownClientWhenConnectFails() {
        RedisClient redisClient = mock(RedisClient.class);
        RuntimeException failure = new RuntimeException("connect failed");
        when(redisClient.connect()).thenThrow(failure);

        try (MockedStatic<RedisClient> redisClients = mockStatic(RedisClient.class)) {
            redisClients.when(() -> RedisClient.create(REDIS_URI)).thenReturn(redisClient);

            assertThatThrownBy(() -> new RedisLockBackend(new RedisBackendConfiguration(REDIS_URI, 30L)))
                .isSameAs(failure);
        }

        verify(redisClient).shutdown();
    }

    @Test
    void constructorShouldCloseConnectionAndShutdownClientWhenSyncFails() {
        RedisClient redisClient = mock(RedisClient.class);
        StatefulRedisConnection<String, String> connection = mockConnection();
        RuntimeException failure = new RuntimeException("sync failed");
        when(redisClient.connect()).thenReturn(connection);
        when(connection.sync()).thenThrow(failure);

        try (MockedStatic<RedisClient> redisClients = mockStatic(RedisClient.class)) {
            redisClients.when(() -> RedisClient.create(REDIS_URI)).thenReturn(redisClient);

            assertThatThrownBy(() -> new RedisLockBackend(new RedisBackendConfiguration(REDIS_URI, 30L)))
                .isSameAs(failure);
        }

        verify(connection).close();
        verify(redisClient).shutdown();
    }

    @Test
    void closeShouldShutdownExecutorAndClientWhenConnectionCloseFails() {
        RedisClient redisClient = mock(RedisClient.class);
        StatefulRedisConnection<String, String> connection = mockConnection();
        RedisCommands<String, String> commands = mockCommands();
        RuntimeException closeFailure = new RuntimeException("close failed");
        RuntimeException shutdownFailure = new RuntimeException("shutdown failed");
        when(redisClient.connect()).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);
        doThrow(closeFailure).when(connection).close();
        doThrow(shutdownFailure).when(redisClient).shutdown();

        RedisLockBackend backend;
        try (MockedStatic<RedisClient> redisClients = mockStatic(RedisClient.class)) {
            redisClients.when(() -> RedisClient.create(REDIS_URI)).thenReturn(redisClient);
            backend = new RedisLockBackend(new RedisBackendConfiguration(REDIS_URI, 30L));
        }

        assertThatThrownBy(backend::close)
            .isSameAs(closeFailure)
            .satisfies(exception -> assertThat(exception.getSuppressed()).containsExactly(shutdownFailure));

        assertThat(backend.renewalExecutor().isShutdown()).isTrue();
        verify(redisClient).shutdown();
    }

    @SuppressWarnings("unchecked")
    private static StatefulRedisConnection<String, String> mockConnection() {
        return mock(StatefulRedisConnection.class);
    }

    @SuppressWarnings("unchecked")
    private static RedisCommands<String, String> mockCommands() {
        return mock(RedisCommands.class);
    }
}
