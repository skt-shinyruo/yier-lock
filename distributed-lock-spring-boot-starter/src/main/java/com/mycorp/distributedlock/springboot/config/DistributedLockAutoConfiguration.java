package com.mycorp.distributedlock.springboot.config;

import com.mycorp.distributedlock.core.observability.*;
import com.mycorp.distributedlock.api.LockProvider;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.springboot.SpringDistributedLockFactory;
import com.mycorp.distributedlock.springboot.actuator.DistributedLockHealthIndicator;
import com.mycorp.distributedlock.springboot.aop.DistributedLockAspect;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;

import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import javax.management.MBeanServer;
import java.lang.management.ManagementFactory;

/**
 * 分布式锁自动配置
 * 集成监控和指标收集功能的Spring Boot自动配置
 */
@Configuration
@ConditionalOnClass({DistributedLockFactory.class, MeterRegistry.class})
@EnableConfigurationProperties(DistributedLockProperties.class)
@EnableAspectJAutoProxy
public class DistributedLockAutoConfiguration {

    /**
     * 分布式锁配置Bean
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "distributed.lock.enabled", havingValue = "true", matchIfMissing = true)
    public DistributedLockFactory distributedLockFactory(
            DistributedLockProperties properties,
            LockProvider lockProvider) {
        return new SpringDistributedLockFactory(properties, lockProvider);
    }

    /**
     * 分布式锁健康检查指示器
     */
    @Bean
    @ConditionalOnBean(DistributedLockFactory.class)
    @ConditionalOnClass(HealthIndicator.class)
    public HealthIndicator distributedLockHealthIndicator(DistributedLockFactory lockFactory) {
        return new DistributedLockHealthIndicator(lockFactory);
    }

    /**
     * 分布式锁切面
     */
    @Bean
    @ConditionalOnProperty(name = "distributed.lock.aspect.enabled", havingValue = "true", matchIfMissing = true)
    public DistributedLockAspect distributedLockAspect(DistributedLockFactory lockFactory) {
        return new DistributedLockAspect(lockFactory);
    }

    // ===== 监控和指标收集相关配置 =====

    /**
     * 指标配置管理
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "distributed.lock.metrics.enabled", havingValue = "true", matchIfMissing = true)
    public MetricsConfiguration metricsConfiguration(DistributedLockProperties properties) {
        MetricsConfiguration config = new MetricsConfiguration();
        
        // 从Spring配置加载指标配置
        DistributedLockProperties.MetricsProperties metricsProps = properties.getMetrics();
        
        // 应用配置
        if (metricsProps != null) {
            MetricsConfiguration.MetricsConfig configBean = config.getConfig();
            configBean.setMetricsEnabled(metricsProps.isEnabled());
            configBean.setTracingEnabled(metricsProps.isTracingEnabled());
            configBean.setMicrometerEnabled(metricsProps.isMicrometerEnabled());
            configBean.setPrometheusEnabled(metricsProps.isPrometheusEnabled());
            configBean.setJmxEnabled(metricsProps.isJmxEnabled());
            configBean.setMonitoringEnabled(metricsProps.isMonitoringEnabled());
            configBean.setAlertingEnabled(metricsProps.isAlertingEnabled());
            configBean.setHealthCheckEnabled(metricsProps.isHealthCheckEnabled());
            
            if (metricsProps.getRetentionDuration() != null) {
                configBean.setMetricsRetentionDuration(metricsProps.getRetentionDuration());
            }
            if (metricsProps.getCollectionInterval() != null) {
                configBean.setMetricsCollectionInterval(metricsProps.getCollectionInterval());
            }
            if (metricsProps.getPrometheusPort() != null) {
                configBean.setPrometheusHttpPort(metricsProps.getPrometheusPort());
            }
        }
        
        return config;
    }

    /**
     * 性能指标实现
     */
    @Bean
    @ConditionalOnBean(MetricsConfiguration.class)
    @ConditionalOnMissingBean
    public LockPerformanceMetrics lockPerformanceMetrics(
            MetricsConfiguration config,
            MeterRegistry meterRegistry) {
        
        MetricsConfiguration.MetricsConfig metricsConfig = config.getConfig();
        
        return new LockPerformanceMetrics(
            meterRegistry,
            null, // OpenTelemetry bean could be injected here
            metricsConfig.isMetricsEnabled(),
            metricsConfig.isTracingEnabled()
        );
    }

