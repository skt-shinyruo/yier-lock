package com.mycorp.distributedlock.api.exception;

public class LockSessionLostException extends DistributedLockException {

    public LockSessionLostException(String message) {
        super(message);
    }

    public LockSessionLostException(String message, Throwable cause) {
        super(message, cause);
    }

    public LockSessionLostException(String message, Throwable cause, LockFailureContext context) {
        super(message, cause, context);
    }
}
