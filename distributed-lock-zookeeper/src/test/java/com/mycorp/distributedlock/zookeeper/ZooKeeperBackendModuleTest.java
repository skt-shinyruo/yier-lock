package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ZooKeeperBackendModuleTest {

    @Test
    void shouldExposeZooKeeperBackendIdentityAndCapabilities() {
        ZooKeeperBackendModule module = new ZooKeeperBackendModule(
            new ZooKeeperBackendConfiguration("127.0.0.1:2181", "/distributed-locks")
        );

        assertThat(module.id()).isEqualTo("zookeeper");
        assertThat(module.capabilities()).isEqualTo(BackendCapabilities.standard());
    }

    @Test
    void shouldExposeOnlyDefaultAndTypedConfigurationConstructors() {
        assertThat(Arrays.stream(ZooKeeperBackendModule.class.getConstructors())
            .map(constructor -> List.of(constructor.getParameterTypes())))
            .containsExactlyInAnyOrder(
                List.of(),
                List.of(ZooKeeperBackendConfiguration.class)
            );
    }
}
