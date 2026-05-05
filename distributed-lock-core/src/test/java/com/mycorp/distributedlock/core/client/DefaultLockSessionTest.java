package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.api.BackendCostModel;
import com.mycorp.distributedlock.api.FairnessSemantics;
import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.FencingSemantics;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LeaseSemantics;
import com.mycorp.distributedlock.api.LeasePolicy;
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
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.api.exception.LockReentryException;
import com.mycorp.distributedlock.api.exception.UnsupportedLockCapabilityException;
import com.mycorp.distributedlock.spi.BackendClient;
import com.mycorp.distributedlock.spi.BackendLease;
import com.mycorp.distributedlock.spi.BackendSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultLockSessionTest {

    @Test
    void closeShouldReleaseUnclosedLeaseBeforeBackendSessionClose() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        DefaultLockClient client = new DefaultLockClient(backend, standardBehavior());
        LockSession session = client.openSession();

        session.acquire(sampleRequest("orders:close-one"));
        session.close();

        assertThat(backend.lease(0).releaseCount()).isEqualTo(1);
        assertThat(backend.lease(0).state()).isEqualTo(LeaseState.RELEASED);
        assertThat(backend.backendCloseCount()).isEqualTo(1);
    }

    @Test
    void closeShouldReleaseEveryUnclosedLease() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        DefaultLockClient client = new DefaultLockClient(backend, standardBehavior());
        LockSession session = client.openSession();

        session.acquire(sampleRequest("orders:close-first"));
        session.acquire(sampleRequest("orders:close-second"));
        session.close();

        assertThat(backend.lease(0).releaseCount()).isEqualTo(1);
        assertThat(backend.lease(1).releaseCount()).isEqualTo(1);
        assertThat(backend.backendCloseCount()).isEqualTo(1);
    }

    @Test
    void manuallyReleasedLeaseShouldNotBeReleasedAgainWhenSessionCloses() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        DefaultLockClient client = new DefaultLockClient(backend, standardBehavior());
        LockSession session = client.openSession();
        LockLease lease = session.acquire(sampleRequest("orders:manual-release"));

        lease.release();
        session.close();

        assertThat(backend.lease(0).releaseCount()).isEqualTo(1);
        assertThat(backend.backendCloseCount()).isEqualTo(1);
    }

    @Test
    void closeShouldContinueAfterLeaseReleaseFailureAndCloseBackendSession() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        DefaultLockClient client = new DefaultLockClient(backend, standardBehavior());
        LockSession session = client.openSession();
        RuntimeException releaseFailure = new LockBackendException("first release failed");

        session.acquire(sampleRequest("orders:release-failure"));
        session.acquire(sampleRequest("orders:release-after-failure"));
        backend.lease(0).failRelease(releaseFailure);

        assertThatThrownBy(session::close)
            .isSameAs(releaseFailure);
        assertThat(backend.lease(0).releaseCount()).isEqualTo(1);
        assertThat(backend.lease(1).releaseCount()).isEqualTo(1);
        assertThat(backend.backendCloseCount()).isEqualTo(1);
    }

    @Test
    void closeShouldSuppressBackendCloseFailureWhenLeaseReleaseAlreadyFailed() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        DefaultLockClient client = new DefaultLockClient(backend, standardBehavior());
        LockSession session = client.openSession();
        RuntimeException releaseFailure = new LockBackendException("release failed");
        RuntimeException backendCloseFailure = new LockBackendException("backend close failed");

        session.acquire(sampleRequest("orders:release-and-close-fail"));
        backend.lease(0).failRelease(releaseFailure);
        backend.failBackendClose(backendCloseFailure);

        assertThatThrownBy(session::close)
            .isSameAs(releaseFailure)
            .satisfies(exception -> assertThat(exception.getSuppressed()).containsExactly(backendCloseFailure));
        assertThat(backend.backendCloseCount()).isEqualTo(1);
    }

    @Test
    void backendCloseFailureShouldSurfaceAfterLeaseCleanupSucceeds() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        DefaultLockClient client = new DefaultLockClient(backend, standardBehavior());
        LockSession session = client.openSession();
        RuntimeException backendCloseFailure = new LockBackendException("backend close failed");

        session.acquire(sampleRequest("orders:backend-close-failure"));
        backend.failBackendClose(backendCloseFailure);

        assertThatThrownBy(session::close)
            .isSameAs(backendCloseFailure);
        assertThat(backend.lease(0).releaseCount()).isEqualTo(1);
        assertThat(backend.backendCloseCount()).isEqualTo(1);
    }

    @Test
    void acquireShouldReleaseLateBackendLeaseWhenSessionClosesDuringAcquire() throws Exception {
        BlockingAcquireBackend backend = new BlockingAcquireBackend();
        DefaultLockClient client = new DefaultLockClient(backend, standardBehavior());
        LockSession session = client.openSession();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<Throwable> acquired = executor.submit(() -> {
                try {
                    session.acquire(sampleRequest("orders:late-acquire"));
                    return null;
                } catch (Throwable exception) {
                    return exception;
                }
            });

            assertThat(backend.awaitAcquireStarted()).isTrue();
            session.close();
            backend.completeAcquire();

            Throwable thrown = acquired.get(1, TimeUnit.SECONDS);
            assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Lock session is already closed");
            assertThat(backend.lease().releaseCount()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void acquireAfterSessionCloseShouldFail() {
        TrackingBackend backend = new TrackingBackend();
        DefaultLockClient client = new DefaultLockClient(backend, standardBehavior());
        LockSession session = client.openSession();

        session.close();

        assertThatThrownBy(() -> session.acquire(sampleRequest("orders:closed")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Lock session is already closed");
    }

    @Test
    void acquireShouldRejectSameKeyAlreadyHeldBySession() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        DefaultLockClient client = new DefaultLockClient(backend, standardBehavior());
        LockRequest request = new LockRequest(new LockKey("orders:42"), LockMode.MUTEX, WaitPolicy.tryOnce());

        try (LockSession session = client.openSession();
             LockLease ignored = session.acquire(request)) {
            assertThatThrownBy(() -> session.acquire(request))
                .isInstanceOfSatisfying(LockReentryException.class, exception -> {
                    assertThat(exception.context().key()).isEqualTo(new LockKey("orders:42"));
                    assertThat(exception.context().mode()).isEqualTo(LockMode.MUTEX);
                    assertThat(exception.context().waitPolicy()).isEqualTo(WaitPolicy.tryOnce());
                });
        }

        assertThat(backend.leaseCount()).isEqualTo(1);
    }

    @Test
    void validatorShouldIncludeRequestContextWhenReadModeUnsupported() {
        LockRequest request = LockRequest.read("orders:read", WaitPolicy.tryOnce());
        LockRequestValidator validator = new LockRequestValidator();

        assertThatThrownBy(() -> validator.validate(mutexOnlyBehavior(), request))
            .isInstanceOfSatisfying(UnsupportedLockCapabilityException.class, exception -> {
                assertThat(exception.context().key()).isEqualTo(new LockKey("orders:read"));
                assertThat(exception.context().mode()).isEqualTo(LockMode.READ);
            });
    }

    @Test
    void acquireShouldAllowDifferentKeysInSameSession() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        DefaultLockClient client = new DefaultLockClient(backend, standardBehavior());

        try (LockSession session = client.openSession();
             LockLease first = session.acquire(sampleRequest("orders:first"));
             LockLease second = session.acquire(sampleRequest("orders:second"))) {
            assertThat(first.key()).isEqualTo(new LockKey("orders:first"));
            assertThat(second.key()).isEqualTo(new LockKey("orders:second"));
        }
    }

    @Test
    void acquireShouldRejectFixedLeaseWhenCapabilityUnsupported() {
        TrackingBackend backend = new TrackingBackend();
        DefaultLockClient client = new DefaultLockClient(backend, mutexOnlyBehavior());
        LockRequest fixedLeaseRequest = new LockRequest(
            new LockKey("orders:fixed"),
            LockMode.MUTEX,
            WaitPolicy.tryOnce(),
            LeasePolicy.fixed(Duration.ofSeconds(5))
        );

        try (LockSession session = client.openSession()) {
            assertThatThrownBy(() -> session.acquire(fixedLeaseRequest))
                .isInstanceOf(UnsupportedLockCapabilityException.class)
                .hasMessageContaining("fixed lease");
        }

        assertThat(backend.leaseCount()).isEqualTo(0);
    }

    @Test
    void acquireShouldAllowRetryForSameKeyAfterBackendAcquireFailure() throws Exception {
        FailingFirstAcquireBackend backend = new FailingFirstAcquireBackend();
        DefaultLockClient client = new DefaultLockClient(backend, standardBehavior());
        RuntimeException acquireFailure = new LockBackendException("backend acquire failed");
        backend.failNextAcquire(acquireFailure);

        try (LockSession session = client.openSession()) {
            assertThatThrownBy(() -> session.acquire(sampleRequest("orders:retry")))
                .isSameAs(acquireFailure);

            try (LockLease lease = session.acquire(sampleRequest("orders:retry"))) {
                assertThat(lease.key()).isEqualTo(new LockKey("orders:retry"));
            }
        }

        assertThat(backend.leaseCount()).isEqualTo(1);
    }

    private static LockRequest sampleRequest(String key) {
        return new LockRequest(
            new LockKey(key),
            LockMode.MUTEX,
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

    private static final class TrackingBackend implements BackendClient {
        private final List<TrackingLease> leases = new CopyOnWriteArrayList<>();
        private final AtomicInteger backendCloseCount = new AtomicInteger();
        private final AtomicReference<RuntimeException> backendCloseFailure = new AtomicReference<>();

        @Override
        public BackendSession openSession() {
            return new BackendSession() {
                @Override
                public BackendLease acquire(LockRequest lockRequest) {
                    TrackingLease lease = new TrackingLease(
                        lockRequest.key(),
                        lockRequest.mode(),
                        new FencingToken(leases.size() + 1L)
                    );
                    leases.add(lease);
                    return lease;
                }

                @Override
                public SessionState state() {
                    return SessionState.ACTIVE;
                }

                @Override
                public void close() {
                    backendCloseCount.incrementAndGet();
                    RuntimeException failure = backendCloseFailure.get();
                    if (failure != null) {
                        throw failure;
                    }
                }
            };
        }

        TrackingLease lease(int index) {
            return leases.get(index);
        }

        int leaseCount() {
            return leases.size();
        }

        int backendCloseCount() {
            return backendCloseCount.get();
        }

        void failBackendClose(RuntimeException failure) {
            backendCloseFailure.set(failure);
        }

        @Override
        public void close() {
        }
    }

    private static final class BlockingAcquireBackend implements BackendClient {
        private final CountDownLatch acquireStarted = new CountDownLatch(1);
        private final CountDownLatch completeAcquire = new CountDownLatch(1);
        private final AtomicReference<TrackingLease> lease = new AtomicReference<>();

        @Override
        public BackendSession openSession() {
            return new BackendSession() {
                @Override
                public BackendLease acquire(LockRequest lockRequest) throws InterruptedException {
                    acquireStarted.countDown();
                    assertThat(completeAcquire.await(1, TimeUnit.SECONDS)).isTrue();
                    TrackingLease acquired = new TrackingLease(lockRequest.key(), lockRequest.mode(), new FencingToken(1L));
                    lease.set(acquired);
                    return acquired;
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

        boolean awaitAcquireStarted() throws InterruptedException {
            return acquireStarted.await(1, TimeUnit.SECONDS);
        }

        void completeAcquire() {
            completeAcquire.countDown();
        }

        TrackingLease lease() {
            return lease.get();
        }

        @Override
        public void close() {
        }
    }

    private static final class FailingFirstAcquireBackend implements BackendClient {
        private final List<TrackingLease> leases = new CopyOnWriteArrayList<>();
        private final AtomicReference<RuntimeException> acquireFailure = new AtomicReference<>();

        @Override
        public BackendSession openSession() {
            return new BackendSession() {
                @Override
                public BackendLease acquire(LockRequest lockRequest) {
                    RuntimeException failure = acquireFailure.getAndSet(null);
                    if (failure != null) {
                        throw failure;
                    }
                    TrackingLease lease = new TrackingLease(
                        lockRequest.key(),
                        lockRequest.mode(),
                        new FencingToken(leases.size() + 1L)
                    );
                    leases.add(lease);
                    return lease;
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

        void failNextAcquire(RuntimeException failure) {
            acquireFailure.set(failure);
        }

        int leaseCount() {
            return leases.size();
        }

        @Override
        public void close() {
        }
    }

    private static final class TrackingLease implements BackendLease {
        private final LockKey key;
        private final LockMode mode;
        private final FencingToken fencingToken;
        private final AtomicInteger releaseCount = new AtomicInteger();
        private final AtomicReference<LeaseState> state = new AtomicReference<>(LeaseState.ACTIVE);
        private final AtomicReference<RuntimeException> releaseFailure = new AtomicReference<>();

        private TrackingLease(LockKey key, LockMode mode, FencingToken fencingToken) {
            this.key = key;
            this.mode = mode;
            this.fencingToken = fencingToken;
        }

        @Override
        public LockKey key() {
            return key;
        }

        @Override
        public LockMode mode() {
            return mode;
        }

        @Override
        public FencingToken fencingToken() {
            return fencingToken;
        }

        @Override
        public LeaseState state() {
            return state.get();
        }

        @Override
        public boolean isValid() {
            return state.get() == LeaseState.ACTIVE;
        }

        @Override
        public void release() {
            releaseCount.incrementAndGet();
            RuntimeException failure = releaseFailure.get();
            if (failure != null) {
                throw failure;
            }
            state.set(LeaseState.RELEASED);
        }

        int releaseCount() {
            return releaseCount.get();
        }

        void failRelease(RuntimeException failure) {
            releaseFailure.set(failure);
        }
    }
}
