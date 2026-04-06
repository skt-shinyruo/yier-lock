package com.mycorp.distributedlock.api;

public interface LockLease extends AutoCloseable {

    LockKey key();

    LockMode mode();

    FencingToken fencingToken();

    LeaseState state();

    void release();

    @Override
    default void close() {
        release();
    }
}
