package com.mycorp.distributedlock.redis.springboot.integration;

import com.mycorp.distributedlock.redis.RedisBackendModule;
import com.mycorp.distributedlock.redis.springboot.config.RedisDistributedLockAutoConfiguration;
import com.mycorp.distributedlock.redis.springboot.config.RedisDistributedLockProperties;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
}
