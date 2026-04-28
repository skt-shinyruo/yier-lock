package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockedAction;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.WaitPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservedLockThrowableTest {

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

    private static LockRequest request() {
        return new LockRequest(new LockKey("observed:error"), LockMode.MUTEX, WaitPolicy.timed(Duration.ofMillis(10)));
    }
}
