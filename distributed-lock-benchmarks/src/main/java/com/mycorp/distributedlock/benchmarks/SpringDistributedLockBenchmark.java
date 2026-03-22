package com.mycorp.distributedlock.benchmarks;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.redis.RedisDistributedLockFactory;
import com.mycorp.distributedlock.springboot.SpringDistributedLockFactory;
import com.mycorp.distributedlock.springboot.config.DistributedLockProperties;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
@Fork(1)
public class SpringDistributedLockBenchmark {

    private GenericContainer<?> redisContainer;
    private RedisClient redisClient;
    private RedisDistributedLockFactory directFactory;
    private SpringDistributedLockFactory springFactory;

    @Setup(Level.Trial)
    public void setupTrial() throws Exception {
        redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        redisContainer.start();

        String redisHost = redisContainer.getHost();
        Integer redisPort = redisContainer.getMappedPort(6379);

        redisClient = RedisClient.create(RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .build());

        directFactory = new RedisDistributedLockFactory(redisClient);
        springFactory = new SpringDistributedLockFactory(redisClient, new DistributedLockProperties());
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        if (springFactory != null) {
            springFactory.shutdown();
        }
        if (directFactory != null) {
            directFactory.shutdown();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
        if (redisContainer != null) {
            redisContainer.stop();
        }
    }

    /**
     * Benchmark direct Redis lock acquisition and release (non-Spring baseline).
     */
    @Benchmark
    public void directLockAcquireRelease(Blackhole bh) throws InterruptedException {
        String lockKey = "bench-direct-" + Thread.currentThread().getId();
        DistributedLock lock = directFactory.getLock(lockKey);

        lock.lock(5, TimeUnit.SECONDS);
        try {
            bh.consume(lock.isHeldByCurrentThread());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Benchmark Spring factory lock acquisition and release.
     */
    @Benchmark
    public void springLockAcquireRelease(Blackhole bh) throws InterruptedException {
        String lockKey = "bench-spring-" + Thread.currentThread().getId();
        DistributedLock lock = springFactory.getLock(lockKey);

        lock.lock(5, TimeUnit.SECONDS);
        try {
            bh.consume(lock.isHeldByCurrentThread());
        } finally {
            lock.unlock();
        }
    }

}
