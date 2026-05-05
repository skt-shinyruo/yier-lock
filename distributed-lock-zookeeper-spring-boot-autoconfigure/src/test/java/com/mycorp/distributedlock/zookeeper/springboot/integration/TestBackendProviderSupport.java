package com.mycorp.distributedlock.zookeeper.springboot.integration;

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
import com.mycorp.distributedlock.spi.BackendConfiguration;
import com.mycorp.distributedlock.spi.BackendDescriptor;
import com.mycorp.distributedlock.spi.BackendProvider;
import com.mycorp.distributedlock.spi.BackendSession;

import java.util.Set;

class TestBackendProviderSupport implements BackendProvider<TestBackendProviderSupport.Configuration> {
    private final String id;

    TestBackendProviderSupport(String id) {
        this.id = id;
    }

    @Override
    public BackendDescriptor<Configuration> descriptor() {
        return new BackendDescriptor<>(id, id, Configuration.class, behavior());
    }

    @Override
    public BackendClient createBackendClient(Configuration configuration) {
        return new BackendClient() {
            @Override
            public BackendSession openSession() {
                throw new UnsupportedOperationException("not used in test");
            }

            @Override
            public void close() {
            }
        };
    }

    record Configuration() implements BackendConfiguration {
    }

    private static BackendBehavior behavior() {
        return BackendBehavior.builder()
            .lockModes(Set.of(LockMode.MUTEX, LockMode.READ, LockMode.WRITE))
            .fencing(FencingSemantics.MONOTONIC_PER_KEY)
            .leaseSemantics(Set.of(LeaseSemantics.SESSION_BOUND))
            .session(SessionSemantics.BACKEND_EPHEMERAL_SESSION)
            .wait(WaitSemantics.WATCHED_QUEUE)
            .fairness(FairnessSemantics.FIFO_QUEUE)
            .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
            .costModel(BackendCostModel.NETWORK_CLIENT_PER_SESSION)
            .build();
    }
}
