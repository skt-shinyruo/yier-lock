package com.mycorp.distributedlock.api.exception;

public class LockAcquisitionException extends DistributedLockException {
    
    public LockAcquisitionException(String message) {
        super(message);
    }
    
    public LockAcquisitionException(String message, Throwable cause) {
        super(message, cause);
    }
}