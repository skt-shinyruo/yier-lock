package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.LockCapabilities;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.exception.UnsupportedLockCapabilityException;

import java.util.Objects;

final class LockRequestValidator {

    void validate(LockCapabilities capabilities, LockRequest request) {
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(request, "request");

        LockMode mode = request.mode();
        if (mode == LockMode.MUTEX && !capabilities.mutexSupported()) {
            throw new UnsupportedLockCapabilityException("Backend does not support " + mode + " mode");
        }
        if ((mode == LockMode.READ || mode == LockMode.WRITE) && !capabilities.readWriteSupported()) {
            throw new UnsupportedLockCapabilityException("Backend does not support " + mode + " mode");
        }
    }
}
