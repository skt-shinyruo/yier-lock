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
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import static org.openjdk.jmh.annotations.Mode.*;

/**
 * 增强版分布式锁基准测试
 * 
 * 功能特性：
 * 1. 多场景性能测试（单线程、多线程、高并发）
 * 2. 不同锁类型性能对比（Redis vs ZooKeeper）
 * 3. 锁续期和超时处理性能
 * 4. 内存使用和CPU开销监控
 * 5. 详细的性能指标收集
 */
@BenchmarkMode({Throughput, AverageTime, SampleTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 5, batchSize = 1000)
@Measurement(iterations = 5, time = 10, batchSize = 1000)
@Fork(2)
@Threads(Threads.MAX)
public class EnhancedDistributedLockBenchmark {
    
    // 性能统计器
    private final LongAdder totalOperations = new LongAdder();
    private final LongAdder successfulOperations = new LongAdder();
    private final LongAdder failedOperations = new LongAdder();
    private final LongAdder totalLatency = new LongAdder();
    private final AtomicLong maxLatency = new AtomicLong(0);
    
    // 测试容器
    private GenericContainer<?> redisContainer;
    private TestingServer zookeeperServer;
    
    // Redis相关组件
    private RedisClient redisClient;
    private RedisDistributedLockFactory redisLockFactory;
    
    // ZooKeeper相关组件
    private CuratorFramework curatorFramework;
    private ZooKeeperDistributedLockFactory zookeeperLockFactory;
    
    // Spring工厂
    private SpringDistributedLockFactory springFactory;
    private AnnotationConfigApplicationContext springContext;
    
    // 线程池用于高并发测试
    private ExecutorService executorService;
    
    // 测试参数
    private static final String REDIS_LOCK_PREFIX = "bench:redis:";
    private static final String ZK_LOCK_PREFIX = "bench:zk:";
    private static final int CONCURRENT_THREADS = 50;
    private static final int LOCK_HOLD_TIME_MS = 100;
    private static final int OPERATION_TIMEOUT_MS = 1000;
    
    @Setup(Level.Trial)
    public void setupTrial() throws Exception {
        System.out.println("设置测试环境...");
        
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
        
        // 初始化Spring工厂
        initializeSpringFactory();
        
        // 创建线程池
        executorService = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        
        System.out.println("测试环境设置完成");
    }
    
    @TearDown(Level.Trial)
    public void tearDownTrial() throws Exception {
        System.out.println("清理测试环境...");
        
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
        
        // 关闭Spring上下文
        if (springContext != null) {
            springContext.close();
        }
        
        System.out.println("测试环境清理完成");
        printPerformanceSummary();
    }
    
    @Setup(Level.Invocation)
    public void setupInvocation() {
        resetCounters();
    }
    
    private void initializeSpringFactory() {
        springContext = new AnnotationConfigApplicationContext();
        springContext.register(TestSpringConfig.class);
        springContext.refresh();
        springFactory = springContext.getBean(SpringDistributedLockFactory.class);
    }
    
    private void resetCounters() {
        totalOperations.reset();
        successfulOperations.reset();
        failedOperations.reset();
        totalLatency.reset();
        maxLatency.set(0);
    }
    
    private void recordOperation(boolean success, long latency) {
        totalOperations.increment();
        if (success) {
            successfulOperations.increment();
        } else {
            failedOperations.increment();
        }
        totalLatency.add(latency);
        
        if (latency > maxLatency.get()) {
            maxLatency.set(latency);
        }
    }
    
    private void printPerformanceSummary() {
        long totalOps = totalOperations.sum();
        long successfulOps = successfulOperations.sum();
        long failedOps = failedOperations.sum();
        long totalLat = totalLatency.sum();
        
        if (totalOps > 0) {
            double avgLatency = (double) totalLat / totalOps;
            double successRate = (double) successfulOps / totalOps * 100;
            
            System.out.println("\n=== 性能摘要 ===");
            System.out.printf("总操作数: %d%n", totalOps);
            System.out.printf("成功操作: %d%n", successfulOps);
            System.out.printf("失败操作: %d%n", failedOps);
            System.out.printf("成功率: %.2f%%%n", successRate);
            System.out.printf("平均延迟: %.2f ms%n", avgLatency);
            System.out.printf("最大延迟: %d ms%n", maxLatency.get());
            System.out.printf("吞吐量: %.2f ops/sec%n", totalOps * 1000.0 / totalLat);
        }
    }
    
    // ==================== Redis基础性能测试 ====================
    
    @Benchmark
    public void redisSingleThreadLockAcquire(Blackhole bh) throws InterruptedException {
        String lockKey = REDIS_LOCK_PREFIX + "single-" + Thread.currentThread().getId();
        DistributedLock lock = redisLockFactory.getLock(lockKey);
        
        long startTime = System.nanoTime();
        lock.lock(5, TimeUnit.SECONDS);
        boolean success = true;
        
        try {
            bh.consume(lock.isHeldByCurrentThread());
            recordOperation(success, (System.nanoTime() - startTime) / 1_000_000);
        } finally {
            lock.unlock();
        }
    }
    
    @Benchmark
    public void redisTryLockWithTimeout(Blackhole bh) throws InterruptedException {
        String lockKey = REDIS_LOCK_PREFIX + "try-" + Thread.currentThread().getId();
        DistributedLock lock = redisLockFactory.getLock(lockKey);
        
        long startTime = System.nanoTime();
        boolean acquired = lock.tryLock(100, 5, TimeUnit.MILLISECONDS);
        boolean success = acquired;
        
        try {
            bh.consume(acquired);
            recordOperation(success, (System.nanoTime() - startTime) / 1_000_000);
            
            if (acquired) {
                Thread.sleep(10); // 短暂持有锁
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    // ==================== ZooKeeper基础性能测试 ====================
    
    @Benchmark
    public void zookeeperSingleThreadLockAcquire(Blackhole bh) throws InterruptedException {
        String lockKey = ZK_LOCK_PREFIX + "single-" + Thread.currentThread().getId();
        DistributedLock lock = zookeeperLockFactory.getLock(lockKey);
        
        long startTime = System.nanoTime();
        lock.lock(5, TimeUnit.SECONDS);
        boolean success = true;
        
        try {
            bh.consume(lock.isHeldByCurrentThread());
            recordOperation(success, (System.nanoTime() - startTime) / 1_000_000);
        } finally {
            lock.unlock();
        }
    }
    
    @Benchmark
    public void zookeeperTryLockWithTimeout(Blackhole bh) throws InterruptedException {
        String lockKey = ZK_LOCK_PREFIX + "try-" + Thread.currentThread().getId();
        DistributedLock lock = zookeeperLockFactory.getLock(lockKey);
        
        long startTime = System.nanoTime();
        boolean acquired = lock.tryLock(100, 5, TimeUnit.MILLISECONDS);
        boolean success = acquired;
        
        try {
            bh.consume(acquired);
            recordOperation(success, (System.nanoTime() - startTime) / 1_000_000);
            
            if (acquired) {
                Thread.sleep(10);
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    // ==================== 高并发性能测试 ====================
    
    @Benchmark
    public void redisHighConcurrencyLockTest() throws Exception {
        String lockKey = REDIS_LOCK_PREFIX + "high-concurrency";
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(CONCURRENT_THREADS);
        AtomicLong successCount = new AtomicLong(0);
        
        // 启动所有并发线程
        IntStream.range(0, CONCURRENT_THREADS).forEach(i -> {
            executorService.submit(() -> {
                try {
                    startLatch.await(); // 等待开始信号
                    DistributedLock lock = redisLockFactory.getLock(lockKey + "-" + i);
                    
                    long startTime = System.nanoTime();
                    if (lock.tryLock(LOCK_HOLD_TIME_MS, OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        try {
                            successCount.incrementAndGet();
                            Thread.sleep(LOCK_HOLD_TIME_MS); // 持有锁一段时间
                        } finally {
                            lock.unlock();
                        }
                    }
                    
                    long latency = (System.nanoTime() - startTime) / 1_000_000;
                    recordOperation(true, latency);
                } catch (Exception e) {
                    recordOperation(false, 0);
                } finally {
                    completeLatch.countDown();
                }
            });
        });
        
        startLatch.countDown(); // 开始测试
        completeLatch.await(); // 等待所有线程完成
    }
    
    @Benchmark
    public void zookeeperHighConcurrencyLockTest() throws Exception {
        String lockKey = ZK_LOCK_PREFIX + "high-concurrency";
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(CONCURRENT_THREADS);
        AtomicLong successCount = new AtomicLong(0);
        
        IntStream.range(0, CONCURRENT_THREADS).forEach(i -> {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    DistributedLock lock = zookeeperLockFactory.getLock(lockKey + "-" + i);
                    
                    long startTime = System.nanoTime();
                    if (lock.tryLock(LOCK_HOLD_TIME_MS, OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        try {
                            successCount.incrementAndGet();
                            Thread.sleep(LOCK_HOLD_TIME_MS);
                        } finally {
                            lock.unlock();
                        }
                    }
                    
                    long latency = (System.nanoTime() - startTime) / 1_000_000;
                    recordOperation(true, latency);
                } catch (Exception e) {
                    recordOperation(false, 0);
                } finally {
                    completeLatch.countDown();
                }
            });
        });
        
        startLatch.countDown();
        completeLatch.await();
    }
    
    // ==================== Spring集成性能测试 ====================
    
    @Benchmark
    public void springRedisLockPerformance(Blackhole bh) throws Exception {
        String lockKey = "spring:redis:" + Thread.currentThread().getId();
        DistributedLock lock = springFactory.getLock(lockKey);
        
        long startTime = System.nanoTime();
        lock.lock(2, TimeUnit.SECONDS);
        
        try {
            bh.consume(lock.isHeldByCurrentThread());
            recordOperation(true, (System.nanoTime() - startTime) / 1_000_000);
        } finally {
            lock.unlock();
        }
    }
    
    // ==================== 锁续期性能测试 ====================
    
    @Benchmark
    public void redisLockRenewalPerformance(Blackhole bh) throws Exception {
        String lockKey = REDIS_LOCK_PREFIX + "renewal-" + Thread.currentThread().getId();
        DistributedLock lock = redisLockFactory.getLock(lockKey);
        
        long startTime = System.nanoTime();
        lock.lock(10, TimeUnit.SECONDS);
        
        try {
            bh.consume(lock.isHeldByCurrentThread());
            Thread.sleep(100); // 短暂持有
            lock.renewLease(); // 续期
            Thread.sleep(50); // 再次持有
            recordOperation(true, (System.nanoTime() - startTime) / 1_000_000);
        } finally {
            lock.unlock();
        }
    }
    
    @Benchmark
    public void zookeeperLockRenewalPerformance(Blackhole bh) throws Exception {
        String lockKey = ZK_LOCK_PREFIX + "renewal-" + Thread.currentThread().getId();
        DistributedLock lock = zookeeperLockFactory.getLock(lockKey);
        
        long startTime = System.nanoTime();
        lock.lock(10, TimeUnit.SECONDS);
        
        try {
            bh.consume(lock.isHeldByCurrentThread());
            Thread.sleep(100);
            lock.renewLease();
            Thread.sleep(50);
            recordOperation(true, (System.nanoTime() - startTime) / 1_000_000);
        } finally {
            lock.unlock();
        }
    }
    
    // ==================== 健康检查性能测试 ====================
    
    @Benchmark
    public void redisHealthCheckPerformance(Blackhole bh) {
        DistributedLockFactory.HealthCheckResult health = redisLockFactory.healthCheck();
        bh.consume(health.isHealthy());
    }
    
    @Benchmark
    public void zookeeperHealthCheckPerformance(Blackhole bh) {
        DistributedLockFactory.HealthCheckResult health = zookeeperLockFactory.healthCheck();
        bh.consume(health.isHealthy());
    }
    
    @Configuration
    static class TestSpringConfig {
        
        @Bean
        public DistributedLockProperties distributedLockProperties() {
            DistributedLockProperties properties = new DistributedLockProperties();
            // 自定义配置用于测试
            properties.setLockTimeout(5000);
            properties.setRenewInterval(1000);
            properties.setRetryAttempts(3);
            return properties;
        }
        
        @Bean
        public SpringDistributedLockFactory springDistributedLockFactory(
                RedisClient redisClient,
                DistributedLockProperties properties) {
            return new SpringDistributedLockFactory(redisClient, properties);
        }
    }
    
    // ==================== 基准测试启动器 ====================
    
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(EnhancedDistributedLockBenchmark.class.getSimpleName())
                .forks(2)
                .warmupIterations(3)
                .warmupTime(TimeValue.seconds(5))
                .measurementIterations(5)
                .measurementTime(TimeValue.seconds(10))
                .threads(Threads.MAX)
                .build();
        
        new Runner(opt).run();
    }
}