package com.mycorp.distributedlock.redis.springboot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "distributed.lock.redis")
public class RedisDistributedLockProperties {

    private String uri = "redis://localhost:6379";
    private Duration leaseTime = Duration.ofSeconds(30);

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
}
