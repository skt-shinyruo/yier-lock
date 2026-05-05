package com.mycorp.distributedlock.spi;

public interface BackendClient extends AutoCloseable {

    BackendSession openSession();

    @Override
    void close();
}
