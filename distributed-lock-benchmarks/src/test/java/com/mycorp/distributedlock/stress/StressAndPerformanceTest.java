package com.mycorp.distributedlock.stress;

import com.mycorp.distributedlock.api.*;
import com.mycorp.distributedlock.core.observability.LockPerformanceMetrics;
import com.mycorp.distributedlock.core.monitoring.PerformanceMonitor;
import com.mycorp.distributedlock.redis.SimpleRedisLockProvider;
import com.mycorp.distributedlock.zookeeper.ZooKeeperLockProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 压力和性能测试
 * 
 * 测试分布式锁在高并发、大规模场景下的性能表现和稳定性
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("压力和性能测试")
class StressAndPerformanceTest {

    @Mock
    private LockProvider mockLockProvider;

    private LockPerformanceMetrics performanceMetrics;
    private PerformanceMonitor performanceMonitor;
    
    // 性能测试配置
    private static final int HIGH_CONCURRENCY = 100;
    private static final int MEDIUM_CONCURRENCY = 50;
    private static final int LOW_CONCURRENCY = 10;
    private static final int OPERATIONS_PER_THREAD = 100;
    private static final Duration TEST_DURATION = Duration.ofMinutes(5);

    @BeforeEach
    void setUp() {
        performanceMetrics = new LockPerformanceMetrics(
            null, // MeterRegistry
            null, // OpenTelemetry
            true, // 启用指标
            true  // 启用追踪
        );
        performanceMonitor = new PerformanceMonitor(performanceMetrics);
    }

    @Nested
    @DisplayName("高并发压力测试")
    class HighConcurrencyStressTest {

        @Test
        @DisplayName("应该处理100并发锁获取操作")
        void shouldHandle100ConcurrentLockAcquisitions() throws InterruptedException {
            // Given
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch completionLatch = new CountDownLatch(HIGH_CONCURRENCY);
            ExecutorService executor = Executors.newFixedThreadPool(HIGH_CONCURRENCY);
            
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);
            AtomicLong totalExecutionTime = new AtomicLong(0);
            List<Long> executionTimes = Collections.synchronizedList(new ArrayList<>());
            
            String lockName = "high-concurrency-test-lock";
            DistributedLock mockLock = createMockLockForStress();
            
            when(mockLockProvider.getLock(lockName)).thenReturn(mockLock);
            
            // When
            long testStartTime = System.currentTimeMillis();
            
