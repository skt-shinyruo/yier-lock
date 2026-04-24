package com.mycorp.distributedlock.redis.springboot.config;

import com.mycorp.distributedlock.redis.RedisBackendConfiguration;
import com.mycorp.distributedlock.redis.RedisBackendModule;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

@AutoConfiguration
@AutoConfigureBefore(name = "com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration")
@EnableConfigurationProperties(RedisDistributedLockProperties.class)
@ConditionalOnProperty(prefix = "distributed.lock", name = "backend", havingValue = "redis")
public class RedisDistributedLockAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "distributed.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean({ BackendModule.class, LockRuntime.class })
    public BackendModule redisBackendModule(RedisDistributedLockProperties properties) {
        RedisBackendConfiguration configuration = new RedisBackendConfiguration(
            requireUri(properties.getUri()),
            toLeaseSeconds(properties.getLeaseTime())
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
