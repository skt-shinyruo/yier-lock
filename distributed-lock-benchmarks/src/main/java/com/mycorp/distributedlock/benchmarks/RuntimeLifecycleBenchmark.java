package com.mycorp.distributedlock.benchmarks;

import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.redis.RedisBackendConfiguration;
import com.mycorp.distributedlock.redis.RedisBackendProvider;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.zookeeper.ZooKeeperBackendConfiguration;
import com.mycorp.distributedlock.zookeeper.ZooKeeperBackendProvider;
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

import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(1)
public class RuntimeLifecycleBenchmark {

    private String redisUri;
    private String zooKeeperConnectString;
    private String zooKeeperBasePath;

    @Setup(Level.Trial)
    public void setUp() {
        try (var redisEnvironment = com.mycorp.distributedlock.benchmarks.support.RedisBenchmarkEnvironment.start();
             var zooKeeperEnvironment = com.mycorp.distributedlock.benchmarks.support.ZooKeeperBenchmarkEnvironment.start()) {
            redisUri = redisEnvironment.redisUri();
            zooKeeperConnectString = zooKeeperEnvironment.connectString();
            zooKeeperBasePath = zooKeeperEnvironment.basePath();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to initialize runtime lifecycle benchmark prerequisites", exception);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        redisUri = null;
        zooKeeperConnectString = null;
        zooKeeperBasePath = null;
    }

    @Benchmark
    public void redisRuntimeLifecycle(Blackhole blackhole) throws Exception {
        BenchmarkWorkloads.runtimeLifecycle(
            () -> LockRuntimeBuilder.create()
                .backend("redis")
                .backendProvider(new RedisBackendProvider())
                .backendConfiguration(new RedisBackendConfiguration(redisUri, 30L))
                .build(),
            "redis",
            blackhole
        );
    }

    @Benchmark
    public void zooKeeperRuntimeLifecycle(Blackhole blackhole) throws Exception {
        BenchmarkWorkloads.runtimeLifecycle(
            () -> LockRuntimeBuilder.create()
                .backend("zookeeper")
                .backendProvider(new ZooKeeperBackendProvider())
                .backendConfiguration(new ZooKeeperBackendConfiguration(zooKeeperConnectString, zooKeeperBasePath))
                .build(),
            "zookeeper",
            blackhole
        );
    }
}
