package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedisBackendModuleTest {

    @Test
    void shouldExposeRedisBackendIdentityAndCapabilities() {
        RedisBackendModule module = new RedisBackendModule(new RedisBackendConfiguration("redis://localhost:6379", 30L));

        assertThat(module.id()).isEqualTo("redis");
        assertThat(module.capabilities()).isEqualTo(BackendCapabilities.standard());
    }

    @Test
    void shouldExposeOnlyDefaultAndTypedConfigurationConstructors() {
        assertThat(Arrays.stream(RedisBackendModule.class.getConstructors())
            .map(constructor -> List.of(constructor.getParameterTypes())))
            .containsExactlyInAnyOrder(
                List.of(),
                List.of(RedisBackendConfiguration.class)
            );
    }
}
