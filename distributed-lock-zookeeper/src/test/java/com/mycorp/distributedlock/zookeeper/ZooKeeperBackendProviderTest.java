package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.spi.BackendCapabilities;
import com.mycorp.distributedlock.spi.BackendModule;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class ZooKeeperBackendModuleTest {

    @Test
    void shouldExposeZooKeeperBackendIdentityAndCapabilities() {
        ZooKeeperBackendModule module = new ZooKeeperBackendModule(
            new ZooKeeperBackendConfiguration("127.0.0.1:2181", "/distributed-locks")
        );

        assertThat(module.id()).isEqualTo("zookeeper");
        assertThat(module.capabilities()).isEqualTo(BackendCapabilities.withoutFixedLeaseDuration());
    }

    @Test
    void shouldExposeOnlyTypedConfigurationConstructor() {
        assertThat(Arrays.stream(ZooKeeperBackendModule.class.getConstructors())
            .map(constructor -> List.of(constructor.getParameterTypes())))
            .containsExactly(List.of(ZooKeeperBackendConfiguration.class));
    }

    @Test
    void shouldNotExposeZooKeeperProviderThroughServiceLoader() {
        assertThat(ServiceLoader.load(BackendModule.class).stream()
            .map(ServiceLoader.Provider::get)
            .map(BackendModule::id))
            .doesNotContain("zookeeper");
    }

    @Test
    void testSupportShouldProvideIsolatedConfigurationAndRuntime() throws Exception {
        try (ZooKeeperTestSupport support = new ZooKeeperTestSupport();
             LockRuntime runtime = support.runtime()) {
            assertThat(support.configuration().connectString()).isEqualTo(support.server().getConnectString());
            assertThat(support.configuration().basePath()).startsWith("/locks-");
            assertThat(runtime.lockClient()).isNotNull();
        }
    }
}
