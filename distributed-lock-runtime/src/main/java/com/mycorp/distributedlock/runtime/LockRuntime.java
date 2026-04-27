package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;

public interface LockRuntime extends AutoCloseable {

    LockClient lockClient();

    SynchronousLockExecutor synchronousLockExecutor();
}
