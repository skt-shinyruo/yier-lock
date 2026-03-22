package com.mycorp.distributedlock.benchmarks;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.redis.RedisDistributedLockFactory;
import com.mycorp.distributedlock.springboot.SpringDistributedLockFactory;
import com.mycorp.distributedlock.springboot.config.DistributedLockProperties;
import com.mycorp.distributedlock.zookeeper.ZooKeeperDistributedLockFactory;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
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

/**
 * Higher-level lifecycle benchmarks across Redis, ZooKeeper, and Spring factories.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(1)
public class EnhancedDistributedLockBenchmark {

    private static final long LEASE_SECONDS = 1L;
    private static final long WAIT_MS = 50L;

    private GenericContainer<?> redisContainer;
    private TestingServer zookeeperServer;
    private RedisClient redisClient;
    private CuratorFramework curatorFramework;
    private RedisDistributedLockFactory redisLockFactory;
    private ZooKeeperDistributedLockFactory zookeeperLockFactory;
    private SpringDistributedLockFactory springFactory;

    @Setup(Level.Trial)
    public void setupTrial() throws Exception {
        redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        redisContainer.start();

        redisClient = RedisClient.create(RedisURI.builder()
                .withHost(redisContainer.getHost())
                .withPort(redisContainer.getMappedPort(6379))
                .build());
        redisLockFactory = new RedisDistributedLockFactory(redisClient);
        springFactory = new SpringDistributedLockFactory(redisClient, new DistributedLockProperties());

        zookeeperServer = new TestingServer();
        zookeeperServer.start();
        curatorFramework = CuratorFrameworkFactory.newClient(
                zookeeperServer.getConnectString(),
                new ExponentialBackoffRetry(1_000, 3)
        );
        curatorFramework.start();
        curatorFramework.blockUntilConnected(10, TimeUnit.SECONDS);
        zookeeperLockFactory = new ZooKeeperDistributedLockFactory(curatorFramework);
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws Exception {
        if (springFactory != null) {
            springFactory.shutdown();
        }
        if (redisLockFactory != null) {
            redisLockFactory.shutdown();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
        if (redisContainer != null) {
            redisContainer.stop();
        }
        if (zookeeperLockFactory != null) {
            zookeeperLockFactory.shutdown();
        }
        if (curatorFramework != null) {
            curatorFramework.close();
        }
        if (zookeeperServer != null) {
            zookeeperServer.close();
        }
    }

    @Benchmark
    public void redisLockLifecycle(Blackhole bh) throws InterruptedException {
        runLifecycle(redisLockFactory.getLock("bench:enhanced:redis:" + Thread.currentThread().getId()), bh);
    }

    @Benchmark
    public void zookeeperLockLifecycle(Blackhole bh) throws InterruptedException {
        runLifecycle(zookeeperLockFactory.getLock("bench:enhanced:zk:" + Thread.currentThread().getId()), bh);
    }

    @Benchmark
    public void springLockLifecycle(Blackhole bh) throws InterruptedException {
        runLifecycle(springFactory.getLock("bench:enhanced:spring:" + Thread.currentThread().getId()), bh);
    }

    @Benchmark
    public void redisTryLockAndRenew(Blackhole bh) throws InterruptedException {
        runTryLockAndRenew(redisLockFactory.getLock("bench:enhanced:redis:renew:" + Thread.currentThread().getId()), bh);
    }

    @Benchmark
    public void zookeeperTryLockAndRenew(Blackhole bh) throws InterruptedException {
        runTryLockAndRenew(zookeeperLockFactory.getLock("bench:enhanced:zk:renew:" + Thread.currentThread().getId()), bh);
    }

    @Benchmark
    public void redisFactoryHealthCheck(Blackhole bh) {
        consumeHealth(redisLockFactory.healthCheck(), bh);
    }

    @Benchmark
    public void springFactoryHealthCheck(Blackhole bh) {
        consumeHealth(springFactory.healthCheck(), bh);
    }

    private void runLifecycle(DistributedLock lock, Blackhole bh) throws InterruptedException {
        lock.lock(LEASE_SECONDS, TimeUnit.SECONDS);
        try {
            bh.consume(lock.isHeldByCurrentThread());
            bh.consume(lock.getRemainingTime(TimeUnit.MILLISECONDS));
            Blackhole.consumeCPU(64);
        } finally {
            lock.unlock();
        }
    }

    private void runTryLockAndRenew(DistributedLock lock, Blackhole bh) throws InterruptedException {
        boolean acquired = lock.tryLock(WAIT_MS, LEASE_SECONDS, TimeUnit.SECONDS);
        bh.consume(acquired);
        if (!acquired) {
            return;
        }

        try {
            bh.consume(lock.renewLease());
            bh.consume(lock.getLockStateInfo().getRemainingTime(TimeUnit.MILLISECONDS));
        } finally {
            lock.unlock();
        }
    }

    private void consumeHealth(DistributedLockFactory.FactoryHealthStatus health, Blackhole bh) {
        bh.consume(health.isHealthy());
        bh.consume(health.getDetails());
        bh.consume(health.getCheckTime());
    }
}
