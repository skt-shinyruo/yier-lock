package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.api.BackendCostModel;
import com.mycorp.distributedlock.api.FairnessSemantics;
import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.FencingSemantics;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LeaseSemantics;
import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.OwnershipLossSemantics;
import com.mycorp.distributedlock.api.SessionSemantics;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.WaitSemantics;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.api.exception.LockReentryException;
import com.mycorp.distributedlock.spi.BackendClient;
import com.mycorp.distributedlock.spi.BackendLease;
import com.mycorp.distributedlock.spi.BackendSession;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultSynchronousLockExecutorTest {

    @Test
    void withLockShouldReleaseLeaseAfterAction() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        SynchronousLockExecutor executor = executor(backend);

        String result = executor.withLock(sampleRequest(), lease -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(backend.releaseCount()).hasValue(1);
        assertThat(backend.sessionCloseCount()).hasValue(1);
        assertThat(SynchronousLockScope.contains(new LockKey("inventory"))).isFalse();
    }

    @Test
    void withLockShouldReleaseLeaseWhenActionFails() {
        TrackingBackend backend = new TrackingBackend();
        SynchronousLockExecutor executor = executor(backend);

        assertThatThrownBy(() -> executor.withLock(sampleRequest(), lease -> {
            throw new IllegalStateException("boom");
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("boom");

        assertThat(backend.releaseCount()).hasValue(1);
        assertThat(backend.sessionCloseCount()).hasValue(1);
        assertThat(SynchronousLockScope.contains(new LockKey("inventory"))).isFalse();
    }

    @Test
    void withLockShouldPassAcquiredLeaseAndBindSynchronousScopeDuringAction() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        SynchronousLockExecutor executor = executor(backend);
        AtomicReference<LockLease> callbackLease = new AtomicReference<>();
        AtomicReference<Boolean> scopeContainedLease = new AtomicReference<>();

        String result = executor.withLock(sampleRequest(), lease -> {
            callbackLease.set(lease);
            scopeContainedLease.set(SynchronousLockScope.contains(lease.key()));
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(callbackLease.get().fencingToken()).isEqualTo(new FencingToken(1L));
        assertThat(scopeContainedLease.get()).isTrue();
        assertThat(SynchronousLockScope.contains(new LockKey("inventory"))).isFalse();
    }

    @Test
    void withLockShouldRejectSameKeyReentryBeforeOpeningNestedSession() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        SynchronousLockExecutor executor = executor(backend);

        assertThatThrownBy(() -> executor.withLock(sampleRequest(), outerLease ->
            executor.withLock(sampleRequest(), innerLease -> "nested")
        ))
            .isInstanceOfSatisfying(LockReentryException.class, exception -> {
                assertThat(exception).hasMessageContaining("inventory");
                assertThat(exception.context().key()).isEqualTo(new LockKey("inventory"));
                assertThat(exception.context().mode()).isEqualTo(LockMode.MUTEX);
                assertThat(exception.context().waitPolicy()).isEqualTo(WaitPolicy.timed(Duration.ofSeconds(1)));
            });

        assertThat(backend.openSessionCount()).hasValue(1);
        assertThat(backend.releaseCount()).hasValue(1);
        assertThat(backend.sessionCloseCount()).hasValue(1);
    }

    @Test
    void withLockShouldRejectSameKeyReentryAcrossNestedDifferentKeyScope() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        SynchronousLockExecutor executor = executor(backend);

        assertThatThrownBy(() -> executor.withLock(sampleRequest("inventory:outer"), outerLease ->
            executor.withLock(sampleRequest("inventory:inner"), innerLease ->
                executor.withLock(sampleRequest("inventory:outer"), repeatedLease -> "nested")
            )
        ))
            .isInstanceOf(LockReentryException.class)
            .hasMessageContaining("inventory:outer");

        assertThat(backend.openSessionCount()).hasValue(2);
        assertThat(backend.releaseCount()).hasValue(2);
        assertThat(backend.sessionCloseCount()).hasValue(2);
    }

    @Test
    void withLockShouldPropagateAcquireInterruptedException() {
        SynchronousLockExecutor executor = new DefaultSynchronousLockExecutor(new LockClient() {
            @Override
            public LockSession openSession() {
                return new LockSession() {
                    @Override
                    public LockLease acquire(LockRequest request) throws InterruptedException {
                        throw new InterruptedException("interrupted acquire");
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

            @Override
            public void close() {
            }
        });

        assertThatThrownBy(() -> executor.withLock(LockRequest.mutex("orders:42", WaitPolicy.tryOnce()), lease -> null))
            .isInstanceOf(InterruptedException.class)
            .hasMessage("interrupted acquire");
    }

    @Test
    void withLockShouldRestoreOuterSynchronousScopeAfterNestedDifferentKeyScope() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        SynchronousLockExecutor executor = executor(backend);
        LockKey outerKey = new LockKey("inventory:outer");
        LockKey innerKey = new LockKey("inventory:inner");
        AtomicReference<Boolean> outerBeforeInner = new AtomicReference<>();
        AtomicReference<Boolean> innerContainsOuter = new AtomicReference<>();
        AtomicReference<Boolean> innerContainsInner = new AtomicReference<>();
        AtomicReference<Boolean> outerAfterInner = new AtomicReference<>();

        String result = executor.withLock(sampleRequest("inventory:outer"), outerLease -> {
            outerBeforeInner.set(SynchronousLockScope.contains(outerKey));
            String innerResult = executor.withLock(sampleRequest("inventory:inner"), innerLease -> {
                innerContainsOuter.set(SynchronousLockScope.contains(outerKey));
                innerContainsInner.set(SynchronousLockScope.contains(innerKey));
                return "inner";
            });
            outerAfterInner.set(SynchronousLockScope.contains(outerKey));
            return innerResult;
        });

        assertThat(result).isEqualTo("inner");
        assertThat(outerBeforeInner.get()).isTrue();
        assertThat(innerContainsOuter.get()).isTrue();
        assertThat(innerContainsInner.get()).isTrue();
        assertThat(outerAfterInner.get()).isTrue();
        assertThat(SynchronousLockScope.contains(outerKey)).isFalse();
        assertThat(SynchronousLockScope.contains(innerKey)).isFalse();
    }

    @Test
    void withLockShouldRejectCompletionStageResults() {
        TrackingBackend backend = new TrackingBackend();
        SynchronousLockExecutor executor = executor(backend);

        assertThatThrownBy(() -> executor.withLock(sampleRequest(), lease -> CompletableFuture.completedFuture("ok")))
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("SynchronousLockExecutor only supports synchronous actions")
            .hasMessageContaining("CompletionStage");

        assertThat(backend.releaseCount()).hasValue(1);
        assertThat(backend.sessionCloseCount()).hasValue(1);
        assertThat(SynchronousLockScope.contains(new LockKey("inventory"))).isFalse();
    }

    @Test
    void withLockShouldRejectFutureResults() {
        TrackingBackend backend = new TrackingBackend();
        SynchronousLockExecutor executor = executor(backend);
        FutureTask<String> futureTask = new FutureTask<>(() -> "ok");
        futureTask.run();

        assertThatThrownBy(() -> executor.withLock(sampleRequest(), lease -> futureTask))
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("SynchronousLockExecutor only supports synchronous actions")
            .hasMessageContaining("Future");

        assertThat(backend.releaseCount()).hasValue(1);
        assertThat(backend.sessionCloseCount()).hasValue(1);
        assertThat(SynchronousLockScope.contains(new LockKey("inventory"))).isFalse();
    }

    @Test
    void withLockShouldRejectReactivePublisherResultsWhenReactiveStreamsIsPresent() throws Exception {
        Assumptions.assumeTrue(isReactiveStreamsPresent());
        TrackingBackend backend = new TrackingBackend();
        SynchronousLockExecutor executor = executor(backend);

        ClassLoader classLoader = DefaultSynchronousLockExecutorTest.class.getClassLoader();
        Class<?> publisherType = Class.forName("org.reactivestreams.Publisher", false, classLoader);
        Object publisher = Proxy.newProxyInstance(
            classLoader,
            new Class<?>[]{publisherType},
            (proxy, method, args) -> null
        );

        assertThatThrownBy(() -> executor.withLock(sampleRequest(), lease -> publisher))
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("SynchronousLockExecutor only supports synchronous actions")
            .hasMessageContaining("Publisher");

        assertThat(backend.releaseCount()).hasValue(1);
        assertThat(backend.sessionCloseCount()).hasValue(1);
        assertThat(SynchronousLockScope.contains(new LockKey("inventory"))).isFalse();
    }

    private static LockRequest sampleRequest() {
        return sampleRequest("inventory");
    }

    private static LockRequest sampleRequest(String key) {
        return new LockRequest(
            new LockKey(key),
            LockMode.MUTEX,
            WaitPolicy.timed(Duration.ofSeconds(1))
        );
    }

    private static boolean isReactiveStreamsPresent() {
        try {
            Class.forName("org.reactivestreams.Publisher", false, DefaultSynchronousLockExecutorTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }

    private static SynchronousLockExecutor executor(TrackingBackend backend) {
        return new DefaultSynchronousLockExecutor(new DefaultLockClient(backend, standardBehavior()));
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

    private static final class TrackingBackend implements BackendClient {
        private final AtomicInteger releaseCount = new AtomicInteger();
        private final AtomicInteger sessionCloseCount = new AtomicInteger();
        private final AtomicInteger openSessionCount = new AtomicInteger();

        @Override
        public BackendSession openSession() {
            openSessionCount.incrementAndGet();
            return new BackendSession() {
                @Override
                public BackendLease acquire(LockRequest lockRequest) {
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

        AtomicInteger openSessionCount() {
            return openSessionCount;
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
    ) implements BackendLease {

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
