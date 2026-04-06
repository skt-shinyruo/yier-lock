package com.mycorp.distributedlock.core.backend;

import com.mycorp.distributedlock.api.LockCapabilities;
import com.mycorp.distributedlock.api.SessionRequest;

public interface LockBackend extends AutoCloseable {

    LockCapabilities capabilities();

    BackendSession openSession(SessionRequest request);
}
