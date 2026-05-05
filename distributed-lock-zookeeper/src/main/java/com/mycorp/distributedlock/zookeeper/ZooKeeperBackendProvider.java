package com.mycorp.distributedlock.zookeeper;

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

public final class ZooKeeperBackendProvider implements BackendProvider<ZooKeeperBackendConfiguration> {

    private static final BackendBehavior BEHAVIOR = BackendBehavior.builder()
        .lockModes(Set.of(LockMode.MUTEX, LockMode.READ, LockMode.WRITE))
        .fencing(FencingSemantics.MONOTONIC_PER_KEY)
        .leaseSemantics(Set.of(LeaseSemantics.SESSION_BOUND))
        .session(SessionSemantics.BACKEND_EPHEMERAL_SESSION)
        .wait(WaitSemantics.WATCHED_QUEUE)
        .fairness(FairnessSemantics.FIFO_QUEUE)
        .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
        .costModel(BackendCostModel.NETWORK_CLIENT_PER_SESSION)
        .build();

    private static final BackendDescriptor<ZooKeeperBackendConfiguration> DESCRIPTOR = new BackendDescriptor<>(
        "zookeeper",
        "ZooKeeper",
        ZooKeeperBackendConfiguration.class,
        BEHAVIOR
    );

    @Override
    public BackendDescriptor<ZooKeeperBackendConfiguration> descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public BackendClient createBackendClient(ZooKeeperBackendConfiguration configuration) {
        return new ZooKeeperLockBackend(configuration);
    }
}
