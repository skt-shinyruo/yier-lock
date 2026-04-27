package com.mycorp.distributedlock.redis;

import java.util.Objects;

public record RedisBackendConfiguration(String redisUri, long leaseSeconds, RedisKeyStrategy keyStrategy) {

    public RedisBackendConfiguration(String redisUri, long leaseSeconds) {
        this(redisUri, leaseSeconds, RedisKeyStrategy.LEGACY);
    }

    public RedisBackendConfiguration {
        Objects.requireNonNull(redisUri, "redisUri");
        Objects.requireNonNull(keyStrategy, "keyStrategy");
        if (redisUri.isBlank()) {
            throw new IllegalArgumentException("redisUri cannot be blank");
        }
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("leaseSeconds must be positive");
        }
    }
}
