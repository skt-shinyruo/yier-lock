package com.mycorp.distributedlock.api;

@FunctionalInterface
public interface LockedSupplier<T> {

    T get() throws Exception;
}
