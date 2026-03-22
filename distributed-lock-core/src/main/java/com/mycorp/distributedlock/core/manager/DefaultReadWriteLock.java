package com.mycorp.distributedlock.core.manager;

import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.api.ReadWriteLock;
import com.mycorp.distributedlock.core.backend.LockMode;

import java.util.Objects;

public final class DefaultReadWriteLock implements ReadWriteLock {

    private final DefaultLockManager manager;
    private final String key;

    DefaultReadWriteLock(DefaultLockManager manager, String key) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.key = Objects.requireNonNull(key, "key");
    }

    @Override
    public MutexLock readLock() {
        return new DefaultMutexLock(manager, key, LockMode.READ);
    }

    @Override
    public MutexLock writeLock() {
        return new DefaultMutexLock(manager, key, LockMode.WRITE);
    }
}
