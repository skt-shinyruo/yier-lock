package com.mycorp.distributedlock.testkit.support;

import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.api.BackendCostModel;
import com.mycorp.distributedlock.api.FairnessSemantics;
import com.mycorp.distributedlock.api.FencingSemantics;
import com.mycorp.distributedlock.api.LeaseSemantics;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.OwnershipLossSemantics;
import com.mycorp.distributedlock.api.SessionSemantics;
import com.mycorp.distributedlock.api.WaitSemantics;
import com.mycorp.distributedlock.spi.BackendClient;
import com.mycorp.distributedlock.spi.BackendDescriptor;
import com.mycorp.distributedlock.spi.BackendProvider;

import java.util.Set;

public final class InMemoryBackendProvider implements BackendProvider<InMemoryBackendConfiguration> {

    private static final BackendBehavior BEHAVIOR = BackendBehavior.builder()
        .lockModes(Set.of(LockMode.MUTEX, LockMode.READ, LockMode.WRITE))
        .fencing(FencingSemantics.MONOTONIC_PER_KEY)
        .leaseSemantics(Set.of(LeaseSemantics.RENEWABLE_WATCHDOG, LeaseSemantics.FIXED_TTL))
        .session(SessionSemantics.CLIENT_LOCAL_TTL)
        .wait(WaitSemantics.POLLING)
        .fairness(FairnessSemantics.EXCLUSIVE_PREFERRED)
        .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
        .costModel(BackendCostModel.CHEAP_SESSION)
        .build();

    private static final BackendDescriptor<InMemoryBackendConfiguration> DESCRIPTOR = new BackendDescriptor<>(
        "in-memory",
        "InMemory",
        InMemoryBackendConfiguration.class,
        BEHAVIOR
    );

    @Override
    public BackendDescriptor<InMemoryBackendConfiguration> descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public BackendClient createBackendClient(InMemoryBackendConfiguration configuration) {
        return new InMemoryLockBackend();
    }
}
