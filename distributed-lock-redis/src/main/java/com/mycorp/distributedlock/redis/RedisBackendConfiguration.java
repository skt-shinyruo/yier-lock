package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.spi.BackendConfiguration;

import java.util.Objects;

public record RedisBackendConfiguration(
    String redisUri,
    long leaseSeconds,
    RedisKeyStrategy keyStrategy,
    boolean fixedLeaseRenewalEnabled,
    int renewalPoolSize
) implements BackendConfiguration {

    public RedisBackendConfiguration(String redisUri, long leaseSeconds) {
        this(redisUri, leaseSeconds, RedisKeyStrategy.LEGACY);
    }

    public RedisBackendConfiguration(String redisUri, long leaseSeconds, RedisKeyStrategy keyStrategy) {
        this(redisUri, leaseSeconds, keyStrategy, false, 0);
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
        if (renewalPoolSize < 0) {
            throw new IllegalArgumentException("renewalPoolSize cannot be negative");
        }
    }

    int effectiveRenewalPoolSize() {
        if (renewalPoolSize > 0) {
            return renewalPoolSize;
        }
        return Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
    }
}
