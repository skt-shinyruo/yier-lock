package com.mycorp.distributedlock.api;

public interface LockClient extends AutoCloseable {

    LockSession openSession(SessionRequest request);

    @Override
    void close();
}
