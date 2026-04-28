package com.mycorp.distributedlock.observability.springboot.support;

import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.observability.LockObservationEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeLockObservationSinkTest {

    @Test
    void shouldContinueToLaterSinkWhenEarlierSinkThrowsAssertionError() {
        LockObservationEvent event = event();
        AtomicReference<LockObservationEvent> recorded = new AtomicReference<>();
        CompositeLockObservationSink sink = new CompositeLockObservationSink(List.of(
            ignored -> {
                throw new AssertionError("sink failed");
            },
            recorded::set
        ));

        sink.record(event);

        assertThat(recorded).hasValue(event);
    }

    private static LockObservationEvent event() {
        return new LockObservationEvent(
            "test",
            "client",
            "acquire",
            "success",
            LockMode.MUTEX,
            null,
            Duration.ofMillis(1),
            null
        );
    }
}
