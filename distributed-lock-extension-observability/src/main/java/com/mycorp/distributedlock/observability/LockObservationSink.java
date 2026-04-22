package com.mycorp.distributedlock.observability;

@FunctionalInterface
public interface LockObservationSink {

    LockObservationSink NOOP = event -> {
    };

    void record(LockObservationEvent event);
}
