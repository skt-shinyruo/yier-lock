package com.mycorp.distributedlock.redis.springboot.integration;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockExecutor;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.redis.springboot.config.RedisDistributedLockAutoConfiguration;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RedisStarterIntegrationTest {

    private static String containerId;
    private static int redisPort;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            AopAutoConfiguration.class,
            DistributedLockAutoConfiguration.class,
            RedisDistributedLockAutoConfiguration.class
        ));

    @BeforeAll
    static void startRedis() throws Exception {
        containerId = run("docker", "run", "-d", "-P", "redis:7-alpine").trim();
        String portOutput = run("docker", "port", containerId, "6379/tcp").trim();
        redisPort = Integer.parseInt(portOutput.substring(portOutput.lastIndexOf(':') + 1));
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (containerId != null && !containerId.isBlank()) {
            run("docker", "rm", "-f", containerId);
        }
    }

    @Test
    void shouldCreateWorkingRedisBackedLockRuntime() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis",
                "distributed.lock.redis.uri=redis://127.0.0.1:" + redisPort,
                "distributed.lock.redis.lease-time=30s"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(LockClient.class);
                assertThat(context).hasSingleBean(LockExecutor.class);

                LockExecutor executor = context.getBean(LockExecutor.class);
                String result = executor.withLock(sampleRequest("redis-starter-test"), () -> "ok");

                assertThat(result).isEqualTo("ok");
            });
    }

    @Test
    void shouldFailWhenUserOwnedBackendRegistryDoesNotContainSelectedBackend() {
        contextRunner
            .withUserConfiguration(UserBackendModuleConfiguration.class)
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

    private static LockRequest sampleRequest(String key) {
        return new LockRequest(
            new LockKey(key),
            LockMode.MUTEX,
            WaitPolicy.timed(Duration.ofSeconds(1))
        );
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

    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Command failed: " + String.join(" ", command) + "\n" + output);
        }
        return output;
    }
}
