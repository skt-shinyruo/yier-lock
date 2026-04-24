package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZooKeeperBackendModuleTest {

    @Test
    void shouldExposeZooKeeperBackendIdentityAndCapabilities() {
        ZooKeeperBackendModule module = new ZooKeeperBackendModule(
            new ZooKeeperBackendConfiguration("127.0.0.1:2181", "/distributed-locks")
        );

        assertThat(module.id()).isEqualTo("zookeeper");
        assertThat(module.capabilities()).isEqualTo(new BackendCapabilities(true, true, true, true));
    }

    @Test
    void shouldExposeOnlyTypedConfigurationConstructor() {
        assertThat(Arrays.stream(ZooKeeperBackendModule.class.getConstructors())
            .map(constructor -> List.of(constructor.getParameterTypes())))
            .containsExactly(List.of(ZooKeeperBackendConfiguration.class));
    }

    @Test
    void shouldAllowServiceLoaderToDiscoverZooKeeperProvider() {
        BackendModule module = discoverZooKeeperProvider();

        assertThat(module.id()).isEqualTo("zookeeper");
        assertThat(module.capabilities()).isEqualTo(BackendCapabilities.standard());
    }

    @Test
    void serviceLoadedZooKeeperProviderShouldRequireExplicitTypedConfiguration() {
        BackendModule module = discoverZooKeeperProvider();

        assertThatThrownBy(module::createBackend)
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("ZooKeeper requires explicit typed configuration")
            .hasMessageContaining("new ZooKeeperBackendModule(new ZooKeeperBackendConfiguration(");
    }

    private BackendModule discoverZooKeeperProvider() {
        return ServiceLoader.load(BackendModule.class).stream()
            .map(ServiceLoader.Provider::get)
            .filter(module -> module.id().equals("zookeeper"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("ZooKeeper BackendModule was not discovered"));
    }
}
