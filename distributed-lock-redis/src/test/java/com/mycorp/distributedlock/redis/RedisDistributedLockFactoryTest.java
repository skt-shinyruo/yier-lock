package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.LockConfigurationBuilder;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisDistributedLockFactoryTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> commands;
    private RedisDistributedLockFactory factory;

    @BeforeEach
    void setUp() {
        redisClient = mock(RedisClient.class);
        connection = mock(StatefulRedisConnection.class);
        commands = mock(RedisCommands.class);

        when(redisClient.connect()).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);

        factory = new RedisDistributedLockFactory(redisClient);
    }

    @Test
    void shouldRejectFairConfiguredLocks() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> factory.getConfiguredLock("fair-lock", fairConfiguration("fair-lock"))
        );
    }

    @Test
    void shouldReportUnhealthyWhenRedisPingFails() {
        when(commands.ping()).thenThrow(new IllegalStateException("redis unavailable"));

        assertFalse(factory.healthCheck().isHealthy());
    }

    @Test
    void shouldReportHealthyWhenRedisPingSucceeds() {
        when(commands.ping()).thenReturn("PONG");

        assertTrue(factory.healthCheck().isHealthy());
    }

    private static LockConfigurationBuilder.LockConfiguration fairConfiguration(String name) {
        return new LockConfigurationBuilder.LockConfiguration() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public LockConfigurationBuilder.LockType getLockType() {
                return LockConfigurationBuilder.LockType.FAIR;
            }

            @Override
            public long getLeaseTime(TimeUnit timeUnit) {
                return timeUnit.convert(5, TimeUnit.SECONDS);
            }

            @Override
            public long getWaitTime(TimeUnit timeUnit) {
                return timeUnit.convert(5, TimeUnit.SECONDS);
            }

            @Override
            public boolean isFairLock() {
                return true;
            }

            @Override
            public boolean isRetryEnabled() {
                return false;
            }

            @Override
            public int getRetryCount() {
                return 0;
            }

            @Override
            public long getRetryInterval(TimeUnit timeUnit) {
                return 0;
            }

            @Override
            public boolean isAutoRenew() {
                return false;
            }

            @Override
            public long getRenewInterval(TimeUnit timeUnit) {
                return 0;
            }

            @Override
            public double getRenewRatio() {
                return 0;
            }

            @Override
            public LockConfigurationBuilder.TimeoutStrategy getTimeoutStrategy() {
                return LockConfigurationBuilder.TimeoutStrategy.BLOCK_UNTIL_TIMEOUT;
            }

            @Override
            public boolean isDeadlockDetectionEnabled() {
                return false;
            }

            @Override
            public long getDeadlockDetectionTimeout(TimeUnit timeUnit) {
                return 0;
            }

            @Override
            public boolean isEventListeningEnabled() {
                return false;
            }

            @Override
            public Map<String, String> getProperties() {
                return Map.of();
            }
        };
    }
}
