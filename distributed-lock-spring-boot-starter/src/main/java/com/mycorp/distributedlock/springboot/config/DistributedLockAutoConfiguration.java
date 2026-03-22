package com.mycorp.distributedlock.springboot.config;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.api.LockProvider;
import com.mycorp.distributedlock.api.ServiceLoaderDistributedLockFactory;
import com.mycorp.distributedlock.core.observability.MetricsConfiguration;
import com.mycorp.distributedlock.springboot.SpringDistributedLockFactory;
import com.mycorp.distributedlock.springboot.actuator.DistributedLockHealthIndicator;
import com.mycorp.distributedlock.springboot.aop.DistributedLockAspect;
import com.mycorp.distributedlock.springboot.health.DistributedLockHealthChecker;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;

import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 分布式锁自动配置
 * 集成监控和指标收集功能的Spring Boot自动配置
 */
@Configuration
@ConditionalOnClass({DistributedLockFactory.class, MeterRegistry.class})
@EnableConfigurationProperties(DistributedLockProperties.class)
@EnableAspectJAutoProxy
public class DistributedLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LockProvider.class)
    @ConditionalOnProperty(name = "distributed.lock.enabled", havingValue = "true", matchIfMissing = true)
    public LockProvider lockProvider(DistributedLockProperties properties) {
        return new ServiceLoaderLockProviderAdapter(new ServiceLoaderDistributedLockFactory(properties.getType()));
    }

    /**
     * 分布式锁配置Bean
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "distributed.lock.enabled", havingValue = "true", matchIfMissing = true)
    public DistributedLockFactory distributedLockFactory(
            LockProvider lockProvider,
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            ObjectProvider<OpenTelemetry> openTelemetryProvider) {
        return new SpringDistributedLockFactory(
            lockProvider,
            meterRegistryProvider.getIfAvailable(),
            openTelemetryProvider.getIfAvailable()
        );
    }

    /**
     * 分布式锁健康检查器
     */
    @Bean
    @ConditionalOnBean(DistributedLockFactory.class)
    @ConditionalOnMissingBean
    public DistributedLockHealthChecker distributedLockHealthChecker(DistributedLockFactory lockFactory) {
        return new DistributedLockHealthChecker(lockFactory);
    }

    /**
     * 分布式锁健康检查指示器
     */
    @Bean
    @ConditionalOnBean(DistributedLockFactory.class)
    @ConditionalOnClass(HealthIndicator.class)
    public DistributedLockHealthIndicator distributedLockHealthIndicator(DistributedLockHealthChecker healthChecker) {
        return new DistributedLockHealthIndicator(healthChecker);
    }

    /**
     * 分布式锁切面
     */
    @Bean
    @ConditionalOnBean(DistributedLockFactory.class)
    @ConditionalOnProperty(name = "distributed.lock.aspect.enabled", havingValue = "true", matchIfMissing = true)
    public DistributedLockAspect distributedLockAspect(
            DistributedLockFactory lockFactory,
            DistributedLockProperties properties) {
        return new DistributedLockAspect(lockFactory, properties);
    }

    // ===== 监控和指标收集相关配置 =====

    /**
     * 指标配置管理
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        name = {"distributed.lock.enabled", "distributed.lock.metrics.enabled"},
        havingValue = "true",
        matchIfMissing = true
    )
    public MetricsConfiguration metricsConfiguration(DistributedLockProperties properties) {
        MetricsConfiguration config = new MetricsConfiguration();
        
        // 从Spring配置加载指标配置
        DistributedLockProperties.MetricsProperties metricsProps = properties.getMetrics();
        
        // 应用配置
        if (metricsProps != null) {
            MetricsConfiguration.MetricsConfig configBean = config.getConfig();
            configBean.setMetricsEnabled(metricsProps.isEnabled());
            configBean.setTracingEnabled(metricsProps.isTracingEnabled());
            configBean.setMicrometerEnabled(metricsProps.getMicrometer().isEnabled());
            configBean.setMicrometerApplicationName(metricsProps.getMicrometer().getApplicationName());
            configBean.setMicrometerInstanceId(metricsProps.getMicrometer().getInstanceId());
            configBean.setMicrometerDetailedTags(metricsProps.getMicrometer().isDetailedTags());
            configBean.setPrometheusEnabled(metricsProps.getPrometheus().isEnabled());
            configBean.setPrometheusAutoPush(metricsProps.getPrometheus().isAutoPush());
            configBean.setPrometheusPushGatewayUrl(metricsProps.getPrometheus().getPushGatewayUrl());
            configBean.setPrometheusPushGatewayJob(metricsProps.getPrometheus().getPushGatewayJob());
            configBean.setPrometheusPushInterval(metricsProps.getPrometheus().getPushInterval());
            configBean.setJmxEnabled(metricsProps.getJmx().isEnabled());
            configBean.setJmxDomainName(metricsProps.getJmx().getDomainName());
            configBean.setJmxRemoteAccess(metricsProps.getJmx().isRemoteAccess());
            configBean.setJmxRmiRegistryPort(metricsProps.getJmx().getRmiRegistryPort());
            configBean.setJmxRmiServerPort(metricsProps.getJmx().getRmiServerPort());
            configBean.setMonitoringEnabled(metricsProps.getMonitoring().isEnabled());
            configBean.setMonitoringInterval(metricsProps.getMonitoring().getInterval());
            configBean.setMonitoringThreadPoolSize(metricsProps.getMonitoring().getThreadPoolSize());
            configBean.setMonitoringDetailedMetrics(metricsProps.getMonitoring().isDetailedMetrics());
            configBean.setAlertingEnabled(metricsProps.getAlerting().isEnabled());
            configBean.setAlertingCheckInterval(metricsProps.getAlerting().getCheckInterval());
            configBean.setAlertingSuppressionDuration(metricsProps.getAlerting().getSuppressionDuration());
            configBean.setAlertingLogEnabled(metricsProps.getAlerting().isLogEnabled());
            configBean.setAlertingMicrometerEnabled(metricsProps.getAlerting().isMicrometerEnabled());
            configBean.setHealthCheckEnabled(metricsProps.getHealthCheck().isEnabled());
            configBean.setHealthCheckInterval(metricsProps.getHealthCheck().getInterval());
            configBean.setHealthCheckThreadPoolSize(metricsProps.getHealthCheck().getThreadPoolSize());
            configBean.setHealthCheckDetailed(metricsProps.getHealthCheck().isDetailed());
            
            if (metricsProps.getRetentionDuration() != null) {
                configBean.setMetricsRetentionDuration(metricsProps.getRetentionDuration());
            }
            if (metricsProps.getCollectionInterval() != null) {
                configBean.setMetricsCollectionInterval(metricsProps.getCollectionInterval());
            }
            if (metricsProps.getPrometheus().getHttpPort() != null) {
                configBean.setPrometheusHttpPort(metricsProps.getPrometheus().getHttpPort());
            }
        }
        
        return config;
    }

    private static final class ServiceLoaderLockProviderAdapter implements LockProvider {

        private final ServiceLoaderDistributedLockFactory delegate;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private ServiceLoaderLockProviderAdapter(ServiceLoaderDistributedLockFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getType() {
            return delegate.getActiveProviderType();
        }

        @Override
        public int getPriority() {
            return 0;
        }

        @Override
        public DistributedLock createLock(String key) {
            return delegate.getLock(key);
        }

        @Override
        public DistributedReadWriteLock createReadWriteLock(String key) {
            return delegate.getReadWriteLock(key);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                delegate.shutdown();
            }
        }
    }
}
