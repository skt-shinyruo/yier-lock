package com.mycorp.distributedlock.observability.springboot.integration;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.observability.springboot.config.DistributedLockObservabilityAutoConfiguration;
import com.mycorp.distributedlock.runtime.DefaultLockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import com.mycorp.distributedlock.testkit.support.InMemoryBackendModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

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
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }

            assertThat(value).isEqualTo("ok");
            assertThat(registry.find("distributed.lock.acquire").timer()).isNotNull();
            assertThat(registry.find("distributed.lock.scope").timer()).isNotNull();
            assertThat(registry.find("distributed.lock.acquire").tags("outcome", "success").timer().count()).isEqualTo(1);
        });
    }

    @Test
    void shouldNotWrapCustomRuntimeImplementations() {
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
            .run(context -> assertThat(context.getBean(LockRuntime.class)).isInstanceOf(CustomRuntime.class));
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
    void shouldKeepMetricsLowCardinalityWhenLockKeyLoggingIsEnabled() {
        contextRunner
            .withPropertyValues("distributed.lock.observability.include-lock-key-in-logs=true")
            .run(context -> {
                SimpleMeterRegistry registry = context.getBean(SimpleMeterRegistry.class);
                LockRuntime runtime = context.getBean(LockRuntime.class);
                runtime.synchronousLockExecutor().withLock(
                    new LockRequest(new LockKey("secret-key"), LockMode.MUTEX, WaitPolicy.tryOnce()),
                    lease -> "ok"
                );

                assertThat(registry.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags()).noneMatch(tag -> tag.getValue().equals("secret-key"))
                );
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {
        @Bean
        BackendModule inMemoryBackendModule() {
            return new InMemoryBackendModule("in-memory");
        }

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
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
        public void close() {
        }
    }
}
