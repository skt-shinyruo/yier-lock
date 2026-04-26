package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
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
        DefaultLockClient client = new DefaultLockClient(backend, new SupportedLockModes(true, true));
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
        DefaultLockClient client = new DefaultLockClient(backend, new SupportedLockModes(true, true));
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
        DefaultLockClient client = new DefaultLockClient(backend, new SupportedLockModes(true, true));
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
        DefaultLockClient client = new DefaultLockClient(backend, new SupportedLockModes(true, true));
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
        DefaultLockClient client = new DefaultLockClient(backend, new SupportedLockModes(true, true));
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
        DefaultLockClient client = new DefaultLockClient(backend, new SupportedLockModes(true, true));
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
        DefaultLockClient client = new DefaultLockClient(backend, new SupportedLockModes(true, true));
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
        DefaultLockClient client = new DefaultLockClient(backend, new SupportedLockModes(true, true));
        LockSession session = client.openSession();

        session.close();

        assertThatThrownBy(() -> session.acquire(sampleRequest("orders:closed")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Lock session is already closed");
    }

    private static LockRequest sampleRequest(String key) {
        return new LockRequest(
            new LockKey(key),
            LockMode.MUTEX,
            WaitPolicy.timed(Duration.ofSeconds(1))
        );
    }

    private static final class TrackingBackend implements LockBackend {
        private final List<TrackingLease> leases = new CopyOnWriteArrayList<>();
        private final AtomicInteger backendCloseCount = new AtomicInteger();
        private final AtomicReference<RuntimeException> backendCloseFailure = new AtomicReference<>();

        @Override
        public BackendSession openSession() {
            return new BackendSession() {
                @Override
                public BackendLockLease acquire(LockRequest lockRequest) {
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

    private static final class BlockingAcquireBackend implements LockBackend {
        private final CountDownLatch acquireStarted = new CountDownLatch(1);
        private final CountDownLatch completeAcquire = new CountDownLatch(1);
        private final AtomicReference<TrackingLease> lease = new AtomicReference<>();

        @Override
        public BackendSession openSession() {
            return new BackendSession() {
                @Override
                public BackendLockLease acquire(LockRequest lockRequest) throws InterruptedException {
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

    private static final class TrackingLease implements BackendLockLease {
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
