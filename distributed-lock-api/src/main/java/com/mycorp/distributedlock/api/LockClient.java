package com.mycorp.distributedlock.api;

public interface LockClient {

    LockSession openSession(SessionRequest request);
}
