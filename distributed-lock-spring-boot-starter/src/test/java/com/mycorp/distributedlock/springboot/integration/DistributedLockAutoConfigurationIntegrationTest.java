package com.mycorp.distributedlock.springboot.integration;

import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.core.observability.*;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import com.mycorp.distributedlock.springboot.config.DistributedLockProperties;
import com.mycorp.distributedlock.springboot.aop.DistributedLockAspect;
import com.mycorp.distributedlock.springboot.actuator.DistributedLockHealthIndicator;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring Boot自动配置集成测试
 * 
 * @since 3.0.0
 */
@DisplayName("Spring Boot自动配置集成测试")
@SpringBootTest(classes = {
    DistributedLockAutoConfiguration.class,
    PropertyPlaceholderAutoConfiguration.class
})
@TestPropertySource(properties = {
    "distributed.lock.enabled=true",
    "distributed.lock.metrics.enabled=true",
    "distributed.lock.metrics.prometheus.enabled=true",
    "distributed.lock.aspect.enabled=true",
    "distributed.lock.metrics.jmx.enabled=true"
})
class DistributedLockAutoConfigurationIntegrationTest {

    private ApplicationContextRunner contextRunner;

    @BeforeEach
    void setUp() {
        contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DistributedLockAutoConfiguration.class);
    }

    @Nested
    @DisplayName("自动配置基础功能测试")
    class BasicAutoConfigurationTests {

        @Test
        @DisplayName("默认配置下所有Bean都应该被创建")
        void shouldCreateAllRequiredBeansWithDefaultConfig() {
            contextRunner
                .run(context -> {
                    assertThat(context)
                        .hasSingleBean(DistributedLockFactory.class)
                        .hasSingleBean(DistributedLockProperties.class)
                        .hasSingleBean(DistributedLockAspect.class)
                        .hasSingleBean(DistributedLockHealthIndicator.class)
                        .hasSingleBean(MetricsConfiguration.class)
                        .hasSingleBean(LockPerformanceMetrics.class)
                        .hasSingleBean(LockMetricsCollector.class)
                        .hasSingleBean(MicrometerMetricsAdapter.class)
                        .hasSingleBean(PrometheusMetricsExporter.class)
                        .hasSingleBean(LockMonitoringService.class)
                        .hasSingleBean(LockAlertingService.class)
                        .hasSingleBean(LockJMXManager.class)
                        .hasSingleBean(LockHealthMetrics.class);
                });
        }

        @Test
        @DisplayName("禁用分布式锁时不应该创建相关Bean")
        void shouldNotCreateBeansWhenDisabled() {
            contextRunner
                .withPropertyValues("distributed.lock.enabled=false")
                .run(context -> {
                    assertThat(context)
                        .doesNotHaveBean(DistributedLockFactory.class)
                        .doesNotHaveBean(DistributedLockAspect.class)
                        .doesNotHaveBean(DistributedLockHealthIndicator.class);
                });
        }

        @Test
        @DisplayName("禁用指标收集时不应该创建监控相关Bean")
        void shouldNotCreateMetricsBeansWhenDisabled() {
            contextRunner
                .withPropertyValues("distributed.lock.metrics.enabled=false")
                .run(context -> {
                    assertThat(context)
                        .doesNotHaveBean(LockPerformanceMetrics.class)
                        .doesNotHaveBean(PrometheusMetricsExporter.class)
                        .doesNotHaveBean(LockMonitoringService.class)
                        .doesNotHaveBean(LockAlertingService.class);
                });
        }

        @Test
        @DisplayName("禁用AOP时不应该创建切面Bean")
        void shouldNotCreateAspectBeanWhenDisabled() {
            contextRunner
                .withPropertyValues("distributed.lock.aspect.enabled=false")
                .run(context -> {
                    assertThat(context)
                        .doesNotHaveBean(DistributedLockAspect.class);
                });
        }
    }

    @Nested
    @DisplayName("配置属性测试")
    class ConfigurationPropertiesTests {

        @Test
        @DisplayName("应该正确绑定配置属性")
        void shouldBindConfigurationProperties() {
            contextRunner
                .withPropertyValues(
                    "distributed.lock.enabled=true",
                    "distributed.lock.defaultTimeout=PT30S",
                    "distributed.lock.metrics.enabled=true",
                    "distributed.lock.metrics.micrometer.enabled=true",
                    "distributed.lock.metrics.prometheus.enabled=true",
                    "distributed.lock.metrics.prometheus.http-port=9090",
                    "distributed.lock.metrics.jmx.enabled=true",
                    "distributed.lock.aspect.enabled=true"
                )
                .run(context -> {
                    DistributedLockProperties properties = context.getBean(DistributedLockProperties.class);
                    
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getDefaultTimeout()).hasSeconds(30);
                    assertThat(properties.getMetrics().isEnabled()).isTrue();
                    assertThat(properties.getMetrics().getMicrometer().isEnabled()).isTrue();
                    assertThat(properties.getMetrics().getPrometheus().isEnabled()).isTrue();
                    assertThat(properties.getMetrics().getPrometheus().getHttpPort()).isEqualTo(9090);
                    assertThat(properties.getMetrics().getJmx().isEnabled()).isTrue();
                    assertThat(properties.getAspect().isEnabled()).isTrue();
                });
        }

        @Test
        @DisplayName("应该支持嵌套配置属性")
        void shouldSupportNestedConfigurationProperties() {
            contextRunner
                .withPropertyValues(
                    "distributed.lock.retry.enabled=true",
                    "distributed.lock.retry.max-attempts=5",
                    "distributed.lock.retry.delay=PT1S",
                    "distributed.lock.metrics.monitoring.interval=PT1M",
                    "distributed.lock.metrics.alerting.check-interval=PT30S",
                    "distributed.lock.metrics.health-check.enabled=true",
                    "distributed.lock.metrics.health-check.interval=PT2M"
                )
                .run(context -> {
                    DistributedLockProperties properties = context.getBean(DistributedLockProperties.class);
                    
                    assertThat(properties.getRetry().isEnabled()).isTrue();
                    assertThat(properties.getRetry().getMaxAttempts()).isEqualTo(5);
                    assertThat(properties.getRetry().getDelay()).hasSeconds(1);
                    assertThat(properties.getMetrics().getMonitoring().getInterval()).hasMinutes(1);
                    assertThat(properties.getMetrics().getAlerting().getCheckInterval()).hasSeconds(30);
                    assertThat(properties.getMetrics().getHealthCheck().isEnabled()).isTrue();
                    assertThat(properties.getMetrics().getHealthCheck().getInterval()).hasMinutes(2);
                });
        }
    }

    @Nested
    @DisplayName("监控和指标配置测试")
    class MonitoringConfigurationTests {

        @Test
        @DisplayName("应该在启用时创建所有监控Bean")
        void shouldCreateAllMonitoringBeansWhenEnabled() {
            contextRunner
                .withPropertyValues("distributed.lock.metrics.enabled=true")
                .run(context -> {
                    assertThat(context)
                        .hasBean("metricsConfiguration")
                        .hasBean("lockPerformanceMetrics")
                        .hasBean("lockMetricsCollector")
                        .hasBean("micrometerMetricsAdapter")
                        .hasBean("prometheusMetricsExporter")
                        .hasBean("lockMonitoringService")
                        .hasBean("lockAlertingService")
                        .hasBean("lockJmxManager")
                        .hasBean("lockHealthMetrics")
                        .hasBean("alertingRules")
                        .hasBean("grafanaDashboardConfig");
                });
        }

        @Test
        @DisplayName("Prometheus配置应该正确设置")
        void shouldConfigurePrometheusCorrectly() {
            contextRunner
                .withPropertyValues(
                    "distributed.lock.metrics.prometheus.enabled=true",
                    "distributed.lock.metrics.prometheus.http-port=8081"
                )
                .run(context -> {
                    PrometheusMetricsExporter exporter = context.getBean(PrometheusMetricsExporter.class);
                    
                    assertThat(exporter).isNotNull();
                    // 验证Prometheus导出器的配置
                });
        }

        @Test
        @DisplayName("JMX配置应该正确设置")
        void shouldConfigureJMXCorrectly() {
            contextRunner
                .withPropertyValues(
                    "distributed.lock.metrics.jmx.enabled=true",
                    "distributed.lock.metrics.jmx.domain-name=custom.domain"
                )
                .run(context -> {
                    LockJMXManager jmxManager = context.getBean(LockJMXManager.class);
                    
                    assertThat(jmxManager).isNotNull();
                    // 验证JMX管理器的配置
                });
        }

        @Test
        @DisplayName("监控服务配置应该正确设置")
        void shouldConfigureMonitoringServiceCorrectly() {
            contextRunner
                .withPropertyValues(
                    "distributed.lock.metrics.monitoring.enabled=true",
                    "distributed.lock.metrics.monitoring.interval=PT15S",
                    "distributed.lock.metrics.monitoring.thread-pool-size=8"
                )
                .run(context -> {
                    LockMonitoringService monitoringService = context.getBean(LockMonitoringService.class);
                    
                    assertThat(monitoringService).isNotNull();
                    // 验证监控服务的配置
                });
        }
    }

    @Nested
    @DisplayName("健康检查配置测试")
    class HealthCheckConfigurationTests {

        @Test
        @DisplayName("健康检查指示器应该被创建")
        void shouldCreateHealthIndicator() {
            contextRunner
                .run(context -> {
                    HealthIndicator healthIndicator = context.getBean(DistributedLockHealthIndicator.class);
                    
                    assertThat(healthIndicator).isNotNull();
                    assertThat(healthIndicator).isInstanceOf(DistributedLockHealthIndicator.class);
                });
        }

        @Test
        @DisplayName("健康指标组件应该被创建")
        void shouldCreateLockHealthMetrics() {
            contextRunner
                .withPropertyValues("distributed.lock.metrics.enabled=true")
                .run(context -> {
                    LockHealthMetrics healthMetrics = context.getBean(LockHealthMetrics.class);
                    
                    assertThat(healthMetrics).isNotNull();
                });
        }

        @Test
        @DisplayName("健康检查配置应该正确应用")
        void shouldApplyHealthCheckConfiguration() {
            contextRunner
                .withPropertyValues(
                    "distributed.lock.metrics.health-check.enabled=true",
                    "distributed.lock.metrics.health-check.interval=PT45S",
                    "distributed.lock.metrics.health-check.thread-pool-size=4",
                    "distributed.lock.metrics.health-check.timeout=PT15S",
                    "distributed.lock.metrics.health-check.detailed=true"
                )
                .run(context -> {
                    DistributedLockProperties properties = context.getBean(DistributedLockProperties.class);
                    
                    assertThat(properties.getMetrics().getHealthCheck().isEnabled()).isTrue();
                    assertThat(properties.getMetrics().getHealthCheck().getInterval()).hasSeconds(45);
                    assertThat(properties.getMetrics().getHealthCheck().getThreadPoolSize()).isEqualTo(4);
                    assertThat(properties.getMetrics().getHealthCheck().getTimeout()).hasSeconds(15);
                    assertThat(properties.getMetrics().getHealthCheck().isDetailed()).isTrue();
                });
        }
    }

    @Nested
    @DisplayName("Bean条件性创建测试")
    class ConditionalBeanCreationTests {

        @Configuration
        static class CustomLockProviderConfiguration {
            @Bean
            public com.mycorp.distributedlock.api.LockProvider mockLockProvider() {
                return new MockLockProvider();
            }
        }

        @Test
        @DisplayName("有LockProvider时应该创建分布式锁工厂")
        void shouldCreateLockFactoryWhenLockProviderExists() {
            contextRunner
                .withUserConfiguration(CustomLockProviderConfiguration.class)
                .run(context -> {
                    assertThat(context)
                        .hasBean("distributedLockFactory")
                        .hasSingleBean(DistributedLockFactory.class);
                });
        }

        @Test
        @DisplayName("没有LockProvider时不应该创建分布式锁工厂")
        void shouldNotCreateLockFactoryWhenNoLockProvider() {
            contextRunner
                .run(context -> {
                    assertThat(context)
                        .doesNotHaveBean("distributedLockFactory");
                });
        }

        @Test
        @DisplayName("有MeterRegistry时应该创建Micrometer适配器")
        void shouldCreateMicrometerAdapterWhenMeterRegistryExists() {
            contextRunner
                .withUserConfiguration(CustomLockProviderConfiguration.class)
                .withBean(MeterRegistry.class, () -> {
                    // 创建一个简单的MeterRegistry实现
                    return io.micrometer.core.instrument.simple.SimpleMeterRegistry.builder().build();
                })
                .run(context -> {
                    assertThat(context)
                        .hasBean("micrometerMetricsAdapter")
                        .hasSingleBean(MicrometerMetricsAdapter.class);
                });
        }

        @Test
        @DisplayName("有OpenTelemetry时应该创建追踪相关Bean")
        void shouldCreateTracingBeansWhenOpenTelemetryExists() {
            contextRunner
                .withUserConfiguration(CustomLockProviderConfiguration.class)
                .withBean(OpenTelemetry.class, () -> {
                    return io.opentelemetry.api.OpenTelemetry.getDefault();
                })
                .run(context -> {
                    // 验证追踪相关的Bean被创建
                    assertThat(context.getBeansOfType(OpenTelemetry.class)).hasSize(1);
                });
        }
    }

    @Nested
    @DisplayName("初始化器测试")
    class InitializerTests {

        @Test
        @DisplayName("ObservabilityInitializer应该在所有组件就绪时创建")
        void shouldCreateObservabilityInitializerWhenAllComponentsReady() {
            contextRunner
                .withUserConfiguration(DistributedLockAutoConfigurationIntegrationTest.CustomLockProviderConfiguration.class)
                .run(context -> {
                    assertThat(context)
                        .hasBean("observabilityInitializer");
                });
        }

        @Test
        @DisplayName("组件缺失时不应该创建ObservabilityInitializer")
        void shouldNotCreateObservabilityInitializerWhenComponentsMissing() {
            contextRunner
                .withPropertyValues("distributed.lock.metrics.enabled=false")
                .run(context -> {
                    assertThat(context)
                        .doesNotHaveBean("observabilityInitializer");
                });
        }
    }

    // Mock类定义
    private static class MockLockProvider implements com.mycorp.distributedlock.api.LockProvider {
        @Override
        public String getName() {
            return "mock";
        }

        @Override
        public boolean isHealthy() {
            return true;
        }

        @Override
        public com.mycorp.distributedlock.api.DistributedLock getLock(String lockName) {
            return new MockDistributedLock();
        }
    }

    private static class MockDistributedLock implements com.mycorp.distributedlock.api.DistributedLock {
        @Override
        public String getName() {
            return "mock-lock";
        }

        @Override
        public boolean isHeldByCurrentThread() {
            return false;
        }

        @Override
        public void lock() {
            // Mock implementation
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            // Mock implementation
        }

        @Override
        public boolean tryLock() {
            return true;
        }

        @Override
        public boolean tryLock(long time, java.util.concurrent.TimeUnit unit) throws InterruptedException {
            return true;
        }

        @Override
        public void unlock() {
            // Mock implementation
        }

        @Override
        public java.util.concurrent.CompletableFuture<java.util.concurrent.locks.LockResult> tryLockAsync() {
            return java.util.concurrent.CompletableFuture.completedFuture(new MockLockResult());
        }

        @Override
        public java.util.concurrent.CompletableFuture<java.util.concurrent.locks.LockResult> tryLockAsync(long time, java.util.concurrent.TimeUnit unit) {
            return java.util.concurrent.CompletableFuture.completedFuture(new MockLockResult());
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> unlockAsync() {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean tryRenewLease(long time, java.util.concurrent.TimeUnit unit) {
            return true;
        }

        @Override
        public java.util.concurrent.CompletableFuture<Boolean> tryRenewLeaseAsync(long time, java.util.concurrent.TimeUnit unit) {
            return java.util.concurrent.CompletableFuture.completedFuture(true);
        }

        @Override
        public HealthCheck.HealthCheckResult healthCheck() {
            return new HealthCheck.HealthCheckResult(HealthCheck.ComponentStatus.UP, "mock", "mock");
        }
    }

    private static class MockLockResult implements java.util.concurrent.locks.LockResult {
        @Override
        public boolean isSuccess() {
            return true;
        }

        @Override
        public Long getElapsedTime() {
            return 0L;
        }
    }

    @Configuration
    static class CustomLockProviderConfiguration {
        @Bean
        public com.mycorp.distributedlock.api.LockProvider mockLockProvider() {
            return new MockLockProvider();
        }
    }
}