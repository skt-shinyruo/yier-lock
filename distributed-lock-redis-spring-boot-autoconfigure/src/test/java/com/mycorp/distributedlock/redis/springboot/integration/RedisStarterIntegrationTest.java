package com.mycorp.distributedlock.redis.springboot.integration;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.redis.springboot.config.RedisDistributedLockAutoConfiguration;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
    void shouldCreateWorkingRedisBackedLockManager() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis",
                "distributed.lock.redis.uri=redis://127.0.0.1:" + redisPort
            )
            .run(context -> {
                LockManager manager = context.getBean(LockManager.class);
                MutexLock lock = manager.mutex("redis-starter-test");
                assertThat(lock.tryLock(Duration.ofSeconds(1))).isTrue();
                lock.unlock();
            });
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
