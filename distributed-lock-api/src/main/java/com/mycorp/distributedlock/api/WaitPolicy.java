package com.mycorp.distributedlock.api;

import java.time.Duration;

public record WaitPolicy(WaitMode mode, Duration timeout) {

    public WaitPolicy {
        if (mode == null) {
            throw new IllegalArgumentException("Wait mode is required");
        }
        if (timeout == null) {
            throw new IllegalArgumentException("Wait timeout is required");
        }
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Wait timeout must not be negative");
        }
        if (mode == WaitMode.TIMED && timeout.isZero()) {
            throw new IllegalArgumentException("Timed wait policy requires a positive timeout");
        }
        if (mode != WaitMode.TIMED && !timeout.isZero()) {
            throw new IllegalArgumentException("Non-timed wait policy must use Duration.ZERO");
        }
    }

    public static WaitPolicy tryOnce() {
        return new WaitPolicy(WaitMode.TRY_ONCE, Duration.ZERO);
    }

    public static WaitPolicy timed(Duration timeout) {
        if (timeout == null) {
            throw new IllegalArgumentException("Wait timeout is required");
        }
        return new WaitPolicy(WaitMode.TIMED, timeout);
    }

    public static WaitPolicy indefinite() {
        return new WaitPolicy(WaitMode.INDEFINITE, Duration.ZERO);
    }
}
