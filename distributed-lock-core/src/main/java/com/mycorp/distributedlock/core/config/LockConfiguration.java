package com.mycorp.distributedlock.core.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class LockConfiguration {
    
    private final Config config;
    
    public LockConfiguration() {
        this(ConfigFactory.load());
    }
    
    public LockConfiguration(Config config) {
        this.config = config;
    }
    
    public Duration getDefaultLeaseTime() {
        return config.hasPath("distributed-lock.default-lease-time") 
            ? config.getDuration("distributed-lock.default-lease-time")
            : Duration.ofSeconds(30);
    }
    
    public Duration getDefaultWaitTime() {
        return config.hasPath("distributed-lock.default-wait-time")
            ? config.getDuration("distributed-lock.default-wait-time")
            : Duration.ofSeconds(10);
    }
    
    public Duration getWatchdogRenewalInterval() {
        return config.hasPath("distributed-lock.watchdog.renewal-interval")
            ? config.getDuration("distributed-lock.watchdog.renewal-interval")
            : Duration.ofSeconds(10);
    }
    
    public boolean isWatchdogEnabled() {
        return config.hasPath("distributed-lock.watchdog.enabled")
            ? config.getBoolean("distributed-lock.watchdog.enabled")
            : true;
    }
    
    public Duration getRetryInterval() {
        return config.hasPath("distributed-lock.retry-interval")
            ? config.getDuration("distributed-lock.retry-interval")
            : Duration.ofMillis(100);
    }
    
    public int getMaxRetries() {
        return config.hasPath("distributed-lock.max-retries")
            ? config.getInt("distributed-lock.max-retries")
            : 3;
    }
    
    public boolean isMetricsEnabled() {
        return config.hasPath("distributed-lock.metrics.enabled")
            ? config.getBoolean("distributed-lock.metrics.enabled")
            : true;
    }
    
    public boolean isTracingEnabled() {
        return config.hasPath("distributed-lock.tracing.enabled")
            ? config.getBoolean("distributed-lock.tracing.enabled")
            : true;
    }
    
    public Config getConfig() {
        return config;
    }
}