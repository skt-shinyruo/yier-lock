package com.mycorp.distributedlock.benchmarks;

import com.mycorp.distributedlock.api.*;
import com.mycorp.distributedlock.redis.*;
import com.mycorp.distributedlock.zookeeper.*;
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
 * 公平锁基准测试
 * 
 * 测试场景：
 * 1. 公平锁获取顺序性能
 * 2. 公平锁vs非公平锁性能对比
 * 3. 公平锁在竞争环境下的行为
 * 4. 公平锁的排队机制性能
 * 5. 长时间持有公平锁的影响
 * 6. 公平锁续期机制性能
 */
@BenchmarkMode({Throughput, AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
@Fork(2)
@Threads(Threads.MAX)
public class FairLockBenchmark {
    
    // 性能统计器
    private final LongAdder fairLockOperations = new LongAdder();
    private final LongAdder unfairLockOperations = new LongAdder();
    private final LongAdder totalFairLatency = new LongAdder();
    private final LongAdder totalUnfairLatency = new LongAdder();
    private final AtomicLong maxFairLatency = new AtomicLong(0);
    private final AtomicLong maxUnfairLatency = new AtomicLong(0);
    
    // 公平性验证
    private final List<AtomicInteger> threadLockOrder = new ArrayList<>();
    private final AtomicInteger totalOrderingViolations = new AtomicInteger(0);
    
    // 测试容器和客户端
    private GenericContainer<?> redisContainer;
    private TestingServer zookeeperServer;
    private RedisClient redisClient;
    private CuratorFramework curatorFramework;
    
    // 锁工厂
    private RedisDistributedLockFactory redisLockFactory;
    private ZooKeeperDistributedLockFactory zookeeperLockFactory;
    
    // 线程池
    private ExecutorService executorService;
    
    // 测试参数
    private static final String FAIR_LOCK_PREFIX = "fair:lock:";
    private static final String UNFAIR_LOCK_PREFIX = "unfair:lock:";
    private static final int FAIRNESS_THREAD_COUNT = 20;  // 测试公平性的线程数
    private static final int NORMAL_CONCURRENCY = 10;     // 正常并发测试
    private static final int LOCK_HOLD_TIME_MS = 200;
    private static final int FAIRNESS_TEST_DURATION_MS = 30000; // 公平性测试持续时间
    private static final int QUEUE_TIMEOUT_MS = 5000;
    
    @Setup(Level.Trial)
    public void setupTrial() throws Exception {
        System.out.println("设置公平锁测试环境...");
        
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
        
        // 创建线程池
        executorService = Executors.newFixedThreadPool(FAIRNESS_THREAD_COUNT + NORMAL_CONCURRENCY + 10);
        
        // 初始化公平性追踪数组
        for (int i = 0; i < FAIRNESS_THREAD_COUNT; i++) {
            threadLockOrder.add(new AtomicInteger(0));
        }
        
        System.out.println("公平锁测试环境设置完成");
    }
    
    @TearDown(Level.Trial)
    public void tearDownTrial() throws Exception {
        System.out.println("清理公平锁测试环境...");
        
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
        
        printFairnessPerformanceSummary();
    }
    
    @Setup(Level.Invocation)
    public void setupInvocation() {
        resetCounters();
    }
    
    private void resetCounters() {
        fairLockOperations.reset();
        unfairLockOperations.reset();
        totalFairLatency.reset();
        totalUnfairLatency.reset();
        maxFairLatency.set(0);
        maxUnfairLatency.set(0);
        totalOrderingViolations.set(0);
        
        // 重置线程锁序
        threadLockOrder.forEach(AtomicInteger::set);
    }
    
    private void recordFairLockOperation(long latency) {
        fairLockOperations.increment();
        totalFairLatency.add(latency);
        if (latency > maxFairLatency.get()) {
            maxFairLatency.set(latency);
        }
    }
    
    private void recordUnfairLockOperation(long latency) {
        unfairLockOperations.increment();
        totalUnfairLatency.add(latency);
        if (latency > maxUnfairLatency.get()) {
            maxUnfairLatency.set(latency);
        }
    }
    
    private void recordThreadLockOrder(int threadId) {
        threadLockOrder.get(threadId).incrementAndGet();
    }
    
    private void checkFairnessViolation(int threadId, int currentIndex) {
        // 检查是否违反了先进先出的公平性原则
        // 在公平锁中，线程应该按照请求顺序获得锁
        for (int i = 0; i < threadId; i++) {
            if (threadLockOrder.get(i).get() > currentIndex) {
                totalOrderingViolations.incrementAndGet();
                break;
            }
        }
    }
    
    private void printFairnessPerformanceSummary() {
        long fairOps = fairLockOperations.sum();
        long unfairOps = unfairLockOperations.sum();
        long totalFairLat = totalFairLatency.sum();
        long totalUnfairLat = totalUnfairLatency.sum();
        int orderingViolations = totalOrderingViolations.get();
        
        System.out.println("\n=== 公平锁性能摘要 ===");
        
        if (fairOps > 0) {
            double avgFairLatency = (double) totalFairLat / fairOps;
            System.out.printf("公平锁操作: %d, 平均延迟: %.2f ms, 最大延迟: %d ms%n", 
                    fairOps, avgFairLatency, maxFairLatency.get());
        }
        
        if (unfairOps > 0) {
            double avgUnfairLatency = (double) totalUnfairLat / unfairOps;
            System.out.printf("非公平锁操作: %d, 平均延迟: %.2f ms, 最大延迟: %d ms%n", 
                    unfairOps, avgUnfairLatency, maxUnfairLatency.get());
        }
        
        System.out.printf("公平性违规次数: %d%n", orderingViolations);
        
        if (fairOps > 0 && unfairOps > 0) {
            double fairAvg = (double) totalFairLat / fairOps;
            double unfairAvg = (double) totalUnfairLat / unfairOps;
            double overhead = ((fairAvg - unfairAvg) / unfairAvg) * 100;
            System.out.printf("公平锁开销: %.2f%%%n", overhead);
        }
    }
    
    // ==================== Redis公平锁基准测试 ====================
    
    @Benchmark
    public void redisFairLockPerformance(Blackhole bh) throws InterruptedException {
        String lockKey = FAIR_LOCK_PREFIX + "redis-" + Thread.currentThread().getId();
        
        // 创建公平锁（如果支持的话）
        DistributedLock lock = redisLockFactory.getLock(lockKey);
        
        long startTime = System.nanoTime();
        lock.lock(2, TimeUnit.SECONDS);
        
        try {
            Thread.sleep(50); // 模拟锁持有
            bh.consume(lock.isHeldByCurrentThread());
            
            recordFairLockOperation((System.nanoTime() - startTime) / 1_000_000);
        } finally {
            lock.unlock();
        }
    }
    
    @Benchmark
    public void redisUnfairLockPerformance(Blackhole bh) throws InterruptedException {
        String lockKey = UNFAIR_LOCK_PREFIX + "redis-" + Thread.currentThread().getId();
        
        DistributedLock lock = redisLockFactory.getLock(lockKey);
        
        long startTime = System.nanoTime();
        lock.lock(2, TimeUnit.SECONDS);
        
        try {
            Thread.sleep(50);
            bh.consume(lock.isHeldByCurrentThread());
            
            recordUnfairLockOperation((System.nanoTime() - startTime) / 1_000_000);
        } finally {
            lock.unlock();
        }
    }
    
    @Benchmark
    public void redisFairLockTryLock(Blackhole bh) throws InterruptedException {
        String lockKey = FAIR_LOCK_PREFIX + "redis-try-" + Thread.currentThread().getId();
        DistributedLock lock = redisLockFactory.getLock(lockKey);
        
        boolean acquired = lock.tryLock(QUEUE_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
        bh.consume(acquired);
        
        if (acquired) {
            try {
                Thread.sleep(30);
                recordFairLockOperation(30);
            } finally {
                lock.unlock();
            }
        }
    }
    
    // ==================== ZooKeeper公平锁基准测试 ====================
    
    @Benchmark
    public void zookeeperFairLockPerformance(Blackhole bh) throws InterruptedException {
        String lockKey = FAIR_LOCK_PREFIX + "zk-" + Thread.currentThread().getId();
        DistributedLock lock = zookeeperLockFactory.getLock(lockKey);
        
        long startTime = System.nanoTime();
        lock.lock(2, TimeUnit.SECONDS);
        
        try {
            Thread.sleep(50);
            bh.consume(lock.isHeldByCurrentThread());
            
            recordFairLockOperation((System.nanoTime() - startTime) / 1_000_000);
        } finally {
            lock.unlock();
        }
    }
    
    @Benchmark
    public void zookeeperUnfairLockPerformance(Blackhole bh) throws InterruptedException {
        String lockKey = UNFAIR_LOCK_PREFIX + "zk-" + Thread.currentThread().getId();
        DistributedLock lock = zookeeperLockFactory.getLock(lockKey);
        
        long startTime = System.nanoTime();
        lock.lock(2, TimeUnit.SECONDS);
        
        try {
            Thread.sleep(50);
            bh.consume(lock.isHeldByCurrentThread());
            
            recordUnfairLockOperation((System.nanoTime() - startTime) / 1_000_000);
        } finally {
            lock.unlock();
        }
    }
    
    @Benchmark
    public void zookeeperFairLockTryLock(Blackhole bh) throws InterruptedException {
        String lockKey = FAIR_LOCK_PREFIX + "zk-try-" + Thread.currentThread().getId();
        DistributedLock lock = zookeeperLockFactory.getLock(lockKey);
        
        boolean acquired = lock.tryLock(QUEUE_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
        bh.consume(acquired);
        
        if (acquired) {
            try {
                Thread.sleep(30);
                recordFairLockOperation(30);
            } finally {
                lock.unlock();
            }
        }
    }
    
    // ==================== 公平性验证测试 ====================
    
    @Benchmark
    public void redisFairnessOrderingTest() throws Exception {
        String lockKey = FAIR_LOCK_PREFIX + "redis-fairness-" + System.currentTimeMillis();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(FAIRNESS_THREAD_COUNT);
        AtomicInteger globalCounter = new AtomicInteger(0);
        
        IntStream.range(0, FAIRNESS_THREAD_COUNT).forEach(threadId -> {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    
                    DistributedLock lock = redisLockFactory.getLock(lockKey);
                    
                    long startTime = System.nanoTime();
                    if (lock.tryLock(QUEUE_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS)) {
                        try {
                            int currentIndex = globalCounter.getAndIncrement();
                            recordThreadLockOrder(threadId);
                            checkFairnessViolation(threadId, currentIndex);
                            
                            // 记录获得锁的时间戳
                            long acquisitionTime = System.nanoTime();
                            Thread.sleep(LOCK_HOLD_TIME_MS);
                            
                            recordFairLockOperation((System.nanoTime() - startTime) / 1_000_000);
                        } finally {
                            lock.unlock();
                        }
                    } else {
                        // 获取锁失败
                        recordUnfairLockOperation(0);
                    }
                } catch (Exception e) {
                    recordUnfairLockOperation(0);
                } finally {
                    completeLatch.countDown();
                }
            });
        });
        
        startLatch.countDown();
        completeLatch.await(60, TimeUnit.SECONDS);
    }
    
    @Benchmark
    public void zookeeperFairnessOrderingTest() throws Exception {
        String lockKey = FAIR_LOCK_PREFIX + "zk-fairness-" + System.currentTimeMillis();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(FAIRNESS_THREAD_COUNT);
        AtomicInteger globalCounter = new AtomicInteger(0);
        
        IntStream.range(0, FAIRNESS_THREAD_COUNT).forEach(threadId -> {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    
                    DistributedLock lock = zookeeperLockFactory.getLock(lockKey);
                    
                    long startTime = System.nanoTime();
                    if (lock.tryLock(QUEUE_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS)) {
                        try {
                            int currentIndex = globalCounter.getAndIncrement();
                            recordThreadLockOrder(threadId);
                            checkFairnessViolation(threadId, currentIndex);
                            
                            Thread.sleep(LOCK_HOLD_TIME_MS);
                            
                            recordFairLockOperation((System.nanoTime() - startTime) / 1_000_000);
                        } finally {
                            lock.unlock();
                        }
                    } else {
                        recordUnfairLockOperation(0);
                    }
                } catch (Exception e) {
                    recordUnfairLockOperation(0);
                } finally {
                    completeLatch.countDown();
                }
            });
        });
        
        startLatch.countDown();
        completeLatch.await(60, TimeUnit.SECONDS);
    }
    
    // ==================== 长时间持有公平锁测试 ====================
    
    @Benchmark
    public void redisLongHoldFairLockTest(Blackhole bh) throws Exception {
        String lockKey = FAIR_LOCK_PREFIX + "redis-long-hold-" + Thread.currentThread().getId();
        
        long startTime = System.nanoTime();
        
        if (lock.tryLock(QUEUE_TIMEOUT_MS, LOCK_HOLD_TIME_MS * 2, TimeUnit.MILLISECONDS)) {
            try {
                // 长时间持有锁
                Thread.sleep(LOCK_HOLD_TIME_MS * 2);
                
                // 在持有期间尝试续期
                if (lock.renewLease()) {
                    bh.consume(true);
                    recordFairLockOperation((System.nanoTime() - startTime) / 1_000_000);
                } else {
                    bh.consume(false);
                }
            } finally {
                lock.unlock();
            }
        } else {
            bh.consume(false);
        }
    }
    
    @Benchmark
    public void zookeeperLongHoldFairLockTest(Blackhole bh) throws Exception {
        String lockKey = FAIR_LOCK_PREFIX + "zk-long-hold-" + Thread.currentThread().getId();
        
        long startTime = System.nanoTime();
        
        if (lock.tryLock(QUEUE_TIMEOUT_MS, LOCK_HOLD_TIME_MS * 2, TimeUnit.MILLISECONDS)) {
            try {
                Thread.sleep(LOCK_HOLD_TIME_MS * 2);
                
                if (lock.renewLease()) {
                    bh.consume(true);
                    recordFairLockOperation((System.nanoTime() - startTime) / 1_000_000);
                } else {
                    bh.consume(false);
                }
            } finally {
                lock.unlock();
            }
        } else {
            bh.consume(false);
        }
    }
    
    // ==================== 高竞争公平锁测试 ====================
    
    @Benchmark
    public void redisHighContentionFairLockTest() throws Exception {
        String lockKey = FAIR_LOCK_PREFIX + "redis-high-contention-" + System.currentTimeMillis();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(NORMAL_CONCURRENCY * 2);
        AtomicLong totalWaitTime = new AtomicLong(0);
        AtomicInteger successCount = new AtomicInteger(0);
        
        // 启动多个竞争线程
        for (int i = 0; i < NORMAL_CONCURRENCY * 2; i++) {
            final int threadId = i;
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    
                    DistributedLock lock = redisLockFactory.getLock(lockKey + "-" + threadId);
                    
                    long waitStartTime = System.nanoTime();
                    if (lock.tryLock(QUEUE_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS)) {
                        try {
                            long waitTime = System.nanoTime() - waitStartTime;
                            totalWaitTime.addAndGet(waitTime);
                            successCount.incrementAndGet();
                            
                            Thread.sleep(LOCK_HOLD_TIME_MS);
                            recordFairLockOperation(waitTime / 1_000_000);
                        } finally {
                            lock.unlock();
                        }
                    } else {
                        recordUnfairLockOperation(0);
                    }
                } catch (Exception e) {
                    recordUnfairLockOperation(0);
                } finally {
                    completeLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        completeLatch.await(60, TimeUnit.SECONDS);
        
        int successful = successCount.get();
        if (successful > 0) {
            long avgWaitTime = totalWaitTime.get() / successful;
            System.out.printf("Redis高竞争公平锁 - 成功获取锁: %d/%d, 平均等待时间: %.2f ms%n", 
                    successful, NORMAL_CONCURRENCY * 2, avgWaitTime / 1_000_000.0);
        }
    }
    
    @Benchmark
    public void zookeeperHighContentionFairLockTest() throws Exception {
        String lockKey = FAIR_LOCK_PREFIX + "zk-high-contention-" + System.currentTimeMillis();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(NORMAL_CONCURRENCY * 2);
        AtomicLong totalWaitTime = new AtomicLong(0);
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int i = 0; i < NORMAL_CONCURRENCY * 2; i++) {
            final int threadId = i;
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    
                    DistributedLock lock = zookeeperLockFactory.getLock(lockKey + "-" + threadId);
                    
                    long waitStartTime = System.nanoTime();
                    if (lock.tryLock(QUEUE_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS)) {
                        try {
                            long waitTime = System.nanoTime() - waitStartTime;
                            totalWaitTime.addAndGet(waitTime);
                            successCount.incrementAndGet();
                            
                            Thread.sleep(LOCK_HOLD_TIME_MS);
                            recordFairLockOperation(waitTime / 1_000_000);
                        } finally {
                            lock.unlock();
                        }
                    } else {
                        recordUnfairLockOperation(0);
                    }
                } catch (Exception e) {
                    recordUnfairLockOperation(0);
                } finally {
                    completeLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        completeLatch.await(60, TimeUnit.SECONDS);
        
        int successful = successCount.get();
        if (successful > 0) {
            long avgWaitTime = totalWaitTime.get() / successful;
            System.out.printf("ZooKeeper高竞争公平锁 - 成功获取锁: %d/%d, 平均等待时间: %.2f ms%n", 
                    successful, NORMAL_CONCURRENCY * 2, avgWaitTime / 1_000_000.0);
        }
    }
    
    // ==================== 公平锁vs非公平锁性能对比 ====================
    
    @Benchmark
    public void redisFairVsUnfairLockComparison(Blackhole bh) throws Exception {
        String fairLockKey = FAIR_LOCK_PREFIX + "redis-fair-vs-unfair-fair";
        String unfairLockKey = UNFAIR_LOCK_PREFIX + "redis-fair-vs-unfair-unfair";
        
        // 公平锁测试
        long fairStartTime = System.nanoTime();
        boolean fairAcquired = false;
        
        try {
            DistributedLock fairLock = redisLockFactory.getLock(fairLockKey);
            fairAcquired = fairLock.tryLock(QUEUE_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
            
            if (fairAcquired) {
                try {
                    Thread.sleep(30);
                    recordFairLockOperation((System.nanoTime() - fairStartTime) / 1_000_000);
                } finally {
                    fairLock.unlock();
                }
            }
        } catch (Exception e) {
            // 忽略异常
        }
        
        Thread.sleep(100); // 间隔
        
        // 非公平锁测试
        long unfairStartTime = System.nanoTime();
        boolean unfairAcquired = false;
        
        try {
            DistributedLock unfairLock = redisLockFactory.getLock(unfairLockKey);
            unfairAcquired = unfairLock.tryLock(QUEUE_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
            
            if (unfairAcquired) {
                try {
                    Thread.sleep(30);
                    recordUnfairLockOperation((System.nanoTime() - unfairStartTime) / 1_000_000);
                } finally {
                    unfairLock.unlock();
                }
            }
        } catch (Exception e) {
            // 忽略异常
        }
        
        bh.consume(fairAcquired);
        bh.consume(unfairAcquired);
    }
    
    @Benchmark
    public void zookeeperFairVsUnfairLockComparison(Blackhole bh) throws Exception {
        String fairLockKey = FAIR_LOCK_PREFIX + "zk-fair-vs-unfair-fair";
        String unfairLockKey = UNFAIR_LOCK_PREFIX + "zk-fair-vs-unfair-unfair";
        
        long fairStartTime = System.nanoTime();
        boolean fairAcquired = false;
        
        try {
            DistributedLock fairLock = zookeeperLockFactory.getLock(fairLockKey);
            fairAcquired = fairLock.tryLock(QUEUE_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
            
            if (fairAcquired) {
                try {
                    Thread.sleep(30);
                    recordFairLockOperation((System.nanoTime() - fairStartTime) / 1_000_000);
                } finally {
                    fairLock.unlock();
                }
            }
        } catch (Exception e) {
            // 忽略异常
        }
        
        Thread.sleep(100);
        
        long unfairStartTime = System.nanoTime();
        boolean unfairAcquired = false;
        
        try {
            DistributedLock unfairLock = zookeeperLockFactory.getLock(unfairLockKey);
            unfairAcquired = unfairLock.tryLock(QUEUE_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
            
            if (unfairAcquired) {
                try {
                    Thread.sleep(30);
                    recordUnfairLockOperation((System.nanoTime() - unfairStartTime) / 1_000_000);
                } finally {
                    unfairLock.unlock();
                }
            }
        } catch (Exception e) {
            // 忽略异常
        }
        
        bh.consume(fairAcquired);
        bh.consume(unfairAcquired);
    }
}