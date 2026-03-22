package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.LockManager;

import java.util.Objects;

public final class DefaultLockRuntime implements LockRuntime {

    private final LockManager lockManager;
    private final AutoCloseable backendResource;

    public DefaultLockRuntime(LockManager lockManager, AutoCloseable backendResource) {
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
        this.backendResource = backendResource;
    }

    @Override
    public LockManager lockManager() {
        return lockManager;
    }

    @Override
    public void close() throws Exception {
        if (backendResource != null) {
            backendResource.close();
        }
    }
}
