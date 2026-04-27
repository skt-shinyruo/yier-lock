package com.mycorp.distributedlock.api;

@FunctionalInterface
public interface SynchronousLockExecutor {

    <T> T withLock(LockRequest request, LockedAction<T> action) throws Exception;
}
