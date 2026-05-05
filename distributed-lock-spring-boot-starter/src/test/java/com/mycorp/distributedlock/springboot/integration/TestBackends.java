package com.mycorp.distributedlock.springboot.integration;

import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.api.BackendCostModel;
import com.mycorp.distributedlock.api.FairnessSemantics;
import com.mycorp.distributedlock.api.FencingSemantics;
import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseSemantics;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.OwnershipLossSemantics;
import com.mycorp.distributedlock.api.SessionSemantics;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitSemantics;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.api.exception.LockFailureContext;
import com.mycorp.distributedlock.spi.BackendClient;
import com.mycorp.distributedlock.spi.BackendConfiguration;
import com.mycorp.distributedlock.spi.BackendDescriptor;
import com.mycorp.distributedlock.spi.BackendLease;
import com.mycorp.distributedlock.spi.BackendProvider;
import com.mycorp.distributedlock.spi.BackendSession;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class TestBackends {

    private TestBackends() {
    }

    static BackendBehavior behavior() {
        return BackendBehavior.builder()
            .lockModes(Set.of(LockMode.MUTEX, LockMode.READ, LockMode.WRITE))
            .fencing(FencingSemantics.MONOTONIC_PER_KEY)
            .leaseSemantics(Set.of(LeaseSemantics.FIXED_TTL))
            .session(SessionSemantics.CLIENT_LOCAL_TTL)
            .wait(WaitSemantics.POLLING)
            .fairness(FairnessSemantics.NONE)
            .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
            .costModel(BackendCostModel.CHEAP_SESSION)
            .build();
    }

    record Configuration() implements BackendConfiguration {
    }

    static final class Provider implements BackendProvider<Configuration> {
        private final String id;
        private final BackendClient backendClient;

        Provider(String id) {
            this(id, new CapturingBackendClient());
        }

        Provider(String id, BackendClient backendClient) {
            this.id = id;
            this.backendClient = backendClient;
        }

        @Override
        public BackendDescriptor<Configuration> descriptor() {
            return new BackendDescriptor<>(id, id, Configuration.class, behavior());
        }

        @Override
        public BackendClient createBackendClient(Configuration configuration) {
            return backendClient;
        }
    }

    static final class CapturingBackendClient implements BackendClient {
        private final AtomicReference<LockRequest> lastRequest = new AtomicReference<>();
        private final AtomicLong fencingCounter = new AtomicLong();
        private final Set<LockKey> heldKeys = ConcurrentHashMap.newKeySet();

        @Override
        public BackendSession openSession() {
            return new Session(this);
        }

        @Override
        public void close() {
        }

        LockRequest lastRequest() {
            return lastRequest.get();
        }

        private BackendLease acquire(LockRequest request) {
            lastRequest.set(request);
            if (!heldKeys.add(request.key())) {
                throw new LockAcquisitionTimeoutException(
                    "Could not acquire lock: " + request.key().value(),
                    null,
                    LockFailureContext.fromRequest(request, null, null)
                );
            }
            return new Lease(request.key(), request.mode(), new FencingToken(fencingCounter.incrementAndGet()), heldKeys);
        }
    }

    private static final class Session implements BackendSession {
        private final CapturingBackendClient backend;

        private Session(CapturingBackendClient backend) {
            this.backend = backend;
        }

        @Override
        public BackendLease acquire(LockRequest request) {
            return backend.acquire(request);
        }

        @Override
        public SessionState state() {
            return SessionState.ACTIVE;
        }

        @Override
        public void close() {
        }
    }

    private record Lease(
        LockKey key,
        LockMode mode,
        FencingToken fencingToken,
        Set<LockKey> heldKeys
    ) implements BackendLease {
        @Override
        public LeaseState state() {
            return heldKeys.contains(key) ? LeaseState.ACTIVE : LeaseState.RELEASED;
        }

        @Override
        public boolean isValid() {
            return state() == LeaseState.ACTIVE;
        }

        @Override
        public void release() {
            heldKeys.remove(key);
        }
    }
}
