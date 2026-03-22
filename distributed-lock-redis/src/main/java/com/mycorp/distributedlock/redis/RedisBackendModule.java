package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendContext;
import com.mycorp.distributedlock.runtime.spi.BackendModule;

import java.util.Map;

public final class RedisBackendModule implements BackendModule {

    private final RedisBackendConfiguration explicitConfiguration;

    public RedisBackendModule() {
        this.explicitConfiguration = null;
    }

    public RedisBackendModule(String redisUri) {
        this.explicitConfiguration = new RedisBackendConfiguration(redisUri, 30L);
    }

    public RedisBackendModule(RedisBackendConfiguration configuration) {
        this.explicitConfiguration = configuration;
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
    public LockBackend createBackend(BackendContext context) {
        RedisBackendConfiguration configuration = explicitConfiguration != null
            ? explicitConfiguration
            : resolveConfiguration(context);
        return new RedisLockBackend(configuration);
    }

    private RedisBackendConfiguration resolveConfiguration(BackendContext context) {
        Object configuration = context != null ? context.configuration() : null;
        if (configuration instanceof RedisBackendConfiguration redisBackendConfiguration) {
            return redisBackendConfiguration;
        }
        if (configuration instanceof Map<?, ?> map) {
            Object redisUri = map.get("uri");
            Object leaseSeconds = map.get("lease-seconds");
            String resolvedUri = redisUri instanceof String value && !value.isBlank()
                ? value
                : RedisBackendConfiguration.defaultLocal().redisUri();
            long resolvedLeaseSeconds = leaseSeconds instanceof Number number
                ? number.longValue()
                : RedisBackendConfiguration.defaultLocal().leaseSeconds();
            return new RedisBackendConfiguration(resolvedUri, resolvedLeaseSeconds);
        }
        if (configuration instanceof String redisUri && !redisUri.isBlank()) {
            return new RedisBackendConfiguration(redisUri, 30L);
        }
        return RedisBackendConfiguration.defaultLocal();
    }
}
