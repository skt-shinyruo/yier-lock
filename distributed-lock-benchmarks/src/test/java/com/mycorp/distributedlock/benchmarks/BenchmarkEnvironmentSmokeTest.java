package com.mycorp.distributedlock.benchmarks;

import com.mycorp.distributedlock.api.MutexLock;
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
            MutexLock lock = environment.lockManager().mutex("bench:smoke:redis");
            assertThat(lock.tryLock(Duration.ofMillis(100))).isTrue();
        }
    }

    @Test
    void zooKeeperEnvironmentShouldCreateWorkingRuntime() throws Exception {
        try (ZooKeeperBenchmarkEnvironment environment = ZooKeeperBenchmarkEnvironment.start()) {
            MutexLock lock = environment.lockManager().mutex("bench:smoke:zk");
            assertThat(lock.tryLock(Duration.ofMillis(100))).isTrue();
        }
    }

    @Test
    void springEnvironmentShouldExposeBenchmarkService() throws Exception {
        try (SpringBenchmarkEnvironment environment = SpringBenchmarkEnvironment.start()) {
            assertThat(environment.programmaticService()).isNotNull();
            assertThat(environment.annotatedService()).isNotNull();
        }
    }
}
