package com.mycorp.distributedlock.redis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisBackendConfigurationTest {

    @Test
    void defaultConstructorsShouldPreserveLegacyStrategyAndDisableFixedLeaseRenewal() {
        RedisBackendConfiguration legacy = new RedisBackendConfiguration("redis://localhost:6379", 30L);
        RedisBackendConfiguration explicitStrategy = new RedisBackendConfiguration(
            "redis://localhost:6379",
            30L,
            RedisKeyStrategy.HASH_TAGGED
        );

        assertThat(legacy.redisUri()).isEqualTo("redis://localhost:6379");
        assertThat(legacy.leaseSeconds()).isEqualTo(30L);
        assertThat(legacy.keyStrategy()).isEqualTo(RedisKeyStrategy.LEGACY);
        assertThat(legacy.fixedLeaseRenewalEnabled()).isFalse();
        assertThat(legacy.renewalPoolSize()).isZero();

        assertThat(explicitStrategy.redisUri()).isEqualTo("redis://localhost:6379");
        assertThat(explicitStrategy.leaseSeconds()).isEqualTo(30L);
        assertThat(explicitStrategy.keyStrategy()).isEqualTo(RedisKeyStrategy.HASH_TAGGED);
        assertThat(explicitStrategy.fixedLeaseRenewalEnabled()).isFalse();
        assertThat(explicitStrategy.renewalPoolSize()).isZero();
    }

    @Test
    void explicitAdvancedOptionsShouldBeExposed() {
        RedisBackendConfiguration configuration = new RedisBackendConfiguration(
            "redis://localhost:6379",
            45L,
            RedisKeyStrategy.HASH_TAGGED,
            true,
            6
        );

        assertThat(configuration.redisUri()).isEqualTo("redis://localhost:6379");
        assertThat(configuration.leaseSeconds()).isEqualTo(45L);
        assertThat(configuration.keyStrategy()).isEqualTo(RedisKeyStrategy.HASH_TAGGED);
        assertThat(configuration.fixedLeaseRenewalEnabled()).isTrue();
        assertThat(configuration.renewalPoolSize()).isEqualTo(6);
        assertThat(configuration.effectiveRenewalPoolSize()).isEqualTo(6);
    }

    @Test
    void zeroRenewalPoolSizeShouldUseComputedDefault() {
        RedisBackendConfiguration configuration = new RedisBackendConfiguration(
            "redis://localhost:6379",
            30L,
            RedisKeyStrategy.LEGACY,
            false,
            0
        );

        int expectedDefault = Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));

        assertThat(configuration.renewalPoolSize()).isZero();
        assertThat(configuration.effectiveRenewalPoolSize()).isEqualTo(expectedDefault);
    }

    @Test
    void negativeRenewalPoolSizeShouldBeRejected() {
        assertThatThrownBy(() -> new RedisBackendConfiguration(
            "redis://localhost:6379",
            30L,
            RedisKeyStrategy.LEGACY,
            false,
            -1
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("renewalPoolSize");
    }
}
