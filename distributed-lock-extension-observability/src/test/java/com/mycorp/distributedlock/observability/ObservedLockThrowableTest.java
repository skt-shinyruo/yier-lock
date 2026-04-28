package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.LockedAction;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.WaitPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservedLockThrowableTest {

    @AfterEach
    void clearInterruptedFlag() {
        Thread.interrupted();
    }

    @Test
    void executorShouldRecordAndRethrowAssertionError() {
        List<LockObservationEvent> events = new ArrayList<>();
        AssertionError error = new AssertionError("boom");
        SynchronousLockExecutor delegate = new SynchronousLockExecutor() {
            @Override
            public <T> T withLock(LockRequest request, LockedAction<T> action) {
                throw error;
            }
        };
        ObservedLockExecutor executor = new ObservedLockExecutor(delegate, events::add, "test", false);

        assertThatThrownBy(() -> executor.withLock(request(), lease -> "unused"))
            .isSameAs(error);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).error()).isSameAs(error);
    }

    @Test
    void executorShouldRethrowOriginalAssertionErrorWhenSinkThrowsAssertionError() {
        AssertionError error = new AssertionError("boom");
        SynchronousLockExecutor delegate = new SynchronousLockExecutor() {
            @Override
            public <T> T withLock(LockRequest request, LockedAction<T> action) {
                throw error;
            }
        };
        ObservedLockExecutor executor = new ObservedLockExecutor(delegate, event -> {
            throw new AssertionError("sink boom");
        }, "test", false);

        assertThatThrownBy(() -> executor.withLock(request(), lease -> "unused"))
            .isSameAs(error);
    }

    @Test
    void sessionShouldRethrowOriginalAssertionErrorWhenSinkThrowsAssertionError() {
        AssertionError error = new AssertionError("boom");
        LockSession delegate = new LockSession() {
            @Override
            public LockLease acquire(LockRequest request) {
                throw error;
            }

            @Override
            public SessionState state() {
                return SessionState.ACTIVE;
            }

            @Override
            public void close() {
            }
        };
        ObservedLockSession session = new ObservedLockSession(delegate, event -> {
            throw new AssertionError("sink boom");
        }, "test", false);

        assertThatThrownBy(() -> session.acquire(request()))
            .isSameAs(error);
    }

    @Test
    void executorShouldRestoreInterruptFlagWhenSinkSneakyThrowsInterruptedException() throws Exception {
        Thread.interrupted();
        SynchronousLockExecutor delegate = new SynchronousLockExecutor() {
            @Override
            public <T> T withLock(LockRequest request, LockedAction<T> action) throws Exception {
                return action.execute(null);
            }
        };
        ObservedLockExecutor executor = new ObservedLockExecutor(delegate, event -> {
            sneakyThrow(new InterruptedException("sink interrupted"));
        }, "test", false);

        String result = executor.withLock(request(), lease -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    private static LockRequest request() {
        return new LockRequest(new LockKey("observed:error"), LockMode.MUTEX, WaitPolicy.timed(Duration.ofMillis(10)));
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
