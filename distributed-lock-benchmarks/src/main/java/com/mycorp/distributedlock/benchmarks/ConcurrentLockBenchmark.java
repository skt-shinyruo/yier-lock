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
import java.util.concurrent.atomic.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import static org.openjdk.jmh.annotations.Mode.*;

/**
 * 并发锁基准测试
 * 
 * 测试场景：
 * 1. 高并发锁获取性能
 * 2. 多线程锁竞争性能
 * 3. 可重入锁嵌套调用性能
 * 4. 锁等待队列性能
 * 5. 死锁检测性能
 * 6. 锁续期在高并发下的性能
 * 7. 不同并发级别下的性能表现
 */
@BenchmarkMode({Throughput, AverageTime, SampleTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
@Fork(2)
@Threads(Threads.MAX)
public class ConcurrentLockBenchmark {
    
    // 性能统计器
    private final LongAdder totalLockAttempts = new LongAdder();
    private final LongAdder successfulLocks = new LongAdder();
    private final LongAdder failedLocks = new LongAdder();
    private final LongAdder totalWaitTime = new LongAdder();
    private final LongAdder totalHoldTime = new LongAdder();
    private final AtomicLong maxWaitTime = new AtomicLong(0);
    private final AtomicLong maxHoldTime = new AtomicLong(0);
    private final AtomicInteger deadlockCount = new AtomicInteger(0);
    
    // 并发统计
    private final AtomicInteger peakConcurrency = new AtomicInteger(0);
    private final List<AtomicInteger> threadLockCounters = new ArrayList<>();
    
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
    private static final String CONCURRENT_LOCK_PREFIX = "concurrent:lock:";
    private static final int LOW_CONCURRENCY = 10;        // 低并发
    private static final int MEDIUM_CONCURRENCY = 50;     // 中并发
    private static final int HIGH_CONCURRENCY = 100;      // 高并发
    private static final int EXTREME_CONCURRENCY = 200;   // 极高并发
    private static final int LOCK_HOLD_TIME_MS = 50;      // 锁持有时间
    private static final int LOCK_TIMEOUT_MS = 2000;      // 锁超时时间
    private static final int REENTRANT_NESTING_DEPTH = 3; // 可重入锁嵌套深度
    private static final int CONCURRENT_TEST_DURATION_MS = 10000; // 并发测试持续时间
    
    @Setup(Level.Trial)
    public void setupTrial() throws Exception {
        System.out.println("设置并发锁测试环境...");
        
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
        executorService = Executors.newFixedThreadPool(EXTREME_CONCURRENCY + 20);
        
        // 初始化线程锁计数器
        for (int i = 0; i < EXTREME_CONCURRENCY; i++) {
            threadLockCounters.add(new AtomicInteger(0));
        }
        
        System.out.println("并发锁测试环境设置完成");
    }
    
    @TearDown(Level.Trial)
    public void tearDownTrial() throws Exception {
        System.out.println("清理并发锁测试环境...");
        
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
        
        printConcurrencyPerformanceSummary();
    }
    
    @Setup(Level.Invocation)
    public void setupInvocation() {
        resetCounters();
    }
    
    private void resetCounters() {
        totalLockAttempts.reset();
        successfulLocks.reset();
        failedLocks.reset();
        totalWaitTime.reset();
        totalHoldTime.reset();
        maxWaitTime.set(0);
        maxHoldTime.set(0);
        deadlockCount.set(0);
        peakConcurrency.set(0);
        
        // 重置线程锁计数器
        threadLockCounters.forEach(AtomicInteger::set);
    }
    
    private void recordLockAttempt(long waitTime, long holdTime, boolean success) {
        totalLockAttempts.increment();
        totalWaitTime.add(waitTime);
        totalHoldTime.add(holdTime);
        
        if (success) {
            successfulLocks.increment();
        } else {
            failedLocks.increment();
        }
        
        if (waitTime > maxWaitTime.get()) {
            maxWaitTime.set(waitTime);
        }
        
        if (holdTime > maxHoldTime.get()) {
            maxHoldTime.set(holdTime);
        }
    }
    
    private void recordThreadLock(int threadId) {
        threadLockCounters.get(threadId).incrementAndGet();
    }
    
    private void updatePeakConcurrency(int currentConcurrency) {
        int currentPeak = peakConcurrency.get();
        while (currentConcurrency > currentPeak) {
            if (peakConcurrency.compareAndSet(currentPeak, currentConcurrency)) {
                break;
            }
            currentPeak = peakConcurrency.get();
        }
    }
    
    private void printConcurrencyPerformanceSummary() {
        long attempts = totalLockAttempts.sum();
        long successes = successfulLocks.sum();
        long failures = failedLocks.sum();
        long totalWait = totalWaitTime.sum();
        long totalHold = totalHoldTime.sum();
        
        System.out.println("\n=== 并发锁性能摘要 ===");
        
        if (attempts > 0) {
            double successRate = (double) successes / attempts * 100;
            double avgWaitTime = (double) totalWait / attempts;
            double avgHoldTime = (double) totalHold / successes;
            double throughput = successes * 1000.0 / Math.max(totalHold, 1);
            
            System.out.printf("总锁请求: %d%n", attempts);
            System.out.printf("成功获取: %d%n", successes);
            System.out.printf("失败获取: %d%n", failures);
            System.out.printf("成功率: %.2f%%%n", successRate);
            System.out.printf("平均等待时间: %.2f ms%n", avgWaitTime);
            System.out.printf("平均持有时间: %.2f ms%n", avgHoldTime);
            System.out.printf("最大等待时间: %d ms%n", maxWaitTime.get());
            System.out.printf("最大持有时间: %d ms%n", maxHoldTime.get());
            System.out.printf("峰值并发数: %d%n", peakConcurrency.get());
            System.out.printf("吞吐量: %.2f ops/sec%n", throughput);
            System.out.printf("死锁检测次数: %d%n", deadlockCount.get());
        }
    }
    
    // ==================== Redis并发锁基准测试 ====================
    
    @Benchmark
    public void redisLowConcurrencyLockTest() throws Exception {
        runConcurrencyTest("redis-low", LOW_CONCURRENCY, redisLockFactory);
    }
    
    @Benchmark
    public void redisMediumConcurrencyLockTest() throws Exception {
        runConcurrencyTest("redis-medium", MEDIUM_CONCURRENCY, redisLockFactory);
    }
    
    @Benchmark
    public void redisHighConcurrencyLockTest() throws Exception {
        runConcurrencyTest("redis-high", HIGH_CONCURRENCY, redisLockFactory);
    }
    
    @Benchmark
    public void redisExtremeConcurrencyLockTest() throws Exception {
        runConcurrencyTest("redis-extreme", EXTREME_CONCURRENCY, redisLockFactory);
    }
    
    @Benchmark
    public void redisReentrantLockTest() throws Exception {
        String lockKey = CONCURRENT_LOCK_PREFIX + "redis-reentrant-" + Thread.currentThread().getId();
        runReentrantLockTest(lockKey, redisLockFactory);
    }
    
    @Benchmark
    public void redisLockNestingPerformance(Blackhole bh) throws Exception {
        String lockKey = CONCURRENT_LOCK_PREFIX + "redis-nesting-" + Thread.currentThread().getId();
        runLockNestingTest(lockKey, redisLockFactory, bh);
    }
    
    // ==================== ZooKeeper并发锁基准测试 ====================
    
    @Benchmark
    public void zookeeperLowConcurrencyLockTest() throws Exception {
        runConcurrencyTest("zk-low", LOW_CONCURRENCY, zookeeperLockFactory);
    }
    
    @Benchmark
    public void zookeeperMediumConcurrencyLockTest() throws Exception {
        runConcurrencyTest("zk-medium", MEDIUM_CONCURRENCY, zookeeperLockFactory);
    }
    
    @Benchmark
    public void zookeeperHighConcurrencyLockTest() throws Exception {
        runConcurrencyTest("zk-high", HIGH_CONCURRENCY, zookeeperLockFactory);
    }
    
    @Benchmark
    public void zookeeperExtremeConcurrencyLockTest() throws Exception {
        runConcurrencyTest("zk-extreme", EXTREME_CONCURRENCY, zookeeperLockFactory);
    }
    
    @Benchmark
    public void zookeeperReentrantLockTest() throws Exception {
        String lockKey = CONCURRENT_LOCK_PREFIX + "zk-reentrant-" + Thread.currentThread().getId();
        runReentrantLockTest(lockKey, zookeeperLockFactory);
    }
    
    @Benchmark
    public void zookeeperLockNestingPerformance(Blackhole bh) throws Exception {
        String lockKey = CONCURRENT_LOCK_PREFIX + "zk-nesting-" + Thread.currentThread().getId();
        runLockNestingTest(lockKey, zookeeperLockFactory, bh);
    }
    
    // ==================== 并发测试核心方法 ====================
    
    private void runConcurrencyTest(String testName, int threadCount, DistributedLockFactory factory) throws Exception {
        String lockKey = CONCURRENT_LOCK_PREFIX + testName + "-" + System.currentTimeMillis();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);
        AtomicInteger activeLocks = new AtomicInteger(0);
        AtomicLong totalActiveTime = new AtomicLong(0);
        
        IntStream.range(0, threadCount).forEach(threadId -> {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    
                    for (int i = 0; i < 5; i++) { // 每个线程执行5次锁操作
                        String threadLockKey = lockKey + "-" + threadId + "-" + i;
                        DistributedLock lock = factory.getLock(threadLockKey);
                        
                        long waitStartTime = System.nanoTime();
                        if (lock.tryLock(LOCK_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS)) {
                            try {
                                long waitTime = System.nanoTime() - waitStartTime;
                                
                                // 记录并发度
                                int currentActive = activeLocks.incrementAndGet();
                                updatePeakConcurrency(currentActive);
                                
                                // 记录锁持有时间
                                long holdStartTime = System.nanoTime();
                                Thread.sleep(LOCK_HOLD_TIME_MS);
                                long holdTime = System.nanoTime() - holdStartTime;
                                
                                // 更新活跃锁统计
                                activeLocks.decrementAndGet();
                                totalActiveTime.addAndGet(holdTime);
                                
                                recordThreadLock(threadId);
                                recordLockAttempt(waitTime / 1_000_000, holdTime / 1_000_000, true);
                                
                            } finally {
                                lock.unlock();
                            }
                        } else {
                            recordLockAttempt(0, 0, false);
                        }
                        
                        // 短暂间隔
                        Thread.sleep(10);
                    }
                } catch (Exception e) {
                    deadlockCount.incrementAndGet();
                } finally {
                    completeLatch.countDown();
                }
            });
        });
        
        startLatch.countDown();
        completeLatch.await(60, TimeUnit.SECONDS);
    }
    
    private void runReentrantLockTest(String lockKey, DistributedLockFactory factory) throws Exception {
        DistributedLock lock = factory.getLock(lockKey);
        
        long startTime = System.nanoTime();
        boolean acquired = lock.tryLock(LOCK_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS);
        
        if (acquired) {
            try {
                // 嵌套调用测试
                performNestedLockOperation(lock, REENTRANT_NESTING_DEPTH);
                
                long holdTime = System.nanoTime() - startTime;
                recordLockAttempt(0, holdTime / 1_000_000, true);
            } finally {
                lock.unlock();
            }
        } else {
            recordLockAttempt(0, 0, false);
        }
    }
    
    private void performNestedLockOperation(DistributedLock lock, int depth) throws Exception {
        if (depth <= 0) {
            // 模拟业务操作
            Thread.sleep(10);
            return;
        }
        
        if (lock.tryLock(500, 200, TimeUnit.MILLISECONDS)) {
            try {
                performNestedLockOperation(lock, depth - 1);
            } finally {
                lock.unlock();
            }
        }
    }
    
    private void runLockNestingTest(String lockKey, DistributedLockFactory factory, Blackhole bh) throws Exception {
        DistributedLock lock = factory.getLock(lockKey);
        
        long startTime = System.nanoTime();
        long totalNestedTime = 0;
        
        if (lock.tryLock(LOCK_TIMEOUT_MS, LOCK_HOLD_TIME_MS, TimeUnit.MILLISECONDS)) {
            try {
                for (int i = 0; i < REENTRANT_NESTING_DEPTH; i++) {
                    long nestedStart = System.nanoTime();
                    
                    // 模拟嵌套的锁操作
                    if (i % 2 == 0) {
                        // 偶数层：获取子锁
                        DistributedLock childLock = factory.getLock(lockKey + "-child-" + i);
                        if (childLock.tryLock(100, 50, TimeUnit.MILLISECONDS)) {
                            try {
                                Thread.sleep(20);
                            } finally {
                                childLock.unlock();
                            }
                        }
                    } else {
                        // 奇数层：业务处理
                        Thread.sleep(10);
                    }
                    
                    totalNestedTime += System.nanoTime() - nestedStart;
                }
                
                bh.consume(totalNestedTime);
                recordLockAttempt(0, totalNestedTime / 1_000_000, true);
            } finally {
                lock.unlock();
            }
        } else {
            bh.consume(0L);
            recordLockAttempt(0, 0, false);
        }
    }
    
    // ==================== 死锁检测测试 ====================
    
    @Benchmark
    public void redisDeadlockDetectionTest() throws Exception {
        // 创建可能导致死锁的场景
        String lockKey1 = CONCURRENT_LOCK_PREFIX + "redis-deadlock-1";
        String lockKey2 = CONCURRENT_LOCK_PREFIX + "redis-deadlock-2";
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(2);
        AtomicInteger deadlockDetected = new AtomicInteger(0);
        
        // 线程1：先获取lockKey1，然后获取lockKey2
        executorService.submit(() -> {
            try {
                startLatch.await();
                
                DistributedLock lock1 = redisLockFactory.getLock(lockKey1);
                DistributedLock lock2 = redisLockFactory.getLock(lockKey2);
                
                if (lock1.tryLock(1000, 500, TimeUnit.MILLISECONDS)) {
                    try {
                        Thread.sleep(100); // 让线程2有机会获取lockKey1
                        
                        if (lock2.tryLock(1000, 500, TimeUnit.MILLISECONDS)) {
                            try {
                                Thread.sleep(100);
                            } finally {
                                lock2.unlock();
                            }
                        }
                    } finally {
                        lock1.unlock();
                    }
                }
            } catch (Exception e) {
                deadlockDetected.incrementAndGet();
            } finally {
                completeLatch.countDown();
            }
        });
        
        // 线程2：先获取lockKey2，然后获取lockKey1（形成死锁环）
        executorService.submit(() -> {
            try {
                startLatch.await();
                
                DistributedLock lock1 = redisLockFactory.getLock(lockKey1);
                DistributedLock lock2 = redisLockFactory.getLock(lockKey2);
                
                if (lock2.tryLock(1000, 500, TimeUnit.MILLISECONDS)) {
                    try {
                        Thread.sleep(100); // 让线程1有机会获取lockKey2
                        
                        if (lock1.tryLock(1000, 500, TimeUnit.MILLISECONDS)) {
                            try {
                                Thread.sleep(100);
                            } finally {
                                lock1.unlock();
                            }
                        }
                    } finally {
                        lock2.unlock();
                    }
                }
            } catch (Exception e) {
                deadlockDetected.incrementAndGet();
            } finally {
                completeLatch.countDown();
            }
        });
        
        startLatch.countDown();
        completeLatch.await(10, TimeUnit.SECONDS);
        
        deadlockCount.addAndGet(deadlockDetected.get());
    }
    
    @Benchmark
    public void zookeeperDeadlockDetectionTest() throws Exception {
        String lockKey1 = CONCURRENT_LOCK_PREFIX + "zk-deadlock-1";
        String lockKey2 = CONCURRENT_LOCK_PREFIX + "zk-deadlock-2";
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(2);
        AtomicInteger deadlockDetected = new AtomicInteger(0);
        
        executorService.submit(() -> {
            try {
                startLatch.await();
                
                DistributedLock lock1 = zookeeperLockFactory.getLock(lockKey1);
                DistributedLock lock2 = zookeeperLockFactory.getLock(lockKey2);
                
                if (lock1.tryLock(1000, 500, TimeUnit.MILLISECONDS)) {
                    try {
                        Thread.sleep(100);
                        
                        if (lock2.tryLock(1000, 500, TimeUnit.MILLISECONDS)) {
                            try {
                                Thread.sleep(100);
                            } finally {
                                lock2.unlock();
                            }
                        }
                    } finally {
                        lock1.unlock();
                    }
                }
            } catch (Exception e) {
                deadlockDetected.incrementAndGet();
            } finally {
                completeLatch.countDown();
            }
        });
        
        executorService.submit(() -> {
            try {
                startLatch.await();
                
                DistributedLock lock1 = zookeeperLockFactory.getLock(lockKey1);
                DistributedLock lock2 = zookeeperLockFactory.getLock(lockKey2);
                
                if (lock2.tryLock(1000, 500, TimeUnit.MILLISECONDS)) {
                    try {
                        Thread.sleep(100);
                        
                        if (lock1.tryLock(1000, 500, TimeUnit.MILLISECONDS)) {
                            try {
                                Thread.sleep(100);
                            } finally {
                                lock1.unlock();
                            }
                        }
                    } finally {
                        lock2.unlock();
                    }
                }
            } catch (Exception e) {
                deadlockDetected.incrementAndGet();
            } finally {
                completeLatch.countDown();
            }
        });
        
        startLatch.countDown();
        completeLatch.await(10, TimeUnit.SECONDS);
        
        deadlockCount.addAndGet(deadlockDetected.get());
    }
    
    // ==================== 锁续期并发测试 ====================
    
    @Benchmark
    public void redisConcurrentLockRenewalTest() throws Exception {
        String lockKey = CONCURRENT_LOCK_PREFIX + "redis-renewal-concurrent-" + System.currentTimeMillis();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(MEDIUM_CONCURRENCY);
        AtomicInteger renewalSuccess = new AtomicInteger(0);
        AtomicInteger renewalFailure = new AtomicInteger(0);
        
        IntStream.range(0, MEDIUM_CONCURRENCY).forEach(i -> {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    
                    DistributedLock lock = redisLockFactory.getLock(lockKey + "-" + i);
                    
                    if (lock.tryLock(LOCK_TIMEOUT_MS, LOCK_HOLD_TIME_MS * 2, TimeUnit.MILLISECONDS)) {
                        try {
                            // 在持有期间进行续期操作
                            for (int j = 0; j < 3; j++) {
                                Thread.sleep(100);
                                if (lock.renewLease()) {
                                    renewalSuccess.incrementAndGet();
                                } else {
                                    renewalFailure.incrementAndGet();
                                }
                            }
                            
                            recordLockAttempt(0, LOCK_HOLD_TIME_MS * 2, true);
                        } finally {
                            lock.unlock();
                        }
                    } else {
                        recordLockAttempt(0, 0, false);
                    }
                } catch (Exception e) {
                    renewalFailure.incrementAndGet();
                } finally {
                    completeLatch.countDown();
                }
            });
        });
        
        startLatch.countDown();
        completeLatch.await(30, TimeUnit.SECONDS);
        
        System.out.printf("Redis并发续期测试 - 成功: %d, 失败: %d%n", 
                renewalSuccess.get(), renewalFailure.get());
    }
    
    @Benchmark
    public void zookeeperConcurrentLockRenewalTest() throws Exception {
        String lockKey = CONCURRENT_LOCK_PREFIX + "zk-renewal-concurrent-" + System.currentTimeMillis();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(MEDIUM_CONCURRENCY);
        AtomicInteger renewalSuccess = new AtomicInteger(0);
        AtomicInteger renewalFailure = new AtomicInteger(0);
        
        IntStream.range(0, MEDIUM_CONCURRENCY).forEach(i -> {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    
                    DistributedLock lock = zookeeperLockFactory.getLock(lockKey + "-" + i);
                    
                    if (lock.tryLock(LOCK_TIMEOUT_MS, LOCK_HOLD_TIME_MS * 2, TimeUnit.MILLISECONDS)) {
                        try {
                            for (int j = 0; j < 3; j++) {
                                Thread.sleep(100);
                                if (lock.renewLease()) {
                                    renewalSuccess.incrementAndGet();
                                } else {
                                    renewalFailure.incrementAndGet();
                                }
                            }
                            
                            recordLockAttempt(0, LOCK_HOLD_TIME_MS * 2, true);
                        } finally {
                            lock.unlock();
                        }
                    } else {
                        recordLockAttempt(0, 0, false);
                    }
                } catch (Exception e) {
                    renewalFailure.incrementAndGet();
                } finally {
                    completeLatch.countDown();
                }
            });
        });
        
        startLatch.countDown();
        completeLatch.await(30, TimeUnit.SECONDS);
        
        System.out.printf("ZooKeeper并发续期测试 - 成功: %d, 失败: %d%n", 
                renewalSuccess.get(), renewalFailure.get());
    }
}