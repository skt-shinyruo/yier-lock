package com.mycorp.distributedlock.springboot.integration;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import com.mycorp.distributedlock.springboot.aop.DistributedLockAspect;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import com.mycorp.distributedlock.springboot.key.LockKeyResolver;
import com.mycorp.distributedlock.testkit.support.InMemoryBackendModule;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class DistributedLockAutoConfigurationIntegrationTest {

    private final ApplicationContextRunner emptyContextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, DistributedLockAutoConfiguration.class));

    private final ApplicationContextRunner contextRunner = emptyContextRunner
        .withUserConfiguration(TestBackendConfiguration.class);

    @Test
    void shouldRegisterLockRuntimeCoreBeansAndAspect() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=in-memory"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(LockRuntime.class);
                assertThat(context).hasSingleBean(LockClient.class);
                assertThat(context).hasSingleBean(SynchronousLockExecutor.class);
                assertThat(context).hasSingleBean(LockKeyResolver.class);
                assertThat(context).hasSingleBean(DistributedLockAspect.class);
                assertThat(context).doesNotHaveBean("lockExecutor");
                assertThat(context).doesNotHaveBean("lockManager");
            });
    }

    @Test
    void shouldFailWhenEnabledWithoutBackendProperty() {
        emptyContextRunner
            .withPropertyValues("distributed.lock.enabled=true")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasMessageContaining("backend id must be configured");
            });
    }

    @Test
    void shouldFailWhenEnabledWithBlankBackendPropertyAndNoSpringBackendBeans() {
        emptyContextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=   "
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasMessageContaining("backend id must be configured");
            });
    }

    @Test
    void shouldFailWhenConfiguredBackendModuleIsMissing() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasMessageContaining("Requested backend not found: redis");
            });
    }

    @Test
    void shouldIgnoreServiceLoaderBackendsWhenSpringHasNoBackendBeans() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, DistributedLockAutoConfiguration.class))
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=service-loader-only"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasMessageContaining("Requested backend not found: service-loader-only");
            });
    }

    @Test
    void shouldFailWhenResolvedBackendLacksRequiredCapabilities() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, DistributedLockAutoConfiguration.class))
            .withUserConfiguration(UnsafeBackendConfiguration.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=unsafe"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasMessageContaining("unsafe")
                    .hasMessageContaining("fencingSupported")
                    .hasMessageContaining("renewableSessionsSupported");
            });
    }

    @Test
    void shouldBackOffWhenDisabled() {
        contextRunner
            .withPropertyValues("distributed.lock.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(LockRuntime.class);
                assertThat(context).doesNotHaveBean(LockClient.class);
                assertThat(context).doesNotHaveBean(SynchronousLockExecutor.class);
                assertThat(context).doesNotHaveBean("lockExecutor");
                assertThat(context).doesNotHaveBean(DistributedLockAspect.class);
                assertThat(context).doesNotHaveBean("lockManager");
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestBackendConfiguration {

        @Bean
        BackendModule inMemoryBackendModule() {
            return new InMemoryBackendModule("in-memory");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UnsafeBackendConfiguration {

        @Bean
        BackendModule unsafeBackendModule() {
            return new BackendModule() {
                @Override
                public String id() {
                    return "unsafe";
                }

                @Override
                public BackendCapabilities capabilities() {
                    return new BackendCapabilities(true, true, false, false, false);
                }

                @Override
                public LockBackend createBackend() {
                    return new LockBackend() {
                        @Override
                        public BackendSession openSession() {
                            throw new UnsupportedOperationException("not used in test");
                        }
                    };
                }
            };
        }
    }
}
