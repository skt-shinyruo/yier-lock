package com.mycorp.distributedlock.core.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 统一配置管理器
 * 整合Typesafe Config和Spring Environment配置
 */
public class UnifiedLockConfiguration {

    private final Config config;
    private final Environment environment;
    private final Map<String, Object> overrides = new HashMap<>();

    public UnifiedLockConfiguration(Environment environment) {
        this.environment = environment;
        this.config = buildUnifiedConfig();
    }

    public UnifiedLockConfiguration(Config config, Environment environment) {
        this.environment = environment;
        this.config = buildUnifiedConfig().withFallback(config);
    }

    /**
     * 从Spring Environment构建Typesafe Config
     */
    private Config buildUnifiedConfig() {
        Map<String, Object> configMap = new HashMap<>();

        // 从Spring Environment读取配置并转换为Typesafe Config格式
        if (environment != null) {
            // Redis配置
            addRedisConfig(configMap);

            // ZooKeeper配置
            addZookeeperConfig(configMap);

            // 通用配置
            addCommonConfig(configMap);
        }

        return ConfigFactory.parseMap(configMap).withFallback(ConfigFactory.load());
    }

    private void addRedisConfig(Map<String, Object> configMap) {
        String prefix = "spring.distributed-lock.redis";

        // 基本连接配置
        setIfPresent(configMap, prefix + ".hosts", "distributed-lock.redis.hosts");
        setIfPresent(configMap, prefix + ".password", "distributed-lock.redis.password");
        setIfPresent(configMap, prefix + ".ssl", "distributed-lock.redis.ssl");

        // TLS配置
        setIfPresent(configMap, prefix + ".trust-store-path", "distributed-lock.redis.trust-store-path");
        setIfPresent(configMap, prefix + ".trust-store-password", "distributed-lock.redis.trust-store-password");
        setIfPresent(configMap, prefix + ".key-store-path", "distributed-lock.redis.key-store-path");
        setIfPresent(configMap, prefix + ".key-store-password", "distributed-lock.redis.key-store-password");

        // 连接池配置
        setIfPresent(configMap, prefix + ".pool.max-total", "distributed-lock.redis.pool.max-total");
        setIfPresent(configMap, prefix + ".pool.max-idle", "distributed-lock.redis.pool.max-idle");
        setIfPresent(configMap, prefix + ".pool.min-idle", "distributed-lock.redis.pool.min-idle");
        setIfPresent(configMap, prefix + ".pool.max-wait", "distributed-lock.redis.pool.max-wait");
    }

    private void addZookeeperConfig(Map<String, Object> configMap) {
        String prefix = "spring.distributed-lock.zookeeper";

        setIfPresent(configMap, prefix + ".connect-string", "distributed-lock.zookeeper.connect-string");
        setIfPresent(configMap, prefix + ".base-path", "distributed-lock.zookeeper.base-path");
        setIfPresent(configMap, prefix + ".session-timeout", "distributed-lock.zookeeper.session-timeout");
        setIfPresent(configMap, prefix + ".connection-timeout", "distributed-lock.zookeeper.connection-timeout");

        // 认证配置
        setIfPresent(configMap, prefix + ".auth-enabled", "distributed-lock.zookeeper.auth-enabled");
        setIfPresent(configMap, prefix + ".auth-scheme", "distributed-lock.zookeeper.auth-scheme");
        setIfPresent(configMap, prefix + ".auth-info", "distributed-lock.zookeeper.auth-info");

        // ACL配置
        setIfPresent(configMap, prefix + ".acl-enabled", "distributed-lock.zookeeper.acl-enabled");
        setIfPresent(configMap, prefix + ".acl.permissions", "distributed-lock.zookeeper.acl.permissions");
        setIfPresent(configMap, prefix + ".acl.scheme", "distributed-lock.zookeeper.acl.scheme");
        setIfPresent(configMap, prefix + ".acl.id", "distributed-lock.zookeeper.acl.id");
    }

    private void addCommonConfig(Map<String, Object> configMap) {
        String prefix = "spring.distributed-lock";

        setIfPresent(configMap, prefix + ".type", "distributed-lock.type");
        setIfPresent(configMap, prefix + ".default-lease-time", "distributed-lock.default-lease-time");
        setIfPresent(configMap, prefix + ".default-wait-time", "distributed-lock.default-wait-time");
        setIfPresent(configMap, prefix + ".retry-interval", "distributed-lock.retry-interval");
        setIfPresent(configMap, prefix + ".max-retries", "distributed-lock.max-retries");
        setIfPresent(configMap, prefix + ".watchdog.enabled", "distributed-lock.watchdog.enabled");
        setIfPresent(configMap, prefix + ".watchdog.renewal-interval", "distributed-lock.watchdog.renewal-interval");
        setIfPresent(configMap, prefix + ".metrics.enabled", "distributed-lock.metrics.enabled");
        setIfPresent(configMap, prefix + ".tracing.enabled", "distributed-lock.tracing.enabled");
    }

    private void setIfPresent(Map<String, Object> configMap, String springKey, String configKey) {
        if (environment != null && environment.containsProperty(springKey)) {
            configMap.put(configKey, environment.getProperty(springKey));
        }
    }

    /**
     * 获取配置值，支持运行时覆盖
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String path, Class<T> type) {
        if (overrides.containsKey(path)) {
            return (T) overrides.get(path);
        }
        return (T) config.getAnyRef(path);
    }

    /**
     * 设置运行时配置覆盖
     */
    public void setOverride(String path, Object value) {
        overrides.put(path, value);
    }

    /**
     * 移除运行时配置覆盖
     */
    public void removeOverride(String path) {
        overrides.remove(path);
    }

    public Config getConfig() {
        return config;
    }

    public Environment getEnvironment() {
        return environment;
    }
}
