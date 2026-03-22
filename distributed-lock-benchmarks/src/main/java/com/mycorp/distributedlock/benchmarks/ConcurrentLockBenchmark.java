package com.mycorp.distributedlock.benchmarks;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.redis.RedisDistributedLockFactory;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.TimeUnit;

/**
 * Compile-safe concurrency benchmarks aligned to the current lock API.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(1)
@Threads(Threads.MAX)
public class ConcurrentLockBenchmark {

    private static final long WAIT_MS = 50L;
    private static final long LEASE_SECONDS = 1L;

    private GenericContainer<?> redisContainer;
    private TestingServer zookeeperServer;
    private RedisClient redisClient;
    private CuratorFramework curatorFramework;
    private RedisDistributedLockFactory redisLockFactory;
    private ZooKeeperDistributedLockFactory zookeeperLockFactory;

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
    @Group("redis_contention")
    @GroupThreads(4)
    public void redisContendedTryLock(Blackhole bh) throws InterruptedException {
        runContendedTryLock(redisLockFactory.getLock("bench:concurrent:redis:shared"), bh);
    }

    @Benchmark
    @Group("zookeeper_contention")
    @GroupThreads(4)
    public void zookeeperContendedTryLock(Blackhole bh) throws InterruptedException {
        runContendedTryLock(zookeeperLockFactory.getLock("bench:concurrent:zk:shared"), bh);
    }

    @Benchmark
    public void redisReentrantLifecycle(Blackhole bh) throws InterruptedException {
        runReentrantLifecycle(redisLockFactory.getLock("bench:concurrent:redis:reentrant:" + Thread.currentThread().getId()), bh);
    }

    @Benchmark
    public void zookeeperReentrantLifecycle(Blackhole bh) throws InterruptedException {
        runReentrantLifecycle(zookeeperLockFactory.getLock("bench:concurrent:zk:reentrant:" + Thread.currentThread().getId()), bh);
    }

    @Benchmark
    public void redisUniqueTryLock(Blackhole bh) throws InterruptedException {
        runUniqueTryLock(redisLockFactory.getLock("bench:concurrent:redis:unique:" + Thread.currentThread().getId()), bh);
    }

    @Benchmark
    public void zookeeperUniqueTryLock(Blackhole bh) throws InterruptedException {
        runUniqueTryLock(zookeeperLockFactory.getLock("bench:concurrent:zk:unique:" + Thread.currentThread().getId()), bh);
    }

    private void runContendedTryLock(DistributedLock lock, Blackhole bh) throws InterruptedException {
        boolean acquired = lock.tryLock(WAIT_MS, LEASE_SECONDS, TimeUnit.SECONDS);
        bh.consume(acquired);
        if (!acquired) {
            return;
        }

        try {
            bh.consume(lock.isHeldByCurrentThread());
            Blackhole.consumeCPU(128);
        } finally {
            lock.unlock();
        }
    }

    private void runUniqueTryLock(DistributedLock lock, Blackhole bh) throws InterruptedException {
        lock.lock(LEASE_SECONDS, TimeUnit.SECONDS);
        try {
            bh.consume(lock.getRemainingTime(TimeUnit.MILLISECONDS));
            Blackhole.consumeCPU(64);
        } finally {
            lock.unlock();
        }
    }

    private void runReentrantLifecycle(DistributedLock lock, Blackhole bh) throws InterruptedException {
        lock.lock(LEASE_SECONDS, TimeUnit.SECONDS);
        try {
            lock.lock(LEASE_SECONDS, TimeUnit.SECONDS);
            try {
                bh.consume(lock.isHeldByCurrentThread());
                bh.consume(lock.renewLease());
                Blackhole.consumeCPU(64);
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
