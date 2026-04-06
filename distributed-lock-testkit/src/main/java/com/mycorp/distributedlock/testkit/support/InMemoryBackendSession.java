package com.mycorp.distributedlock.testkit.support;

import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InMemoryBackendSession implements BackendSession {

    private final Map<String, InMemoryLockBackend.InMemoryLockState> lockStates;
    private final AtomicBoolean closed = new AtomicBoolean();

    InMemoryBackendSession(Map<String, InMemoryLockBackend.InMemoryLockState> lockStates) {
        this.lockStates = lockStates;
    }

    @Override
    public BackendLockLease acquire(LockRequest request) throws InterruptedException {
        if (closed.get()) {
            throw new IllegalStateException("Session is already closed");
        }
        return InMemoryLockBackend.acquireLease(lockStates, request);
    }

    @Override
    public SessionState state() {
        return closed.get() ? SessionState.CLOSED : SessionState.ACTIVE;
    }

    @Override
    public void close() {
        closed.set(true);
    }
}
