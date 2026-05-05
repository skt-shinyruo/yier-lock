package com.mycorp.distributedlock.observability.springboot.integration;

import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.api.BackendCostModel;
import com.mycorp.distributedlock.api.FairnessSemantics;
import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.FencingSemantics;
import com.mycorp.distributedlock.api.LeaseSemantics;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.OwnershipLossSemantics;
import com.mycorp.distributedlock.api.RuntimeInfo;
import com.mycorp.distributedlock.api.SessionSemantics;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.WaitSemantics;
import com.mycorp.distributedlock.observability.springboot.config.DistributedLockObservabilityAutoConfiguration;
import com.mycorp.distributedlock.runtime.DefaultLockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeDecorator;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import com.mycorp.distributedlock.springboot.config.LockRuntimeCustomizer;
import com.mycorp.distributedlock.spi.BackendClient;
import com.mycorp.distributedlock.spi.BackendConfiguration;
import com.mycorp.distributedlock.spi.BackendDescriptor;
import com.mycorp.distributedlock.spi.BackendLease;
import com.mycorp.distributedlock.spi.BackendProvider;
import com.mycorp.distributedlock.spi.BackendSession;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class DistributedLockObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            AopAutoConfiguration.class,
            DistributedLockAutoConfiguration.class,
            DistributedLockObservabilityAutoConfiguration.class
        ))
        .withUserConfiguration(TestConfiguration.class)
        .withPropertyValues(
            "distributed.lock.enabled=true",
            "distributed.lock.backend=in-memory",
            "distributed.lock.observability.enabled=true"
        );

    @Test
    void shouldRecordAcquireAndScopeMeters() {
        contextRunner.run(context -> {
            SynchronousLockExecutor executor = context.getBean(SynchronousLockExecutor.class);
            LockClient client = context.getBean(LockClient.class);
            SimpleMeterRegistry registry = context.getBean(SimpleMeterRegistry.class);
            String value;
            try {
                value = executor.withLock(
                    new LockRequest(
                        new LockKey("orders:42"),
                        LockMode.MUTEX,
                        WaitPolicy.timed(Duration.ofSeconds(1))
                    ),
                    lease -> "ok"
                );
                try (LockSession session = client.openSession();
                     LockLease lease = session.acquire(new LockRequest(
                         new LockKey("orders:43"),
                         LockMode.MUTEX,
                         WaitPolicy.timed(Duration.ofSeconds(1))
                     ))) {
                    assertThat(lease.isValid()).isTrue();
                }
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }

            assertThat(value).isEqualTo("ok");
            assertThat(registry.find("distributed.lock.acquire").timer()).isNotNull();
            assertThat(registry.find("distributed.lock.scope").timer()).isNotNull();
            assertThat(registry.find("distributed.lock.acquire").tags("outcome", "success").timer().count()).isEqualTo(1);
            assertThat(registry.find("distributed.lock.scope").tags("outcome", "success").timer().count()).isEqualTo(1);
        });
    }

    @Test
    void shouldDecorateStandardRuntimeWithCustomizerOnce() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LockRuntimeDecorator.class);

            LockRuntimeDecorator customizer = context.getBean(LockRuntimeDecorator.class);
            LockRuntime runtime = context.getBean(LockRuntime.class);

            assertThat(runtime).isNotInstanceOf(DefaultLockRuntime.class);
            assertThat(customizer.decorate(runtime)).isNotNull();
        });
    }

    @Test
    void shouldPreserveUserCustomizerExecutorBehaviorAfterObservabilityDecoration() {
        contextRunner
            .withUserConfiguration(CustomExecutorCustomizerConfiguration.class)
            .run(context -> {
                LockRuntime runtime = context.getBean(LockRuntime.class);

                String value = runtime.synchronousLockExecutor().withLock(
                    new LockRequest(new LockKey("orders:42"), LockMode.MUTEX, WaitPolicy.tryOnce()),
                    lease -> "delegate"
                );

                assertThat(value).isEqualTo("customized");
            });
    }

    @Test
    void shouldNotDecorateCustomRuntimeImplementationsAutomatically() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                DistributedLockAutoConfiguration.class,
                DistributedLockObservabilityAutoConfiguration.class
            ))
            .withUserConfiguration(CustomRuntimeConfiguration.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.observability.enabled=true"
            )
            .run(context -> {
                LockRuntime runtime = context.getBean(LockRuntime.class);
                assertThat(runtime).isInstanceOf(CustomRuntime.class);
                assertThat(runtime.info().backendId()).isEqualTo("custom");
            });
    }

    @Test
    void shouldNotDecorateRuntimeWhenAllSinksAreDisabled() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.observability.logging.enabled=false",
                "distributed.lock.observability.metrics.enabled=false"
            )
            .run(context -> assertThat(context.getBean(LockRuntime.class)).isInstanceOf(DefaultLockRuntime.class));
    }

    @Test
    void shouldLoadWithLoggingOnlyWhenMicrometerIsAbsent() {
        new ApplicationContextRunner()
            .withClassLoader(new FilteredClassLoader("io.micrometer"))
            .withConfiguration(AutoConfigurations.of(
                AopAutoConfiguration.class,
                DistributedLockAutoConfiguration.class,
                DistributedLockObservabilityAutoConfiguration.class
            ))
            .withUserConfiguration(LoggingOnlyTestConfiguration.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=in-memory",
                "distributed.lock.observability.enabled=true"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(LockRuntime.class)).isNotInstanceOf(DefaultLockRuntime.class);
            });
    }

    @Test
    void shouldKeepMetricsLowCardinalityWhenLockKeyLoggingIsEnabled() {
        contextRunner
            .withPropertyValues("distributed.lock.observability.include-lock-key-in-logs=true")
            .run(context -> {
                SimpleMeterRegistry registry = context.getBean(SimpleMeterRegistry.class);
                LockClient client = context.getBean(LockClient.class);
                try (LockSession session = client.openSession();
                     LockLease lease = session.acquire(new LockRequest(new LockKey("secret-key"), LockMode.MUTEX, WaitPolicy.tryOnce()))) {
                    assertThat(lease.isValid()).isTrue();
                }

                assertThat(registry.find("distributed.lock.acquire").tags("outcome", "success").timer().count()).isEqualTo(1);
                assertThat(registry.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags()).noneMatch(tag -> tag.getValue().equals("secret-key"))
                );
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {
        @Bean
        BackendProvider<TestBackendConfiguration> inMemoryBackendProvider() {
            return new TestBackendProvider("in-memory");
        }

        @Bean
        TestBackendConfiguration inMemoryBackendConfiguration() {
            return new TestBackendConfiguration();
        }

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LoggingOnlyTestConfiguration {
        @Bean
        BackendProvider<TestBackendConfiguration> inMemoryBackendProvider() {
            return new TestBackendProvider("in-memory");
        }

        @Bean
        TestBackendConfiguration inMemoryBackendConfiguration() {
            return new TestBackendConfiguration();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomExecutorCustomizerConfiguration {
        @Bean
        LockRuntimeCustomizer executorReplacingCustomizer() {
            return runtime -> new ExecutorReplacingRuntime(runtime);
        }
    }

    static class ExecutorReplacingRuntime implements LockRuntime {
        private final LockRuntime delegate;

        ExecutorReplacingRuntime(LockRuntime delegate) {
            this.delegate = delegate;
        }

        @Override
        public LockClient lockClient() {
            return delegate.lockClient();
        }

        @Override
        public SynchronousLockExecutor synchronousLockExecutor() {
            return new SynchronousLockExecutor() {
                @Override
                @SuppressWarnings("unchecked")
                public <T> T withLock(LockRequest request, com.mycorp.distributedlock.api.LockedAction<T> action) {
                    return (T) "customized";
                }
            };
        }

        @Override
        public RuntimeInfo info() {
            return delegate.info();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomRuntimeConfiguration {
        @Bean(destroyMethod = "close")
        LockRuntime customRuntime() {
            return new CustomRuntime();
        }
    }

    static class CustomRuntime implements LockRuntime {
        @Override
        public LockClient lockClient() {
            return new LockClient() {
                @Override
                public LockSession openSession() {
                    return new LockSession() {
                        @Override
                        public LockLease acquire(LockRequest request) {
                            return new LockLease() {
                                @Override
                                public LockKey key() {
                                    return request.key();
                                }

                                @Override
                                public LockMode mode() {
                                    return request.mode();
                                }

                                @Override
                                public FencingToken fencingToken() {
                                    return new FencingToken(1L);
                                }

                                @Override
                                public LeaseState state() {
                                    return LeaseState.ACTIVE;
                                }

                                @Override
                                public boolean isValid() {
                                    return true;
                                }

                                @Override
                                public void release() {
                                }
                            };
                        }

                        @Override
                        public SessionState state() {
                            return SessionState.ACTIVE;
                        }

                        @Override
                        public void close() {
                        }
                    };
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public SynchronousLockExecutor synchronousLockExecutor() {
            return new SynchronousLockExecutor() {
                @Override
                public <T> T withLock(LockRequest request, com.mycorp.distributedlock.api.LockedAction<T> action) throws Exception {
                    try (LockSession session = lockClient().openSession();
                         LockLease lease = session.acquire(request)) {
                        return action.execute(lease);
                    }
                }
            };
        }

        @Override
        public RuntimeInfo info() {
            return new RuntimeInfo("custom", "Custom", behavior(), "test");
        }

        @Override
        public void close() {
        }
    }

    record TestBackendConfiguration() implements BackendConfiguration {
    }

    static final class TestBackendProvider implements BackendProvider<TestBackendConfiguration> {
        private final String id;

        private TestBackendProvider(String id) {
            this.id = id;
        }

        @Override
        public BackendDescriptor<TestBackendConfiguration> descriptor() {
            return new BackendDescriptor<>(id, id, TestBackendConfiguration.class, behavior());
        }

        @Override
        public BackendClient createBackendClient(TestBackendConfiguration configuration) {
            return new TestBackendClient();
        }
    }

    static final class TestBackendClient implements BackendClient {
        private final AtomicLong fencingCounter = new AtomicLong();

        @Override
        public BackendSession openSession() {
            return new TestBackendSession(fencingCounter);
        }

        @Override
        public void close() {
        }
    }

    static final class TestBackendSession implements BackendSession {
        private final AtomicLong fencingCounter;

        private TestBackendSession(AtomicLong fencingCounter) {
            this.fencingCounter = fencingCounter;
        }

        @Override
        public BackendLease acquire(LockRequest request) {
            return new TestBackendLease(request.key(), request.mode(), new FencingToken(fencingCounter.incrementAndGet()));
        }

        @Override
        public SessionState state() {
            return SessionState.ACTIVE;
        }

        @Override
        public void close() {
        }
    }

    record TestBackendLease(LockKey key, LockMode mode, FencingToken fencingToken) implements BackendLease {
        @Override
        public LeaseState state() {
            return LeaseState.ACTIVE;
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public void release() {
        }
    }

    private static BackendBehavior behavior() {
        return BackendBehavior.builder()
            .lockModes(Set.of(LockMode.MUTEX, LockMode.READ, LockMode.WRITE))
            .fencing(FencingSemantics.MONOTONIC_PER_KEY)
            .leaseSemantics(Set.of(LeaseSemantics.FIXED_TTL))
            .session(SessionSemantics.CLIENT_LOCAL_TTL)
            .wait(WaitSemantics.POLLING)
            .fairness(FairnessSemantics.NONE)
            .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
            .costModel(BackendCostModel.CHEAP_SESSION)
            .build();
    }
}
