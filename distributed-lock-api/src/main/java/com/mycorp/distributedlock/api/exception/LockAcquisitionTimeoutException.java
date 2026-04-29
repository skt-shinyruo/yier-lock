package com.mycorp.distributedlock.api.exception;

public class LockAcquisitionTimeoutException extends DistributedLockException {

    public LockAcquisitionTimeoutException(String message) {
        super(message);
    }

    public LockAcquisitionTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    public LockAcquisitionTimeoutException(String message, Throwable cause, LockFailureContext context) {
        super(message, cause, context);
    }
}