    /**
     * 指标收集器
     */
    @Bean
    @ConditionalOnBean(LockPerformanceMetrics.class)
    @ConditionalOnMissingBean
    public LockMetricsCollector lockMetricsCollector(
            LockPerformanceMetrics performanceMetrics,
            MetricsConfiguration config) {
        
        MetricsConfiguration.MetricsConfig metricsConfig = config.getConfig();
        
        return new LockMetricsCollector(
            null, // MeterRegistry can be injected
            null, // OpenTelemetry can be injected
            metricsConfig.isMetricsEnabled()
        );
    }

    /**
     * Micrometer适配器
     */
    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnBean(LockPerformanceMetrics.class)
    @ConditionalOnMissingBean
    public MicrometerMetricsAdapter micrometerMetricsAdapter(
            MeterRegistry meterRegistry,
            LockPerformanceMetrics performanceMetrics,
            MetricsConfiguration config) {
        
        MetricsConfiguration.MetricsConfig metricsConfig = config.getConfig();
        
        return new MicrometerMetricsAdapter(
            meterRegistry,
            metricsConfig.isMicrometerEnabled(),
            metricsConfig.getMicrometerApplicationName(),
            metricsConfig.getMicrometerInstanceId()
        );
    }

    /**
     * Prometheus指标导出器
     */
    @Bean
    @ConditionalOnBean(LockPerformanceMetrics.class)
    @ConditionalOnMissingBean
    public PrometheusMetricsExporter prometheusMetricsExporter(
            LockPerformanceMetrics performanceMetrics,
            MetricsConfiguration config) {
        
        MetricsConfiguration.MetricsConfig metricsConfig = config.getConfig();
        PrometheusMetricsExporter.PrometheusExporterConfig exporterConfig = 
            new PrometheusMetricsExporter.PrometheusExporterConfig();
        
        exporterConfig.setPrometheusEnabled(metricsConfig.isPrometheusEnabled());
        exporterConfig.setApplicationName(metricsConfig.getMicrometerApplicationName());
        exporterConfig.setInstanceId(metricsConfig.getMicrometerInstanceId());
        
        if (metricsConfig.getPrometheusPushGatewayUrl() != null) {
            exporterConfig.setPushGatewayUrl(metricsConfig.getPrometheusPushGatewayUrl());
            exporterConfig.setPushGatewayJob(metricsConfig.getPrometheusPushGatewayJob());
            exporterConfig.setAutoPushEnabled(metricsConfig.isPrometheusAutoPush());
        }
        
        return new PrometheusMetricsExporter(
            performanceMetrics,
            null, // MicrometerMetricsAdapter can be injected
            null, // LockMetricsCollector can be injected
            exporterConfig
        );
    }

    /**
     * 监控服务
     */
    @Bean
    @ConditionalOnBean(LockPerformanceMetrics.class)
    @ConditionalOnMissingBean
    public LockMonitoringService lockMonitoringService(
            LockPerformanceMetrics performanceMetrics,
            MetricsConfiguration config) {
        
        MetricsConfiguration.MetricsConfig metricsConfig = config.getConfig();
        LockMonitoringService.MonitoringConfig monitoringConfig = 
            new LockMonitoringService.MonitoringConfig();
        
        monitoringConfig.setMainMonitoringInterval(metricsConfig.getMonitoringInterval());
        monitoringConfig.setThreadPoolSize(metricsConfig.getMonitoringThreadPoolSize());
        
        return new LockMonitoringService(performanceMetrics, null, monitoringConfig);
    }

    /**
     * 告警服务
     */
    @Bean
    @ConditionalOnBean(LockPerformanceMetrics.class)
    @ConditionalOnMissingBean
    public LockAlertingService lockAlertingService(
            LockPerformanceMetrics performanceMetrics,
            MetricsConfiguration config) {
        
        MetricsConfiguration.MetricsConfig metricsConfig = config.getConfig();
        LockAlertingService.AlertingConfig alertingConfig = 
            new LockAlertingService.AlertingConfig();
        
        alertingConfig.setCheckInterval(metricsConfig.getAlertingCheckInterval());
        alertingConfig.setLogNotificationEnabled(metricsConfig.isAlertingLogEnabled());
        alertingConfig.setMicrometerNotificationEnabled(metricsConfig.isAlertingMicrometerEnabled());
        
        return new LockAlertingService(performanceMetrics, null, alertingConfig);
    }

