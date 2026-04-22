package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockMode;

import java.time.Duration;

public record LockObservationEvent(
    String backendId,
    String surface,
    String operation,
    String outcome,
    LockMode mode,
    String key,
    Duration duration,
    Throwable error
) {
}
