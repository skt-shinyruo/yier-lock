package com.mycorp.distributedlock.api.exception;

/**
 * Unchecked wrapper for backend/client/protocol failures during lock operations.
 */
public class LockBackendException extends DistributedLockException {

    public LockBackendException(String message) {
        super(message);
    }

    public LockBackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
