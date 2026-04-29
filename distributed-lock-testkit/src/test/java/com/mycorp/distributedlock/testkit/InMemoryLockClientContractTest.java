package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.testkit.support.InMemoryBackendModule;

import java.util.List;

class InMemoryMutexLockContractTest extends MutexLockContract {
    @Override
    protected LockRuntime createRuntime() {
        return InMemoryContractRuntimes.createRuntime();
    }
}

class InMemoryWaitPolicyContractTest extends WaitPolicyContract {
    @Override
    protected LockRuntime createRuntime() {
        return InMemoryContractRuntimes.createRuntime();
    }
}

class InMemoryFencingContractTest extends FencingContract {
    @Override
    protected LockRuntime createRuntime() {
        return InMemoryContractRuntimes.createRuntime();
    }
}

class InMemoryReadWriteLockContractTest extends ReadWriteLockContract {
    @Override
    protected LockRuntime createRuntime() {
        return InMemoryContractRuntimes.createRuntime();
    }
}

class InMemoryLeasePolicyContractTest extends LeasePolicyContract {
    @Override
    protected LockRuntime createRuntime() {
        return InMemoryContractRuntimes.createRuntime();
    }
}

class InMemoryFixedLeasePolicyContractTest extends FixedLeasePolicyContract {
    @Override
    protected LockRuntime createRuntime() {
        return InMemoryContractRuntimes.createRuntime();
    }
}

class InMemoryReentryContractTest extends ReentryContract {
    @Override
    protected LockRuntime createRuntime() {
        return InMemoryContractRuntimes.createRuntime();
    }
}

class InMemorySessionLifecycleContractTest extends SessionLifecycleContract {
    @Override
    protected LockRuntime createRuntime() {
        return InMemoryContractRuntimes.createRuntime();
    }
}

final class InMemoryContractRuntimes {
    private InMemoryContractRuntimes() {
    }

    static LockRuntime createRuntime() {
        return LockRuntimeBuilder.create()
            .backend("in-memory")
            .backendModules(List.of(new InMemoryBackendModule("in-memory")))
            .build();
    }
}
