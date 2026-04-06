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

import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(1)
public class ReadWriteLockBenchmark {

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
    public void redisReadSection(Blackhole blackhole) throws Exception {
        BenchmarkWorkloads.readSection(redisEnvironment.runtime(), BenchmarkKeys.unique("rw-read", "redis", Thread.currentThread().getId()), blackhole);
    }

    @Benchmark
    public void redisWriteSection(Blackhole blackhole) throws Exception {
        BenchmarkWorkloads.writeSection(redisEnvironment.runtime(), BenchmarkKeys.unique("rw-write", "redis", Thread.currentThread().getId()), blackhole);
    }

    @Benchmark
    public void zooKeeperReadSection(Blackhole blackhole) throws Exception {
        BenchmarkWorkloads.readSection(zooKeeperEnvironment.runtime(), BenchmarkKeys.unique("rw-read", "zookeeper", Thread.currentThread().getId()), blackhole);
    }

    @Benchmark
    public void zooKeeperWriteSection(Blackhole blackhole) throws Exception {
        BenchmarkWorkloads.writeSection(zooKeeperEnvironment.runtime(), BenchmarkKeys.unique("rw-write", "zookeeper", Thread.currentThread().getId()), blackhole);
    }

    @Benchmark
    @Group("redis_rw_mixed")
    @GroupThreads(4)
    public void redisMixedRead(Blackhole blackhole) throws Exception {
        BenchmarkWorkloads.readSection(redisEnvironment.runtime(), BenchmarkKeys.shared("rw-mixed", "redis"), blackhole);
    }

    @Benchmark
    @Group("redis_rw_mixed")
    @GroupThreads(1)
    public void redisMixedWrite(Blackhole blackhole) throws Exception {
        BenchmarkWorkloads.writeSection(redisEnvironment.runtime(), BenchmarkKeys.shared("rw-mixed", "redis"), blackhole);
    }

    @Benchmark
    @Group("zookeeper_rw_mixed")
    @GroupThreads(4)
    public void zooKeeperMixedRead(Blackhole blackhole) throws Exception {
        BenchmarkWorkloads.readSection(zooKeeperEnvironment.runtime(), BenchmarkKeys.shared("rw-mixed", "zookeeper"), blackhole);
    }

    @Benchmark
    @Group("zookeeper_rw_mixed")
    @GroupThreads(1)
    public void zooKeeperMixedWrite(Blackhole blackhole) throws Exception {
        BenchmarkWorkloads.writeSection(zooKeeperEnvironment.runtime(), BenchmarkKeys.shared("rw-mixed", "zookeeper"), blackhole);
    }
}
