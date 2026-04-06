package com.mycorp.distributedlock.api;

public interface LockSession extends AutoCloseable {

    LockLease acquire(LockRequest request) throws InterruptedException;

    SessionState state();

    @Override
    void close();
}
