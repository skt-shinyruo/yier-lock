package com.mycorp.distributedlock.observability.springboot.support;

import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.observability.LockObservationEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeLockObservationSinkTest {

    @AfterEach
    void clearInterruptedFlag() {
        Thread.interrupted();
    }

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

    @Test
    void shouldRestoreInterruptFlagAndContinueWhenDelegateSneakyThrowsInterruptedException() {
        Thread.interrupted();
        LockObservationEvent event = event();
        AtomicReference<LockObservationEvent> recorded = new AtomicReference<>();
        CompositeLockObservationSink sink = new CompositeLockObservationSink(List.of(
            ignored -> sneakyThrow(new InterruptedException("sink interrupted")),
            recorded::set
        ));

        sink.record(event);

        assertThat(recorded).hasValue(event);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
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

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
