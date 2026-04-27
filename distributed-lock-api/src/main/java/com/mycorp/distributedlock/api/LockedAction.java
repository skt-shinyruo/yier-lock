package com.mycorp.distributedlock.api;

@FunctionalInterface
public interface LockedAction<T> {

    T execute(LockLease lease) throws Exception;
}
