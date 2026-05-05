package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.api.RuntimeInfo;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;

import java.util.Objects;

public final class DefaultLockRuntime implements LockRuntime {

    private final RuntimeInfo info;
    private final LockClient lockClient;
    private final SynchronousLockExecutor synchronousLockExecutor;

    public DefaultLockRuntime(
        RuntimeInfo info,
        LockClient lockClient,
        SynchronousLockExecutor synchronousLockExecutor
    ) {
        this.info = Objects.requireNonNull(info, "info");
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
    public RuntimeInfo info() {
        return info;
    }

    @Override
    public void close() {
        lockClient.close();
    }
}
