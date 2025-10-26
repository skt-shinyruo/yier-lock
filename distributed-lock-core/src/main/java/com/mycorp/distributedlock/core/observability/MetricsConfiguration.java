package com.mycorp.distributedlock.core.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指标配置管理
 * 统一管理分布式锁指标系统的配置
 */
public class MetricsConfiguration {
    
    private static final Logger logger = LoggerFactory.getLogger(MetricsConfiguration.class);
    
    private final MetricsConfig config;
    private final Map<String, Object> dynamicConfig;
    private final List<ConfigChangeListener> listeners;
    
    public MetricsConfiguration() {
        this(new MetricsConfig());
    }
    
    public MetricsConfiguration(MetricsConfig config) {
        this.config = config != null ? config : new MetricsConfig();
        this.dynamicConfig = new ConcurrentHashMap<>();
        this.listeners = new ArrayList<>();
        
        // 设置默认值
        initializeDefaultConfig();
        
        logger.info("MetricsConfiguration initialized with config: {}", config);
    }
    
    /**
     * 获取配置
     */
    public MetricsConfig getConfig() {
        return config;
    }
    
    /**
     * 获取动态配置值
     */
    public Object getDynamicConfig(String key) {
        return dynamicConfig.get(key);
    }
    
    /**
     * 设置动态配置值
     */
    public void setDynamicConfig(String key, Object value) {
        Object oldValue = dynamicConfig.put(key, value);
        notifyConfigChange(key, oldValue, value);
        logger.debug("Updated dynamic config: {} = {}", key, value);
    }
    
    /**
     * 获取配置值（带默认值）
     */
    public <T> T getConfigValue(String key, T defaultValue) {
        Object value = dynamicConfig.get(key);
        return value != null ? (T) value : defaultValue;
    }
    
    /**
     * 添加配置变更监听器
     */
    public void addConfigChangeListener(ConfigChangeListener listener) {
        listeners.add(listener);
    }
    
    /**
     * 移除配置变更监听器
     */
    public void removeConfigChangeListener(ConfigChangeListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * 验证配置
     */
    public ConfigValidationResult validateConfig() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        
        // 验证基本配置
        if (config.getMetricsRetentionDuration().toHours() < 1) {
            errors.add("Metrics retention duration must be at least 1 hour");
        }
        
        if (config.getMetricsCollectionInterval().toSeconds() < 5) {
            warnings.add("Metrics collection interval is very short, may impact performance");
        }
        
        if (config.getBufferSize() < 1000) {
            warnings.add("Buffer size is small, may lose metrics during high load");
        }
        
        return new ConfigValidationResult(errors.isEmpty(), errors, warnings);
    }
    
    /**
     * 创建指标配置构建器
     */
    public MetricsConfigBuilder createBuilder() {
        return new MetricsConfigBuilder();
    }
    
    /**
     * 加载环境变量配置
     */
    public void loadFromEnvironment() {
        // 从系统属性加载配置
        String prometheusEnabled = System.getProperty("distributed.lock.metrics.prometheus.enabled");
        if (prometheusEnabled != null) {
            config.setPrometheusEnabled(Boolean.parseBoolean(prometheusEnabled));
        }
        
        String jmxEnabled = System.getProperty("distributed.lock.metrics.jmx.enabled");
        if (jmxEnabled != null) {
            config.setJmxEnabled(Boolean.parseBoolean(jmxEnabled));
        }
        
        String collectionInterval = System.getProperty("distributed.lock.metrics.collection.interval");
        if (collectionInterval != null) {
            try {
                config.setMetricsCollectionInterval(Duration.parse(collectionInterval));
            } catch (Exception e) {
                logger.warn("Invalid collection interval format: {}", collectionInterval);
            }
        }
        
        logger.info("Loaded configuration from environment properties");
    }
    
    /**
     * 重新加载配置
     */
    public void reload() {
        logger.info("Reloading metrics configuration");
        loadFromEnvironment();
    }
    
    // 私有方法
    
    private void initializeDefaultConfig() {
        // 设置默认值
        dynamicConfig.put("metrics.retention.hours", 24);
        dynamicConfig.put("alerting.enabled", true);
        dynamicConfig.put("jmx.remote.access", false);
        dynamicConfig.put("prometheus.export.interval", 30);
        dynamicConfig.put("micrometer.detailed.tags", false);
        dynamicConfig.put("health.check.enabled", true);
    }
    
