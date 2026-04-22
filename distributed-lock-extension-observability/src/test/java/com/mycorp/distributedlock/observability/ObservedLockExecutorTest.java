package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockExecutor;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private static LockRequest sampleRequest() {
        return new LockRequest(
            new LockKey("orders:42"),
            LockMode.MUTEX,
            WaitPolicy.timed(Duration.ofSeconds(1))
        );
    }
}
