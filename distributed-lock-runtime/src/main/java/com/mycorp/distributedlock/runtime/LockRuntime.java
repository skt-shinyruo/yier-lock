package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.spi.BackendCapabilities;

public interface LockRuntime extends AutoCloseable {

    LockClient lockClient();

    SynchronousLockExecutor synchronousLockExecutor();

    String backendId();

    BackendCapabilities capabilities();

    @Override
    void close();
}
