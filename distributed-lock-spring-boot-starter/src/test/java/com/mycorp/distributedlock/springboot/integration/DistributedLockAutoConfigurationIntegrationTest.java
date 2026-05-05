package com.mycorp.distributedlock.springboot.integration;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.api.RuntimeInfo;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.runtime.LockRuntimeDecorator;
import com.mycorp.distributedlock.spi.BackendProvider;
import com.mycorp.distributedlock.springboot.aop.DistributedLockAspect;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import com.mycorp.distributedlock.springboot.config.LockRuntimeCustomizer;
import com.mycorp.distributedlock.springboot.key.LockKeyResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.List;

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
                assertThat(context.getBean(LockRuntime.class).info().backendId()).isEqualTo("in-memory");
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
                    .hasMessageContaining("A backend id must be configured before building the lock runtime");
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
                    .hasMessageContaining("A backend id must be configured before building the lock runtime");
            });
    }

    @Test
    void shouldFailWhenConfiguredBackendProviderIsMissing() {
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
    void shouldFailWhenUserBackendProvidersHaveDuplicateIds() {
        emptyContextRunner
            .withUserConfiguration(DuplicateBackendProviderConfiguration.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasMessageContaining("Duplicate backend providers registered for id: redis");
            });
    }

    @Test
    void shouldFailWhenSelectedProviderHasNoMatchingConfigurationBean() {
        emptyContextRunner
            .withUserConfiguration(MissingConfigurationBackendProviderConfiguration.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=in-memory"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasMessageContaining("Missing backend configuration for backend in-memory");
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

    @Test
    void shouldBackOffForUserSuppliedLockRuntimeWithoutBackendProperty() {
        emptyContextRunner
            .withUserConfiguration(UserLockRuntimeConfiguration.class)
            .withPropertyValues("distributed.lock.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(LockRuntime.class);
                assertThat(context.getBean(LockRuntime.class)).isInstanceOf(StubLockRuntime.class);
            });
    }

    @Test
    void shouldApplyDecoratorsAndCustomizerAdaptersToDefaultRuntimeInOrder() {
        contextRunner
            .withUserConfiguration(DecoratorConfiguration.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=in-memory"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(LockRuntime.class);
                assertThat(context.getBean(LockRuntime.class).info().backendId()).isEqualTo("customized");
                assertThat(context.getBean(DecoratorEvents.class).events()).containsExactly("decorator", "customizer");
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestBackendConfiguration {

        @Bean
        BackendProvider<TestBackends.Configuration> inMemoryBackendProvider() {
            return new TestBackends.Provider("in-memory");
        }

        @Bean
        TestBackends.Configuration inMemoryBackendConfiguration() {
            return new TestBackends.Configuration();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MissingConfigurationBackendProviderConfiguration {

        @Bean
        BackendProvider<TestBackends.Configuration> inMemoryBackendProvider() {
            return new TestBackends.Provider("in-memory");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserLockRuntimeConfiguration {

        @Bean
        LockRuntime userLockRuntime() {
            return new StubLockRuntime("test");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DuplicateBackendProviderConfiguration {

        @Bean
        BackendProvider<TestBackends.Configuration> firstRedisBackendProvider() {
            return new TestBackends.Provider("redis");
        }

        @Bean
        BackendProvider<TestBackends.Configuration> secondRedisBackendProvider() {
            return new TestBackends.Provider("redis");
        }

        @Bean
        TestBackends.Configuration redisBackendConfiguration() {
            return new TestBackends.Configuration();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DecoratorConfiguration {

        @Bean
        DecoratorEvents decoratorEvents() {
            return new DecoratorEvents();
        }

        @Bean
        @Order(1)
        LockRuntimeDecorator metadataDecorator(DecoratorEvents events) {
            return runtime -> {
                events.add("decorator");
                return new MetadataOverrideLockRuntime(runtime, "decorated");
            };
        }

        @Bean
        @Order(2)
        LockRuntimeCustomizer backendIdCustomizer(DecoratorEvents events) {
            return runtime -> {
                events.add("customizer");
                return new MetadataOverrideLockRuntime(runtime, "customized");
            };
        }
    }

    private static final class MetadataOverrideLockRuntime implements LockRuntime {
        private final LockRuntime delegate;
        private final String backendId;

        private MetadataOverrideLockRuntime(LockRuntime delegate, String backendId) {
            this.delegate = delegate;
            this.backendId = backendId;
        }

        @Override
        public LockClient lockClient() {
            return delegate.lockClient();
        }

        @Override
        public SynchronousLockExecutor synchronousLockExecutor() {
            return delegate.synchronousLockExecutor();
        }

        @Override
        public RuntimeInfo info() {
            RuntimeInfo info = delegate.info();
            return new RuntimeInfo(backendId, info.backendDisplayName(), info.behavior(), info.runtimeVersion());
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class StubLockRuntime implements LockRuntime {
        private final String backendId;

        private StubLockRuntime(String backendId) {
            this.backendId = backendId;
        }

        @Override
        public LockClient lockClient() {
            return null;
        }

        @Override
        public SynchronousLockExecutor synchronousLockExecutor() {
            return null;
        }

        @Override
        public RuntimeInfo info() {
            return new RuntimeInfo(backendId, backendId, TestBackends.behavior(), "test");
        }

        @Override
        public void close() {
        }
    }

    static final class DecoratorEvents {
        private final List<String> events = new java.util.ArrayList<>();

        void add(String event) {
            events.add(event);
        }

        List<String> events() {
            return List.copyOf(events);
        }
    }

}
