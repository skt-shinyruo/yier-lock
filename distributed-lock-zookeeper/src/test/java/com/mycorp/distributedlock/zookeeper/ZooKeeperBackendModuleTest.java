package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ZooKeeperBackendModuleTest {

    @Test
    void shouldExposeZooKeeperBackendIdentityAndCapabilities() {
        ZooKeeperBackendModule module = new ZooKeeperBackendModule("127.0.0.1:2181");

        assertThat(module.id()).isEqualTo("zookeeper");
        assertThat(module.capabilities()).isEqualTo(BackendCapabilities.standard());
    }
}
