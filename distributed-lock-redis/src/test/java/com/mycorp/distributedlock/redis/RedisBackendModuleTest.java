package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void shouldAllowServiceLoaderToDiscoverRedisProvider() {
        BackendModule module = discoverRedisProvider();

        assertThat(module.id()).isEqualTo("redis");
        assertThat(module.capabilities()).isEqualTo(BackendCapabilities.standard());
    }

    @Test
    void serviceLoadedRedisProviderShouldRequireExplicitTypedConfiguration() {
        BackendModule module = discoverRedisProvider();

        assertThatThrownBy(module::createBackend)
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("Redis requires explicit typed configuration")
            .hasMessageContaining("new RedisBackendModule(new RedisBackendConfiguration(");
    }

    private BackendModule discoverRedisProvider() {
        return ServiceLoader.load(BackendModule.class).stream()
            .map(ServiceLoader.Provider::get)
            .filter(module -> module.id().equals("redis"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Redis BackendModule was not discovered"));
    }
}
