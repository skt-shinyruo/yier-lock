package com.mycorp.distributedlock.api.exception;

public class LockReleaseException extends DistributedLockException {
    
    public LockReleaseException(String message) {
        super(message);
    }
    
    public LockReleaseException(String message, Throwable cause) {
        super(message, cause);
    }
}