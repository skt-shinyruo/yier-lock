package com.mycorp.distributedlock.benchmarks;

import com.mycorp.distributedlock.api.*;
import com.mycorp.distributedlock.redis.*;
import com.mycorp.distributedlock.zookeeper.*;
import com.mycorp.distributedlock.core.*;
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

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import static org.openjdk.jmh.annotations.Mode.*;

/**
 * 批量锁操作基准测试
 * 
 * 测试场景：
 * 1. 批量获取多个锁的性能
 * 2. 批量释放多个锁的性能
 * 3. 批量续期操作的性能
 * 4. 批量锁超时和失败处理
 * 5. 不同批量大小的性能对比
 * 6. 并发批量操作性能
 */
@BenchmarkMode({Throughput, AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
@Fork(2)
@Threads(Threads.MAX)
public class BatchLockOperationsBenchmark {
    
    // 性能统计器
    private final LongAdder batchOperations = new LongAdder();
    private final LongAdder successfulBatchOperations = new LongAdder();
    private final LongAdder failedBatchOperations = new LongAdder();
    private final LongAdder totalBatchLatency = new LongAdder();
    private final AtomicLong maxBatchLatency = new AtomicLong(0);
    private final AtomicInteger totalLocksAcquired = new AtomicInteger(0);
    private final AtomicInteger totalLocksFailed = new AtomicInteger(0);
    
    // 测试容器和客户端
    private GenericContainer<?> redisContainer;
    private TestingServer zookeeperServer;
    private RedisClient redisClient;
    private CuratorFramework curatorFramework;
    
    // 锁工厂
    private RedisDistributedLockFactory redisLockFactory;
    private ZooKeeperDistributedLockFactory zookeeperLockFactory;
    
    // 批量操作工厂
    private BatchLockOperations redisBatchOperations;
    private BatchLockOperations zkBatchOperations;
    
    // 线程池
    private ExecutorService executorService;
    
    // 测试参数
    private static final String BATCH_LOCK_PREFIX = "batch:lock:";
    private static final int SMALL_BATCH_SIZE = 5;    // 小批量
    private static final int MEDIUM_BATCH_SIZE = 20;  // 中批量
    private static final int LARGE_BATCH_SIZE = 50;   // 大批量
    private static final int BATCH_OPERATION_TIMEOUT_MS = 1000;
    private static final int LOCK_HOLD_TIME_MS = 100;
    private static final int CONCURRENT_BATCH_THREADS = 10;
    
    @Setup(Level.Trial)
    public void setupTrial() throws Exception {
        System.out.println("设置批量锁操作测试环境...");
        
        // 启动Redis容器
        redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        redisContainer.start();
        
        String redisHost = redisContainer.getHost();
        Integer redisPort = redisContainer.getMappedPort(6379);
        
        // 初始化Redis客户端和工厂
        redisClient = RedisClient.create(RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .withConnectionTimeout(Duration.ofSeconds(5))
                .build());
        redisLockFactory = new RedisDistributedLockFactory(redisClient);
        redisBatchOperations = new RedisBatchLockOperations(redisLockFactory);
        
        // 启动ZooKeeper测试服务器
        zookeeperServer = new TestingServer();
        zookeeperServer.start();
        
        // 初始化ZooKeeper客户端和工厂
        curatorFramework = CuratorFrameworkFactory.newClient(
                zookeeperServer.getConnectString(),
                new ExponentialBackoffRetry(1000, 3)
        );
        curatorFramework.start();
        curatorFramework.blockUntilConnected(10, TimeUnit.SECONDS);
        zookeeperLockFactory = new ZooKeeperDistributedLockFactory(curatorFramework);
        zkBatchOperations = new ZooKeeperBatchLockOperations(zookeeperLockFactory);
        
        // 创建线程池
        executorService = Executors.newFixedThreadPool(CONCURRENT_BATCH_THREADS + 5);
        
        System.out.println("批量锁操作测试环境设置完成");
    }
    
    @TearDown(Level.Trial)
    public void tearDownTrial() throws Exception {
        System.out.println("清理批量锁操作测试环境...");
        
        // 关闭线程池
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            executorService.awaitTermination(10, TimeUnit.SECONDS);
        }
        
        // 关闭Redis组件
        if (redisLockFactory != null) {
            redisLockFactory.shutdown();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
        if (redisContainer != null) {
            redisContainer.stop();
        }
        
        // 关闭ZooKeeper组件
        if (zookeeperLockFactory != null) {
            zookeeperLockFactory.shutdown();
        }
        if (curatorFramework != null) {
            curatorFramework.close();
        }
        if (zookeeperServer != null) {
            zookeeperServer.close();
        }
        
        printBatchPerformanceSummary();
    }
    
    @Setup(Level.Invocation)
    public void setupInvocation() {
        resetCounters();
    }
    
    private void resetCounters() {
        batchOperations.reset();
        successfulBatchOperations.reset();
        failedBatchOperations.reset();
        totalBatchLatency.reset();
        maxBatchLatency.set(0);
        totalLocksAcquired.set(0);
        totalLocksFailed.set(0);
    }
    
    private void recordBatchOperation(boolean success, long latency, int locksAcquired, int locksFailed) {
        batchOperations.increment();
        if (success) {
            successfulBatchOperations.increment();
        } else {
            failedBatchOperations.increment();
        }
        
        totalBatchLatency.add(latency);
        if (latency > maxBatchLatency.get()) {
            maxBatchLatency.set(latency);
        }
        
        totalLocksAcquired.addAndGet(locksAcquired);
        totalLocksFailed.addAndGet(locksFailed);
    }
    
    private void printBatchPerformanceSummary() {
        long totalBatches = batchOperations.sum();
        long successfulBatches = successfulBatchOperations.sum();
        long failedBatches = failedBatchOperations.sum();
        long totalLat = totalBatchLatency.sum();
        int totalAcquired = totalLocksAcquired.get();
        int totalFailed = totalLocksFailed.get();
        
        System.out.println("\n=== 批量锁操作性能摘要 ===");
        if (totalBatches > 0) {
            double avgLatency = (double) totalLat / totalBatches;
            double successRate = (double) successfulBatches / totalBatches * 100;
            
            System.out.printf("总批量操作: %d%n", totalBatches);
            System.out.printf("成功批量操作: %d%n", successfulBatches);
            System.out.printf("失败批量操作: %d%n", failedBatches);
            System.out.printf("成功率: %.2f%%%n", successRate);
            System.out.printf("平均批量延迟: %.2f ms%n", avgLatency);
            System.out.printf("最大批量延迟: %d ms%n", maxBatchLatency.get());
            System.out.printf("总获取锁数: %d%n", totalAcquired);
            System.out.printf("总失败锁数: %d%n", totalFailed);
            System.out.printf("锁获取成功率: %.2f%%%n", 
                    totalAcquired * 100.0 / (totalAcquired + totalFailed));
        }
    }
    
    // 生成批量锁键的工具方法
    private List<String> generateBatchLockKeys(String prefix, int count) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            keys.add(BATCH_LOCK_PREFIX + prefix + "-" + Thread.currentThread().getId() + "-" + i);
        }
        return keys;
    }
    
    // ==================== Redis批量锁基准测试 ====================
    
    @Benchmark
    public void redisSmallBatchLockAcquire(Blackhole bh) throws Exception {
        List<String> lockKeys = generateBatchLockKeys("redis-small", SMALL_BATCH_SIZE);
        
        long startTime = System.nanoTime();
        Map<String, Boolean> results = redisBatchOperations.acquireMultipleLocks(
                lockKeys, BATCH_OPERATION_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
        
        long latency = (System.nanoTime() - startTime) / 1_000_000;
        boolean success = results.values().stream().allMatch(Boolean::booleanValue);
        
        bh.consume(results);
        recordBatchOperation(success, latency, 
                (int) results.values().stream().filter(Boolean::booleanValue).count(),
                (int) results.values().stream().filter(v -> !v).count());
        
        // 清理已获取的锁
        redisBatchOperations.releaseMultipleLocks(lockKeys);
    }
    
    @Benchmark
    public void redisMediumBatchLockAcquire(Blackhole bh) throws Exception {
        List<String> lockKeys = generateBatchLockKeys("redis-medium", MEDIUM_BATCH_SIZE);
        
        long startTime = System.nanoTime();
        Map<String, Boolean> results = redisBatchOperations.acquireMultipleLocks(
                lockKeys, BATCH_OPERATION_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
        
        long latency = (System.nanoTime() - startTime) / 1_000_000;
        boolean success = results.values().stream().allMatch(Boolean::booleanValue);
        
        bh.consume(results);
        recordBatchOperation(success, latency,
                (int) results.values().stream().filter(Boolean::booleanValue).count(),
                (int) results.values().stream().filter(v -> !v).count());
        
        redisBatchOperations.releaseMultipleLocks(lockKeys);
    }
    
    @Benchmark
    public void redisLargeBatchLockAcquire(Blackhole bh) throws Exception {
        List<String> lockKeys = generateBatchLockKeys("redis-large", LARGE_BATCH_SIZE);
        
        long startTime = System.nanoTime();
        Map<String, Boolean> results = redisBatchOperations.acquireMultipleLocks(
                lockKeys, BATCH_OPERATION_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
        
        long latency = (System.nanoTime() - startTime) / 1_000_000;
        boolean success = results.values().stream().allMatch(Boolean::booleanValue);
        
        bh.consume(results);
        recordBatchOperation(success, latency,
                (int) results.values().stream().filter(Boolean::booleanValue).count(),
                (int) results.values().stream().filter(v -> !v).count());
        
        redisBatchOperations.releaseMultipleLocks(lockKeys);
    }
    
    @Benchmark
    public void redisBatchLockRelease(Blackhole bh) throws Exception {
        // 先获取锁
        List<String> lockKeys = generateBatchLockKeys("redis-release", SMALL_BATCH_SIZE);
        redisBatchOperations.acquireMultipleLocks(
                lockKeys, BATCH_OPERATION_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
        
        long startTime = System.nanoTime();
        Map<String, Boolean> releaseResults = redisBatchOperations.releaseMultipleLocks(lockKeys);
        
        long latency = (System.nanoTime() - startTime) / 1_000_000;
        bh.consume(releaseResults);
        
        // 记录释放操作的性能
        recordBatchOperation(true, latency,
                (int) releaseResults.values().stream().filter(Boolean::booleanValue).count(),
                (int) releaseResults.values().stream().filter(v -> !v).count());
    }
    
    @Benchmark
    public void redisBatchLockRenewal(Blackhole bh) throws Exception {
        // 先获取锁
        List<String> lockKeys = generateBatchLockKeys("redis-renewal", SMALL_BATCH_SIZE);
        redisBatchOperations.acquireMultipleLocks(
                lockKeys, BATCH_OPERATION_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
        
        long startTime = System.nanoTime();
        Map<String, Boolean> renewalResults = redisBatchOperations.renewMultipleLocks(lockKeys);
        
        long latency = (System.nanoTime() - startTime) / 1_000_000;
        bh.consume(renewalResults);
        
        recordBatchOperation(true, latency,
                (int) renewalResults.values().stream().filter(Boolean::booleanValue).count(),
                (int) renewalResults.values().stream().filter(v -> !v).count());
        
        // 清理
        redisBatchOperations.releaseMultipleLocks(lockKeys);
    }
    
    // ==================== ZooKeeper批量锁基准测试 ====================
    
    @Benchmark
    public void zookeeperSmallBatchLockAcquire(Blackhole bh) throws Exception {
        List<String> lockKeys = generateBatchLockKeys("zk-small", SMALL_BATCH_SIZE);
        
        long startTime = System.nanoTime();
        Map<String, Boolean> results = zkBatchOperations.acquireMultipleLocks(
                lockKeys, BATCH_OPERATION_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
        
        long latency = (System.nanoTime() - startTime) / 1_000_000;
        boolean success = results.values().stream().allMatch(Boolean::booleanValue);
        
        bh.consume(results);
        recordBatchOperation(success, latency,
                (int) results.values().stream().filter(Boolean::booleanValue).count(),
                (int) results.values().stream().filter(v -> !v).count());
        
        zkBatchOperations.releaseMultipleLocks(lockKeys);
    }
    
    @Benchmark
    public void zookeeperMediumBatchLockAcquire(Blackhole bh) throws Exception {
        List<String> lockKeys = generateBatchLockKeys("zk-medium", MEDIUM_BATCH_SIZE);
        
        long startTime = System.nanoTime();
        Map<String, Boolean> results = zkBatchOperations.acquireMultipleLocks(
                lockKeys, BATCH_OPERATION_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
        
        long latency = (System.nanoTime() - startTime) / 1_000_000;
        boolean success = results.values().stream().allMatch(Boolean::booleanValue);
        
        bh.consume(results);
        recordBatchOperation(success, latency,
                (int) results.values().stream().filter(Boolean::booleanValue).count(),
                (int) results.values().stream().filter(v -> !v).count());
        
        zkBatchOperations.releaseMultipleLocks(lockKeys);
    }
    
    @Benchmark
    public void zookeeperLargeBatchLockAcquire(Blackhole bh) throws Exception {
        List<String> lockKeys = generateBatchLockKeys("zk-large", LARGE_BATCH_SIZE);
        
        long startTime = System.nanoTime();
        Map<String, Boolean> results = zkBatchOperations.acquireMultipleLocks(
                lockKeys, BATCH_OPERATION_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
        
        long latency = (System.nanoTime() - startTime) / 1_000_000;
        boolean success = results.values().stream().allMatch(Boolean::booleanValue);
        
        bh.consume(results);
        recordBatchOperation(success, latency,
                (int) results.values().stream().filter(Boolean::booleanValue).count(),
                (int) results.values().stream().filter(v -> !v).count());
        
        zkBatchOperations.releaseMultipleLocks(lockKeys);
    }
    
    @Benchmark
    public void zookeeperBatchLockRelease(Blackhole bh) throws Exception {
        List<String> lockKeys = generateBatchLockKeys("zk-release", SMALL_BATCH_SIZE);
        zkBatchOperations.acquireMultipleLocks(
                lockKeys, BATCH_OPERATION_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
        
        long startTime = System.nanoTime();
        Map<String, Boolean> releaseResults = zkBatchOperations.releaseMultipleLocks(lockKeys);
        
        long latency = (System.nanoTime() - startTime) / 1_000_000;
        bh.consume(releaseResults);
        
        recordBatchOperation(true, latency,
                (int) releaseResults.values().stream().filter(Boolean::booleanValue).count(),
                (int) releaseResults.values().stream().filter(v -> !v).count());
    }
    
    @Benchmark
    public void zookeeperBatchLockRenewal(Blackhole bh) throws Exception {
        List<String> lockKeys = generateBatchLockKeys("zk-renewal", SMALL_BATCH_SIZE);
        zkBatchOperations.acquireMultipleLocks(
                lockKeys, BATCH_OPERATION_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
        
        long startTime = System.nanoTime();
        Map<String, Boolean> renewalResults = zkBatchOperations.renewMultipleLocks(lockKeys);
        
        long latency = (System.nanoTime() - startTime) / 1_000_000;
        bh.consume(renewalResults);
        
        recordBatchOperation(true, latency,
                (int) renewalResults.values().stream().filter(Boolean::booleanValue).count(),
                (int) renewalResults.values().stream().filter(v -> !v).count());
        
        zkBatchOperations.releaseMultipleLocks(lockKeys);
    }
    
    // ==================== 并发批量锁测试 ====================
    
    @Benchmark
    public void redisConcurrentBatchLockTest() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(CONCURRENT_BATCH_THREADS);
        
        IntStream.range(0, CONCURRENT_BATCH_THREADS).forEach(i -> {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    
                    List<String> lockKeys = generateBatchLockKeys("redis-concurrent-" + i, MEDIUM_BATCH_SIZE);
                    
                    long startTime = System.nanoTime();
                    Map<String, Boolean> results = redisBatchOperations.acquireMultipleLocks(
                            lockKeys, BATCH_OPERATION_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
                    
                    long latency = (System.nanoTime() - startTime) / 1_000_000;
                    boolean success = results.values().stream().allMatch(Boolean::booleanValue);
                    
                    recordBatchOperation(success, latency,
                            (int) results.values().stream().filter(Boolean::booleanValue).count(),
                            (int) results.values().stream().filter(v -> !v).count());
                    
                    // 短暂持有后释放
                    Thread.sleep(50);
                    redisBatchOperations.releaseMultipleLocks(lockKeys);
                    
                } catch (Exception e) {
                    recordBatchOperation(false, 0, 0, 1);
                } finally {
                    completeLatch.countDown();
                }
            });
        });
        
        startLatch.countDown();
        completeLatch.await(30, TimeUnit.SECONDS);
    }
    
    @Benchmark
    public void zookeeperConcurrentBatchLockTest() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(CONCURRENT_BATCH_THREADS);
        
        IntStream.range(0, CONCURRENT_BATCH_THREADS).forEach(i -> {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    
                    List<String> lockKeys = generateBatchLockKeys("zk-concurrent-" + i, MEDIUM_BATCH_SIZE);
                    
                    long startTime = System.nanoTime();
                    Map<String, Boolean> results = zkBatchOperations.acquireMultipleLocks(
                            lockKeys, BATCH_OPERATION_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
                    
                    long latency = (System.nanoTime() - startTime) / 1_000_000;
                    boolean success = results.values().stream().allMatch(Boolean::booleanValue);
                    
                    recordBatchOperation(success, latency,
                            (int) results.values().stream().filter(Boolean::booleanValue).count(),
                            (int) results.values().stream().filter(v -> !v).count());
                    
                    Thread.sleep(50);
                    zkBatchOperations.releaseMultipleLocks(lockKeys);
                    
                } catch (Exception e) {
                    recordBatchOperation(false, 0, 0, 1);
                } finally {
                    completeLatch.countDown();
                }
            });
        });
        
        startLatch.countDown();
        completeLatch.await(30, TimeUnit.SECONDS);
    }
    
    // ==================== 批量锁失败场景测试 ====================
    
    @Benchmark
    public void redisBatchLockFailureScenario(Blackhole bh) throws Exception {
        // 创建部分冲突的锁键，增加失败概率
        List<String> lockKeys = new ArrayList<>();
        for (int i = 0; i < SMALL_BATCH_SIZE; i++) {
            lockKeys.add(BATCH_LOCK_PREFIX + "redis-failure-shared-" + (i % 3));
        }
        
        long startTime = System.nanoTime();
        Map<String, Boolean> results = redisBatchOperations.acquireMultipleLocks(
                lockKeys, BATCH_OPERATION_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
        
        long latency = (System.nanoTime() - startTime) / 1_000_000;
        
        bh.consume(results);
        recordBatchOperation(true, latency,
                (int) results.values().stream().filter(Boolean::booleanValue).count(),
                (int) results.values().stream().filter(v -> !v).count());
        
        // 清理已获取的锁
        redisBatchOperations.releaseMultipleLocks(lockKeys);
    }
    
    @Benchmark
    public void zookeeperBatchLockFailureScenario(Blackhole bh) throws Exception {
        List<String> lockKeys = new ArrayList<>();
        for (int i = 0; i < SMALL_BATCH_SIZE; i++) {
            lockKeys.add(BATCH_LOCK_PREFIX + "zk-failure-shared-" + (i % 3));
        }
        
        long startTime = System.nanoTime();
        Map<String, Boolean> results = zkBatchOperations.acquireMultipleLocks(
                lockKeys, BATCH_OPERATION_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
        
        long latency = (System.nanoTime() - startTime) / 1_000_000;
        
        bh.consume(results);
        recordBatchOperation(true, latency,
                (int) results.values().stream().filter(Boolean::booleanValue).count(),
                (int) results.values().stream().filter(v -> !v).count());
        
        zkBatchOperations.releaseMultipleLocks(lockKeys);
    }
    
    // ==================== 批量vs单个锁操作性能对比 ====================
    
    @Benchmark
    public void redisIndividualLocksVsBatchComparison(Blackhole bh) throws Exception {
        List<String> lockKeys = generateBatchLockKeys("redis-comparison", SMALL_BATCH_SIZE);
        
        // 测试单个锁获取（串行）
        long individualStartTime = System.nanoTime();
        Map<String, Boolean> individualResults = new HashMap<>();
        for (String key : lockKeys) {
            DistributedLock lock = redisLockFactory.getLock(key);
            try {
                boolean acquired = lock.tryLock(BATCH_OPERATION_TIMEOUT_MS / lockKeys.size(), 
                        LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
                individualResults.put(key, acquired);
                if (acquired) {
                    lock.unlock();
                }
            } catch (Exception e) {
                individualResults.put(key, false);
            }
        }
        long individualLatency = (System.nanoTime() - individualStartTime) / 1_000_000;
        
        // 恢复锁键为可获取状态
        Thread.sleep(100);
        
        // 测试批量锁获取
        long batchStartTime = System.nanoTime();
        Map<String, Boolean> batchResults = redisBatchOperations.acquireMultipleLocks(
                lockKeys, BATCH_OPERATION_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
        long batchLatency = (System.nanoTime() - batchStartTime) / 1_000_000;
        
        bh.consume(individualResults);
        bh.consume(batchResults);
        
        // 记录性能对比数据
        double individualAvg = individualLatency / (double) lockKeys.size();
        double batchAvg = batchLatency / (double) lockKeys.size();
        double improvement = ((individualAvg - batchAvg) / individualAvg) * 100;
        
        System.out.printf("Redis批量vs单个对比 - 单个锁平均: %.2f ms, 批量锁平均: %.2f ms, 改进: %.2f%%%n",
                individualAvg, batchAvg, improvement);
        
        recordBatchOperation(true, batchLatency,
                (int) batchResults.values().stream().filter(Boolean::booleanValue).count(),
                (int) batchResults.values().stream().filter(v -> !v).count());
        
        redisBatchOperations.releaseMultipleLocks(lockKeys);
    }
    
    @Benchmark
    public void zookeeperIndividualLocksVsBatchComparison(Blackhole bh) throws Exception {
        List<String> lockKeys = generateBatchLockKeys("zk-comparison", SMALL_BATCH_SIZE);
        
        // 单个锁获取
        long individualStartTime = System.nanoTime();
        Map<String, Boolean> individualResults = new HashMap<>();
        for (String key : lockKeys) {
            DistributedLock lock = zookeeperLockFactory.getLock(key);
            try {
                boolean acquired = lock.tryLock(BATCH_OPERATION_TIMEOUT_MS / lockKeys.size(), 
                        LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
                individualResults.put(key, acquired);
                if (acquired) {
                    lock.unlock();
                }
            } catch (Exception e) {
                individualResults.put(key, false);
            }
        }
        long individualLatency = (System.nanoTime() - individualStartTime) / 1_000_000;
        
        Thread.sleep(100);
        
        // 批量锁获取
        long batchStartTime = System.nanoTime();
        Map<String, Boolean> batchResults = zkBatchOperations.acquireMultipleLocks(
                lockKeys, BATCH_OPERATION_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
        long batchLatency = (System.nanoTime() - batchStartTime) / 1_000_000;
        
        bh.consume(individualResults);
        bh.consume(batchResults);
        
        double individualAvg = individualLatency / (double) lockKeys.size();
        double batchAvg = batchLatency / (double) lockKeys.size();
        double improvement = ((individualAvg - batchAvg) / individualAvg) * 100;
        
        System.out.printf("ZooKeeper批量vs单个对比 - 单个锁平均: %.2f ms, 批量锁平均: %.2f ms, 改进: %.2f%%%n",
                individualAvg, batchAvg, improvement);
        
        recordBatchOperation(true, batchLatency,
                (int) batchResults.values().stream().filter(Boolean::booleanValue).count(),
                (int) batchResults.values().stream().filter(v -> !v).count());
        
        zkBatchOperations.releaseMultipleLocks(lockKeys);
    }
}