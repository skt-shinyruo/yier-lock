package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockExecutor;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.runtime.LockRuntime;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObservedLockExecutorTest {

    @Test
    void withLockShouldRecordSuccessfulScopeCompletion() throws Exception {
        List<LockObservationEvent> events = new ArrayList<>();
        LockExecutor executor = new ObservedLockExecutor(new LockExecutor() {
            @Override
            public <T> T withLock(LockRequest request, com.mycorp.distributedlock.api.LockedSupplier<T> action) throws Exception {
                return action.get();
            }
        }, events::add, "redis", false);

        String result = executor.withLock(sampleRequest(), () -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.surface()).isEqualTo("executor");
            assertThat(event.operation()).isEqualTo("scope");
            assertThat(event.outcome()).isEqualTo("success");
        });
    }

    @Test
    void withLockShouldRecordFailedScopeCompletion() {
        List<LockObservationEvent> events = new ArrayList<>();
        LockExecutor executor = new ObservedLockExecutor(new LockExecutor() {
            @Override
            public <T> T withLock(LockRequest request, com.mycorp.distributedlock.api.LockedSupplier<T> action) throws Exception {
                return action.get();
            }
        }, events::add, "redis", true);

        assertThatThrownBy(() -> executor.withLock(sampleRequest(), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo("failure");
            assertThat(event.key()).isEqualTo("orders:42");
            assertThat(event.error()).isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void withLockShouldCollapseTimeoutIntoFailureScopeOutcome() {
        List<LockObservationEvent> events = new ArrayList<>();
        LockExecutor executor = new ObservedLockExecutor(new LockExecutor() {
            @Override
            public <T> T withLock(LockRequest request, com.mycorp.distributedlock.api.LockedSupplier<T> action) {
                throw new LockAcquisitionTimeoutException("timed out");
            }
        }, events::add, "redis", true);

        assertThatThrownBy(() -> executor.withLock(sampleRequest(), () -> "ignored"))
            .isInstanceOf(LockAcquisitionTimeoutException.class);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo("failure");
            assertThat(event.key()).isEqualTo("orders:42");
            assertThat(event.error()).isInstanceOf(LockAcquisitionTimeoutException.class);
        });
    }

    @Test
    void withLockShouldNotLetSinkFailureMaskSuccessfulScope() throws Exception {
        LockExecutor executor = new ObservedLockExecutor(new LockExecutor() {
            @Override
            public <T> T withLock(LockRequest request, com.mycorp.distributedlock.api.LockedSupplier<T> action) throws Exception {
                return action.get();
            }
        }, event -> {
            throw new IllegalStateException("sink failed");
        }, "redis", false);

        assertThat(executor.withLock(sampleRequest(), () -> "ok")).isEqualTo("ok");
    }

    @Test
    void withLockShouldNotLetSinkFailureMaskScopeFailure() {
        IllegalStateException actionFailure = new IllegalStateException("boom");
        LockExecutor executor = new ObservedLockExecutor(new LockExecutor() {
            @Override
            public <T> T withLock(LockRequest request, com.mycorp.distributedlock.api.LockedSupplier<T> action) throws Exception {
                return action.get();
            }
        }, event -> {
            throw new IllegalStateException("sink failed");
        }, "redis", false);

        assertThatThrownBy(() -> executor.withLock(sampleRequest(), () -> {
            throw actionFailure;
        })).isSameAs(actionFailure);
    }

    @Test
    void observedRuntimeShouldEmitAcquireAndScopeEventsFromObservedClient() throws Exception {
        LockLease lease = mock(LockLease.class);
        LockSession session = mock(LockSession.class);
        when(session.acquire(sampleRequest())).thenReturn(lease);

        LockClient client = mock(LockClient.class);
        when(client.openSession()).thenReturn(session);

        LockRuntime delegate = mock(LockRuntime.class);
        when(delegate.lockClient()).thenReturn(client);
        when(delegate.lockExecutor()).thenReturn(mock(LockExecutor.class));

        List<LockObservationEvent> events = new ArrayList<>();
        LockRuntime observedRuntime = ObservedLockRuntime.decorate(delegate, events::add, "redis", false);

        String result = observedRuntime.lockExecutor().withLock(sampleRequest(), () -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).operation()).isEqualTo("acquire");
        assertThat(events.get(0).surface()).isEqualTo("client");
        assertThat(events.get(1).operation()).isEqualTo("scope");
        assertThat(events.get(1).surface()).isEqualTo("executor");
        verify(client).openSession();
        verify(session).acquire(sampleRequest());
        verify(delegate, never()).lockExecutor();
    }

    private static LockRequest sampleRequest() {
        return new LockRequest(
            new LockKey("orders:42"),
            LockMode.MUTEX,
            WaitPolicy.timed(Duration.ofSeconds(1))
        );
    }
}
