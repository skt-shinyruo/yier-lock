package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.api.BackendCostModel;
import com.mycorp.distributedlock.api.FairnessSemantics;
import com.mycorp.distributedlock.api.FencingSemantics;
import com.mycorp.distributedlock.api.LeaseSemantics;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.api.OwnershipLossSemantics;
import com.mycorp.distributedlock.api.SessionSemantics;
import com.mycorp.distributedlock.api.WaitSemantics;
import com.mycorp.distributedlock.spi.BackendDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZooKeeperBackendProviderTest {

    @Test
    void shouldExposeZooKeeperBackendDescriptorAndClientFactory() {
        ZooKeeperBackendProvider provider = new ZooKeeperBackendProvider();
        ZooKeeperBackendConfiguration configuration = new ZooKeeperBackendConfiguration(
            "127.0.0.1:2181",
            "/distributed-locks"
        );
        BackendDescriptor<ZooKeeperBackendConfiguration> expectedDescriptor = new BackendDescriptor<>(
            "zookeeper",
            "ZooKeeper",
            ZooKeeperBackendConfiguration.class,
            BackendBehavior.builder()
                .lockModes(Set.of(LockMode.MUTEX, LockMode.READ, LockMode.WRITE))
                .fencing(FencingSemantics.MONOTONIC_PER_KEY)
                .leaseSemantics(Set.of(LeaseSemantics.SESSION_BOUND))
                .session(SessionSemantics.BACKEND_EPHEMERAL_SESSION)
                .wait(WaitSemantics.WATCHED_QUEUE)
                .fairness(FairnessSemantics.FIFO_QUEUE)
                .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
                .costModel(BackendCostModel.NETWORK_CLIENT_PER_SESSION)
                .build()
        );

        assertThat(provider.descriptor()).isEqualTo(expectedDescriptor);
        assertThat(provider.descriptor().behavior().supportsLockMode(LockMode.READ)).isTrue();
        assertThat(provider.descriptor().behavior().supportsLeaseSemantics(LeaseSemantics.FIXED_TTL)).isFalse();
        assertThat(provider.createClient(configuration)).isInstanceOf(ZooKeeperLockBackend.class);
    }

    @Test
    void shouldRejectNullConfigurationThroughSpiDefaultMethod() {
        ZooKeeperBackendProvider provider = new ZooKeeperBackendProvider();

        assertThatThrownBy(() -> provider.createClient(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("configuration");
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
