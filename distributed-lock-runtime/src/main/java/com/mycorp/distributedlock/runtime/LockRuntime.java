package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockExecutor;

public interface LockRuntime extends AutoCloseable {

    LockClient lockClient();

    LockExecutor lockExecutor();
}
