package com.mycorp.distributedlock.springboot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.*;

/**
 * 分布式锁配置属性
 * 包含监控和指标收集相关配置
 */
@ConfigurationProperties(prefix = "distributed.lock")
public class DistributedLockProperties {

    /** 是否启用分布式锁 */
    private boolean enabled = true;

    /** 默认锁超时时间 */
    private Duration defaultTimeout = Duration.ofSeconds(30);

    /** 旧版基准兼容用续期间隔 */
    private Duration renewInterval = Duration.ofSeconds(10);

    /** SPI provider类型，例如 redis / zookeeper */
    private String type;

    /** 重试配置 */
    private Retry retry = new Retry();

    /** 监控和指标配置 */
    private MetricsProperties metrics = new MetricsProperties();

    /** AOP配置 */
    private AspectProperties aspect = new AspectProperties();

    // ===== Getters and Setters =====

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getDefaultTimeout() {
        return defaultTimeout;
    }

    public void setDefaultTimeout(Duration defaultTimeout) {
        this.defaultTimeout = defaultTimeout;
    }

    public Duration getRenewInterval() {
        return renewInterval;
    }

    public void setRenewInterval(Duration renewInterval) {
        this.renewInterval = renewInterval;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public long getLockTimeout() {
        return defaultTimeout.toMillis();
    }

    public void setLockTimeout(long lockTimeoutMs) {
        this.defaultTimeout = Duration.ofMillis(lockTimeoutMs);
    }

    public long getRenewIntervalMillis() {
        return renewInterval.toMillis();
    }

    public void setRenewInterval(long renewIntervalMs) {
        this.renewInterval = Duration.ofMillis(renewIntervalMs);
    }

    public int getRetryAttempts() {
        return retry.getMaxAttempts();
    }

    public void setRetryAttempts(int retryAttempts) {
        retry.setMaxAttempts(retryAttempts);
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public MetricsProperties getMetrics() {
        return metrics;
    }

    public void setMetrics(MetricsProperties metrics) {
        this.metrics = metrics;
    }

    public AspectProperties getAspect() {
        return aspect;
    }

    public void setAspect(AspectProperties aspect) {
        this.aspect = aspect;
    }

    // ===== 内部配置类 =====

    /**
     * 重试配置
     */
    public static class Retry {
        /** 是否启用重试 */
        private boolean enabled = false;

        /** 最大重试次数 */
        private int maxAttempts = 3;

        /** 重试间隔 */
        private Duration delay = Duration.ofMillis(100);

        /** 指数退避 */
        private boolean exponentialBackoff = false;

        /** 最大退避时间 */
        private Duration maxDelay = Duration.ofSeconds(10);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getDelay() {
            return delay;
        }

        public void setDelay(Duration delay) {
            this.delay = delay;
        }

        public boolean isExponentialBackoff() {
            return exponentialBackoff;
        }

        public void setExponentialBackoff(boolean exponentialBackoff) {
            this.exponentialBackoff = exponentialBackoff;
        }

        public Duration getMaxDelay() {
            return maxDelay;
        }

        public void setMaxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
        }
    }

    /**
     * 监控和指标配置
     */
    public static class MetricsProperties {
        /** 是否启用监控和指标收集 */
        private boolean enabled = true;

        /** 是否启用分布式追踪 */
        private boolean tracingEnabled = true;

        // Micrometer配置
        private MicrometerProperties micrometer = new MicrometerProperties();

        // Prometheus配置
        private PrometheusProperties prometheus = new PrometheusProperties();

        // JMX配置
        private JmxProperties jmx = new JmxProperties();

        // 监控配置
        private MonitoringProperties monitoring = new MonitoringProperties();

        // 告警配置
        private AlertingProperties alerting = new AlertingProperties();

        // 健康检查配置
        private HealthCheckProperties healthCheck = new HealthCheckProperties();

        // 基础配置
        /** 指标保留时间 */
        private Duration retentionDuration = Duration.ofHours(24);

        /** 指标收集间隔 */
        private Duration collectionInterval = Duration.ofSeconds(30);

        /** 缓冲区大小 */
        private int bufferSize = 10000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isTracingEnabled() {
            return tracingEnabled;
        }

        public void setTracingEnabled(boolean tracingEnabled) {
            this.tracingEnabled = tracingEnabled;
        }

        public MicrometerProperties getMicrometer() {
            return micrometer;
        }

        public void setMicrometer(MicrometerProperties micrometer) {
            this.micrometer = micrometer;
        }

        public PrometheusProperties getPrometheus() {
            return prometheus;
        }

        public void setPrometheus(PrometheusProperties prometheus) {
            this.prometheus = prometheus;
        }

        public JmxProperties getJmx() {
            return jmx;
        }

        public void setJmx(JmxProperties jmx) {
            this.jmx = jmx;
        }

        public MonitoringProperties getMonitoring() {
            return monitoring;
        }

        public void setMonitoring(MonitoringProperties monitoring) {
            this.monitoring = monitoring;
        }

        public AlertingProperties getAlerting() {
            return alerting;
        }

        public void setAlerting(AlertingProperties alerting) {
            this.alerting = alerting;
        }

        public HealthCheckProperties getHealthCheck() {
            return healthCheck;
        }

        public void setHealthCheck(HealthCheckProperties healthCheck) {
            this.healthCheck = healthCheck;
        }

        public Duration getRetentionDuration() {
            return retentionDuration;
        }

        public void setRetentionDuration(Duration retentionDuration) {
            this.retentionDuration = retentionDuration;
        }

        public Duration getCollectionInterval() {
            return collectionInterval;
        }

        public void setCollectionInterval(Duration collectionInterval) {
            this.collectionInterval = collectionInterval;
        }

        public int getBufferSize() {
            return bufferSize;
        }

        public void setBufferSize(int bufferSize) {
            this.bufferSize = bufferSize;
        }
    }

    /**
     * Micrometer配置
     */
    public static class MicrometerProperties {
        /** 是否启用Micrometer指标 */
        private boolean enabled = true;

        /** 应用名称 */
        private String applicationName = "distributed-lock";

        /** 实例ID */
        private String instanceId = "default";

        /** 是否启用详细标签 */
        private boolean detailedTags = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApplicationName() {
            return applicationName;
        }

        public void setApplicationName(String applicationName) {
            this.applicationName = applicationName;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
        }

        public boolean isDetailedTags() {
            return detailedTags;
        }

        public void setDetailedTags(boolean detailedTags) {
            this.detailedTags = detailedTags;
        }
    }

    /**
     * Prometheus配置
     */
    public static class PrometheusProperties {
        /** 是否启用Prometheus指标导出 */
        private boolean enabled = true;

        /** HTTP服务器端口 */
        private Integer httpPort = 8080;

        /** PushGateway URL */
        private String pushGatewayUrl;

        /** PushGateway Job名称 */
        private String pushGatewayJob = "distributed-lock";

        /** 是否启用自动推送 */
        private boolean autoPush = false;

        /** 推送间隔 */
        private Duration pushInterval = Duration.ofSeconds(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getHttpPort() {
            return httpPort;
        }

        public void setHttpPort(Integer httpPort) {
            this.httpPort = httpPort;
        }

        public String getPushGatewayUrl() {
            return pushGatewayUrl;
        }

        public void setPushGatewayUrl(String pushGatewayUrl) {
            this.pushGatewayUrl = pushGatewayUrl;
        }

        public String getPushGatewayJob() {
            return pushGatewayJob;
        }

        public void setPushGatewayJob(String pushGatewayJob) {
            this.pushGatewayJob = pushGatewayJob;
        }

        public boolean isAutoPush() {
            return autoPush;
        }

        public void setAutoPush(boolean autoPush) {
            this.autoPush = autoPush;
        }

        public Duration getPushInterval() {
            return pushInterval;
        }

        public void setPushInterval(Duration pushInterval) {
            this.pushInterval = pushInterval;
        }
    }

    /**
     * JMX配置
     */
    public static class JmxProperties {
        /** 是否启用JMX */
        private boolean enabled = true;

        /** MBean域名 */
        private String domainName = "com.mycorp.distributedlock";

        /** 是否启用远程访问 */
        private boolean remoteAccess = false;

        /** RMI注册表端口 */
        private int rmiRegistryPort = 9999;

        /** RMI服务器端口 */
        private int rmiServerPort = 10000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDomainName() {
            return domainName;
        }

        public void setDomainName(String domainName) {
            this.domainName = domainName;
        }

        public boolean isRemoteAccess() {
            return remoteAccess;
        }

        public void setRemoteAccess(boolean remoteAccess) {
            this.remoteAccess = remoteAccess;
        }

        public int getRmiRegistryPort() {
            return rmiRegistryPort;
        }

        public void setRmiRegistryPort(int rmiRegistryPort) {
            this.rmiRegistryPort = rmiRegistryPort;
        }

        public int getRmiServerPort() {
            return rmiServerPort;
        }

        public void setRmiServerPort(int rmiServerPort) {
            this.rmiServerPort = rmiServerPort;
        }
    }

    /**
     * 监控配置
     */
    public static class MonitoringProperties {
        /** 是否启用监控 */
        private boolean enabled = true;

        /** 监控间隔 */
        private Duration interval = Duration.ofSeconds(30);

        /** 线程池大小 */
        private int threadPoolSize = 4;

        /** 是否启用详细指标 */
        private boolean detailedMetrics = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public int getThreadPoolSize() {
            return threadPoolSize;
        }

        public void setThreadPoolSize(int threadPoolSize) {
            this.threadPoolSize = threadPoolSize;
        }

        public boolean isDetailedMetrics() {
            return detailedMetrics;
        }

        public void setDetailedMetrics(boolean detailedMetrics) {
            this.detailedMetrics = detailedMetrics;
        }
    }

    /**
     * 告警配置
     */
    public static class AlertingProperties {
        /** 是否启用告警 */
        private boolean enabled = true;

        /** 检查间隔 */
        private Duration checkInterval = Duration.ofSeconds(30);

        /** 告警抑制时间 */
        private Duration suppressionDuration = Duration.ofMinutes(5);

        /** 是否启用日志告警 */
        private boolean logEnabled = true;

        /** 是否启用Micrometer告警 */
        private boolean micrometerEnabled = true;

        /** 是否启用定期摘要 */
        private boolean periodicSummary = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getCheckInterval() {
            return checkInterval;
        }

        public void setCheckInterval(Duration checkInterval) {
            this.checkInterval = checkInterval;
        }

        public Duration getSuppressionDuration() {
            return suppressionDuration;
        }

        public void setSuppressionDuration(Duration suppressionDuration) {
            this.suppressionDuration = suppressionDuration;
        }

        public boolean isLogEnabled() {
            return logEnabled;
        }

        public void setLogEnabled(boolean logEnabled) {
            this.logEnabled = logEnabled;
        }

        public boolean isMicrometerEnabled() {
            return micrometerEnabled;
        }

        public void setMicrometerEnabled(boolean micrometerEnabled) {
            this.micrometerEnabled = micrometerEnabled;
        }

        public boolean isPeriodicSummary() {
            return periodicSummary;
        }

        public void setPeriodicSummary(boolean periodicSummary) {
            this.periodicSummary = periodicSummary;
        }
    }

    /**
     * 健康检查配置
     */
    public static class HealthCheckProperties {
        /** 是否启用健康检查 */
        private boolean enabled = true;

        /** 检查间隔 */
        private Duration interval = Duration.ofSeconds(60);

        /** 线程池大小 */
        private int threadPoolSize = 2;

        /** 是否启用详细检查 */
        private boolean detailed = true;

        /** 是否启用自动恢复 */
        private boolean autoRecovery = true;

        /** 检查超时时间 */
        private Duration timeout = Duration.ofSeconds(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public int getThreadPoolSize() {
            return threadPoolSize;
        }

        public void setThreadPoolSize(int threadPoolSize) {
            this.threadPoolSize = threadPoolSize;
        }

        public boolean isDetailed() {
            return detailed;
        }

        public void setDetailed(boolean detailed) {
            this.detailed = detailed;
        }

        public boolean isAutoRecovery() {
            return autoRecovery;
        }

        public void setAutoRecovery(boolean autoRecovery) {
            this.autoRecovery = autoRecovery;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    /**
     * AOP配置
     */
    public static class AspectProperties {
        /** 是否启用AOP切面 */
        private boolean enabled = true;

        /** 是否启用注解支持 */
        private boolean annotationSupport = true;

        /** 是否启用方法级配置 */
        private boolean methodLevelConfig = true;

        /** 默认超时时间 */
        private Duration defaultTimeout = Duration.ofSeconds(10);

        /** 是否记录方法参数 */
        private boolean logParameters = false;

        /** 是否记录方法返回值 */
        private boolean logReturnValue = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isAnnotationSupport() {
            return annotationSupport;
        }

        public void setAnnotationSupport(boolean annotationSupport) {
            this.annotationSupport = annotationSupport;
        }

        public boolean isMethodLevelConfig() {
            return methodLevelConfig;
        }

        public void setMethodLevelConfig(boolean methodLevelConfig) {
            this.methodLevelConfig = methodLevelConfig;
        }

        public Duration getDefaultTimeout() {
            return defaultTimeout;
        }

        public void setDefaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = defaultTimeout;
        }

        public boolean isLogParameters() {
            return logParameters;
        }

        public void setLogParameters(boolean logParameters) {
            this.logParameters = logParameters;
        }

        public boolean isLogReturnValue() {
            return logReturnValue;
        }

        public void setLogReturnValue(boolean logReturnValue) {
            this.logReturnValue = logReturnValue;
        }
    }
}
