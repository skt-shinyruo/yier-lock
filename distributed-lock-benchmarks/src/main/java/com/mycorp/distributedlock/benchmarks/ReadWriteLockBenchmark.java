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

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import static org.openjdk.jmh.annotations.Mode.*;

/**
 * 读写锁基准测试
 * 
 * 测试场景：
 * 1. 读锁独占性能（多个读线程同时获取读锁）
 * 2. 写锁独占性能（写操作时读锁阻塞）
 * 3. 读写锁转换性能
 * 4. 高并发读写混合场景
 * 5. 不同锁类型性能对比（Redis vs ZooKeeper）
 */
@BenchmarkMode({Throughput, AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
@Fork(2)
@Threads(Threads.MAX)
public class ReadWriteLockBenchmark {
    
    // 性能统计器
    private final LongAdder readOperations = new LongAdder();
    private final LongAdder writeOperations = new LongAdder();
    private final LongAdder totalReadLatency = new LongAdder();
    private final LongAdder totalWriteLatency = new LongAdder();
    private final AtomicLong maxReadLatency = new AtomicLong(0);
    private final AtomicLong maxWriteLatency = new AtomicLong(0);
    
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
    private static final String READ_LOCK_KEY = "readwrite:lock:";
    private static final int READ_THREADS = 20;  // 读线程数量
    private static final int WRITE_THREADS = 5;  // 写线程数量
    private static final int READ_DURATION_MS = 50;  // 读操作持续时间
    private static final int WRITE_DURATION_MS = 100; // 写操作持续时间
    private static final int OPERATION_TIMEOUT_MS = 5000;
    
    @Setup(Level.Trial)
    public void setupTrial() throws Exception {
        System.out.println("设置读写锁测试环境...");
        
        // 启动Redis容器
        redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        redisContainer.start();
        
        String redisHost = redisContainer.getHost();
        Integer redisPort = redisContainer.getMappedPort(6379);
        
        // 初始化Redis客户端
        redisClient = RedisClient.create(RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .withConnectionTimeout(Duration.ofSeconds(5))
                .build());
        redisLockFactory = new RedisDistributedLockFactory(redisClient);
        
        // 启动ZooKeeper测试服务器
        zookeeperServer = new TestingServer();
        zookeeperServer.start();
        
        // 初始化ZooKeeper客户端
        curatorFramework = CuratorFrameworkFactory.newClient(
                zookeeperServer.getConnectString(),
                new ExponentialBackoffRetry(1000, 3)
        );
        curatorFramework.start();
        curatorFramework.blockUntilConnected(10, TimeUnit.SECONDS);
        zookeeperLockFactory = new ZooKeeperDistributedLockFactory(curatorFramework);
        
        // 创建线程池
        executorService = Executors.newFixedThreadPool(READ_THREADS + WRITE_THREADS + 5);
        
        System.out.println("读写锁测试环境设置完成");
    }
    
    @TearDown(Level.Trial)
    public void tearDownTrial() throws Exception {
        System.out.println("清理读写锁测试环境...");
        
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
        
        printReadWritePerformanceSummary();
    }
    
    @Setup(Level.Invocation)
    public void setupInvocation() {
        resetCounters();
    }
    
    private void resetCounters() {
        readOperations.reset();
        writeOperations.reset();
        totalReadLatency.reset();
        totalWriteLatency.reset();
        maxReadLatency.set(0);
        maxWriteLatency.set(0);
    }
    
    private void recordReadOperation(long latency) {
        readOperations.increment();
        totalReadLatency.add(latency);
        if (latency > maxReadLatency.get()) {
            maxReadLatency.set(latency);
        }
    }
    
    private void recordWriteOperation(long latency) {
        writeOperations.increment();
        totalWriteLatency.add(latency);
        if (latency > maxWriteLatency.get()) {
            maxWriteLatency.set(latency);
        }
    }
    
    private void printReadWritePerformanceSummary() {
        long readOps = readOperations.sum();
        long writeOps = writeOperations.sum();
        long totalReadLat = totalReadLatency.sum();
        long totalWriteLat = totalWriteLatency.sum();
        
        System.out.println("\n=== 读写锁性能摘要 ===");
        if (readOps > 0) {
            double avgReadLatency = (double) totalReadLat / readOps;
            System.out.printf("读操作: %d, 平均延迟: %.2f ms, 最大延迟: %d ms%n", 
                    readOps, avgReadLatency, maxReadLatency.get());
        }
        
        if (writeOps > 0) {
            double avgWriteLatency = (double) totalWriteLat / writeOps;
            System.out.printf("写操作: %d, 平均延迟: %.2f ms, 最大延迟: %d ms%n", 
                    writeOps, avgWriteLatency, maxWriteLatency.get());
        }
        
        System.out.printf("读写比例: %.2f:1%n", (double) readOps / writeOps);
    }
    
    // ==================== Redis读写锁基准测试 ====================
    
    @Benchmark
    public void redisReadLockPerformance(Blackhole bh) throws InterruptedException {
        String lockKey = READ_LOCK_KEY + "redis-read-" + Thread.currentThread().getId();
        DistributedReadWriteLock lock = redisLockFactory.getReadWriteLock(lockKey);
        
        long startTime = System.nanoTime();
        lock.readLock().lock(2, TimeUnit.SECONDS);
        
        try {
            // 模拟读操作
            Thread.sleep(READ_DURATION_MS);
            bh.consume(lock.isReadLockedByCurrentThread());
            
            recordReadOperation((System.nanoTime() - startTime) / 1_000_000);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    @Benchmark
    public void redisWriteLockPerformance(Blackhole bh) throws InterruptedException {
        String lockKey = READ_LOCK_KEY + "redis-write-" + Thread.currentThread().getId();
        DistributedReadWriteLock lock = redisLockFactory.getReadWriteLock(lockKey);
        
        long startTime = System.nanoTime();
        lock.writeLock().lock(5, TimeUnit.SECONDS);
        
        try {
            // 模拟写操作
            Thread.sleep(WRITE_DURATION_MS);
            bh.consume(lock.isWriteLockedByCurrentThread());
            
            recordWriteOperation((System.nanoTime() - startTime) / 1_000_000);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    @Benchmark
    public void redisReadWriteLockTryLock(Blackhole bh) throws InterruptedException {
        String lockKey = READ_LOCK_KEY + "redis-try-" + Thread.currentThread().getId();
        DistributedReadWriteLock lock = redisLockFactory.getReadWriteLock(lockKey);
        
        boolean readAcquired = lock.readLock().tryLock(100, TimeUnit.MILLISECONDS);
        bh.consume(readAcquired);
        
        if (readAcquired) {
            try {
                Thread.sleep(20);
            } finally {
                lock.readLock().unlock();
            }
        }
    }
    
    @Benchmark
    public void redisMixedReadWriteLockTest() throws Exception {
        String lockKey = READ_LOCK_KEY + "redis-mixed-" + Thread.currentThread().getId();
        DistributedReadWriteLock lock = redisLockFactory.getReadWriteLock(lockKey);
        
        // 随机选择读或写操作
        boolean isRead = Thread.currentThread().getId() % 3 == 0;
        
        if (isRead) {
            long startTime = System.nanoTime();
            if (lock.readLock().tryLock(1, TimeUnit.SECONDS)) {
                try {
                    Thread.sleep(10);
                    recordReadOperation((System.nanoTime() - startTime) / 1_000_000);
                } finally {
                    lock.readLock().unlock();
                }
            }
        } else {
            long startTime = System.nanoTime();
            if (lock.writeLock().tryLock(1, TimeUnit.SECONDS)) {
                try {
                    Thread.sleep(20);
                    recordWriteOperation((System.nanoTime() - startTime) / 1_000_000);
                } finally {
                    lock.writeLock().unlock();
                }
            }
        }
    }
    
    // ==================== ZooKeeper读写锁基准测试 ====================
    
    @Benchmark
    public void zookeeperReadLockPerformance(Blackhole bh) throws InterruptedException {
        String lockKey = READ_LOCK_KEY + "zk-read-" + Thread.currentThread().getId();
        DistributedReadWriteLock lock = zookeeperLockFactory.getReadWriteLock(lockKey);
        
        long startTime = System.nanoTime();
        lock.readLock().lock(2, TimeUnit.SECONDS);
        
        try {
            Thread.sleep(READ_DURATION_MS);
            bh.consume(lock.isReadLockedByCurrentThread());
            
            recordReadOperation((System.nanoTime() - startTime) / 1_000_000);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    @Benchmark
    public void zookeeperWriteLockPerformance(Blackhole bh) throws InterruptedException {
        String lockKey = READ_LOCK_KEY + "zk-write-" + Thread.currentThread().getId();
        DistributedReadWriteLock lock = zookeeperLockFactory.getReadWriteLock(lockKey);
        
        long startTime = System.nanoTime();
        lock.writeLock().lock(5, TimeUnit.SECONDS);
        
        try {
            Thread.sleep(WRITE_DURATION_MS);
            bh.consume(lock.isWriteLockedByCurrentThread());
            
            recordWriteOperation((System.nanoTime() - startTime) / 1_000_000);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    @Benchmark
    public void zookeeperReadWriteLockTryLock(Blackhole bh) throws InterruptedException {
        String lockKey = READ_LOCK_KEY + "zk-try-" + Thread.currentThread().getId();
        DistributedReadWriteLock lock = zookeeperLockFactory.getReadWriteLock(lockKey);
        
        boolean readAcquired = lock.readLock().tryLock(100, TimeUnit.MILLISECONDS);
        bh.consume(readAcquired);
        
        if (readAcquired) {
            try {
                Thread.sleep(20);
            } finally {
                lock.readLock().unlock();
            }
        }
    }
    
    @Benchmark
    public void zookeeperMixedReadWriteLockTest() throws Exception {
        String lockKey = READ_LOCK_KEY + "zk-mixed-" + Thread.currentThread().getId();
        DistributedReadWriteLock lock = zookeeperLockFactory.getReadWriteLock(lockKey);
        
        boolean isRead = Thread.currentThread().getId() % 3 == 0;
        
        if (isRead) {
            long startTime = System.nanoTime();
            if (lock.readLock().tryLock(1, TimeUnit.SECONDS)) {
                try {
                    Thread.sleep(10);
                    recordReadOperation((System.nanoTime() - startTime) / 1_000_000);
                } finally {
                    lock.readLock().unlock();
                }
            }
        } else {
            long startTime = System.nanoTime();
            if (lock.writeLock().tryLock(1, TimeUnit.SECONDS)) {
                try {
                    Thread.sleep(20);
                    recordWriteOperation((System.nanoTime() - startTime) / 1_000_000);
                } finally {
                    lock.writeLock().unlock();
                }
            }
        }
    }
    
    // ==================== 高并发读写混合测试 ====================
    
    @Benchmark
    public void redisHighConcurrencyReadWriteTest() throws Exception {
        String lockKey = READ_LOCK_KEY + "redis-high-concurrency";
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger readCount = new AtomicInteger(0);
        AtomicInteger writeCount = new AtomicInteger(0);
        
        // 启动读线程
        for (int i = 0; i < READ_THREADS; i++) {
            final int threadId = i;
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    
                    for (int j = 0; j < 10; j++) { // 每个线程执行10次读操作
                        String threadLockKey = lockKey + "-read-" + threadId + "-" + j;
                        DistributedReadWriteLock lock = redisLockFactory.getReadWriteLock(threadLockKey);
                        
                        long startTime = System.nanoTime();
                        if (lock.readLock().tryLock(1, TimeUnit.SECONDS)) {
                            try {
                                readCount.incrementAndGet();
                                Thread.sleep(READ_DURATION_MS / 2); // 较短的读操作
                            } finally {
                                lock.readLock().unlock();
                            }
                        }
                        
                        recordReadOperation((System.nanoTime() - startTime) / 1_000_000);
                    }
                } catch (Exception e) {
                    // 记录失败但不中断测试
                }
            });
        }
        
        // 启动写线程
        for (int i = 0; i < WRITE_THREADS; i++) {
            final int threadId = i;
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    
                    for (int j = 0; j < 5; j++) { // 每个线程执行5次写操作
                        String threadLockKey = lockKey + "-write-" + threadId + "-" + j;
                        DistributedReadWriteLock lock = redisLockFactory.getReadWriteLock(threadLockKey);
                        
                        long startTime = System.nanoTime();
                        if (lock.writeLock().tryLock(2, TimeUnit.SECONDS)) {
                            try {
                                writeCount.incrementAndGet();
                                Thread.sleep(WRITE_DURATION_MS / 2); // 较短的写操作
                            } finally {
                                lock.writeLock().unlock();
                            }
                        }
                        
                        recordWriteOperation((System.nanoTime() - startTime) / 1_000_000);
                    }
                } catch (Exception e) {
                    // 记录失败但不中断测试
                }
            });
        }
        
        startLatch.countDown(); // 开始测试
        Thread.sleep(30000); // 等待30秒让所有操作完成
    }
    
    @Benchmark
    public void zookeeperHighConcurrencyReadWriteTest() throws Exception {
        String lockKey = READ_LOCK_KEY + "zk-high-concurrency";
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger readCount = new AtomicInteger(0);
        AtomicInteger writeCount = new AtomicInteger(0);
        
        // 启动读线程
        for (int i = 0; i < READ_THREADS; i++) {
            final int threadId = i;
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    
                    for (int j = 0; j < 10; j++) {
                        String threadLockKey = lockKey + "-read-" + threadId + "-" + j;
                        DistributedReadWriteLock lock = zookeeperLockFactory.getReadWriteLock(threadLockKey);
                        
                        long startTime = System.nanoTime();
                        if (lock.readLock().tryLock(1, TimeUnit.SECONDS)) {
                            try {
                                readCount.incrementAndGet();
                                Thread.sleep(READ_DURATION_MS / 2);
                            } finally {
                                lock.readLock().unlock();
                            }
                        }
                        
                        recordReadOperation((System.nanoTime() - startTime) / 1_000_000);
                    }
                } catch (Exception e) {
                    // 记录失败但不中断测试
                }
            });
        }
        
        // 启动写线程
        for (int i = 0; i < WRITE_THREADS; i++) {
            final int threadId = i;
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    
                    for (int j = 0; j < 5; j++) {
                        String threadLockKey = lockKey + "-write-" + threadId + "-" + j;
                        DistributedReadWriteLock lock = zookeeperLockFactory.getReadWriteLock(threadLockKey);
                        
                        long startTime = System.nanoTime();
                        if (lock.writeLock().tryLock(2, TimeUnit.SECONDS)) {
                            try {
                                writeCount.incrementAndGet();
                                Thread.sleep(WRITE_DURATION_MS / 2);
                            } finally {
                                lock.writeLock().unlock();
                            }
                        }
                        
                        recordWriteOperation((System.nanoTime() - startTime) / 1_000_000);
                    }
                } catch (Exception e) {
                    // 记录失败但不中断测试
                }
            });
        }
        
        startLatch.countDown();
        Thread.sleep(30000); // 等待30秒让所有操作完成
    }
    
    // ==================== 读写锁转换测试 ====================
    
    @Benchmark
    public void redisReadToWriteLockUpgrade() throws Exception {
        String lockKey = READ_LOCK_KEY + "redis-upgrade-" + Thread.currentThread().getId();
        DistributedReadWriteLock lock = redisLockFactory.getReadWriteLock(lockKey);
        
        // 先获取读锁
        if (lock.readLock().tryLock(1, TimeUnit.SECONDS)) {
            try {
                Thread.sleep(50); // 读操作
                
                // 升级为写锁（这里需要释放读锁再获取写锁）
                lock.readLock().unlock();
                
                if (lock.writeLock().tryLock(1, TimeUnit.SECONDS)) {
                    try {
                        Thread.sleep(50); // 写操作
                        recordWriteOperation(100); // 记录转换开销
                    } finally {
                        lock.writeLock().unlock();
                    }
                }
            } finally {
                // 确保清理读锁
                try {
                    lock.readLock().unlock();
                } catch (IllegalMonitorStateException e) {
                    // 读锁已经被释放，忽略异常
                }
            }
        }
    }
    
    @Benchmark
    public void zookeeperReadToWriteLockUpgrade() throws Exception {
        String lockKey = READ_LOCK_KEY + "zk-upgrade-" + Thread.currentThread().getId();
        DistributedReadWriteLock lock = zookeeperLockFactory.getReadWriteLock(lockKey);
        
        if (lock.readLock().tryLock(1, TimeUnit.SECONDS)) {
            try {
                Thread.sleep(50);
                
                lock.readLock().unlock();
                
                if (lock.writeLock().tryLock(1, TimeUnit.SECONDS)) {
                    try {
                        Thread.sleep(50);
                        recordWriteOperation(100);
                    } finally {
                        lock.writeLock().unlock();
                    }
                }
            } finally {
                try {
                    lock.readLock().unlock();
                } catch (IllegalMonitorStateException e) {
                    // 读锁已经被释放，忽略异常
                }
            }
        }
    }
}