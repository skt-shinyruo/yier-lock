package com.mycorp.distributedlock.api.exception;

public class LockOwnershipLostException extends DistributedLockException {

    public LockOwnershipLostException(String message) {
        super(message);
    }

    public LockOwnershipLostException(String message, Throwable cause) {
        super(message, cause);
    }

    public LockOwnershipLostException(String message, Throwable cause, LockFailureContext context) {
        super(message, cause, context);
    }
}