    private void notifyConfigChange(String key, Object oldValue, Object newValue) {
        for (ConfigChangeListener listener : listeners) {
            try {
                listener.onConfigChanged(key, oldValue, newValue);
            } catch (Exception e) {
                logger.error("Error notifying config change listener", e);
            }
        }
    }
    
    /**
     * 指标配置
     */
    public static class MetricsConfig {
        // 基础配置
        private boolean metricsEnabled = true;
        private boolean tracingEnabled = true;
        private Duration metricsRetentionDuration = Duration.ofHours(24);
        private Duration metricsCollectionInterval = Duration.ofSeconds(30);
        private int bufferSize = 10000;
        
        // Micrometer配置
        private boolean micrometerEnabled = true;
        private String micrometerApplicationName = "distributed-lock";
        private String micrometerInstanceId = "default";
        private boolean micrometerDetailedTags = false;
        
        // Prometheus配置
        private boolean prometheusEnabled = true;
        private int prometheusHttpPort = 8080;
        private String prometheusPushGatewayUrl;
        private String prometheusPushGatewayJob = "distributed-lock";
        private boolean prometheusAutoPush = false;
        private Duration prometheusPushInterval = Duration.ofSeconds(30);
        
        // JMX配置
        private boolean jmxEnabled = true;
        private String jmxDomainName = "com.mycorp.distributedlock";
        private boolean jmxRemoteAccess = false;
        private int jmxRmiRegistryPort = 9999;
        private int jmxRmiServerPort = 10000;
        
        // 监控配置
        private boolean monitoringEnabled = true;
        private Duration monitoringInterval = Duration.ofSeconds(30);
        private int monitoringThreadPoolSize = 4;
        private boolean monitoringDetailedMetrics = true;
        
        // 告警配置
        private boolean alertingEnabled = true;
        private Duration alertingCheckInterval = Duration.ofSeconds(30);
        private Duration alertingSuppressionDuration = Duration.ofMinutes(5);
        private boolean alertingLogEnabled = true;
        private boolean alertingMicrometerEnabled = true;
        
        // 健康检查配置
        private boolean healthCheckEnabled = true;
        private Duration healthCheckInterval = Duration.ofSeconds(60);
        private int healthCheckThreadPoolSize = 2;
        private boolean healthCheckDetailed = true;
        
        // Getters and setters
        public boolean isMetricsEnabled() { return metricsEnabled; }
        public void setMetricsEnabled(boolean metricsEnabled) { 
            this.metricsEnabled = metricsEnabled; 
        }
        
        public boolean isTracingEnabled() { return tracingEnabled; }
        public void setTracingEnabled(boolean tracingEnabled) { 
            this.tracingEnabled = tracingEnabled; 
        }
        
        public Duration getMetricsRetentionDuration() { return metricsRetentionDuration; }
        public void setMetricsRetentionDuration(Duration metricsRetentionDuration) { 
            this.metricsRetentionDuration = metricsRetentionDuration; 
        }
        
        public Duration getMetricsCollectionInterval() { return metricsCollectionInterval; }
        public void setMetricsCollectionInterval(Duration metricsCollectionInterval) { 
            this.metricsCollectionInterval = metricsCollectionInterval; 
        }
        
        public int getBufferSize() { return bufferSize; }
        public void setBufferSize(int bufferSize) { 
            this.bufferSize = bufferSize; 
        }
        
        public boolean isMicrometerEnabled() { return micrometerEnabled; }
        public void setMicrometerEnabled(boolean micrometerEnabled) { 
            this.micrometerEnabled = micrometerEnabled; 
        }
        
        public String getMicrometerApplicationName() { 
            return micrometerApplicationName; 
        }
        public void setMicrometerApplicationName(String micrometerApplicationName) { 
            this.micrometerApplicationName = micrometerApplicationName; 
        }
        
        public String getMicrometerInstanceId() { return micrometerInstanceId; }
        public void setMicrometerInstanceId(String micrometerInstanceId) { 
            this.micrometerInstanceId = micrometerInstanceId; 
        }
        
        public boolean isMicrometerDetailedTags() { return micrometerDetailedTags; }
        public void setMicrometerDetailedTags(boolean micrometerDetailedTags) { 
            this.micrometerDetailedTags = micrometerDetailedTags; 
        }
        
        public boolean isPrometheusEnabled() { return prometheusEnabled; }
        public void setPrometheusEnabled(boolean prometheusEnabled) { 
            this.prometheusEnabled = prometheusEnabled; 
        }
        
