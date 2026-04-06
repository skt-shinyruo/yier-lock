package com.mycorp.distributedlock.api;

@FunctionalInterface
public interface LockExecutor {

    <T> T withLock(LockRequest request, LockedSupplier<T> action) throws Exception;
}
