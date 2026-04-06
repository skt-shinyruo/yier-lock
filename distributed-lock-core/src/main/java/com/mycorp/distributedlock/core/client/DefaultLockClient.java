package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.SessionRequest;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.core.backend.LockBackend;

import java.util.Objects;

public final class DefaultLockClient implements LockClient {

    private final LockBackend backend;
    private final LockRequestValidator validator;

    public DefaultLockClient(LockBackend backend) {
        this(backend, new LockRequestValidator());
    }

    DefaultLockClient(LockBackend backend, LockRequestValidator validator) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    @Override
    public LockSession openSession(SessionRequest request) {
        Objects.requireNonNull(request, "request");
        return new DefaultLockSession(backend.capabilities(), backend.openSession(request), validator);
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
