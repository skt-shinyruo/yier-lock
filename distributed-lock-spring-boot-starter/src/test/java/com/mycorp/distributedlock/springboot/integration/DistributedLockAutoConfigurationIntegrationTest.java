package com.mycorp.distributedlock.springboot.integration;

import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.api.LockConfigurationBuilder;
import com.mycorp.distributedlock.api.LockProvider;
import com.mycorp.distributedlock.core.observability.MetricsConfiguration;
import com.mycorp.distributedlock.springboot.SpringDistributedLockFactory;
import com.mycorp.distributedlock.springboot.actuator.DistributedLockHealthIndicator;
import com.mycorp.distributedlock.springboot.aop.DistributedLockAspect;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import com.mycorp.distributedlock.springboot.config.DistributedLockProperties;
import com.mycorp.distributedlock.springboot.health.DistributedLockHealthChecker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Distributed lock starter auto-configuration integration")
class DistributedLockAutoConfigurationIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(DistributedLockAutoConfiguration.class)
        .withBean(LockProvider.class, StubLockProvider::new);

    @Test
    @DisplayName("creates the factory, aspect, and health beans with the real constructors")
    void shouldCreateCoreStarterBeansWithRealConstructors() {
        contextRunner
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("distributed.lock.metrics.enabled=false")
            .run(context -> {
                assertThat(context).hasSingleBean(DistributedLockFactory.class);
                assertThat(context).hasSingleBean(DistributedLockProperties.class);
                assertThat(context).hasSingleBean(DistributedLockAspect.class);
                assertThat(context).hasSingleBean(DistributedLockHealthChecker.class);
                assertThat(context).hasSingleBean(DistributedLockHealthIndicator.class);

                DistributedLockFactory lockFactory = context.getBean(DistributedLockFactory.class);
                assertThat(lockFactory).isInstanceOf(SpringDistributedLockFactory.class);

                SpringDistributedLockFactory springFactory = (SpringDistributedLockFactory) lockFactory;
                assertThat(springFactory.getDelegate()).isSameAs(context.getBean(LockProvider.class));
                assertThat(springFactory.getMeterRegistry()).contains(context.getBean(MeterRegistry.class));
                assertThat(springFactory.getOpenTelemetry()).isEmpty();

                DistributedLockHealthIndicator healthIndicator = context.getBean(DistributedLockHealthIndicator.class);
                assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.UP);
            });
    }

    @Test
    @DisplayName("uses the actual nested properties structure when building metrics configuration")
    void shouldMapNestedMetricsPropertiesIntoMetricsConfiguration() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.metrics.enabled=true",
                "distributed.lock.metrics.tracing-enabled=false",
                "distributed.lock.metrics.collection-interval=PT45S",
                "distributed.lock.metrics.micrometer.enabled=false",
                "distributed.lock.metrics.micrometer.application-name=test-app",
                "distributed.lock.metrics.micrometer.instance-id=node-a",
                "distributed.lock.metrics.prometheus.enabled=true",
                "distributed.lock.metrics.prometheus.http-port=9095",
                "distributed.lock.metrics.jmx.enabled=false",
                "distributed.lock.metrics.monitoring.enabled=false",
                "distributed.lock.metrics.monitoring.interval=PT2M",
                "distributed.lock.metrics.monitoring.thread-pool-size=6",
                "distributed.lock.metrics.alerting.enabled=false",
                "distributed.lock.metrics.alerting.check-interval=PT20S",
                "distributed.lock.metrics.alerting.log-enabled=false",
                "distributed.lock.metrics.health-check.enabled=true",
                "distributed.lock.metrics.health-check.interval=PT3M",
                "distributed.lock.metrics.health-check.thread-pool-size=4",
                "distributed.lock.metrics.health-check.detailed=false"
            )
            .run(context -> {
                MetricsConfiguration.MetricsConfig metricsConfig = context.getBean(MetricsConfiguration.class).getConfig();

                assertThat(metricsConfig.isMetricsEnabled()).isTrue();
                assertThat(metricsConfig.isTracingEnabled()).isFalse();
                assertThat(metricsConfig.getMetricsCollectionInterval()).hasSeconds(45);
                assertThat(metricsConfig.isMicrometerEnabled()).isFalse();
                assertThat(metricsConfig.getMicrometerApplicationName()).isEqualTo("test-app");
                assertThat(metricsConfig.getMicrometerInstanceId()).isEqualTo("node-a");
                assertThat(metricsConfig.isPrometheusEnabled()).isTrue();
                assertThat(metricsConfig.getPrometheusHttpPort()).isEqualTo(9095);
                assertThat(metricsConfig.isJmxEnabled()).isFalse();
                assertThat(metricsConfig.isMonitoringEnabled()).isFalse();
                assertThat(metricsConfig.getMonitoringInterval()).hasMinutes(2);
                assertThat(metricsConfig.getMonitoringThreadPoolSize()).isEqualTo(6);
                assertThat(metricsConfig.isAlertingEnabled()).isFalse();
                assertThat(metricsConfig.getAlertingCheckInterval()).hasSeconds(20);
                assertThat(metricsConfig.isAlertingLogEnabled()).isFalse();
                assertThat(metricsConfig.isHealthCheckEnabled()).isTrue();
                assertThat(metricsConfig.getHealthCheckInterval()).hasMinutes(3);
                assertThat(metricsConfig.getHealthCheckThreadPoolSize()).isEqualTo(4);
                assertThat(metricsConfig.isHealthCheckDetailed()).isFalse();
            });
    }

    @Test
    @DisplayName("backs off the aspect bean when the nested aspect property is disabled")
    void shouldNotCreateAspectBeanWhenAspectDisabled() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.metrics.enabled=false",
                "distributed.lock.aspect.enabled=false"
            )
            .run(context -> assertThat(context).doesNotHaveBean(DistributedLockAspect.class));
    }

    @Test
    @DisplayName("backs off the starter beans when distributed locking is disabled")
    void shouldNotCreateStarterBeansWhenDisabled() {
        contextRunner
            .withPropertyValues("distributed.lock.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(DistributedLockFactory.class);
                assertThat(context).doesNotHaveBean(DistributedLockAspect.class);
                assertThat(context).doesNotHaveBean(DistributedLockHealthChecker.class);
                assertThat(context).doesNotHaveBean(DistributedLockHealthIndicator.class);
            });
    }

    @Test
    @DisplayName("marks health down when the delegate backend probe is unhealthy")
    void shouldReportDownWhenDelegateHealthProbeFails() {
        new ApplicationContextRunner()
            .withUserConfiguration(DistributedLockAutoConfiguration.class)
            .withBean(LockProvider.class, FailingHealthLockProvider::new)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("distributed.lock.metrics.enabled=false")
            .run(context -> {
                DistributedLockHealthIndicator healthIndicator = context.getBean(DistributedLockHealthIndicator.class);

                assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.DOWN);
            });
    }

    @Test
    @DisplayName("fails fast instead of silently downgrading fair lock requests")
    void shouldRejectFairConfiguredLocksWhenFactoryCannotHonorThem() {
        contextRunner
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("distributed.lock.metrics.enabled=false")
            .run(context -> {
                SpringDistributedLockFactory lockFactory =
                    (SpringDistributedLockFactory) context.getBean(DistributedLockFactory.class);

                assertThatThrownBy(() -> lockFactory.getConfiguredLock("fair-lock", fairConfiguration("fair-lock")))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("Fair locks");
            });
    }

    private static final class StubLockProvider implements LockProvider {

        @Override
        public String getType() {
            return "test";
        }

        @Override
        public com.mycorp.distributedlock.api.DistributedLock createLock(String key) {
            com.mycorp.distributedlock.api.DistributedLock lock = mock(com.mycorp.distributedlock.api.DistributedLock.class);
            when(lock.getName()).thenReturn(key);
            try {
                when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            when(lock.isHeldByCurrentThread()).thenReturn(false);
            when(lock.healthCheck()).thenReturn(new com.mycorp.distributedlock.api.DistributedLock.HealthCheckResult() {
                @Override
                public boolean isHealthy() {
                    return true;
                }

                @Override
                public String getDetails() {
                    return "stub healthy";
                }

                @Override
                public long getCheckTime() {
                    return System.currentTimeMillis();
                }
            });
            return lock;
        }

        @Override
        public DistributedReadWriteLock createReadWriteLock(String key) {
            return mock(DistributedReadWriteLock.class);
        }
    }

    private static final class FailingHealthLockProvider implements LockProvider {

        @Override
        public String getType() {
            return "failing-health";
        }

        @Override
        public com.mycorp.distributedlock.api.DistributedLock createLock(String key) {
            com.mycorp.distributedlock.api.DistributedLock lock = mock(com.mycorp.distributedlock.api.DistributedLock.class);
            when(lock.getName()).thenReturn(key);
            when(lock.healthCheck()).thenReturn(new com.mycorp.distributedlock.api.DistributedLock.HealthCheckResult() {
                @Override
                public boolean isHealthy() {
                    return false;
                }

                @Override
                public String getDetails() {
                    return "backend unreachable";
                }

                @Override
                public long getCheckTime() {
                    return System.currentTimeMillis();
                }
            });
            return lock;
        }

        @Override
        public DistributedReadWriteLock createReadWriteLock(String key) {
            return mock(DistributedReadWriteLock.class);
        }
    }

    private static LockConfigurationBuilder.LockConfiguration fairConfiguration(String name) {
        return new LockConfigurationBuilder.LockConfiguration() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public LockConfigurationBuilder.LockType getLockType() {
                return LockConfigurationBuilder.LockType.FAIR;
            }

            @Override
            public long getLeaseTime(TimeUnit timeUnit) {
                return timeUnit.convert(5, TimeUnit.SECONDS);
            }

            @Override
            public long getWaitTime(TimeUnit timeUnit) {
                return timeUnit.convert(5, TimeUnit.SECONDS);
            }

            @Override
            public boolean isFairLock() {
                return true;
            }

            @Override
            public boolean isRetryEnabled() {
                return false;
            }

            @Override
            public int getRetryCount() {
                return 0;
            }

            @Override
            public long getRetryInterval(TimeUnit timeUnit) {
                return 0;
            }

            @Override
            public boolean isAutoRenew() {
                return false;
            }

            @Override
            public long getRenewInterval(TimeUnit timeUnit) {
                return 0;
            }

            @Override
            public double getRenewRatio() {
                return 0;
            }

            @Override
            public LockConfigurationBuilder.TimeoutStrategy getTimeoutStrategy() {
                return LockConfigurationBuilder.TimeoutStrategy.BLOCK_UNTIL_TIMEOUT;
            }

            @Override
            public boolean isDeadlockDetectionEnabled() {
                return false;
            }

            @Override
            public long getDeadlockDetectionTimeout(TimeUnit timeUnit) {
                return 0;
            }

            @Override
            public boolean isEventListeningEnabled() {
                return false;
            }

            @Override
            public java.util.Map<String, String> getProperties() {
                return java.util.Map.of();
            }
        };
    }
}
