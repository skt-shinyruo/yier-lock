package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.testkit.support.InMemoryBackendModule;

class InMemoryLockClientContractTest extends LockClientContract {

    @Override
    protected LockRuntime createRuntime() {
        return LockRuntimeBuilder.create()
            .backend("in-memory")
            .backendModules(java.util.List.of(new InMemoryBackendModule("in-memory")))
            .build();
    }
}
