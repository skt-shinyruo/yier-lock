package com.mycorp.distributedlock.api;

public interface LockRuntime extends AutoCloseable {

    LockClient lockClient();

    SynchronousLockExecutor synchronousLockExecutor();

    RuntimeInfo info();

    @Override
    void close();
}
