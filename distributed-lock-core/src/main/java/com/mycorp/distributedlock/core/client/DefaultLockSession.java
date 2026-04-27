package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.api.exception.LockReentryException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DefaultLockSession implements LockSession {

    private final SupportedLockModes supportedLockModes;
    private final BackendSession backendSession;
    private final LockRequestValidator validator;
    private final Set<SessionBoundLockLease> activeLeases = ConcurrentHashMap.newKeySet();
    private final Set<LockKey> activeLeaseKeys = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public DefaultLockSession(
        SupportedLockModes supportedLockModes,
        BackendSession backendSession,
        LockRequestValidator validator
    ) {
        this.supportedLockModes = Objects.requireNonNull(supportedLockModes, "supportedLockModes");
        this.backendSession = Objects.requireNonNull(backendSession, "backendSession");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    @Override
    public LockLease acquire(LockRequest request) throws InterruptedException {
        if (closed.get()) {
            throw new IllegalStateException("Lock session is already closed");
        }
        validator.validate(supportedLockModes, request);
        registerKey(request.key());
        boolean leaseCreated = false;
        try {
            BackendLockLease backendLease = backendSession.acquire(request);
            SessionBoundLockLease lease = new SessionBoundLockLease(backendLease, this::forgetLease);
            if (!registerLease(lease)) {
                throw closeLateAcquiredLease(lease);
            }
            leaseCreated = true;
            return lease;
        } finally {
            if (!leaseCreated) {
                activeLeaseKeys.remove(request.key());
            }
        }
    }

    @Override
    public SessionState state() {
        if (closed.get()) {
            return SessionState.CLOSED;
        }
        return backendSession.state();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        for (SessionBoundLockLease lease : new ArrayList<>(activeLeases)) {
            failure = releaseLease(lease, failure);
        }
        failure = closeBackendSession(failure);
        if (failure != null) {
            throw failure;
        }
    }

    private boolean registerLease(SessionBoundLockLease lease) {
        if (closed.get()) {
            return false;
        }
        activeLeases.add(lease);
        if (closed.get()) {
            activeLeases.remove(lease);
            return false;
        }
        return true;
    }

    private void registerKey(LockKey key) {
        if (!activeLeaseKeys.add(key)) {
            throw new LockReentryException("Lock key is already held by this session: " + key.value());
        }
    }

    private void forgetLease(SessionBoundLockLease lease) {
        activeLeases.remove(lease);
        activeLeaseKeys.remove(lease.key());
    }

    private RuntimeException closeLateAcquiredLease(SessionBoundLockLease lease) {
        RuntimeException closedException = new IllegalStateException("Lock session is already closed");
        try {
            lease.release();
        } catch (RuntimeException exception) {
            exception.addSuppressed(closedException);
            return exception;
        }
        return closedException;
    }

    private RuntimeException releaseLease(SessionBoundLockLease lease, RuntimeException failure) {
        try {
            lease.release();
            return failure;
        } catch (RuntimeException exception) {
            return recordFailure(failure, exception);
        }
    }

    private RuntimeException closeBackendSession(RuntimeException failure) {
        try {
            backendSession.close();
            return failure;
        } catch (RuntimeException exception) {
            return recordFailure(failure, exception);
        } catch (Exception exception) {
            return recordFailure(failure, new LockBackendException("Failed to close lock session", exception));
        }
    }

    private static RuntimeException recordFailure(RuntimeException current, RuntimeException next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }
}
