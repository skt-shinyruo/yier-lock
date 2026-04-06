package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeasePolicy;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockCapabilities;
import com.mycorp.distributedlock.api.LockExecutor;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionPolicy;
import com.mycorp.distributedlock.api.SessionRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultLockExecutorTest {

    @Test
    void withLockShouldReleaseLeaseAfterAction() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        LockExecutor executor = new DefaultLockExecutor(
            new DefaultLockClient(backend),
            new SessionRequest(SessionPolicy.MANUAL_CLOSE)
        );

        String result = executor.withLock(sampleRequest(), () -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(backend.releaseCount()).hasValue(1);
        assertThat(backend.sessionCloseCount()).hasValue(1);
    }

    @Test
    void withLockShouldReleaseLeaseWhenActionFails() {
        TrackingBackend backend = new TrackingBackend();
        LockExecutor executor = new DefaultLockExecutor(
            new DefaultLockClient(backend),
            new SessionRequest(SessionPolicy.MANUAL_CLOSE)
        );

        assertThatThrownBy(() -> executor.withLock(sampleRequest(), () -> {
            throw new IllegalStateException("boom");
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("boom");

        assertThat(backend.releaseCount()).hasValue(1);
        assertThat(backend.sessionCloseCount()).hasValue(1);
    }

    private static LockRequest sampleRequest() {
        return new LockRequest(
            new LockKey("inventory"),
            LockMode.MUTEX,
            WaitPolicy.timed(Duration.ofSeconds(1)),
            LeasePolicy.RELEASE_ON_CLOSE
        );
    }

    private static final class TrackingBackend implements LockBackend {
        private final AtomicInteger releaseCount = new AtomicInteger();
        private final AtomicInteger sessionCloseCount = new AtomicInteger();

        @Override
        public LockCapabilities capabilities() {
            return new LockCapabilities(true, true, true, true);
        }

        @Override
        public BackendSession openSession(SessionRequest request) {
            return new BackendSession() {
                @Override
                public BackendLockLease acquire(LockRequest lockRequest) {
                    return new TrackingLease(lockRequest.key(), lockRequest.mode(), new FencingToken(1L), releaseCount);
                }

                @Override
                public SessionState state() {
                    return SessionState.ACTIVE;
                }

                @Override
                public void close() {
                    sessionCloseCount.incrementAndGet();
                }
            };
        }

        AtomicInteger releaseCount() {
            return releaseCount;
        }

        AtomicInteger sessionCloseCount() {
            return sessionCloseCount;
        }

        @Override
        public void close() {
        }
    }

    private record TrackingLease(
        LockKey key,
        LockMode mode,
        FencingToken fencingToken,
        AtomicInteger releaseCount
    ) implements BackendLockLease {

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
            releaseCount.incrementAndGet();
        }
    }
}
