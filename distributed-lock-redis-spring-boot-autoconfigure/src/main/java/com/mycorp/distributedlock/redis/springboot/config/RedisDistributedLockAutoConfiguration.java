package com.mycorp.distributedlock.redis.springboot.config;

import com.mycorp.distributedlock.redis.RedisBackendConfiguration;
import com.mycorp.distributedlock.redis.RedisBackendModule;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.spi.BackendModule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

@AutoConfiguration
@AutoConfigureBefore(name = "com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration")
@ConditionalOnProperty(prefix = "distributed.lock", name = "backend", havingValue = "redis")
public class RedisDistributedLockAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RedisDistributedLockProperties.class)
    @ConditionalOnProperty(prefix = "distributed.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(value = LockRuntime.class, name = "redisDistributedLockBackendModule")
    static class DefaultRedisBackendConfiguration {

        @Bean("redisDistributedLockBackendModule")
        @ConditionalOnMissingBean(name = "redisDistributedLockBackendModule")
        BackendModule redisBackendModule(RedisDistributedLockProperties properties) {
            int renewalPoolSize = properties.getRenewalPoolSize();
            if (renewalPoolSize < 0) {
                throw new IllegalArgumentException("distributed.lock.redis.renewal-pool-size must not be negative");
            }
            RedisBackendConfiguration configuration = new RedisBackendConfiguration(
                requireUri(properties.getUri()),
                toLeaseSeconds(properties.getLeaseTime()),
                properties.getKeyStrategy(),
                properties.isFixedLeaseRenewalEnabled(),
                renewalPoolSize
            );
            return new RedisBackendModule(configuration);
        }

        private String requireUri(String uri) {
            if (uri == null || uri.isBlank()) {
                throw new IllegalArgumentException("distributed.lock.redis.uri must be configured");
            }
            return uri;
        }

        private long toLeaseSeconds(Duration leaseTime) {
            if (leaseTime == null) {
                throw new IllegalArgumentException("distributed.lock.redis.lease-time must be configured");
            }
            if (leaseTime.isZero() || leaseTime.isNegative()) {
                throw new IllegalArgumentException("distributed.lock.redis.lease-time must be positive");
            }
            if (!leaseTime.truncatedTo(ChronoUnit.SECONDS).equals(leaseTime)) {
                throw new IllegalArgumentException("distributed.lock.redis.lease-time must use whole seconds");
            }
            return leaseTime.toSeconds();
        }
    }
}