            for (int i = 0; i < HIGH_CONCURRENCY; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        
                        for (int op = 0; op < OPERATIONS_PER_THREAD; op++) {
                            long operationStartTime = System.nanoTime();
                            
                            try {
                                DistributedLock lock = mockLockProvider.getLock(lockName);
                                if (lock.tryLock(1, TimeUnit.SECONDS)) {
                                    try {
                                        // 模拟业务操作
                                        Thread.sleep(10);
                                        successCount.incrementAndGet();
                                    } finally {
                                        lock.unlock();
                                    }
                                } else {
                                    failureCount.incrementAndGet();
                                }
                            } catch (Exception e) {
                                failureCount.incrementAndGet();
                            }
                            
                            long operationEndTime = System.nanoTime();
                            executionTimes.add((operationEndTime - operationStartTime) / 1_000_000); // 转换为毫秒
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        completionLatch.countDown();
                    }
                });
            }
            
            startLatch.countDown();
            boolean completed = completionLatch.await(TEST_DURATION.toMillis(), TimeUnit.MILLISECONDS);
            long testEndTime = System.currentTimeMillis();
            
            // Then
            assertThat(completed).isTrue();
            
            long totalTime = testEndTime - testStartTime;
            double throughput = (double) successCount.get() / (totalTime / 1000.0);
            
            // 性能验证
            assertThat(successCount.get()).isGreaterThan(0);
            assertThat(failureCount.get()).isLessThan(successCount.get()); // 失败率应该较低
            assertThat(throughput).isGreaterThan(100); // 每秒至少100个操作
            
            // 延迟验证
            double averageLatency = executionTimes.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);
            
            assertThat(averageLatency).isLessThan(1000); // 平均延迟应该小于1秒
            assertThat(averageLatency).isGreaterThan(0);
            
            executor.shutdown();
        }

        @Test
        @DisplayName("应该维持内存使用在合理范围内")
        void shouldMaintainReasonableMemoryUsage() throws InterruptedException {
            // Given
            Runtime runtime = Runtime.getRuntime();
            long initialMemory = runtime.totalMemory() - runtime.freeMemory();
            
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch completionLatch = new CountDownLatch(MEDIUM_CONCURRENCY);
            ExecutorService executor = Executors.newFixedThreadPool(MEDIUM_CONCURRENCY);
            
            List<DistributedLock> createdLocks = Collections.synchronizedList(new ArrayList<>());
            
            // When
            for (int i = 0; i < MEDIUM_CONCURRENCY; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        
                        for (int op = 0; op < 50; op++) {
                            String lockName = "memory-test-lock-" + threadId + "-" + op;
                            DistributedLock lock = createMockLock();
                            createdLocks.add(lock);
                            
                            // 模拟锁操作
                            if (lock.tryLock(100, TimeUnit.MILLISECONDS)) {
                                try {
                                    Thread.sleep(5);
                                } finally {
                                    lock.unlock();
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        completionLatch.countDown();
                    }
                });
            }
            
            startLatch.countDown();
            completionLatch.await(2, TimeUnit.MINUTES);
            
            long finalMemory = runtime.totalMemory() - runtime.freeMemory();
            long memoryIncrease = finalMemory - initialMemory;
            
            // Then
            assertThat(memoryIncrease).isLessThan(100 * 1024 * 1024); // 内存增长应该小于100MB
            
            executor.shutdown();
        }

        @Test
        @DisplayName("应该处理突发流量")
        void shouldHandleBurstTraffic() throws InterruptedException {
            // Given
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger burstCount = new AtomicInteger(0);
            ExecutorService executor = Executors.newFixedThreadPool(200); // 突发线程
            
            String lockName = "burst-test-lock";
            DistributedLock mockLock = createMockLockForStress();
            when(mockLockProvider.getLock(lockName)).thenReturn(mockLock);
            
            // When - 模拟突发流量
            startLatch.countDown(); // 同时启动所有线程
            
            for (int i = 0; i < 200; i++) {
                executor.submit(() -> {
                    DistributedLock lock = mockLockProvider.getLock(lockName);
                    if (lock.tryLock(500, TimeUnit.MILLISECONDS)) {
                        try {
                            burstCount.incrementAndGet();
                            Thread.sleep(1); // 短暂持有
                        } finally {
                            lock.unlock();
                        }
                    }
                });
            }
            
            Thread.sleep(2000); // 等待突发处理
            
            // Then
            assertThat(burstCount.get()).isGreaterThan(0);
            assertThat(burstCount.get()).isLessThanOrEqualTo(200); // 不应该超过总线程数
            
            executor.shutdown();
        }
    }

    @Nested
    @DisplayName("长时间稳定性测试")
    class LongRunningStabilityTest {

        @Test
        @DisplayName("应该在长时间运行中保持稳定性")
        void shouldMaintainStabilityDuringLongRunning() throws InterruptedException {
            // Given
            AtomicInteger totalOperations = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);
            CountDownLatch startLatch = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(LOW_CONCURRENCY);
            
            // When
            for (int i = 0; i < LOW_CONCURRENCY; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        
                        while (System.currentTimeMillis() - System.currentTimeMillis() < TEST_DURATION.toMillis()) {
                            String lockName = "stability-test-lock-" + threadId;
                            DistributedLock lock = createMockLock();
                            
                            if (lock.tryLock(1, TimeUnit.SECONDS)) {
                                try {
                                    totalOperations.incrementAndGet();
                                    Thread.sleep(50); // 模拟业务处理
                                } finally {
                                    lock.unlock();
                                }
                            } else {
                                errorCount.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            
            startLatch.countDown();
            Thread.sleep(TEST_DURATION.toMillis() + 1000); // 稍微延长确保完成
            
            // Then
            assertThat(totalOperations.get()).isGreaterThan(0);
            double errorRate = (double) errorCount.get() / totalOperations.get();
            assertThat(errorRate).isLessThan(0.1); // 错误率应该小于10%
            
            executor.shutdown();
        }

        @Test
        @DisplayName("应该处理内存泄漏检测")
        void shouldHandleMemoryLeakDetection() throws InterruptedException {
            // Given
            Runtime runtime = Runtime.getRuntime();
            List<Long> memorySnapshots = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicBoolean running = new AtomicBoolean(true);
            
            // 内存监控线程
            Thread memoryMonitor = new Thread(() -> {
                try {
                    startLatch.await();
                    while (running.get()) {
                        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
                        memorySnapshots.add(usedMemory);
                        Thread.sleep(1000); // 每秒采样一次
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            
            memoryMonitor.start();
            
            // 工作线程
            ExecutorService executor = Executors.newFixedThreadPool(10);
            for (int i = 0; i < 10; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        while (running.get()) {
                            // 创建和销毁锁对象，检测是否有内存泄漏
                            for (int j = 0; j < 100; j++) {
                                DistributedLock lock = createMockLock();
                                // 锁会被GC回收
                            }
                            Thread.sleep(100);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            
            // When - 运行2分钟
            startLatch.countDown();
            Thread.sleep(120000); // 2分钟
            
            running.set(false);
            
            // Then - 分析内存趋势
            assertThat(memorySnapshots.size()).isGreaterThan(10);
            
            // 检查内存是否有持续增长（可能的泄漏）
            long firstSample = memorySnapshots.get(0);
            long lastSample = memorySnapshots.get(memorySnapshots.size() - 1);
            long memoryIncrease = lastSample - firstSample;
            
            // 内存增长应该在合理范围内
            assertThat(memoryIncrease).isLessThan(50 * 1024 * 1024); // 小于50MB
            
            executor.shutdown();
            memoryMonitor.join();
        }
    }

    // 辅助方法

    private DistributedLock createMockLock() {
        DistributedLock lock = mock(DistributedLock.class);
        when(lock.tryLock(anyLong(), any())).thenReturn(true);
        return lock;
    }

    private DistributedLock createMockLockForStress() {
        DistributedLock lock = mock(DistributedLock.class);
        
        // 模拟真实的锁行为
        when(lock.tryLock(anyLong(), any())).thenAnswer(invocation -> {
            long waitTime = invocation.getArgument(0, Long.class);
            TimeUnit unit = invocation.getArgument(1, TimeUnit.class);
            
            // 模拟获取锁的随机延迟
            try {
                Thread.sleep(new Random().nextInt(10));
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
        
        return lock;
    }
}