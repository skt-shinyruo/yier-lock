package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.core.backend.BackendSession;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DefaultLockSession implements LockSession {

    private final SupportedLockModes supportedLockModes;
    private final BackendSession backendSession;
    private final LockRequestValidator validator;
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
        return backendSession.acquire(request);
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
        try {
            backendSession.close();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new LockBackendException("Failed to close lock session", exception);
        }
    }
}
