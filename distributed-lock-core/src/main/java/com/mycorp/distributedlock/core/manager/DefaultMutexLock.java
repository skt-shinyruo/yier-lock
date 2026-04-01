package com.mycorp.distributedlock.core.manager;

import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.core.backend.LockMode;

import java.time.Duration;
import java.util.Objects;

public final class DefaultMutexLock implements MutexLock {

    private final DefaultLockManager manager;
    private final String key;
    private final LockMode mode;

    DefaultMutexLock(DefaultLockManager manager, String key, LockMode mode) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.key = Objects.requireNonNull(key, "key");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    @Override
    public void lock() throws InterruptedException {
        if (!manager.acquire(key, mode, DefaultLockManager.indefiniteWait())) {
            throw new IllegalStateException("Failed to acquire lock for key " + key);
        }
    }

    @Override
    public boolean tryLock(Duration waitTime) throws InterruptedException {
        return manager.acquire(key, mode, DefaultLockManager.timedWait(waitTime));
    }

    @Override
    public void unlock() {
        manager.release(key, mode);
    }

    @Override
    public boolean isHeldByCurrentThread() {
        return manager.isHeldByCurrentThread(key, mode);
    }

    @Override
    public void close() {
        manager.close(key, mode);
    }

    @Override
    public String key() {
        return key;
    }
}
