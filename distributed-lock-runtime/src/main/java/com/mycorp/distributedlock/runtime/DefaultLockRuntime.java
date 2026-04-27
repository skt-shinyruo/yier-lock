package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;

import java.util.Objects;

public final class DefaultLockRuntime implements LockRuntime {

    private final LockClient lockClient;
    private final SynchronousLockExecutor synchronousLockExecutor;

    public DefaultLockRuntime(LockClient lockClient, SynchronousLockExecutor synchronousLockExecutor) {
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
    public void close() {
        lockClient.close();
    }
}
