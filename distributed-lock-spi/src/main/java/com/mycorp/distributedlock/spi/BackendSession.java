package com.mycorp.distributedlock.spi;

import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;

public interface BackendSession extends AutoCloseable {

    BackendLease acquire(LockRequest request) throws InterruptedException;

    SessionState state();

    @Override
    void close();
}
