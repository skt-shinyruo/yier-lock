package com.mycorp.distributedlock.springboot.integration;

import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.spi.BackendCapabilities;
import com.mycorp.distributedlock.spi.BackendModule;

public class ServiceLoaderOnlyBackendModule implements BackendModule {

    @Override
    public String id() {
        return "service-loader-only";
    }

    @Override
    public BackendCapabilities capabilities() {
        return BackendCapabilities.standard();
    }

    @Override
    public LockBackend createBackend() {
        return new LockBackend() {
            @Override
            public BackendSession openSession() {
                throw new UnsupportedOperationException("not used in test");
            }
        };
    }
}
