package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockContext;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.api.exception.LockReentryException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
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
        SynchronousLockExecutor executor = new DefaultSynchronousLockExecutor(new DefaultLockClient(
            backend,
            new SupportedLockModes(true, true, true)
        ));

        String result = executor.withLock(sampleRequest(), lease -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(backend.releaseCount()).hasValue(1);
        assertThat(backend.sessionCloseCount()).hasValue(1);
        assertThat(LockContext.currentLease()).isEmpty();
    }

    @Test
    void withLockShouldReleaseLeaseWhenActionFails() {
        TrackingBackend backend = new TrackingBackend();
        SynchronousLockExecutor executor = new DefaultSynchronousLockExecutor(new DefaultLockClient(
            backend,
            new SupportedLockModes(true, true, true)
        ));

        assertThatThrownBy(() -> executor.withLock(sampleRequest(), lease -> {
            throw new IllegalStateException("boom");
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("boom");

        assertThat(backend.releaseCount()).hasValue(1);
        assertThat(backend.sessionCloseCount()).hasValue(1);
        assertThat(LockContext.currentLease()).isEmpty();
    }

    @Test
    void withLockShouldPassAcquiredLeaseAndBindLockContextDuringAction() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        SynchronousLockExecutor executor = new DefaultSynchronousLockExecutor(new DefaultLockClient(
            backend,
            new SupportedLockModes(true, true, true)
        ));
        AtomicReference<LockLease> callbackLease = new AtomicReference<>();
        AtomicReference<LockLease> contextLease = new AtomicReference<>();

        String result = executor.withLock(sampleRequest(), lease -> {
            callbackLease.set(lease);
            contextLease.set(LockContext.requireCurrentLease());
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(callbackLease.get()).isSameAs(contextLease.get());
        assertThat(contextLease.get().fencingToken()).isEqualTo(new FencingToken(1L));
        assertThat(LockContext.currentLease()).isEmpty();
    }

    @Test
    void withLockShouldRejectSameKeyReentryBeforeOpeningNestedSession() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        SynchronousLockExecutor executor = new DefaultSynchronousLockExecutor(new DefaultLockClient(
            backend,
            new SupportedLockModes(true, true, true)
        ));

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
        SynchronousLockExecutor executor = new DefaultSynchronousLockExecutor(new DefaultLockClient(
            backend,
            new SupportedLockModes(true, true, true)
        ));

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
    void withLockShouldRestoreOuterLockContextAfterNestedDifferentKeyScope() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        SynchronousLockExecutor executor = new DefaultSynchronousLockExecutor(new DefaultLockClient(
            backend,
            new SupportedLockModes(true, true, true)
        ));
        AtomicReference<LockLease> outerBeforeInner = new AtomicReference<>();
        AtomicReference<LockLease> innerContext = new AtomicReference<>();
        AtomicReference<LockLease> outerAfterInner = new AtomicReference<>();

        String result = executor.withLock(sampleRequest("inventory:outer"), outerLease -> {
            outerBeforeInner.set(LockContext.requireCurrentLease());
            assertThat(outerBeforeInner.get()).isSameAs(outerLease);
            String innerResult = executor.withLock(sampleRequest("inventory:inner"), innerLease -> {
                innerContext.set(LockContext.requireCurrentLease());
                assertThat(innerContext.get()).isSameAs(innerLease);
                return "inner";
            });
            outerAfterInner.set(LockContext.requireCurrentLease());
            assertThat(outerAfterInner.get()).isSameAs(outerLease);
            return innerResult;
        });

        assertThat(result).isEqualTo("inner");
        assertThat(outerBeforeInner.get().key()).isEqualTo(new LockKey("inventory:outer"));
        assertThat(innerContext.get().key()).isEqualTo(new LockKey("inventory:inner"));
        assertThat(outerAfterInner.get()).isSameAs(outerBeforeInner.get());
        assertThat(LockContext.currentLease()).isEmpty();
    }

    @Test
    void withLockShouldRejectCompletionStageResults() {
        TrackingBackend backend = new TrackingBackend();
        SynchronousLockExecutor executor = new DefaultSynchronousLockExecutor(new DefaultLockClient(
            backend,
            new SupportedLockModes(true, true, true)
        ));

        assertThatThrownBy(() -> executor.withLock(sampleRequest(), lease -> CompletableFuture.completedFuture("ok")))
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("SynchronousLockExecutor only supports synchronous actions")
            .hasMessageContaining("CompletionStage");

        assertThat(backend.releaseCount()).hasValue(1);
        assertThat(backend.sessionCloseCount()).hasValue(1);
        assertThat(LockContext.currentLease()).isEmpty();
    }

    @Test
    void withLockShouldRejectFutureResults() {
        TrackingBackend backend = new TrackingBackend();
        SynchronousLockExecutor executor = new DefaultSynchronousLockExecutor(new DefaultLockClient(
            backend,
            new SupportedLockModes(true, true, true)
        ));
        FutureTask<String> futureTask = new FutureTask<>(() -> "ok");
        futureTask.run();

        assertThatThrownBy(() -> executor.withLock(sampleRequest(), lease -> futureTask))
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("SynchronousLockExecutor only supports synchronous actions")
            .hasMessageContaining("Future");

        assertThat(backend.releaseCount()).hasValue(1);
        assertThat(backend.sessionCloseCount()).hasValue(1);
        assertThat(LockContext.currentLease()).isEmpty();
    }

    @Test
    void withLockShouldRejectReactivePublisherResultsWhenReactiveStreamsIsPresent() throws Exception {
        Assumptions.assumeTrue(isReactiveStreamsPresent());
        TrackingBackend backend = new TrackingBackend();
        SynchronousLockExecutor executor = new DefaultSynchronousLockExecutor(new DefaultLockClient(
            backend,
            new SupportedLockModes(true, true, true)
        ));

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
        assertThat(LockContext.currentLease()).isEmpty();
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

    private static final class TrackingBackend implements LockBackend {
        private final AtomicInteger releaseCount = new AtomicInteger();
        private final AtomicInteger sessionCloseCount = new AtomicInteger();
        private final AtomicInteger openSessionCount = new AtomicInteger();

        @Override
        public BackendSession openSession() {
            openSessionCount.incrementAndGet();
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