        public int getPrometheusHttpPort() { return prometheusHttpPort; }
        public void setPrometheusHttpPort(int prometheusHttpPort) { 
            this.prometheusHttpPort = prometheusHttpPort; 
        }
        
        public String getPrometheusPushGatewayUrl() { 
            return prometheusPushGatewayUrl; 
        }
        public void setPrometheusPushGatewayUrl(String prometheusPushGatewayUrl) { 
            this.prometheusPushGatewayUrl = prometheusPushGatewayUrl; 
        }
        
        public String getPrometheusPushGatewayJob() { return prometheusPushGatewayJob; }
        public void setPrometheusPushGatewayJob(String prometheusPushGatewayJob) { 
            this.prometheusPushGatewayJob = prometheusPushGatewayJob; 
        }
        
        public boolean isPrometheusAutoPush() { return prometheusAutoPush; }
        public void setPrometheusAutoPush(boolean prometheusAutoPush) { 
            this.prometheusAutoPush = prometheusAutoPush; 
        }
        
        public Duration getPrometheusPushInterval() { return prometheusPushInterval; }
        public void setPrometheusPushInterval(Duration prometheusPushInterval) { 
            this.prometheusPushInterval = prometheusPushInterval; 
        }
        
        public boolean isJmxEnabled() { return jmxEnabled; }
        public void setJmxEnabled(boolean jmxEnabled) { 
            this.jmxEnabled = jmxEnabled; 
        }
        
        public String getJmxDomainName() { return jmxDomainName; }
        public void setJmxDomainName(String jmxDomainName) { 
            this.jmxDomainName = jmxDomainName; 
        }
        
        public boolean isJmxRemoteAccess() { return jmxRemoteAccess; }
        public void setJmxRemoteAccess(boolean jmxRemoteAccess) { 
            this.jmxRemoteAccess = jmxRemoteAccess; 
        }
        
        public int getJmxRmiRegistryPort() { return jmxRmiRegistryPort; }
        public void setJmxRmiRegistryPort(int jmxRmiRegistryPort) { 
            this.jmxRmiRegistryPort = jmxRmiRegistryPort; 
        }
        
        public int getJmxRmiServerPort() { return jmxRmiServerPort; }
        public void setJmxRmiServerPort(int jmxRmiServerPort) { 
            this.jmxRmiServerPort = jmxRmiServerPort; 
        }
        
        public boolean isMonitoringEnabled() { return monitoringEnabled; }
        public void setMonitoringEnabled(boolean monitoringEnabled) { 
            this.monitoringEnabled = monitoringEnabled; 
        }
        
        public Duration getMonitoringInterval() { return monitoringInterval; }
        public void setMonitoringInterval(Duration monitoringInterval) { 
            this.monitoringInterval = monitoringInterval; 
        }
        
        public int getMonitoringThreadPoolSize() { return monitoringThreadPoolSize; }
        public void setMonitoringThreadPoolSize(int monitoringThreadPoolSize) { 
            this.monitoringThreadPoolSize = monitoringThreadPoolSize; 
        }
        
        public boolean isMonitoringDetailedMetrics() { 
            return monitoringDetailedMetrics; 
        }
        public void setMonitoringDetailedMetrics(boolean monitoringDetailedMetrics) { 
            this.monitoringDetailedMetrics = monitoringDetailedMetrics; 
        }
        
        public boolean isAlertingEnabled() { return alertingEnabled; }
        public void setAlertingEnabled(boolean alertingEnabled) { 
            this.alertingEnabled = alertingEnabled; 
        }
        
        public Duration getAlertingCheckInterval() { return alertingCheckInterval; }
        public void setAlertingCheckInterval(Duration alertingCheckInterval) { 
            this.alertingCheckInterval = alertingCheckInterval; 
        }
        
        public Duration getAlertingSuppressionDuration() { 
            return alertingSuppressionDuration; 
        }
        public void setAlertingSuppressionDuration(Duration alertingSuppressionDuration) { 
            this.alertingSuppressionDuration = alertingSuppressionDuration; 
        }
        
        public boolean isAlertingLogEnabled() { return alertingLogEnabled; }
        public void setAlertingLogEnabled(boolean alertingLogEnabled) { 
            this.alertingLogEnabled = alertingLogEnabled; 
        }
        
        public boolean isAlertingMicrometerEnabled() { 
            return alertingMicrometerEnabled; 
        }
        public void setAlertingMicrometerEnabled(boolean alertingMicrometerEnabled) { 
            this.alertingMicrometerEnabled = alertingMicrometerEnabled; 
        }
        
