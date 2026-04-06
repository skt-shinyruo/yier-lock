package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockExecutor;

import java.util.Objects;

public final class DefaultLockRuntime implements LockRuntime {

    private final LockClient lockClient;
    private final LockExecutor lockExecutor;

    public DefaultLockRuntime(LockClient lockClient, LockExecutor lockExecutor) {
        this.lockClient = Objects.requireNonNull(lockClient, "lockClient");
        this.lockExecutor = Objects.requireNonNull(lockExecutor, "lockExecutor");
    }

    @Override
    public LockClient lockClient() {
        return lockClient;
    }

    @Override
    public LockExecutor lockExecutor() {
        return lockExecutor;
    }

    @Override
    public void close() {
        lockClient.close();
    }
}
