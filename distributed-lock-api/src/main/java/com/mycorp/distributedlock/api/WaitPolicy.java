package com.mycorp.distributedlock.api;

import java.time.Duration;

public record WaitPolicy(Duration duration) {

    public WaitPolicy {
        if (duration != null && duration.isNegative()) {
            throw new IllegalArgumentException("Wait duration must not be negative");
        }
    }

    public static WaitPolicy timed(Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException("Wait duration is required");
        }
        return new WaitPolicy(duration);
    }

    public static WaitPolicy indefinite() {
        return new WaitPolicy(null);
    }
}
