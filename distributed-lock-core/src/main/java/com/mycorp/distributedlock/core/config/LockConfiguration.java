package com.mycorp.distributedlock.core.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class LockConfiguration {

    private final UnifiedLockConfiguration unifiedConfig;
    private volatile Config config;
    private final List<Consumer<LockConfiguration>> changeListeners = new ArrayList<>();

    public LockConfiguration() {
        this(ConfigFactory.load());
    }

    public LockConfiguration(Config config) {
        this.unifiedConfig = null;
        this.config = config;
    }

    public LockConfiguration(Environment environment) {
        this.unifiedConfig = new UnifiedLockConfiguration(environment);
        this.config = unifiedConfig.getConfig();
    }

    public LockConfiguration(Config config, Environment environment) {
        this.unifiedConfig = new UnifiedLockConfiguration(config, environment);
        this.config = unifiedConfig.getConfig();
    }

    public Optional<String> getLockType() {
        if (config.hasPath("distributed-lock.type")) {
            String value = config.getString("distributed-lock.type");
            if (value != null && !value.isBlank()) {
                return Optional.of(value.trim());
            }
        }
        return Optional.empty();
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
    
    // Redis 配置方法
    public String getRedisHosts() {
        return config.hasPath("distributed-lock.redis.hosts")
            ? config.getString("distributed-lock.redis.hosts")
            : "localhost:6379";
    }

    public String getRedisPassword() {
        return config.hasPath("distributed-lock.redis.password")
            ? config.getString("distributed-lock.redis.password")
            : null;
    }

    public int getRedisDatabase() {
        return config.hasPath("distributed-lock.redis.database")
            ? config.getInt("distributed-lock.redis.database")
            : 0;
    }

    public String getRedisClientName() {
        return config.hasPath("distributed-lock.redis.client-name")
            ? config.getString("distributed-lock.redis.client-name")
            : null;
    }

    public boolean isRedisSslEnabled() {
        return config.hasPath("distributed-lock.redis.ssl")
            ? config.getBoolean("distributed-lock.redis.ssl")
            : false;
    }

    public String getRedisTrustStorePath() {
        return config.hasPath("distributed-lock.redis.trust-store-path")
            ? config.getString("distributed-lock.redis.trust-store-path")
            : null;
    }

    public String getRedisTrustStorePassword() {
        return config.hasPath("distributed-lock.redis.trust-store-password")
            ? config.getString("distributed-lock.redis.trust-store-password")
            : null;
    }

    // 连接池配置
    public int getRedisPoolMaxTotal() {
        return config.hasPath("distributed-lock.redis.pool.max-total")
            ? config.getInt("distributed-lock.redis.pool.max-total")
            : 8;
    }

    public int getRedisPoolMaxIdle() {
        return config.hasPath("distributed-lock.redis.pool.max-idle")
            ? config.getInt("distributed-lock.redis.pool.max-idle")
            : 8;
    }

    public int getRedisPoolMinIdle() {
        return config.hasPath("distributed-lock.redis.pool.min-idle")
            ? config.getInt("distributed-lock.redis.pool.min-idle")
            : 0;
    }

    // ZooKeeper 配置方法
    public String getZookeeperConnectString() {
        return config.hasPath("distributed-lock.zookeeper.connect-string")
            ? config.getString("distributed-lock.zookeeper.connect-string")
            : "localhost:2181";
    }

    public String getZookeeperBasePath() {
        return config.hasPath("distributed-lock.zookeeper.base-path")
            ? config.getString("distributed-lock.zookeeper.base-path")
            : "/distributed-locks";
    }

    public Duration getZookeeperSessionTimeout() {
        return config.hasPath("distributed-lock.zookeeper.session-timeout")
            ? config.getDuration("distributed-lock.zookeeper.session-timeout")
            : Duration.ofSeconds(60);
    }

    public Duration getZookeeperConnectionTimeout() {
        return config.hasPath("distributed-lock.zookeeper.connection-timeout")
            ? config.getDuration("distributed-lock.zookeeper.connection-timeout")
            : Duration.ofSeconds(15);
    }

    public boolean isZookeeperAuthEnabled() {
        return config.hasPath("distributed-lock.zookeeper.auth-enabled")
            ? config.getBoolean("distributed-lock.zookeeper.auth-enabled")
            : false;
    }

    public String getZookeeperAuthScheme() {
        return config.hasPath("distributed-lock.zookeeper.auth-scheme")
            ? config.getString("distributed-lock.zookeeper.auth-scheme")
            : "digest";
    }

    public String getZookeeperAuthInfo() {
        return config.hasPath("distributed-lock.zookeeper.auth-info")
            ? config.getString("distributed-lock.zookeeper.auth-info")
            : null;
    }

    public boolean isZookeeperAclEnabled() {
        return config.hasPath("distributed-lock.zookeeper.acl-enabled")
            ? config.getBoolean("distributed-lock.zookeeper.acl-enabled")
            : false;
    }

    public String getZookeeperAclPermissions() {
        return config.hasPath("distributed-lock.zookeeper.acl.permissions")
            ? config.getString("distributed-lock.zookeeper.acl.permissions")
            : "ALL";
    }

    public String getZookeeperAclScheme() {
        return config.hasPath("distributed-lock.zookeeper.acl.scheme")
            ? config.getString("distributed-lock.zookeeper.acl.scheme")
            : "digest";
    }

    public String getZookeeperAclId() {
        return config.hasPath("distributed-lock.zookeeper.acl.id")
            ? config.getString("distributed-lock.zookeeper.acl.id")
            : null;
    }

    public Config getConfig() {
        return config;
    }

    public UnifiedLockConfiguration getUnifiedConfig() {
        return unifiedConfig;
    }

    /**
     * Validate the configuration for consistency and correctness.
     *
     * @throws ConfigException if validation fails
     */
    public void validate() throws ConfigException {
        List<String> errors = new ArrayList<>();

        // Validate lease times
        Duration defaultLease = getDefaultLeaseTime();
        if (defaultLease.isNegative() || defaultLease.isZero()) {
            errors.add("default-lease-time must be positive");
        }

        Duration defaultWait = getDefaultWaitTime();
        if (defaultWait.isNegative()) {
            errors.add("default-wait-time cannot be negative");
        }

        // Validate watchdog settings
        if (isWatchdogEnabled()) {
            Duration renewalInterval = getWatchdogRenewalInterval();
            if (renewalInterval.isNegative() || renewalInterval.isZero()) {
                errors.add("watchdog.renewal-interval must be positive when watchdog is enabled");
            }
        }

        // Validate retry settings
        Duration retryInterval = getRetryInterval();
        if (retryInterval.isNegative() || retryInterval.isZero()) {
            errors.add("retry-interval must be positive");
        }

        int maxRetries = getMaxRetries();
        if (maxRetries < 0) {
            errors.add("max-retries cannot be negative");
        }

        // Validate Redis settings
        String redisHosts = getRedisHosts();
        if (redisHosts != null && !redisHosts.trim().isEmpty()) {
            String[] hosts = redisHosts.split(",");
            for (String host : hosts) {
                if (!host.contains(":")) {
                    errors.add("Redis host '" + host + "' must be in format host:port");
                }
            }
        }

        if (!errors.isEmpty()) {
            String message = errors.stream().collect(Collectors.joining(", "));
            throw new ConfigException.BadValue("distributed-lock", message);
        }
    }

    /**
     * Update the configuration dynamically.
     * This will notify all registered change listeners.
     *
     * @param newConfig the new configuration
     */
    public synchronized void updateConfiguration(Config newConfig) {
        this.config = newConfig;
        // Notify listeners
        changeListeners.forEach(listener -> {
            try {
                listener.accept(this);
            } catch (Exception e) {
                // Log error but continue with other listeners
                System.err.println("Error notifying configuration change listener: " + e.getMessage());
            }
        });
    }

    /**
     * Add a listener for configuration changes.
     *
     * @param listener the listener to add
     */
    public void addChangeListener(Consumer<LockConfiguration> listener) {
        synchronized (changeListeners) {
            changeListeners.add(listener);
        }
    }

    /**
     * Remove a configuration change listener.
     *
     * @param listener the listener to remove
     */
    public void removeChangeListener(Consumer<LockConfiguration> listener) {
        synchronized (changeListeners) {
            changeListeners.remove(listener);
        }
    }
}
