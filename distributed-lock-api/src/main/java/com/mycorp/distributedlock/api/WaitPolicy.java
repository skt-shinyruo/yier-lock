package com.mycorp.distributedlock.api;

import java.time.Duration;

public record WaitPolicy(Duration waitTime, boolean unbounded) {

    public WaitPolicy {
        if (waitTime == null) {
            throw new IllegalArgumentException("Wait time is required");
        }
        if (waitTime.isNegative()) {
            throw new IllegalArgumentException("Wait duration must not be negative");
        }
    }

    public static WaitPolicy timed(Duration waitTime) {
        if (waitTime == null) {
            throw new IllegalArgumentException("Wait duration is required");
        }
        return new WaitPolicy(waitTime, false);
    }

    public static WaitPolicy indefinite() {
        return new WaitPolicy(Duration.ZERO, true);
    }
}
