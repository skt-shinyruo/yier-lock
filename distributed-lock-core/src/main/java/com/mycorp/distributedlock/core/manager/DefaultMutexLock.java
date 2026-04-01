package com.mycorp.distributedlock.core.manager;

import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.core.backend.LockMode;

import java.time.Duration;
import java.util.Objects;

public final class DefaultMutexLock implements MutexLock {

    private final DefaultLockManager manager;
    private final String key;
    private final LockMode mode;
    private String lostOwnershipMessage;

    DefaultMutexLock(DefaultLockManager manager, String key, LockMode mode) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.key = Objects.requireNonNull(key, "key");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    @Override
    public void lock() throws InterruptedException {
        try {
            if (!manager.acquire(key, mode, DefaultLockManager.indefiniteWait())) {
                throw new IllegalStateException("Failed to acquire lock for key " + key);
            }
        } catch (LockOwnershipLostException exception) {
            rememberOwnershipLost(exception);
            throw exception;
        }
    }

    @Override
    public boolean tryLock(Duration waitTime) throws InterruptedException {
        try {
            return manager.acquire(key, mode, DefaultLockManager.timedWait(waitTime));
        } catch (LockOwnershipLostException exception) {
            rememberOwnershipLost(exception);
            throw exception;
        }
    }

    @Override
    public void unlock() {
        if (lostOwnershipMessage != null) {
            throw rememberedOwnershipLost();
        }
        try {
            manager.release(key, mode);
        } catch (LockOwnershipLostException exception) {
            rememberOwnershipLost(exception);
            throw exception;
        }
    }

    @Override
    public boolean isHeldByCurrentThread() {
        return manager.isHeldByCurrentThread(key, mode);
    }

    @Override
    public void close() {
        if (lostOwnershipMessage != null) {
            throw rememberedOwnershipLost();
        }
        try {
            manager.close(key, mode);
        } catch (LockOwnershipLostException exception) {
            rememberOwnershipLost(exception);
            throw exception;
        }
    }

    @Override
    public String key() {
        return key;
    }

    private void rememberOwnershipLost(LockOwnershipLostException exception) {
        if (lostOwnershipMessage == null) {
            lostOwnershipMessage = exception.getMessage();
        }
    }

    private LockOwnershipLostException rememberedOwnershipLost() {
        String message = lostOwnershipMessage != null ? lostOwnershipMessage : "Backend ownership lost for key " + key;
        return new LockOwnershipLostException(message);
    }
}
