package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockedAction;
import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
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
        SynchronousLockExecutor executor = new ObservedLockExecutor(new SynchronousLockExecutor() {
            @Override
            public <T> T withLock(LockRequest request, LockedAction<T> action) throws Exception {
                return action.execute(mock(LockLease.class));
            }
        }, events::add, "redis", false);

        String result = executor.withLock(sampleRequest(), lease -> "ok");

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
        SynchronousLockExecutor executor = new ObservedLockExecutor(new SynchronousLockExecutor() {
            @Override
            public <T> T withLock(LockRequest request, LockedAction<T> action) throws Exception {
                return action.execute(mock(LockLease.class));
            }
        }, events::add, "redis", true);

        assertThatThrownBy(() -> executor.withLock(sampleRequest(), lease -> {
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
        SynchronousLockExecutor executor = new ObservedLockExecutor(new SynchronousLockExecutor() {
            @Override
            public <T> T withLock(LockRequest request, LockedAction<T> action) {
                throw new LockAcquisitionTimeoutException("timed out");
            }
        }, events::add, "redis", true);

        assertThatThrownBy(() -> executor.withLock(sampleRequest(), lease -> "ignored"))
            .isInstanceOf(LockAcquisitionTimeoutException.class);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo("failure");
            assertThat(event.key()).isEqualTo("orders:42");
            assertThat(event.error()).isInstanceOf(LockAcquisitionTimeoutException.class);
        });
    }

    @Test
    void withLockShouldNotLetSinkFailureMaskSuccessfulScope() throws Exception {
        SynchronousLockExecutor executor = new ObservedLockExecutor(new SynchronousLockExecutor() {
            @Override
            public <T> T withLock(LockRequest request, LockedAction<T> action) throws Exception {
                return action.execute(mock(LockLease.class));
            }
        }, event -> {
            throw new IllegalStateException("sink failed");
        }, "redis", false);

        String result = executor.withLock(sampleRequest(), lease -> "ok");

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void withLockShouldNotLetSinkFailureMaskScopeFailure() {
        IllegalStateException actionFailure = new IllegalStateException("boom");
        SynchronousLockExecutor executor = new ObservedLockExecutor(new SynchronousLockExecutor() {
            @Override
            public <T> T withLock(LockRequest request, LockedAction<T> action) throws Exception {
                return action.execute(mock(LockLease.class));
            }
        }, event -> {
            throw new IllegalStateException("sink failed");
        }, "redis", false);

        assertThatThrownBy(() -> executor.withLock(sampleRequest(), lease -> {
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
        when(delegate.synchronousLockExecutor()).thenReturn(mock(SynchronousLockExecutor.class));

        List<LockObservationEvent> events = new ArrayList<>();
        LockRuntime observedRuntime = ObservedLockRuntime.decorate(delegate, events::add, "redis", false);

        String result = observedRuntime.synchronousLockExecutor().withLock(sampleRequest(), leaseArgument -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).operation()).isEqualTo("acquire");
        assertThat(events.get(0).surface()).isEqualTo("client");
        assertThat(events.get(1).operation()).isEqualTo("scope");
        assertThat(events.get(1).surface()).isEqualTo("executor");
        verify(client).openSession();
        verify(session).acquire(sampleRequest());
        verify(delegate, never()).synchronousLockExecutor();
    }

    private static LockRequest sampleRequest() {
        return new LockRequest(
            new LockKey("orders:42"),
            LockMode.MUTEX,
            WaitPolicy.timed(Duration.ofSeconds(1))
        );
    }
}
