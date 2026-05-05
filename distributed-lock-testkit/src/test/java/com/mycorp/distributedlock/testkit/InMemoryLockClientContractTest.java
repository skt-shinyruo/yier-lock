package com.mycorp.distributedlock.testkit;

class InMemoryMutexLockContractTest extends MutexLockContract {
    @Override
    protected BackendConformanceFixture<?> createFixture() {
        return new InMemoryConformanceFixture();
    }
}

class InMemoryWaitPolicyContractTest extends WaitPolicyContract {
    @Override
    protected BackendConformanceFixture<?> createFixture() {
        return new InMemoryConformanceFixture();
    }
}

class InMemoryFencingContractTest extends FencingContract {
    @Override
    protected BackendConformanceFixture<?> createFixture() {
        return new InMemoryConformanceFixture();
    }
}

class InMemoryReadWriteLockContractTest extends ReadWriteLockContract {
    @Override
    protected BackendConformanceFixture<?> createFixture() {
        return new InMemoryConformanceFixture();
    }
}

class InMemoryLeasePolicyContractTest extends LeasePolicyContract {
    @Override
    protected BackendConformanceFixture<?> createFixture() {
        return new InMemoryConformanceFixture();
    }
}

class InMemoryFixedLeasePolicyContractTest extends FixedLeasePolicyContract {
    @Override
    protected BackendConformanceFixture<?> createFixture() {
        return new InMemoryConformanceFixture();
    }
}

class InMemoryReentryContractTest extends ReentryContract {
    @Override
    protected BackendConformanceFixture<?> createFixture() {
        return new InMemoryConformanceFixture();
    }
}

class InMemorySessionLifecycleContractTest extends SessionLifecycleContract {
    @Override
    protected BackendConformanceFixture<?> createFixture() {
        return new InMemoryConformanceFixture();
    }
}
