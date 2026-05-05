package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.api.BackendCostModel;
import com.mycorp.distributedlock.api.FairnessSemantics;
import com.mycorp.distributedlock.api.FencingSemantics;
import com.mycorp.distributedlock.api.LeaseSemantics;
import com.mycorp.distributedlock.api.LockedAction;
import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.api.OwnershipLossSemantics;
import com.mycorp.distributedlock.api.RuntimeInfo;
import com.mycorp.distributedlock.api.SessionSemantics;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.WaitSemantics;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
    void observedRuntimeShouldUseDelegateExecutorAndObserveScope() throws Exception {
        LockLease lease = mock(LockLease.class);
        LockClient client = mock(LockClient.class);
        SynchronousLockExecutor delegateExecutor = new SynchronousLockExecutor() {
            @Override
            public <T> T withLock(LockRequest request, LockedAction<T> action) throws Exception {
                return action.execute(lease);
            }
        };

        LockRuntime delegate = mock(LockRuntime.class);
        when(delegate.lockClient()).thenReturn(client);
        when(delegate.synchronousLockExecutor()).thenReturn(delegateExecutor);
        when(delegate.info()).thenReturn(new RuntimeInfo("redis", "Redis", behavior(), "test"));

        List<LockObservationEvent> events = new ArrayList<>();
        LockRuntime observedRuntime = new ObservedLockRuntimeDecorator(events::add, false).decorate(delegate);

        String result = observedRuntime.synchronousLockExecutor().withLock(sampleRequest(), leaseArgument -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.operation()).isEqualTo("scope");
            assertThat(event.surface()).isEqualTo("executor");
            assertThat(event.backendId()).isEqualTo("redis");
        });
        verify(client, never()).openSession();
        verify(delegate).synchronousLockExecutor();
    }

    @Test
    void observedRuntimeShouldPreserveDelegateMetadata() {
        LockClient client = mock(LockClient.class);
        LockRuntime delegate = mock(LockRuntime.class);
        when(delegate.lockClient()).thenReturn(client);
        when(delegate.synchronousLockExecutor()).thenReturn(mock(SynchronousLockExecutor.class));
        RuntimeInfo info = new RuntimeInfo("redis-primary", "Redis Primary", behavior(), "test");
        when(delegate.info()).thenReturn(info);

        LockRuntime observedRuntime = new ObservedLockRuntimeDecorator(event -> { }, false).decorate(delegate);

        assertThat(observedRuntime.info()).isSameAs(info);
    }

    private static LockRequest sampleRequest() {
        return new LockRequest(
            new LockKey("orders:42"),
            LockMode.MUTEX,
            WaitPolicy.timed(Duration.ofSeconds(1))
        );
    }

    private static BackendBehavior behavior() {
        return BackendBehavior.builder()
            .lockModes(Set.of(LockMode.MUTEX, LockMode.READ, LockMode.WRITE))
            .fencing(FencingSemantics.MONOTONIC_PER_KEY)
            .leaseSemantics(Set.of(LeaseSemantics.FIXED_TTL))
            .session(SessionSemantics.CLIENT_LOCAL_TTL)
            .wait(WaitSemantics.POLLING)
            .fairness(FairnessSemantics.NONE)
            .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
            .costModel(BackendCostModel.CHEAP_SESSION)
            .build();
    }
}
