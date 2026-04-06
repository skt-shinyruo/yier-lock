package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeasePolicy;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockCapabilities;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.SessionPolicy;
import com.mycorp.distributedlock.api.SessionRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.UnsupportedLockCapabilityException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultLockClientTest {

    @Test
    void sessionShouldAcquireLeaseWithoutUsingThreadOwnership() throws Exception {
        StubBackend backend = new StubBackend(standardCapabilities());
        DefaultLockClient client = new DefaultLockClient(backend);

        try (LockSession session = client.openSession(new SessionRequest(SessionPolicy.MANUAL_CLOSE));
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
        StubBackend backend = new StubBackend(new LockCapabilities(false, true, true, true));
        DefaultLockClient client = new DefaultLockClient(backend);

        try (LockSession session = client.openSession(new SessionRequest(SessionPolicy.MANUAL_CLOSE))) {
            assertThatThrownBy(() -> session.acquire(sampleRequest(LockMode.MUTEX)))
                .isInstanceOf(UnsupportedLockCapabilityException.class)
                .hasMessageContaining("MUTEX");
        }

        assertThat(backend.acquireCount()).hasValue(0);
    }

    private static LockRequest sampleRequest(LockMode mode) {
        return new LockRequest(
            new LockKey("orders"),
            mode,
            WaitPolicy.timed(Duration.ofSeconds(1)),
            LeasePolicy.RELEASE_ON_CLOSE
        );
    }

    private static LockCapabilities standardCapabilities() {
        return new LockCapabilities(true, true, true, true);
    }

    private static final class StubBackend implements LockBackend {
        private final LockCapabilities capabilities;
        private final AtomicInteger acquireCount = new AtomicInteger();

        private StubBackend(LockCapabilities capabilities) {
            this.capabilities = capabilities;
        }

        @Override
        public LockCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public BackendSession openSession(SessionRequest request) {
            return new BackendSession() {
                @Override
                public BackendLockLease acquire(LockRequest lockRequest) {
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

    private record StubLease(LockKey key, LockMode mode, FencingToken fencingToken) implements BackendLockLease {

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
