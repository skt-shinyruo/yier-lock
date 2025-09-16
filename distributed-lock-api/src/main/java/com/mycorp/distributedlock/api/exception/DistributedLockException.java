package com.mycorp.distributedlock.api.exception;

public class DistributedLockException extends RuntimeException {
    
    public DistributedLockException(String message) {
        super(message);
    }
    
    public DistributedLockException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public DistributedLockException(Throwable cause) {
        super(cause);
    }
}