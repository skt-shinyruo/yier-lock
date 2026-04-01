package com.mycorp.distributedlock.testkit.support;

import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;

import java.util.Objects;

public final class InMemoryBackendModule implements BackendModule {

    private final String id;

    public InMemoryBackendModule(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public BackendCapabilities capabilities() {
        return BackendCapabilities.standard();
    }

    @Override
    public LockBackend createBackend() {
        return new InMemoryLockBackend();
    }
}
