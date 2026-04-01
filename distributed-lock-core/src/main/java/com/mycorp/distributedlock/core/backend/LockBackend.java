package com.mycorp.distributedlock.core.backend;

public interface LockBackend {

    BackendLockLease acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) throws InterruptedException;
}
