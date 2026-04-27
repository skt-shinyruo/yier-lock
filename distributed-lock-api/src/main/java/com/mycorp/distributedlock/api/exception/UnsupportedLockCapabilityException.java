package com.mycorp.distributedlock.api.exception;

public class UnsupportedLockCapabilityException extends DistributedLockException {

    public UnsupportedLockCapabilityException(String message) {
        super(message);
    }

    public UnsupportedLockCapabilityException(String message, Throwable cause) {
        super(message, cause);
    }
}
