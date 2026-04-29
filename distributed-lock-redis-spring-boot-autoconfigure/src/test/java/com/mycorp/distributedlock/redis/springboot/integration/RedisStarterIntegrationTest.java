package com.mycorp.distributedlock.redis.springboot.integration;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.redis.springboot.config.RedisDistributedLockAutoConfiguration;
import com.mycorp.distributedlock.spi.BackendCapabilities;
import com.mycorp.distributedlock.spi.BackendModule;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("redis-integration")
class RedisStarterIntegrationTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            AopAutoConfiguration.class,
            DistributedLockAutoConfiguration.class,
            RedisDistributedLockAutoConfiguration.class
        ));

    @BeforeAll
    static void startRedis() {
        REDIS.start();
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @Test
    void shouldCreateWorkingRedisBackedLockRuntime() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis",
                "distributed.lock.redis.uri=" + redisUri(),
                "distributed.lock.redis.lease-time=30s"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(LockClient.class);
                assertThat(context).hasSingleBean(SynchronousLockExecutor.class);

                SynchronousLockExecutor executor = context.getBean(SynchronousLockExecutor.class);
                String result = executor.withLock(sampleRequest("redis-starter-test"), lease -> "ok");

                assertThat(result).isEqualTo("ok");
            });
    }

    @Test
    void shouldFailRedisPropertyValidationWhenUnrelatedBackendModuleExistsWithoutRedisProperties() {
        contextRunner
            .withUserConfiguration(UserBackendModuleConfiguration.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("BindValidationException")
                    .hasStackTraceContaining("distributed.lock.redis.uri")
                    .hasStackTraceContaining("distributed.lock.redis.leaseTime");
            });
    }

    private static LockRequest sampleRequest(String key) {
        return new LockRequest(
            new LockKey(key),
            LockMode.MUTEX,
            WaitPolicy.timed(Duration.ofSeconds(1))
        );
    }

    private static String redisUri() {
        return "redis://%s:%d".formatted(REDIS.getHost(), REDIS.getMappedPort(6379));
    }

    @Configuration(proxyBeanMethods = false)
    static class UserBackendModuleConfiguration {

        @Bean
        BackendModule customBackendModule() {
            return new NamedBackendModule("custom");
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

}