        public boolean isHealthCheckEnabled() { return healthCheckEnabled; }
        public void setHealthCheckEnabled(boolean healthCheckEnabled) { 
            this.healthCheckEnabled = healthCheckEnabled; 
        }
        
        public Duration getHealthCheckInterval() { return healthCheckInterval; }
        public void setHealthCheckInterval(Duration healthCheckInterval) { 
            this.healthCheckInterval = healthCheckInterval; 
        }
        
        public int getHealthCheckThreadPoolSize() { return healthCheckThreadPoolSize; }
        public void setHealthCheckThreadPoolSize(int healthCheckThreadPoolSize) { 
            this.healthCheckThreadPoolSize = healthCheckThreadPoolSize; 
        }
        
        public boolean isHealthCheckDetailed() { return healthCheckDetailed; }
        public void setHealthCheckDetailed(boolean healthCheckDetailed) { 
            this.healthCheckDetailed = healthCheckDetailed; 
        }
        
        @Override
        public String toString() {
            return "MetricsConfig{" +
                "metricsEnabled=" + metricsEnabled +
                ", tracingEnabled=" + tracingEnabled +
                ", metricsRetentionDuration=" + metricsRetentionDuration +
                ", metricsCollectionInterval=" + metricsCollectionInterval +
                ", bufferSize=" + bufferSize +
                ", micrometerEnabled=" + micrometerEnabled +
                ", prometheusEnabled=" + prometheusEnabled +
                ", jmxEnabled=" + jmxEnabled +
                ", monitoringEnabled=" + monitoringEnabled +
                ", alertingEnabled=" + alertingEnabled +
                ", healthCheckEnabled=" + healthCheckEnabled +
                '}';
        }
    }
    
    /**
     * 配置构建器
     */
    public static class MetricsConfigBuilder {
        private final MetricsConfig config = new MetricsConfig();
        
        public MetricsConfigBuilder enableMetrics(boolean enabled) {
            config.setMetricsEnabled(enabled);
            return this;
        }
        
        public MetricsConfigBuilder enableTracing(boolean enabled) {
            config.setTracingEnabled(enabled);
            return this;
        }
        
        public MetricsConfigBuilder retentionDuration(Duration duration) {
            config.setMetricsRetentionDuration(duration);
            return this;
        }
        
        public MetricsConfigBuilder collectionInterval(Duration interval) {
            config.setMetricsCollectionInterval(interval);
            return this;
        }
        
        public MetricsConfigBuilder bufferSize(int size) {
            config.setBufferSize(size);
            return this;
        }
        
        public MetricsConfigBuilder enablePrometheus(boolean enabled) {
            config.setPrometheusEnabled(enabled);
            return this;
        }
        
        public MetricsConfigBuilder prometheusPort(int port) {
            config.setPrometheusHttpPort(port);
            return this;
        }
        
        public MetricsConfigBuilder enableJmx(boolean enabled) {
            config.setJmxEnabled(enabled);
            return this;
        }
        
        public MetricsConfigBuilder enableMonitoring(boolean enabled) {
            config.setMonitoringEnabled(enabled);
            return this;
        }
        
        public MetricsConfigBuilder enableAlerting(boolean enabled) {
            config.setAlertingEnabled(enabled);
            return this;
        }
        
        public MetricsConfigBuilder enableHealthCheck(boolean enabled) {
            config.setHealthCheckEnabled(enabled);
            return this;
        }
        
        public MetricsConfig build() {
            return config;
        }
    }
    
    /**
     * 配置验证结果
     */
    public static class ConfigValidationResult {
        private final boolean valid;
        private final List<String> errors;
        private final List<String> warnings;
        
        public ConfigValidationResult(boolean valid, List<String> errors, List<String> warnings) {
            this.valid = valid;
            this.errors = new ArrayList<>(errors);
            this.warnings = new ArrayList<>(warnings);
        }
        
        public boolean isValid() { return valid; }
        public List<String> getErrors() { return new ArrayList<>(errors); }
        public List<String> getWarnings() { return new ArrayList<>(warnings); }
        
        public boolean hasErrors() { return !errors.isEmpty(); }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }
    
    /**
     * 配置变更监听器
     */
    @FunctionalInterface
    public interface ConfigChangeListener {
        void onConfigChanged(String key, Object oldValue, Object newValue);
    }
}