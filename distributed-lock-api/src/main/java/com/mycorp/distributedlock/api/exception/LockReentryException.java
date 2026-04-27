package com.mycorp.distributedlock.api.exception;

public class LockReentryException extends DistributedLockException {

    public LockReentryException(String message) {
        super(message);
    }

    public LockReentryException(String message, Throwable cause) {
        super(message, cause);
    }
}
