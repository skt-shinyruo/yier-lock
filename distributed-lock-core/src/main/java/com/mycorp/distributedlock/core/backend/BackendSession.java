package com.mycorp.distributedlock.core.backend;

import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;

public interface BackendSession extends AutoCloseable {

    BackendLockLease acquire(LockRequest request) throws InterruptedException;

    SessionState state();
}
