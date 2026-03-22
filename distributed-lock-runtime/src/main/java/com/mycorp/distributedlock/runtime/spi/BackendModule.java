package com.mycorp.distributedlock.runtime.spi;

import com.mycorp.distributedlock.core.backend.LockBackend;

public interface BackendModule {

    String id();

    BackendCapabilities capabilities();

    LockBackend createBackend(BackendContext context);
}
