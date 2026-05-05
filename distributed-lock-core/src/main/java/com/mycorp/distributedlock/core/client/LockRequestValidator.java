package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.api.LeaseMode;
import com.mycorp.distributedlock.api.LeaseSemantics;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.exception.LockFailureContext;
import com.mycorp.distributedlock.api.exception.UnsupportedLockCapabilityException;

import java.util.Objects;

final class LockRequestValidator {

    void validate(BackendBehavior behavior, LockRequest request) {
        Objects.requireNonNull(behavior, "behavior");
        Objects.requireNonNull(request, "request");

        LockMode mode = request.mode();
        if (!behavior.supportsLockMode(mode)) {
            throw unsupportedCapability("Backend does not support " + mode + " mode", request);
        }
        if (request.leasePolicy().mode() == LeaseMode.FIXED
            && !behavior.supportsLeaseSemantics(LeaseSemantics.FIXED_TTL)) {
            throw unsupportedCapability("Backend does not support fixed lease duration", request);
        }
    }

    private static UnsupportedLockCapabilityException unsupportedCapability(String message, LockRequest request) {
        return new UnsupportedLockCapabilityException(
            message,
            null,
            LockFailureContext.fromRequest(request, null, null)
        );
    }
}
