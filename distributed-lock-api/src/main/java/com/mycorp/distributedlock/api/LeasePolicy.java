package com.mycorp.distributedlock.api;

import java.time.Duration;

public record LeasePolicy(LeaseMode mode, Duration duration) {

    public LeasePolicy {
        if (mode == null) {
            throw new IllegalArgumentException("Lease mode is required");
        }
        if (duration == null) {
            throw new IllegalArgumentException("Lease duration is required");
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Lease duration must not be negative");
        }
        if (mode == LeaseMode.FIXED && duration.isZero()) {
            throw new IllegalArgumentException("Fixed lease policy requires a positive duration");
        }
        if (mode == LeaseMode.BACKEND_DEFAULT && !duration.isZero()) {
            throw new IllegalArgumentException("Backend-default lease policy must use Duration.ZERO");
        }
    }

    public static LeasePolicy backendDefault() {
        return new LeasePolicy(LeaseMode.BACKEND_DEFAULT, Duration.ZERO);
    }

    public static LeasePolicy fixed(Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException("Lease duration is required");
        }
        return new LeasePolicy(LeaseMode.FIXED, duration);
    }
}
