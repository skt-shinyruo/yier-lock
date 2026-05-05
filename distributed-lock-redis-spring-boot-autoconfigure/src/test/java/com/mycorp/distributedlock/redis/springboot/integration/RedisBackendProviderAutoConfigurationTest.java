package com.mycorp.distributedlock.redis.springboot.integration;

import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.redis.RedisBackendConfiguration;
import com.mycorp.distributedlock.redis.RedisBackendProvider;
import com.mycorp.distributedlock.redis.RedisKeyStrategy;
import com.mycorp.distributedlock.redis.springboot.config.RedisDistributedLockAutoConfiguration;
import com.mycorp.distributedlock.redis.springboot.config.RedisDistributedLockProperties;
import com.mycorp.distributedlock.spi.BackendConfiguration;
import com.mycorp.distributedlock.spi.BackendProvider;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RedisBackendProviderAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RedisDistributedLockAutoConfiguration.class));

    private final ApplicationContextRunner starterContextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            DistributedLockAutoConfiguration.class,
            RedisDistributedLockAutoConfiguration.class
        ));

    @Test
    void shouldBindRedisPropertiesAndExposeProviderAndConfiguration() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis",
                "distributed.lock.redis.uri=redis://127.0.0.1:6380",
                "distributed.lock.redis.lease-time=45s"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(RedisDistributedLockProperties.class);
                assertThat(context).hasSingleBean(BackendProvider.class);
                assertThat(context.getBean(BackendProvider.class)).isInstanceOf(RedisBackendProvider.class);
                assertThat(context).hasBean("redisDistributedLockBackendProvider");
                assertThat(context).hasSingleBean(BackendConfiguration.class);
                assertThat(context.getBean(BackendConfiguration.class)).isInstanceOf(RedisBackendConfiguration.class);

                RedisDistributedLockProperties properties = context.getBean(RedisDistributedLockProperties.class);
                assertThat(properties.getUri()).isEqualTo("redis://127.0.0.1:6380");
                assertThat(properties.getLeaseTime()).isEqualTo(Duration.ofSeconds(45));
            });
    }

    @Test
    void shouldBindAdvancedRedisPropertiesIntoBackendConfiguration() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis",
                "distributed.lock.redis.uri=redis://127.0.0.1:6380",
                "distributed.lock.redis.lease-time=45s",
                "distributed.lock.redis.key-strategy=hash-tagged",
                "distributed.lock.redis.fixed-lease-renewal-enabled=true",
                "distributed.lock.redis.renewal-pool-size=4"
            )
            .run(context -> {
                RedisDistributedLockProperties properties = context.getBean(RedisDistributedLockProperties.class);
                assertThat(properties.getKeyStrategy()).isEqualTo(RedisKeyStrategy.HASH_TAGGED);
                assertThat(properties.isFixedLeaseRenewalEnabled()).isTrue();
                assertThat(properties.getRenewalPoolSize()).isEqualTo(4);

                RedisBackendConfiguration configuration = context.getBean(RedisBackendConfiguration.class);
                assertThat(configuration.keyStrategy()).isEqualTo(RedisKeyStrategy.HASH_TAGGED);
                assertThat(configuration.fixedLeaseRenewalEnabled()).isTrue();
                assertThat(configuration.renewalPoolSize()).isEqualTo(4);
            });
    }

    @Test
    void shouldRejectNegativeRenewalPoolSize() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis",
                "distributed.lock.redis.uri=redis://127.0.0.1:6380",
                "distributed.lock.redis.lease-time=45s",
                "distributed.lock.redis.renewal-pool-size=-1"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasMessageContaining("distributed.lock.redis.renewal-pool-size");
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
                    .hasStackTraceContaining("BindValidationException")
                    .hasStackTraceContaining("distributed.lock.redis.uri");
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
                    .hasStackTraceContaining("BindValidationException")
                    .hasStackTraceContaining("distributed.lock.redis.leaseTime");
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
                assertThat(context).doesNotHaveBean(BackendProvider.class);
                assertThat(context).doesNotHaveBean(BackendConfiguration.class);
                assertThat(context).doesNotHaveBean(RedisDistributedLockProperties.class);
            });
    }

    @Test
    void shouldBackOffForUserSuppliedDefaultProviderBeanName() {
        contextRunner
            .withUserConfiguration(UserRedisBackendOverrideConfiguration.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(BackendProvider.class);
                assertThat(context.getBean("redisDistributedLockBackendProvider")).isInstanceOf(NamedBackendProvider.class);
            });
    }

    @Test
    void shouldAutoConfigureDefaultProviderWhenUnrelatedProviderExists() {
        contextRunner
            .withUserConfiguration(UnrelatedBackendProviderConfiguration.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis",
                "distributed.lock.redis.uri=redis://127.0.0.1:6380",
                "distributed.lock.redis.lease-time=45s"
            )
            .run(context -> {
                assertThat(context.getBeansOfType(BackendProvider.class)).hasSize(2);
                assertThat(context.getBean("unrelatedBackendProvider")).isInstanceOf(NamedBackendProvider.class);
                assertThat(context.getBean("redisDistributedLockBackendProvider")).isInstanceOf(RedisBackendProvider.class);
            });
    }

    @Test
    void shouldFailWhenUserDefaultNamedProviderDuplicatesAnotherSameIdProvider() {
        starterContextRunner
            .withUserConfiguration(DuplicateUserRedisBackendProviderConfiguration.class)
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
    void shouldBackOffWhenLockRuntimeIsUserSupplied() {
        contextRunner
            .withUserConfiguration(UserLockRuntimeOverrideConfiguration.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(LockRuntime.class);
                assertThat(context).doesNotHaveBean("redisDistributedLockBackendProvider");
                assertThat(context).doesNotHaveBean(BackendProvider.class);
                assertThat(context).doesNotHaveBean(BackendConfiguration.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class UserRedisBackendOverrideConfiguration {

        @Bean
        BackendProvider<?> redisDistributedLockBackendProvider() {
            return new NamedBackendProvider("redis");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UnrelatedBackendProviderConfiguration {

        @Bean
        BackendProvider<?> unrelatedBackendProvider() {
            return new NamedBackendProvider("unrelated");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DuplicateUserRedisBackendProviderConfiguration {

        @Bean("redisDistributedLockBackendProvider")
        BackendProvider<?> defaultNamedRedisBackendProvider() {
            return new NamedBackendProvider("redis");
        }

        @Bean
        BackendProvider<?> customRedisBackendProvider() {
            return new NamedBackendProvider("redis");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserLockRuntimeOverrideConfiguration {

        @Bean
        LockRuntime userLockRuntime() {
            return TestRuntimes.stub("redis");
        }
    }

    private static final class NamedBackendProvider extends TestBackendProviderSupport {
        private NamedBackendProvider(String id) {
            super(id);
        }
    }
}
