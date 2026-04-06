package com.mycorp.distributedlock.api.exception;

public class LockSessionLostException extends RuntimeException {

    public LockSessionLostException(String message) {
        super(message);
    }

    public LockSessionLostException(String message, Throwable cause) {
        super(message, cause);
    }
}
