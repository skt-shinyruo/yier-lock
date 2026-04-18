package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.core.backend.LockBackend;

import java.util.Objects;

public final class DefaultLockClient implements LockClient {

    private final LockBackend backend;
    private final SupportedLockModes supportedLockModes;
    private final LockRequestValidator validator;

    public DefaultLockClient(LockBackend backend, SupportedLockModes supportedLockModes) {
        this(backend, supportedLockModes, new LockRequestValidator());
    }

    DefaultLockClient(LockBackend backend, SupportedLockModes supportedLockModes, LockRequestValidator validator) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.supportedLockModes = Objects.requireNonNull(supportedLockModes, "supportedLockModes");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    @Override
    public LockSession openSession() {
        return new DefaultLockSession(supportedLockModes, backend.openSession(), validator);
    }

    @Override
    public void close() {
        try {
            backend.close();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new LockBackendException("Failed to close lock backend", exception);
        }
    }
}
