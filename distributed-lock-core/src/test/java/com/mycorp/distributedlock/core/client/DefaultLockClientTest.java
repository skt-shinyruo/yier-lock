package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.api.BackendCostModel;
import com.mycorp.distributedlock.api.FairnessSemantics;
import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.FencingSemantics;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LeaseSemantics;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.OwnershipLossSemantics;
import com.mycorp.distributedlock.api.SessionSemantics;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.WaitSemantics;
import com.mycorp.distributedlock.api.exception.UnsupportedLockCapabilityException;
import com.mycorp.distributedlock.spi.BackendClient;
import com.mycorp.distributedlock.spi.BackendLease;
import com.mycorp.distributedlock.spi.BackendSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultLockClientTest {

    @Test
    void sessionShouldAcquireLeaseWithoutUsingThreadOwnership() throws Exception {
        StubBackend backend = new StubBackend();
        DefaultLockClient client = new DefaultLockClient(backend, standardBehavior());

        try (LockSession session = client.openSession();
             LockLease lease = session.acquire(sampleRequest(LockMode.MUTEX))) {
            assertThat(session.state()).isEqualTo(SessionState.ACTIVE);
            assertThat(lease.key()).isEqualTo(new LockKey("orders"));
            assertThat(lease.mode()).isEqualTo(LockMode.MUTEX);
            assertThat(lease.fencingToken()).isEqualTo(new FencingToken(1L));
            assertThat(lease.state()).isEqualTo(LeaseState.ACTIVE);
            assertThat(lease.isValid()).isTrue();
            assertThat(backend.acquireCount()).hasValue(1);
        }
    }

    @Test
    void sessionShouldRejectUnsupportedModesBeforeBackendAcquire() {
        StubBackend backend = new StubBackend();
        DefaultLockClient client = new DefaultLockClient(backend, mutexOnlyBehavior());

        try (LockSession session = client.openSession()) {
            assertThatThrownBy(() -> session.acquire(sampleRequest(LockMode.READ)))
                .isInstanceOf(UnsupportedLockCapabilityException.class)
                .hasMessageContaining("READ");
        }

        assertThat(backend.acquireCount()).hasValue(0);
    }

    private static LockRequest sampleRequest(LockMode mode) {
        return new LockRequest(
            new LockKey("orders"),
            mode,
            WaitPolicy.timed(Duration.ofSeconds(1))
        );
    }

    private static BackendBehavior standardBehavior() {
        return BackendBehavior.builder()
            .lockModes(Set.of(LockMode.MUTEX, LockMode.READ, LockMode.WRITE))
            .fencing(FencingSemantics.MONOTONIC_PER_KEY)
            .leaseSemantics(Set.of(LeaseSemantics.RENEWABLE_WATCHDOG, LeaseSemantics.FIXED_TTL))
            .session(SessionSemantics.CLIENT_LOCAL_TTL)
            .wait(WaitSemantics.POLLING)
            .fairness(FairnessSemantics.EXCLUSIVE_PREFERRED)
            .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
            .costModel(BackendCostModel.CHEAP_SESSION)
            .build();
    }

    private static BackendBehavior mutexOnlyBehavior() {
        return BackendBehavior.builder()
            .lockModes(Set.of(LockMode.MUTEX))
            .fencing(FencingSemantics.MONOTONIC_PER_KEY)
            .leaseSemantics(Set.of(LeaseSemantics.RENEWABLE_WATCHDOG))
            .session(SessionSemantics.CLIENT_LOCAL_TTL)
            .wait(WaitSemantics.POLLING)
            .fairness(FairnessSemantics.NONE)
            .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
            .costModel(BackendCostModel.CHEAP_SESSION)
            .build();
    }

    private static final class StubBackend implements BackendClient {
        private final AtomicInteger acquireCount = new AtomicInteger();

        @Override
        public BackendSession openSession() {
            return new BackendSession() {
                @Override
                public BackendLease acquire(LockRequest lockRequest) {
                    acquireCount.incrementAndGet();
                    return new StubLease(lockRequest.key(), lockRequest.mode(), new FencingToken(1L));
                }

                @Override
                public SessionState state() {
                    return SessionState.ACTIVE;
                }

                @Override
                public void close() {
                }
            };
        }

        AtomicInteger acquireCount() {
            return acquireCount;
        }

        @Override
        public void close() {
        }
    }

    private record StubLease(LockKey key, LockMode mode, FencingToken fencingToken) implements BackendLease {

        @Override
        public LeaseState state() {
            return LeaseState.ACTIVE;
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public void release() {
        }
    }
}
