package com.mycorp.distributedlock.benchmarks;

import com.mycorp.distributedlock.benchmarks.support.RedisBenchmarkEnvironment;
import com.mycorp.distributedlock.benchmarks.support.ZooKeeperBenchmarkEnvironment;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
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
public class MutexContentionBenchmark {

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
    @Group("redis_mutex_contention_4")
    @GroupThreads(4)
    public void redisSharedTryLock4(Blackhole blackhole) throws Exception {
        String key = BenchmarkKeys.shared("mutex-contention-4", "redis");
        BenchmarkWorkloads.contendedTryLock(redisEnvironment.lockClient(), key, Duration.ofMillis(50), blackhole);
    }

    @Benchmark
    @Group("redis_mutex_contention_8")
    @GroupThreads(8)
    public void redisSharedTryLock8(Blackhole blackhole) throws Exception {
        String key = BenchmarkKeys.shared("mutex-contention-8", "redis");
        BenchmarkWorkloads.contendedTryLock(redisEnvironment.lockClient(), key, Duration.ofMillis(50), blackhole);
    }

    @Benchmark
    @Group("zookeeper_mutex_contention_4")
    @GroupThreads(4)
    public void zooKeeperSharedTryLock4(Blackhole blackhole) throws Exception {
        String key = BenchmarkKeys.shared("mutex-contention-4", "zookeeper");
        BenchmarkWorkloads.contendedTryLock(zooKeeperEnvironment.lockClient(), key, Duration.ofMillis(50), blackhole);
    }

    @Benchmark
    @Group("zookeeper_mutex_contention_8")
    @GroupThreads(8)
    public void zooKeeperSharedTryLock8(Blackhole blackhole) throws Exception {
        String key = BenchmarkKeys.shared("mutex-contention-8", "zookeeper");
        BenchmarkWorkloads.contendedTryLock(zooKeeperEnvironment.lockClient(), key, Duration.ofMillis(50), blackhole);
    }
}
