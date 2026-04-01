package com.mycorp.distributedlock.core.backend;

public interface BackendLockLease extends AutoCloseable {

    String key();

    LockMode mode();

    boolean isValidForCurrentExecution();

    void release();

    @Override
    default void close() {
        release();
    }
}
