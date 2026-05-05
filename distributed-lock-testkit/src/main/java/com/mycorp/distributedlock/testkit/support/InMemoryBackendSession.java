package com.mycorp.distributedlock.testkit.support;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.spi.BackendLease;
import com.mycorp.distributedlock.spi.BackendSession;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InMemoryBackendSession implements BackendSession {

    private final Map<String, InMemoryLockBackend.InMemoryLockState> lockStates;
    private final Set<SessionTrackedLease> activeLeases = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    InMemoryBackendSession(Map<String, InMemoryLockBackend.InMemoryLockState> lockStates) {
        this.lockStates = lockStates;
    }

    @Override
    public BackendLease acquire(LockRequest request) throws InterruptedException {
        if (closed.get()) {
            throw new IllegalStateException("Session is already closed");
        }
        BackendLease backendLease = InMemoryLockBackend.acquireLease(lockStates, request);
        SessionTrackedLease trackedLease = new SessionTrackedLease(backendLease, activeLeases);
        activeLeases.add(trackedLease);
        if (closed.get()) {
            trackedLease.release();
            throw new IllegalStateException("Session is already closed");
        }
        return trackedLease;
    }

    @Override
    public SessionState state() {
        return closed.get() ? SessionState.CLOSED : SessionState.ACTIVE;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (SessionTrackedLease lease : new ArrayList<>(activeLeases)) {
                lease.release();
            }
        }
    }

    private static final class SessionTrackedLease implements BackendLease {

        private final BackendLease delegate;
        private final Set<SessionTrackedLease> activeLeases;

        private SessionTrackedLease(BackendLease delegate, Set<SessionTrackedLease> activeLeases) {
            this.delegate = delegate;
            this.activeLeases = activeLeases;
        }

        @Override
        public LockKey key() {
            return delegate.key();
        }

        @Override
        public LockMode mode() {
            return delegate.mode();
        }

        @Override
        public FencingToken fencingToken() {
            return delegate.fencingToken();
        }

        @Override
        public LeaseState state() {
            return delegate.state();
        }

        @Override
        public boolean isValid() {
            return delegate.isValid();
        }

        @Override
        public void release() {
            try {
                delegate.release();
            } finally {
                activeLeases.remove(this);
            }
        }
    }
}
