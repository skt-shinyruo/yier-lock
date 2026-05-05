package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.spi.BackendClient;
import com.mycorp.distributedlock.spi.BackendSession;

import java.util.Objects;

public final class DefaultLockClient implements LockClient {

    private final BackendClient backendClient;
    private final BackendBehavior behavior;
    private final LockRequestValidator validator;

    public DefaultLockClient(BackendClient backendClient, BackendBehavior behavior) {
        this(backendClient, behavior, new LockRequestValidator());
    }

    DefaultLockClient(BackendClient backendClient, BackendBehavior behavior, LockRequestValidator validator) {
        this.backendClient = Objects.requireNonNull(backendClient, "backendClient");
        this.behavior = Objects.requireNonNull(behavior, "behavior");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    @Override
    public LockSession openSession() {
        BackendSession backendSession = backendClient.openSession();
        if (backendSession == null) {
            throw new LockBackendException("Backend client returned null session");
        }
        return new DefaultLockSession(behavior, backendSession, validator);
    }

    @Override
    public void close() {
        try {
            backendClient.close();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new LockBackendException("Failed to close lock backend client", exception);
        }
    }
}
