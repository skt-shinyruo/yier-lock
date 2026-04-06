package com.mycorp.distributedlock.api;

public interface LockExecutor {

    <T> T withLock(LockRequest request, LockedSupplier<T> action) throws Exception;
}
