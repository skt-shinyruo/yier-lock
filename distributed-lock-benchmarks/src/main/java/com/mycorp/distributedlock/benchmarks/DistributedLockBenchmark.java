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
import org.openjdk.jmh.annotations.*;
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
public class DistributedLockBenchmark {
    
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
        
        String redisHost = redisContainer.getHost();
        Integer redisPort = redisContainer.getMappedPort(6379);
        
        redisClient = RedisClient.create(RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .build());
        
        redisLockFactory = new RedisDistributedLockFactory(redisClient);
        
        zookeeperServer = new TestingServer();
        zookeeperServer.start();
        
        curatorFramework = CuratorFrameworkFactory.newClient(
                zookeeperServer.getConnectString(),
                new ExponentialBackoffRetry(1000, 3)
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
    @Group("redis")
    @GroupThreads(1)
    public void redisLockAcquisitionAndRelease(Blackhole bh) throws InterruptedException {
        DistributedLock lock = redisLockFactory.getLock("benchmark-lock-" + Thread.currentThread().getId());
        
        lock.lock(5, TimeUnit.SECONDS);
        try {
            bh.consume(lock.isHeldByCurrentThread());
        } finally {
            lock.unlock();
        }
    }
    
    @Benchmark
    @Group("redis")
    @GroupThreads(4)
    public void redisLockContentionTest(Blackhole bh) throws InterruptedException {
        DistributedLock lock = redisLockFactory.getLock("contention-lock");
        
        if (lock.tryLock(100, 5, TimeUnit.MILLISECONDS)) {
            try {
                bh.consume(lock.isHeldByCurrentThread());
                Thread.sleep(1);
            } finally {
                lock.unlock();
            }
        }
    }
    
    @Benchmark
    @Group("zookeeper")
    @GroupThreads(1)
    public void zookeeperLockAcquisitionAndRelease(Blackhole bh) throws InterruptedException {
        DistributedLock lock = zookeeperLockFactory.getLock("benchmark-lock-" + Thread.currentThread().getId());
        
        lock.lock(5, TimeUnit.SECONDS);
        try {
            bh.consume(lock.isHeldByCurrentThread());
        } finally {
            lock.unlock();
        }
    }
    
    @Benchmark
    @Group("zookeeper")
    @GroupThreads(4)
    public void zookeeperLockContentionTest(Blackhole bh) throws InterruptedException {
        DistributedLock lock = zookeeperLockFactory.getLock("contention-lock");
        
        if (lock.tryLock(100, 5, TimeUnit.MILLISECONDS)) {
            try {
                bh.consume(lock.isHeldByCurrentThread());
                Thread.sleep(1);
            } finally {
                lock.unlock();
            }
        }
    }
    
    @Benchmark
    public void redisTryLockPerformance(Blackhole bh) throws InterruptedException {
        DistributedLock lock = redisLockFactory.getLock("trylock-benchmark-" + Thread.currentThread().getId());
        boolean acquired = lock.tryLock(0, 1, TimeUnit.SECONDS);
        bh.consume(acquired);
        if (acquired) {
            lock.unlock();
        }
    }
    
    @Benchmark
    public void zookeeperTryLockPerformance(Blackhole bh) throws InterruptedException {
        DistributedLock lock = zookeeperLockFactory.getLock("trylock-benchmark-" + Thread.currentThread().getId());
        boolean acquired = lock.tryLock(0, 1, TimeUnit.SECONDS);
        bh.consume(acquired);
        if (acquired) {
            lock.unlock();
        }
    }
}