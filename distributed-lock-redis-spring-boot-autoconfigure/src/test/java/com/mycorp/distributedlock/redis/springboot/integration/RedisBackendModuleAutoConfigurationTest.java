package com.mycorp.distributedlock.redis.springboot.integration;

import com.mycorp.distributedlock.api.LockCapabilities;
import com.mycorp.distributedlock.api.SessionRequest;
import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.redis.RedisBackendModule;
import com.mycorp.distributedlock.redis.springboot.config.RedisDistributedLockAutoConfiguration;
import com.mycorp.distributedlock.redis.springboot.config.RedisDistributedLockProperties;
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
    void shouldRejectLeaseTimesThatAreNotWholeSeconds() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis",
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
    void shouldBackOffForUserSuppliedBackendModuleOverrideByBeanName() {
        contextRunner
            .withUserConfiguration(UserRedisBackendOverrideConfiguration.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(BackendModule.class);
                assertThat(context.getBean("redisBackendModule")).isInstanceOf(NamedBackendModule.class);
                assertThat(context.getBeansOfType(BackendModule.class)).hasSize(1);
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class UserRedisBackendOverrideConfiguration {

        @Bean
        BackendModule redisBackendModule() {
            return new NamedBackendModule("redis");
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
                public LockCapabilities capabilities() {
                    return new LockCapabilities(true, true, true, true);
                }

                @Override
                public BackendSession openSession(SessionRequest request) {
                    throw new UnsupportedOperationException("not used in test");
                }
            };
        }
    }
}
