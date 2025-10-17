package com.mycorp.distributedlock.api;

public class MockLockProvider implements LockProvider {
    @Override
    public DistributedLock createLock(String name) {
        return null;
    }

    @Override
    public DistributedReadWriteLock createReadWriteLock(String name) {
        return null;
    }

    @Override
    public String getType() {
        return "mock";
    }
}
