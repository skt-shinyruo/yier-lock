package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.api.BackendCostModel;
import com.mycorp.distributedlock.api.FairnessSemantics;
import com.mycorp.distributedlock.api.FencingSemantics;
import com.mycorp.distributedlock.api.LeaseSemantics;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.OwnershipLossSemantics;
import com.mycorp.distributedlock.api.SessionSemantics;
import com.mycorp.distributedlock.api.WaitSemantics;
import com.mycorp.distributedlock.spi.BackendDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisBackendProviderTest {

    @Test
    void shouldExposeRedisBackendDescriptorAndClientFactory() {
        RedisBackendProvider provider = new RedisBackendProvider();
        RedisBackendConfiguration configuration = new RedisBackendConfiguration("redis://localhost:6379", 30L);
        BackendDescriptor<RedisBackendConfiguration> expectedDescriptor = new BackendDescriptor<>(
            "redis",
            "Redis",
            RedisBackendConfiguration.class,
            BackendBehavior.builder()
                .lockModes(Set.of(LockMode.MUTEX, LockMode.READ, LockMode.WRITE))
                .fencing(FencingSemantics.MONOTONIC_PER_KEY)
                .leaseSemantics(Set.of(LeaseSemantics.RENEWABLE_WATCHDOG, LeaseSemantics.FIXED_TTL))
                .session(SessionSemantics.CLIENT_LOCAL_TTL)
                .wait(WaitSemantics.POLLING)
                .fairness(FairnessSemantics.EXCLUSIVE_PREFERRED)
                .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
                .costModel(BackendCostModel.CHEAP_SESSION)
                .build()
        );

        assertThat(provider.descriptor()).isEqualTo(expectedDescriptor);
        assertThat(provider.createClient(configuration)).isInstanceOf(RedisLockBackend.class);
    }

    @Test
    void shouldRejectNullConfigurationThroughSpiDefaultMethod() {
        RedisBackendProvider provider = new RedisBackendProvider();

        assertThatThrownBy(() -> provider.createClient(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("configuration");
    }
}
