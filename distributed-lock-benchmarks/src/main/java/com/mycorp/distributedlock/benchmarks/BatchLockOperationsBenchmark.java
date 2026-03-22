package com.mycorp.distributedlock.benchmarks;

import com.mycorp.distributedlock.api.BatchLockOperations;
import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.redis.RedisBatchLockOperations;
import com.mycorp.distributedlock.redis.RedisDistributedLockFactory;
import com.mycorp.distributedlock.zookeeper.ZooKeeperDistributedLockFactory;
import com.mycorp.distributedlock.zookeeper.operation.ZooKeeperBatchLockOperations;
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

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * Batch operation benchmarks aligned to the current BatchLockOperations API.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(1)
public class BatchLockOperationsBenchmark {

    private static final long LEASE_MS = 1_000L;

    private GenericContainer<?> redisContainer;
    private TestingServer zookeeperServer;
    private RedisClient redisClient;
    private CuratorFramework curatorFramework;
    private RedisDistributedLockFactory redisLockFactory;
    private ZooKeeperDistributedLockFactory zookeeperLockFactory;
    private RedisBatchLockOperations redisBatchOperations;
    private ZooKeeperBatchLockOperations zooKeeperBatchOperations;

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
        redisBatchOperations = new RedisBatchLockOperations(redisLockFactory);

        zookeeperServer = new TestingServer();
        zookeeperServer.start();
        curatorFramework = CuratorFrameworkFactory.newClient(
                zookeeperServer.getConnectString(),
                new ExponentialBackoffRetry(1_000, 3)
        );
        curatorFramework.start();
        curatorFramework.blockUntilConnected(10, TimeUnit.SECONDS);
        zookeeperLockFactory = new ZooKeeperDistributedLockFactory(curatorFramework);
        zooKeeperBatchOperations = new ZooKeeperBatchLockOperations(zookeeperLockFactory);
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
    public void redisSmallBatchLock(Blackhole bh) throws InterruptedException {
        runBatchLock(redisBatchOperations, keys("bench:batch:redis:small", 5), bh);
    }

    @Benchmark
    public void redisMediumBatchLock(Blackhole bh) throws InterruptedException {
        runBatchLock(redisBatchOperations, keys("bench:batch:redis:medium", 20), bh);
    }

    @Benchmark
    public void redisTransaction(Blackhole bh) throws Exception {
        runTransaction(redisBatchOperations, keys("bench:batch:redis:tx", 5), bh);
    }

    @Benchmark
    public void zookeeperSmallBatchLock(Blackhole bh) throws InterruptedException {
        runBatchLock(zooKeeperBatchOperations, keys("bench:batch:zk:small", 5), bh);
    }

    @Benchmark
    public void zookeeperMediumBatchLock(Blackhole bh) throws InterruptedException {
        runBatchLock(zooKeeperBatchOperations, keys("bench:batch:zk:medium", 20), bh);
    }

    @Benchmark
    public void zookeeperTransaction(Blackhole bh) throws Exception {
        runTransaction(zooKeeperBatchOperations, keys("bench:batch:zk:tx", 5), bh);
    }

    private void runBatchLock(BatchLockOperations<DistributedLock> operations, List<String> lockNames, Blackhole bh)
            throws InterruptedException {
        BatchLockOperations.BatchLockResult<DistributedLock> result =
                operations.batchLock(lockNames, LEASE_MS, TimeUnit.MILLISECONDS);
        try {
            bh.consume(result.isAllSuccessful());
            bh.consume(result.getSuccessfulLocks().size());
            bh.consume(result.getFailedLockNames().size());
        } finally {
            operations.batchUnlock(result.getSuccessfulLocks());
        }
    }

    private void runTransaction(BatchLockOperations<DistributedLock> operations, List<String> lockNames, Blackhole bh)
            throws Exception {
        Integer size = operations.executeInTransaction(
                lockNames,
                LEASE_MS,
                TimeUnit.MILLISECONDS,
                locks -> locks.size()
        );
        bh.consume(size);
    }

    private List<String> keys(String prefix, int count) {
        long threadId = Thread.currentThread().getId();
        return IntStream.range(0, count)
                .mapToObj(index -> prefix + ":" + threadId + ":" + index)
                .toList();
    }
}
