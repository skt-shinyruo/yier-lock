package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;

import java.time.Duration;

final class LockObservationSupport {

    private LockObservationSupport() {
    }

    static Duration durationSince(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }

    static String keyFor(LockRequest request, boolean includeKey) {
        return includeKey ? request.key().value() : null;
    }

    static String outcomeFor(Throwable throwable) {
        if (throwable == null) {
            return "success";
        }
        if (throwable instanceof LockAcquisitionTimeoutException) {
            return "timeout";
        }
        if (throwable instanceof InterruptedException) {
            return "interrupted";
        }
        return "failure";
    }
}
