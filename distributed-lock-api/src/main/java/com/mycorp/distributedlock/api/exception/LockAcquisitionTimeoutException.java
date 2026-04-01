package com.mycorp.distributedlock.api.exception;

public class LockAcquisitionTimeoutException extends RuntimeException {

    public LockAcquisitionTimeoutException(String message) {
        super(message);
    }
}
