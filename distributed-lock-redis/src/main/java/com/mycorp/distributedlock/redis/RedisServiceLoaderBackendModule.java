package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;

public final class RedisServiceLoaderBackendModule implements BackendModule {

    public RedisServiceLoaderBackendModule() {
    }

    @Override
    public String id() {
        return "redis";
    }

    @Override
    public BackendCapabilities capabilities() {
        return BackendCapabilities.standard();
    }

    @Override
    public LockBackend createBackend() {
        throw new LockConfigurationException(
            "Redis requires explicit typed configuration. "
                + "Instantiate it with new RedisBackendModule(new RedisBackendConfiguration(\"redis://127.0.0.1:6379\", 30L))."
        );
    }
}
