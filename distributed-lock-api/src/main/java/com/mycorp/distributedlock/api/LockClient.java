package com.mycorp.distributedlock.api;

public interface LockClient extends AutoCloseable {

    LockSession openSession();

    @Override
    void close();
}
