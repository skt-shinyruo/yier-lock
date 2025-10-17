package com.mycorp.distributedlock.core.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockConfigurationTest {

    @Test
    void resolvesLockTypeWhenConfigured() {
        Config config = ConfigFactory.parseString("distributed-lock.type=redis");
        LockConfiguration lockConfiguration = new LockConfiguration(config);

        assertTrue(lockConfiguration.getLockType().isPresent());
        assertEquals("redis", lockConfiguration.getLockType().get());
    }

    @Test
    void returnsRedisDatabaseAndClientNameDefaults() {
        Config config = ConfigFactory.parseString(
            "distributed-lock.redis.database = 5\n" +
            "distributed-lock.redis.client-name = test-client"
        );
        LockConfiguration lockConfiguration = new LockConfiguration(config);

        assertEquals(5, lockConfiguration.getRedisDatabase());
        assertEquals("test-client", lockConfiguration.getRedisClientName());
    }
}
