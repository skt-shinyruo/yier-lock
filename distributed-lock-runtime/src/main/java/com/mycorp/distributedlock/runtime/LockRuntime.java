package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.LockManager;

public interface LockRuntime extends AutoCloseable {

    LockManager lockManager();
}
