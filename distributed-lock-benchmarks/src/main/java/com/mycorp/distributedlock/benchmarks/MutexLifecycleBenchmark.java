package com.mycorp.distributedlock.benchmarks;

import com.mycorp.distributedlock.benchmarks.support.RedisBenchmarkEnvironment;
import com.mycorp.distributedlock.benchmarks.support.ZooKeeperBenchmarkEnvironment;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(1)
public class MutexLifecycleBenchmark {

    private RedisBenchmarkEnvironment redisEnvironment;
    private ZooKeeperBenchmarkEnvironment zooKeeperEnvironment;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        redisEnvironment = RedisBenchmarkEnvironment.start();
        zooKeeperEnvironment = ZooKeeperBenchmarkEnvironment.start();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        if (redisEnvironment != null) {
            redisEnvironment.close();
        }
        if (zooKeeperEnvironment != null) {
            zooKeeperEnvironment.close();
        }
    }

    @Benchmark
    public void redisMutexLifecycle(Blackhole blackhole) throws Exception {
        String key = BenchmarkKeys.unique("mutex-lifecycle", "redis", Thread.currentThread().getId());
        BenchmarkWorkloads.mutexLifecycle(redisEnvironment.runtime(), key, blackhole);
    }

    @Benchmark
    public void zooKeeperMutexLifecycle(Blackhole blackhole) throws Exception {
        String key = BenchmarkKeys.unique("mutex-lifecycle", "zookeeper", Thread.currentThread().getId());
        BenchmarkWorkloads.mutexLifecycle(zooKeeperEnvironment.runtime(), key, blackhole);
    }

    @Benchmark
    public void redisTryLockSuccess(Blackhole blackhole) throws Exception {
        String key = BenchmarkKeys.unique("mutex-try-lock", "redis", Thread.currentThread().getId());
        BenchmarkWorkloads.successfulTryLock(redisEnvironment.lockClient(), key, Duration.ofMillis(250), blackhole);
    }

    @Benchmark
    public void zooKeeperTryLockSuccess(Blackhole blackhole) throws Exception {
        String key = BenchmarkKeys.unique("mutex-try-lock", "zookeeper", Thread.currentThread().getId());
        BenchmarkWorkloads.successfulTryLock(zooKeeperEnvironment.lockClient(), key, Duration.ofMillis(250), blackhole);
    }
}
