package com.mycorp.distributedlock.core.backend;

public interface LockBackend {

    BackendLockHandle acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) throws InterruptedException;

    void release(BackendLockHandle handle);

    boolean isHeldByCurrentExecution(BackendLockHandle handle);
}
