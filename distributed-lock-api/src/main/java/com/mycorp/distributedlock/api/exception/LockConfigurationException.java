package com.mycorp.distributedlock.api.exception;

/**
 * Unchecked exception for invalid runtime/backend selection or invalid configuration.
 */
public class LockConfigurationException extends DistributedLockException {

    public LockConfigurationException(String message) {
        super(message);
    }

    public LockConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
