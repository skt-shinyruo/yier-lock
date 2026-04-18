package com.mycorp.distributedlock.core.backend;

public interface LockBackend extends AutoCloseable {

    BackendSession openSession();

    @Override
    default void close() {
    }
}
