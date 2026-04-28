package com.mycorp.distributedlock.redis.springboot.integration;

import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.redis.RedisBackendModule;
import com.mycorp.distributedlock.redis.springboot.config.RedisDistributedLockAutoConfiguration;
import com.mycorp.distributedlock.redis.springboot.config.RedisDistributedLockProperties;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RedisBackendModuleAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RedisDistributedLockAutoConfiguration.class));

    @Test
    void shouldBindRedisPropertiesAndExposeBackendModule() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis",
                "distributed.lock.redis.uri=redis://127.0.0.1:6380",
                "distributed.lock.redis.lease-time=45s"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(RedisDistributedLockProperties.class);
                assertThat(context).hasSingleBean(BackendModule.class);
                assertThat(context.getBean(BackendModule.class)).isInstanceOf(RedisBackendModule.class);

                RedisDistributedLockProperties properties = context.getBean(RedisDistributedLockProperties.class);
                assertThat(properties.getUri()).isEqualTo("redis://127.0.0.1:6380");
                assertThat(properties.getLeaseTime()).isEqualTo(Duration.ofSeconds(45));
            });
    }

    @Test
    void shouldFailWhenRedisUriIsMissing() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis",
                "distributed.lock.redis.lease-time=45s"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("distributed.lock.redis.uri")
                    .hasStackTraceContaining("must not be blank");
            });
    }

    @Test
    void shouldFailWhenRedisLeaseTimeIsMissing() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis",
                "distributed.lock.redis.uri=redis://127.0.0.1:6380"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("distributed.lock.redis.leaseTime")
                    .hasStackTraceContaining("must not be null");
            });
    }

    @Test
    void shouldRejectLeaseTimesThatAreNotWholeSeconds() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis",
                "distributed.lock.redis.uri=redis://127.0.0.1:6380",
                "distributed.lock.redis.lease-time=1500ms"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasMessageContaining("whole seconds");
            });
    }

    @Test
    void shouldBackOffWhenBackendSelectionDoesNotMatch() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=zookeeper"
            )
            .run(context -> {
                assertThat(context).doesNotHaveBean(BackendModule.class);
                assertThat(context).doesNotHaveBean(RedisDistributedLockProperties.class);
            });
    }

    @Test
    void shouldBackOffForUserSuppliedBackendModuleRegardlessOfBeanName() {
        contextRunner
            .withUserConfiguration(UserRedisBackendOverrideConfiguration.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis",
                "distributed.lock.redis.uri=redis://127.0.0.1:6380",
                "distributed.lock.redis.lease-time=45s"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(BackendModule.class);
                assertThat(context.getBean("customRedisBackendModule")).isInstanceOf(NamedBackendModule.class);
                assertThat(context).doesNotHaveBean("redisBackendModule");
            });
    }

    @Test
    void shouldBackOffWhenLockRuntimeIsUserSupplied() {
        contextRunner
            .withUserConfiguration(UserLockRuntimeOverrideConfiguration.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis",
                "distributed.lock.redis.uri=redis://127.0.0.1:6380",
                "distributed.lock.redis.lease-time=45s"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(LockRuntime.class);
                assertThat(context).doesNotHaveBean("redisBackendModule");
                assertThat(context).doesNotHaveBean(BackendModule.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class UserRedisBackendOverrideConfiguration {

        @Bean
        BackendModule customRedisBackendModule() {
            return new NamedBackendModule("redis");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserLockRuntimeOverrideConfiguration {

        @Bean
        LockRuntime userLockRuntime() {
            return new StubLockRuntime();
        }
    }

    private static final class NamedBackendModule implements BackendModule {

        private final String id;

        private NamedBackendModule(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public BackendCapabilities capabilities() {
            return BackendCapabilities.standard();
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
    }

    private static final class StubLockRuntime implements LockRuntime {

        @Override
        public com.mycorp.distributedlock.api.LockClient lockClient() {
            return null;
        }

        @Override
        public com.mycorp.distributedlock.api.SynchronousLockExecutor synchronousLockExecutor() {
            return null;
        }

        @Override
        public void close() {
        }
    }
}
