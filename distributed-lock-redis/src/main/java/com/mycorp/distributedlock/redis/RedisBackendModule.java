package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;

import java.util.Objects;

public final class RedisBackendModule implements BackendModule {

    private final RedisBackendConfiguration configuration;

    public RedisBackendModule() {
        this(RedisBackendConfiguration.defaultLocal());
    }

    public RedisBackendModule(String redisUri) {
        this(new RedisBackendConfiguration(redisUri, 30L));
    }

    public RedisBackendModule(RedisBackendConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
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
        return new RedisLockBackend(configuration);
    }
}
