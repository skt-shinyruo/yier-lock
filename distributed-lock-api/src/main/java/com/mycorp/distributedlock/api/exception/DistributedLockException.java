package com.mycorp.distributedlock.api.exception;

public class DistributedLockException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient LockFailureContext context;

    public DistributedLockException(String message) {
        this(message, null, LockFailureContext.empty());
    }

    public DistributedLockException(String message, Throwable cause) {
        this(message, cause, LockFailureContext.empty());
    }

    public DistributedLockException(String message, Throwable cause, LockFailureContext context) {
        super(message);
        if (cause != null) {
            initCause(cause);
        }
        this.context = context == null ? LockFailureContext.empty() : context;
    }

    public LockFailureContext context() {
        return context == null ? LockFailureContext.empty() : context;
    }
}
