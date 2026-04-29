package com.mycorp.distributedlock.redis.springboot.config;

import com.mycorp.distributedlock.redis.RedisKeyStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "distributed.lock.redis")
public class RedisDistributedLockProperties {

    @jakarta.validation.constraints.NotBlank
    private String uri;
    @jakarta.validation.constraints.NotNull
    private Duration leaseTime;
    private RedisKeyStrategy keyStrategy = RedisKeyStrategy.LEGACY;
    private boolean fixedLeaseRenewalEnabled;
    private int renewalPoolSize;

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public Duration getLeaseTime() {
        return leaseTime;
    }

    public void setLeaseTime(Duration leaseTime) {
        this.leaseTime = leaseTime;
    }

    public RedisKeyStrategy getKeyStrategy() {
        return keyStrategy;
    }

    public void setKeyStrategy(RedisKeyStrategy keyStrategy) {
        this.keyStrategy = keyStrategy;
    }

    public boolean isFixedLeaseRenewalEnabled() {
        return fixedLeaseRenewalEnabled;
    }

    public void setFixedLeaseRenewalEnabled(boolean fixedLeaseRenewalEnabled) {
        this.fixedLeaseRenewalEnabled = fixedLeaseRenewalEnabled;
    }

    public int getRenewalPoolSize() {
        return renewalPoolSize;
    }

    public void setRenewalPoolSize(int renewalPoolSize) {
        this.renewalPoolSize = renewalPoolSize;
    }
}
