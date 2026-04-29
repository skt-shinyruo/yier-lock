package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.spi.BackendCapabilities;

import java.util.Objects;

public final class DefaultLockRuntime implements LockRuntime {

    private final LockClient lockClient;
    private final SynchronousLockExecutor synchronousLockExecutor;
    private final String backendId;
    private final BackendCapabilities capabilities;

    public DefaultLockRuntime(
        String backendId,
        BackendCapabilities capabilities,
        LockClient lockClient,
        SynchronousLockExecutor synchronousLockExecutor
    ) {
        this.backendId = Objects.requireNonNull(backendId, "backendId");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.lockClient = Objects.requireNonNull(lockClient, "lockClient");
        this.synchronousLockExecutor = Objects.requireNonNull(synchronousLockExecutor, "synchronousLockExecutor");
    }

    @Override
    public LockClient lockClient() {
        return lockClient;
    }

    @Override
    public SynchronousLockExecutor synchronousLockExecutor() {
        return synchronousLockExecutor;
    }

    @Override
    public String backendId() {
        return backendId;
    }

    @Override
    public BackendCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public void close() {
        lockClient.close();
    }
}
