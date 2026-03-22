package com.mycorp.distributedlock.core.backend;

import java.time.Duration;
import java.util.Objects;

public record WaitPolicy(Duration waitTime, boolean unbounded) {

    public WaitPolicy {
        Objects.requireNonNull(waitTime, "waitTime");
        if (waitTime.isNegative()) {
            throw new IllegalArgumentException("waitTime cannot be negative");
        }
    }

    public static WaitPolicy indefinite() {
        return new WaitPolicy(Duration.ZERO, true);
    }

    public static WaitPolicy timed(Duration waitTime) {
        return new WaitPolicy(waitTime, false);
    }
}
