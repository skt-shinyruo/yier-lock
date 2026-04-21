package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockExecutor;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultLockExecutorTest {

    @Test
    void withLockShouldReleaseLeaseAfterAction() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        LockExecutor executor = new DefaultLockExecutor(new DefaultLockClient(
            backend,
            new SupportedLockModes(true, true)
        ));

        String result = executor.withLock(sampleRequest(), () -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(backend.releaseCount()).hasValue(1);
        assertThat(backend.sessionCloseCount()).hasValue(1);
    }

    @Test
    void withLockShouldReleaseLeaseWhenActionFails() {
        TrackingBackend backend = new TrackingBackend();
        LockExecutor executor = new DefaultLockExecutor(new DefaultLockClient(
            backend,
            new SupportedLockModes(true, true)
        ));

        assertThatThrownBy(() -> executor.withLock(sampleRequest(), () -> {
            throw new IllegalStateException("boom");
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("boom");

        assertThat(backend.releaseCount()).hasValue(1);
        assertThat(backend.sessionCloseCount()).hasValue(1);
    }

    @Test
    void withLockShouldExposeCurrentFencingTokenDuringAction() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        LockExecutor executor = new DefaultLockExecutor(new DefaultLockClient(
            backend,
            new SupportedLockModes(true, true)
        ));
        AtomicLong observedToken = new AtomicLong();

        String result = executor.withLock(sampleRequest(), () -> {
            observedToken.set(CurrentLockContext.requireCurrentFencingToken().value());
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(observedToken).hasValue(1L);
    }

    @Test
    void withLockShouldRejectCompletionStageResults() {
        TrackingBackend backend = new TrackingBackend();
        LockExecutor executor = new DefaultLockExecutor(new DefaultLockClient(
            backend,
            new SupportedLockModes(true, true)
        ));

        assertThatThrownBy(() -> executor.withLock(sampleRequest(), () -> CompletableFuture.completedFuture("ok")))
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("CompletionStage");

        assertThat(backend.releaseCount()).hasValue(1);
        assertThat(backend.sessionCloseCount()).hasValue(1);
    }

    @Test
    void withLockShouldRejectFutureResults() {
        TrackingBackend backend = new TrackingBackend();
        LockExecutor executor = new DefaultLockExecutor(new DefaultLockClient(
            backend,
            new SupportedLockModes(true, true)
        ));
        FutureTask<String> futureTask = new FutureTask<>(() -> "ok");
        futureTask.run();

        assertThatThrownBy(() -> executor.withLock(sampleRequest(), () -> futureTask))
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("Future");

        assertThat(backend.releaseCount()).hasValue(1);
        assertThat(backend.sessionCloseCount()).hasValue(1);
    }

    @Test
    void withLockShouldRejectReactivePublisherResultsWhenReactiveStreamsIsPresent() throws Exception {
        Assumptions.assumeTrue(isReactiveStreamsPresent());
        TrackingBackend backend = new TrackingBackend();
        LockExecutor executor = new DefaultLockExecutor(new DefaultLockClient(
            backend,
            new SupportedLockModes(true, true)
        ));

        ClassLoader classLoader = DefaultLockExecutorTest.class.getClassLoader();
        Class<?> publisherType = Class.forName("org.reactivestreams.Publisher", false, classLoader);
        Object publisher = Proxy.newProxyInstance(
            classLoader,
            new Class<?>[]{publisherType},
            (proxy, method, args) -> null
        );

        assertThatThrownBy(() -> executor.withLock(sampleRequest(), () -> publisher))
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("Publisher");

        assertThat(backend.releaseCount()).hasValue(1);
        assertThat(backend.sessionCloseCount()).hasValue(1);
    }

    private static LockRequest sampleRequest() {
        return new LockRequest(
            new LockKey("inventory"),
            LockMode.MUTEX,
            WaitPolicy.timed(Duration.ofSeconds(1))
        );
    }

    private static boolean isReactiveStreamsPresent() {
        try {
            Class.forName("org.reactivestreams.Publisher", false, DefaultLockExecutorTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }

    private static final class TrackingBackend implements LockBackend {
        private final AtomicInteger releaseCount = new AtomicInteger();
        private final AtomicInteger sessionCloseCount = new AtomicInteger();

        @Override
        public BackendSession openSession() {
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