    /**
     * JMX管理器
     */
    @Bean
    @ConditionalOnBean(LockPerformanceMetrics.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "distributed.lock.metrics.jmx.enabled", havingValue = "true", matchIfMissing = true)
    public LockJMXManager lockJmxManager(
            LockPerformanceMetrics performanceMetrics,
            MetricsConfiguration config) {
        
        MetricsConfiguration.MetricsConfig metricsConfig = config.getConfig();
        LockJMXManager.JMXConfig jmxConfig = new LockJMXManager.JMXConfig();
        
        jmxConfig.setMBeanDomainName(metricsConfig.getJmxDomainName());
        jmxConfig.setEnableRemoteAccess(metricsConfig.isJmxRemoteAccess());
        jmxConfig.setRmiRegistryPort(metricsConfig.getJmxRmiRegistryPort());
        jmxConfig.setRmiServerPort(metricsConfig.getJmxRmiServerPort());
        
        return new LockJMXManager(performanceMetrics, null, jmxConfig);
    }

    /**
     * 健康指标组件
     */
    @Bean
    @ConditionalOnBean(LockPerformanceMetrics.class)
    @ConditionalOnMissingBean
    public LockHealthMetrics lockHealthMetrics(
            LockPerformanceMetrics performanceMetrics,
            MetricsConfiguration config) {
        
        MetricsConfiguration.MetricsConfig metricsConfig = config.getConfig();
        LockHealthMetrics.HealthConfig healthConfig = new LockHealthMetrics.HealthConfig();
        
        healthConfig.setHealthCheckInterval(metricsConfig.getHealthCheckInterval());
        healthConfig.setHealthCheckThreadPoolSize(metricsConfig.getHealthCheckThreadPoolSize());
        healthConfig.setEnableDetailedHealthChecks(metricsConfig.isHealthCheckDetailed());
        
        return new LockHealthMetrics(performanceMetrics, null, healthConfig);
    }

    /**
     * 告警规则管理
     */
    @Bean
    @ConditionalOnMissingBean
    public AlertingRules alertingRules(MetricsConfiguration config) {
        AlertingRules.RulesConfig rulesConfig = new AlertingRules.RulesConfig();
        return new AlertingRules(rulesConfig);
    }

    /**
     * Grafana仪表板配置
     */
    @Bean
    @ConditionalOnMissingBean
    public GrafanaDashboardConfig grafanaDashboardConfig() {
        return new GrafanaDashboardConfig();
    }

    // ===== 启动时初始化逻辑 =====

    /**
     * 启动时初始化监控组件
     */
    @Bean
    @ConditionalOnBean({
        LockPerformanceMetrics.class,
        PrometheusMetricsExporter.class,
        LockJMXManager.class,
        LockHealthMetrics.class
    })
    public ObservabilityInitializer observabilityInitializer(
            LockPerformanceMetrics performanceMetrics,
            PrometheusMetricsExporter prometheusExporter,
            LockJMXManager jmxManager,
            LockHealthMetrics healthMetrics,
            MetricsConfiguration config) {
        
        return new ObservabilityInitializer(
            performanceMetrics,
            prometheusExporter,
            jmxManager,
            healthMetrics,
            config
        );
    }

    /**
     * 监控和指标收集初始化器
     */
    public static class ObservabilityInitializer {
        
        private final LockPerformanceMetrics performanceMetrics;
        private final PrometheusMetricsExporter prometheusExporter;
        private final LockJMXManager jmxManager;
        private final LockHealthMetrics healthMetrics;
        private final MetricsConfiguration config;
        
        public ObservabilityInitializer(
                LockPerformanceMetrics performanceMetrics,
                PrometheusMetricsExporter prometheusExporter,
                LockJMXManager jmxManager,
                LockHealthMetrics healthMetrics,
                MetricsConfiguration config) {
            this.performanceMetrics = performanceMetrics;
            this.prometheusExporter = prometheusExporter;
            this.jmxManager = jmxManager;
            this.healthMetrics = healthMetrics;
            this.config = config;
        }
        
        // 可以在这里添加启动时的初始化逻辑
    }
}