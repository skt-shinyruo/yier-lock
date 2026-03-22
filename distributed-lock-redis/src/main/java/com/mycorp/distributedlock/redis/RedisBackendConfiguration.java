package com.mycorp.distributedlock.redis;

import java.util.Objects;

public record RedisBackendConfiguration(String redisUri, long leaseSeconds) {

    public RedisBackendConfiguration {
        Objects.requireNonNull(redisUri, "redisUri");
        if (redisUri.isBlank()) {
            throw new IllegalArgumentException("redisUri cannot be blank");
        }
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("leaseSeconds must be positive");
        }
    }

    public static RedisBackendConfiguration defaultLocal() {
        return new RedisBackendConfiguration("redis://localhost:6379", 30L);
    }
}
