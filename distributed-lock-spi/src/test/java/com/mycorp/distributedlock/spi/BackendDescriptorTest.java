package com.mycorp.distributedlock.spi;

import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.api.BackendCostModel;
import com.mycorp.distributedlock.api.FairnessSemantics;
import com.mycorp.distributedlock.api.FencingSemantics;
import com.mycorp.distributedlock.api.LeaseSemantics;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.OwnershipLossSemantics;
import com.mycorp.distributedlock.api.SessionSemantics;
import com.mycorp.distributedlock.api.WaitSemantics;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackendDescriptorTest {

    @Test
    void descriptorShouldExposeProviderMetadataWithoutCoreTypes() {
        BackendBehavior behavior = BackendBehavior.builder()
            .lockModes(Set.of(LockMode.MUTEX))
            .fencing(FencingSemantics.MONOTONIC_PER_KEY)
            .leaseSemantics(Set.of(LeaseSemantics.RENEWABLE_WATCHDOG))
            .session(SessionSemantics.CLIENT_LOCAL_TTL)
            .wait(WaitSemantics.POLLING)
            .fairness(FairnessSemantics.NONE)
            .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
            .costModel(BackendCostModel.CHEAP_SESSION)
            .build();

        BackendDescriptor<TestConfiguration> descriptor = new BackendDescriptor<>(
            "test",
            "Test Backend",
            TestConfiguration.class,
            behavior
        );

        assertThat(descriptor.id()).isEqualTo("test");
        assertThat(descriptor.displayName()).isEqualTo("Test Backend");
        assertThat(descriptor.configurationType()).isEqualTo(TestConfiguration.class);
        assertThat(descriptor.behavior()).isEqualTo(behavior);
    }

    @Test
    void descriptorShouldRejectBlankIds() {
        BackendBehavior behavior = BackendBehavior.builder()
            .lockModes(Set.of(LockMode.MUTEX))
            .fencing(FencingSemantics.MONOTONIC_PER_KEY)
            .leaseSemantics(Set.of(LeaseSemantics.RENEWABLE_WATCHDOG))
            .session(SessionSemantics.CLIENT_LOCAL_TTL)
            .wait(WaitSemantics.POLLING)
            .fairness(FairnessSemantics.NONE)
            .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
            .costModel(BackendCostModel.CHEAP_SESSION)
            .build();

        assertThatThrownBy(() -> new BackendDescriptor<>("", "Test Backend", TestConfiguration.class, behavior))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("id");
    }

    private record TestConfiguration() implements BackendConfiguration {
    }
}
