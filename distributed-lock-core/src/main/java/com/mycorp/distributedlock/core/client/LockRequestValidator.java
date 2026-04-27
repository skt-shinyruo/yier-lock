package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.LeaseMode;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.exception.UnsupportedLockCapabilityException;

import java.util.Objects;

final class LockRequestValidator {

    void validate(SupportedLockModes supportedLockModes, LockRequest request) {
        Objects.requireNonNull(supportedLockModes, "supportedLockModes");
        Objects.requireNonNull(request, "request");

        LockMode mode = request.mode();
        if (mode == LockMode.MUTEX && !supportedLockModes.mutexSupported()) {
            throw new UnsupportedLockCapabilityException("Backend does not support " + mode + " mode");
        }
        if ((mode == LockMode.READ || mode == LockMode.WRITE) && !supportedLockModes.readWriteSupported()) {
            throw new UnsupportedLockCapabilityException("Backend does not support " + mode + " mode");
        }
        if (request.leasePolicy().mode() == LeaseMode.FIXED && !supportedLockModes.fixedLeaseDurationSupported()) {
            throw new UnsupportedLockCapabilityException("Backend does not support fixed lease duration");
        }
    }
}
