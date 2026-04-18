package com.mycorp.distributedlock.benchmarks;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.benchmarks.support.RedisBenchmarkEnvironment;
import com.mycorp.distributedlock.benchmarks.support.SpringBenchmarkEnvironment;
import com.mycorp.distributedlock.benchmarks.support.ZooKeeperBenchmarkEnvironment;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkEnvironmentSmokeTest {

    @Test
    void redisEnvironmentShouldCreateWorkingRuntime() throws Exception {
        try (RedisBenchmarkEnvironment environment = RedisBenchmarkEnvironment.start()) {
            String result = environment.lockExecutor().withLock(sampleRequest("bench:smoke:redis"), () -> "ok");
            assertThat(result).isEqualTo("ok");
        }
    }

    @Test
    void zooKeeperEnvironmentShouldCreateWorkingRuntime() throws Exception {
        try (ZooKeeperBenchmarkEnvironment environment = ZooKeeperBenchmarkEnvironment.start()) {
            String result = environment.lockExecutor().withLock(sampleRequest("bench:smoke:zk"), () -> "ok");
            assertThat(result).isEqualTo("ok");
        }
    }

    @Test
    void springEnvironmentShouldExposeBenchmarkService() throws Exception {
        try (SpringBenchmarkEnvironment environment = SpringBenchmarkEnvironment.start()) {
            assertThat(environment.lockClient()).isNotNull();
            assertThat(environment.lockExecutor()).isNotNull();
            assertThat(environment.programmaticService()).isNotNull();
            assertThat(environment.annotatedService()).isNotNull();
        }
    }

    private static LockRequest sampleRequest(String key) {
        return new LockRequest(
            new LockKey(key),
            LockMode.MUTEX,
            WaitPolicy.timed(Duration.ofMillis(100))
        );
    }
}
