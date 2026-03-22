package com.mycorp.distributedlock.benchmarks;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
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
 * Read/write lock benchmarks aligned to the current lock-handle API.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(1)
public class ReadWriteLockBenchmark {

    private static final long LEASE_SECONDS = 1L;
    private static final long WAIT_MS = 50L;

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
    public void redisReadLock(Blackhole bh) throws InterruptedException {
        runReadLock(redisLockFactory.getReadWriteLock("bench:rw:redis:read:" + Thread.currentThread().getId()), bh);
    }

    @Benchmark
    public void redisWriteLock(Blackhole bh) throws InterruptedException {
        runWriteLock(redisLockFactory.getReadWriteLock("bench:rw:redis:write:" + Thread.currentThread().getId()), bh);
    }

    @Benchmark
    public void redisDowngradePath(Blackhole bh) throws InterruptedException {
        runDowngradePath(redisLockFactory.getReadWriteLock("bench:rw:redis:downgrade:" + Thread.currentThread().getId()), bh);
    }

    @Benchmark
    public void redisUpgradeAttempt(Blackhole bh) throws InterruptedException {
        runUpgradeAttempt(redisLockFactory.getReadWriteLock("bench:rw:redis:upgrade:" + Thread.currentThread().getId()), bh);
    }

    @Benchmark
    public void zookeeperReadLock(Blackhole bh) throws InterruptedException {
        runReadLock(zookeeperLockFactory.getReadWriteLock("bench:rw:zk:read:" + Thread.currentThread().getId()), bh);
    }

    @Benchmark
    public void zookeeperWriteLock(Blackhole bh) throws InterruptedException {
        runWriteLock(zookeeperLockFactory.getReadWriteLock("bench:rw:zk:write:" + Thread.currentThread().getId()), bh);
    }

    @Benchmark
    public void zookeeperDowngradePath(Blackhole bh) throws InterruptedException {
        runDowngradePath(zookeeperLockFactory.getReadWriteLock("bench:rw:zk:downgrade:" + Thread.currentThread().getId()), bh);
    }

    @Benchmark
    public void zookeeperUpgradeAttempt(Blackhole bh) throws InterruptedException {
        runUpgradeAttempt(zookeeperLockFactory.getReadWriteLock("bench:rw:zk:upgrade:" + Thread.currentThread().getId()), bh);
    }

    private void runReadLock(DistributedReadWriteLock lock, Blackhole bh) throws InterruptedException {
        DistributedLock readLock = lock.readLock();
        readLock.lock(LEASE_SECONDS, TimeUnit.SECONDS);
        try {
            bh.consume(lock.getReadWriteLockStateInfo().isReadLockedByCurrentThread());
            bh.consume(readLock.getRemainingTime(TimeUnit.MILLISECONDS));
            Blackhole.consumeCPU(64);
        } finally {
            readLock.unlock();
        }
    }

    private void runWriteLock(DistributedReadWriteLock lock, Blackhole bh) throws InterruptedException {
        DistributedLock writeLock = lock.writeLock();
        writeLock.lock(LEASE_SECONDS, TimeUnit.SECONDS);
        try {
            bh.consume(lock.getReadWriteLockStateInfo().isWriteLockedByCurrentThread());
            bh.consume(writeLock.getRemainingTime(TimeUnit.MILLISECONDS));
            Blackhole.consumeCPU(64);
        } finally {
            writeLock.unlock();
        }
    }

    private void runDowngradePath(DistributedReadWriteLock lock, Blackhole bh) throws InterruptedException {
        DistributedLock writeLock = lock.writeLock();
        writeLock.lock(LEASE_SECONDS, TimeUnit.SECONDS);
        try {
            boolean downgraded = lock.tryDowngradeToReadLock(LEASE_SECONDS, TimeUnit.SECONDS);
            bh.consume(downgraded);
            bh.consume(lock.readLock().isHeldByCurrentThread());
        } finally {
            if (lock.readLock().isHeldByCurrentThread()) {
                lock.readLock().unlock();
            }
            if (lock.writeLock().isHeldByCurrentThread()) {
                lock.writeLock().unlock();
            }
        }
    }

    private void runUpgradeAttempt(DistributedReadWriteLock lock, Blackhole bh) throws InterruptedException {
        DistributedLock readLock = lock.readLock();
        boolean acquired = readLock.tryLock(WAIT_MS, LEASE_SECONDS, TimeUnit.SECONDS);
        bh.consume(acquired);
        if (!acquired) {
            return;
        }

        try {
            boolean upgraded;
            try {
                upgraded = lock.tryUpgradeToWriteLock(WAIT_MS, LEASE_SECONDS, TimeUnit.SECONDS);
            } catch (UnsupportedOperationException exception) {
                upgraded = false;
            }
            bh.consume(upgraded);
            bh.consume(lock.writeLock().isHeldByCurrentThread());
        } finally {
            if (lock.writeLock().isHeldByCurrentThread()) {
                lock.writeLock().unlock();
            }
            if (lock.readLock().isHeldByCurrentThread()) {
                lock.readLock().unlock();
            }
        }
    }
}
