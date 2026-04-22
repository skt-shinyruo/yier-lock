package com.mycorp.distributedlock.observability.springboot.integration;

import com.mycorp.distributedlock.api.LockExecutor;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.observability.springboot.config.DistributedLockObservabilityAutoConfiguration;
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
            LockExecutor executor = context.getBean(LockExecutor.class);
            SimpleMeterRegistry registry = context.getBean(SimpleMeterRegistry.class);
            String value;
            try {
                value = executor.withLock(
                    new LockRequest(
                        new LockKey("orders:42"),
                        LockMode.MUTEX,
                        WaitPolicy.timed(Duration.ofSeconds(1))
                    ),
                    () -> "ok"
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
        public LockExecutor lockExecutor() {
            return new LockExecutor() {
                @Override
                public <T> T withLock(LockRequest request, com.mycorp.distributedlock.api.LockedSupplier<T> action) throws Exception {
                    return action.get();
                }
            };
        }

        @Override
        public void close() {
        }
    }
}
