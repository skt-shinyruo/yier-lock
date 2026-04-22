package com.mycorp.distributedlock.observability.springboot.metrics;

import com.mycorp.distributedlock.observability.LockObservationEvent;
import com.mycorp.distributedlock.observability.LockObservationSink;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.util.Objects;

public final class MicrometerLockObservationSink implements LockObservationSink {
    private final MeterRegistry meterRegistry;

    public MicrometerLockObservationSink(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    @Override
    public void record(LockObservationEvent event) {
        Tags tags = Tags.of(
            "backend", event.backendId() == null ? "unknown" : event.backendId(),
            "surface", event.surface(),
            "operation", event.operation(),
            "outcome", event.outcome(),
            "mode", event.mode() == null ? "unknown" : event.mode().name().toLowerCase()
        );
        meterRegistry.timer("distributed.lock." + event.operation(), tags)
            .record(event.duration());
    }
}
