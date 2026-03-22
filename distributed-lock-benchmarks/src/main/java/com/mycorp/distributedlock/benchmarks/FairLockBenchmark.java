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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.TimeUnit;

/**
 * Fairness-adjacent contention benchmarks that avoid stale fairness-only APIs.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(1)
public class FairLockBenchmark {

    private static final long WAIT_MS = 100L;
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
    @Group("redis_shared_queue")
    @GroupThreads(6)
    public void redisSharedLockQueueing(Blackhole bh) throws InterruptedException {
        runQueuedAccess(redisLockFactory.getLock("bench:fair:redis:shared"), bh);
    }

    @Benchmark
    public void redisUncontendedLock(Blackhole bh) throws InterruptedException {
        runUncontendedAccess(redisLockFactory.getLock("bench:fair:redis:unique:" + Thread.currentThread().getId()), bh);
    }

    @Benchmark
    @Group("zookeeper_shared_queue")
    @GroupThreads(6)
    public void zookeeperSharedLockQueueing(Blackhole bh) throws InterruptedException {
        runQueuedAccess(zookeeperLockFactory.getLock("bench:fair:zk:shared"), bh);
    }

    @Benchmark
    public void zookeeperUncontendedLock(Blackhole bh) throws InterruptedException {
        runUncontendedAccess(zookeeperLockFactory.getLock("bench:fair:zk:unique:" + Thread.currentThread().getId()), bh);
    }

    @Benchmark
    public void redisRenewAfterAcquire(Blackhole bh) throws InterruptedException {
        runRenewal(redisLockFactory.getLock("bench:fair:redis:renew:" + Thread.currentThread().getId()), bh);
    }

    @Benchmark
    public void zookeeperRenewAfterAcquire(Blackhole bh) throws InterruptedException {
        runRenewal(zookeeperLockFactory.getLock("bench:fair:zk:renew:" + Thread.currentThread().getId()), bh);
    }

    private void runQueuedAccess(DistributedLock lock, Blackhole bh) throws InterruptedException {
        boolean acquired = lock.tryLock(WAIT_MS, LEASE_SECONDS, TimeUnit.SECONDS);
        bh.consume(acquired);
        if (!acquired) {
            return;
        }

        try {
            bh.consume(lock.getLockHolder());
            Blackhole.consumeCPU(64);
        } finally {
            lock.unlock();
        }
    }

    private void runUncontendedAccess(DistributedLock lock, Blackhole bh) throws InterruptedException {
        lock.lock(LEASE_SECONDS, TimeUnit.SECONDS);
        try {
            bh.consume(lock.isHeldByCurrentThread());
            Blackhole.consumeCPU(64);
        } finally {
            lock.unlock();
        }
    }

    private void runRenewal(DistributedLock lock, Blackhole bh) throws InterruptedException {
        lock.lock(LEASE_SECONDS, TimeUnit.SECONDS);
        try {
            bh.consume(lock.renewLease());
            bh.consume(lock.getRemainingTime(TimeUnit.MILLISECONDS));
        } finally {
            lock.unlock();
        }
    }
}
