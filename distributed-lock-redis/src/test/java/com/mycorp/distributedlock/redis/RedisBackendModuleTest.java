package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.spi.BackendCapabilities;
import com.mycorp.distributedlock.spi.BackendModule;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class RedisBackendModuleTest {

    @Test
    void shouldExposeRedisBackendIdentityAndCapabilities() {
        RedisBackendModule module = new RedisBackendModule(new RedisBackendConfiguration("redis://localhost:6379", 30L));

        assertThat(module.id()).isEqualTo("redis");
        assertThat(module.capabilities()).isEqualTo(BackendCapabilities.standard());
    }

    @Test
    void shouldExposeOnlyTypedConfigurationConstructor() {
        assertThat(Arrays.stream(RedisBackendModule.class.getConstructors())
            .map(constructor -> List.of(constructor.getParameterTypes())))
            .containsExactly(List.of(RedisBackendConfiguration.class));
    }

    @Test
    void shouldNotExposeRedisProviderThroughServiceLoader() {
        assertThat(ServiceLoader.load(BackendModule.class).stream()
            .map(ServiceLoader.Provider::get)
            .map(BackendModule::id))
            .doesNotContain("redis");
    }
}
