package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisBackendModuleTest {

    @Test
    void shouldExposeRedisBackendIdentityAndCapabilities() {
        RedisBackendModule module = new RedisBackendModule("redis://localhost:6379");

        assertThat(module.id()).isEqualTo("redis");
        assertThat(module.capabilities()).isEqualTo(BackendCapabilities.standard());
    }
}
