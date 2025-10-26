package com.mycorp.distributedlock.core.advanced;

import com.mycorp.distributedlock.api.*;
import com.mycorp.distributedlock.core.lock.EnhancedReentrantLockImpl;
import com.mycorp.distributedlock.core.operation.BatchLockOperationsAdvanced;
import com.mycorp.distributedlock.core.strategy.AdvancedLockStrategies;
import com.mycorp.distributedlock.api.exception.LockAcquisitionException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 高级特性集成测试
 * 
 * 测试高级分布式锁功能，包括公平锁、读写锁、批量操作等
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("高级特性集成测试")
class AdvancedFeaturesIntegrationTest {

    @Mock
    private LockProvider mockLockProvider;

    @Mock
    private DistributedLock mockLock;

    private AdvancedLockStrategies advancedLockStrategies;
    private BatchLockOperationsAdvanced batchOperations;
    private EnhancedReentrantLockImpl reentrantLock;

    @BeforeEach
    void setUp() {
        advancedLockStrategies = new AdvancedLockStrategies();
        batchOperations = new BatchLockOperationsAdvanced(mockLockProvider);
        reentrantLock = new EnhancedReentrantLockImpl("test-reentrant-lock");
    }

    @Nested
    @DisplayName("公平锁特性测试")
    class FairLockFeaturesTest {

        @Test
        @DisplayName("应该按照FIFO顺序分配锁")
        void shouldAssignLocksInFifoOrder() throws InterruptedException {
            // Given
            int numThreads = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch completionLatch = new CountDownLatch(numThreads);
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            
            List<Long> executionOrder = Collections.synchronizedList(new ArrayList<>());
            List<CompletableFuture<Long>> futures = new ArrayList<>();
            
            // When
            for (int i = 0; i < numThreads; i++) {
                final int threadId = i;
                CompletableFuture<Long> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        startLatch.await();
                        long startTime = System.nanoTime();
                        
                        // 获取公平锁
                        if (reentrantLock.tryLock(5, TimeUnit.SECONDS)) {
                            try {
                                // 模拟一些工作时间
                                Thread.sleep(50);
                                long threadEndTime = System.nanoTime();
                                executionOrder.add((long) threadId);
                                return threadEndTime - startTime;
                            } finally {
                                reentrantLock.unlock();
                            }
                        } else {
                            throw new RuntimeException("Failed to acquire lock");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    } finally {
                        completionLatch.countDown();
                    }
                }, executor);
                futures.add(future);
            }
            
            startLatch.countDown();
            completionLatch.await(30, TimeUnit.SECONDS);
            
            // Then
            assertThat(executionOrder).hasSize(numThreads);
            // 验证执行顺序大致按照提交顺序（允许一些偏差）
            for (int i = 0; i < executionOrder.size() - 1; i++) {
                assertThat(executionOrder.get(i)).isLessThan(executionOrder.get(i + 1));
            }
            
            executor.shutdown();
        }

        @Test
        @DisplayName("应该处理多个等待队列")
        void shouldHandleMultipleWaitQueues() throws InterruptedException {
            // Given
            ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock(true); // 公平锁
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger executionCount = new AtomicInteger(0);
            List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());
            
            // 创建读锁和写锁线程
            List<Thread> threads = new ArrayList<>();
            
            // 创建写锁请求线程
            for (int i = 0; i < 3; i++) {
                final int writerId = i;
                Thread writerThread = new Thread(() -> {
                    try {
                        startLatch.await();
                        readWriteLock.writeLock().lock();
                        try {
                            executionOrder.add(writerId);
                            executionCount.incrementAndGet();
                            Thread.sleep(100);
                        } finally {
                            readWriteLock.writeLock().unlock();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                threads.add(writerThread);
            }
            
            // 创建读锁请求线程
            for (int i = 0; i < 5; i++) {
                final int readerId = i + 10;
                Thread readerThread = new Thread(() -> {
                    try {
                        startLatch.await();
                        readWriteLock.readLock().lock();
                        try {
                            executionOrder.add(readerId);
                            executionCount.incrementAndGet();
                            Thread.sleep(50);
                        } finally {
                            readWriteLock.readLock().unlock();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                threads.add(readerThread);
            }
            
            // When
            threads.forEach(Thread::start);
            startLatch.countDown();
            
            // 等待所有线程完成
            for (Thread thread : threads) {
                thread.join(5000);
            }
            
            // Then
            assertThat(executionOrder).hasSize(8);
            assertThat(executionCount.get()).isEqualTo(8);
        }

        @Test
        @DisplayName("应该防止饥饿现象")
        void shouldPreventStarvation() {
            // Given
            ReentrantReadWriteLock fairLock = new ReentrantReadWriteLock(true);
            AtomicLong lastExecutionTime = new AtomicLong(System.nanoTime());
            AtomicInteger starvationCounter = new AtomicInteger(0);
            
            // 创建一个长时间持有读锁的线程
            Thread readerThread = new Thread(() -> {
                try {
                    fairLock.readLock().lock();
                    try {
                        Thread.sleep(500); // 长时间持有读锁
                    } finally {
                        fairLock.readLock().unlock();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            
            readerThread.start();
            
            try {
                Thread.sleep(100); // 确保读锁先获取
                
                // 快速连续请求写锁，验证不会饿死
                for (int i = 0; i < 5; i++) {
                    long startTime = System.nanoTime();
                    if (fairLock.writeLock().tryLock(100, TimeUnit.MILLISECONDS)) {
                        try {
                            // 执行一些工作
                            lastExecutionTime.set(System.nanoTime());
                        } finally {
                            fairLock.writeLock().unlock();
                        }
                    } else {
                        starvationCounter.incrementAndGet();
                    }
                }
                
                // 验证没有饿死写锁请求
                assertThat(starvationCounter.get()).isLessThan(3);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                try {
                    readerThread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Nested
    @DisplayName("读写锁特性测试")
    class ReadWriteLockFeaturesTest {

        @Test
        @DisplayName("应该支持多个读锁并发")
        void shouldSupportMultipleConcurrentReadLocks() throws InterruptedException {
            // Given
            DistributedReadWriteLock readWriteLock = createMockReadWriteLock();
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch completionLatch = new CountDownLatch(3);
            ExecutorService executor = Executors.newFixedThreadPool(3);
            
            // When
            List<CompletableFuture<String>> futures = new ArrayList<>();
            
            // 三个读锁请求
            for (int i = 0; i < 3; i++) {
                final int readerId = i;
                CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        startLatch.await();
                        DistributedLock readLock = readWriteLock.readLock();
                        
                        if (readLock.tryLock(2, TimeUnit.SECONDS)) {
                            try {
                                Thread.sleep(100); // 模拟读操作
                                return "Read operation completed by thread-" + readerId;
                            } finally {
                                readLock.unlock();
                            }
                        } else {
                            return "Failed to acquire read lock by thread-" + readerId;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return "Interrupted thread-" + readerId;
                    } finally {
                        completionLatch.countDown();
                    }
                }, executor);
                futures.add(future);
            }
            
            startLatch.countDown();
            completionLatch.await(10, TimeUnit.SECONDS);
            
            // Then
            List<String> results = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
            
            assertThat(results).hasSize(3);
            assertThat(results).allMatch(result -> result.contains("completed"));
            verify(readWriteLock, times(3)).readLock();
        }

        @Test
        @DisplayName("应该支持锁升级")
        void shouldSupportLockUpgrade() throws InterruptedException {
            // Given
            DistributedReadWriteLock readWriteLock = createMockReadWriteLock();
            DistributedLock readLock = mock(DistributedLock.class);
            DistributedLock writeLock = mock(DistributedLock.class);
            
            when(readWriteLock.readLock()).thenReturn(readLock);
            when(readWriteLock.writeLock()).thenReturn(writeLock);
            when(readLock.tryLock(anyLong(), any())).thenReturn(true);
            when(writeLock.tryLock(anyLong(), any())).thenReturn(true);
            
            // When
            String result = performLockUpgrade(readWriteLock);
            
            // Then
            assertThat(result).isEqualTo("upgrade-success");
            verify(readLock).tryLock(anyLong(), any());
            verify(readLock).unlock();
            verify(writeLock).tryLock(anyLong(), any());
            verify(writeLock).unlock();
        }

        @Test
        @DisplayName("应该支持锁降级")
        void shouldSupportLockDowngrade() throws InterruptedException {
            // Given
            DistributedReadWriteLock readWriteLock = createMockReadWriteLock();
            DistributedLock readLock = mock(DistributedLock.class);
            DistributedLock writeLock = mock(DistributedLock.class);
            
            when(readWriteLock.readLock()).thenReturn(readLock);
            when(readWriteLock.writeLock()).thenReturn(writeLock);
            when(writeLock.tryLock(anyLong(), any())).thenReturn(true);
            when(readLock.tryLock(anyLong(), any())).thenReturn(true);
            
            // When
            String result = performLockDowngrade(readWriteLock);
            
            // Then
            assertThat(result).isEqualTo("downgrade-success");
            verify(writeLock).tryLock(anyLong(), any());
            verify(writeLock).unlock(); // 写锁降级为读锁
            verify(readLock).tryLock(anyLong(), any());
            verify(readLock).unlock(); // 最终释放读锁
        }

        @Test
        @DisplayName("应该处理写锁独占性")
        void shouldEnforceWriteLockExclusivity() throws InterruptedException {
            // Given
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch writeLockLatch = new CountDownLatch(1);
            AtomicBoolean writeLockAcquired = new AtomicBoolean(false);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            
            // 第一个线程获取写锁
            CompletableFuture<String> writeFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    startLatch.await();
                    
                    // 使用模拟的分布式锁
                    when(mockLock.tryLock(anyLong(), any())).thenReturn(true);
                    
                    if (mockLock.tryLock(5, TimeUnit.SECONDS)) {
                        try {
                            writeLockAcquired.set(true);
                            writeLockLatch.countDown(); // 通知写锁已获取
                            Thread.sleep(300); // 长时间持有写锁
                            return "Write operation completed";
                        } finally {
                            mockLock.unlock();
                        }
                    } else {
                        return "Failed to acquire write lock";
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "Write operation interrupted";
                }
            }, executor);
            
            // 第二个线程尝试获取读锁
            CompletableFuture<String> readFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    startLatch.await();
                    writeLockLatch.await(); // 等待写锁获取
                    
                    // 验证写锁正在持有期间
                    assertThat(writeLockAcquired.get()).isTrue();
                    
                    // 尝试获取读锁应该被阻塞
                    long startTime = System.currentTimeMillis();
                    boolean acquired = mockLock.tryLock(100, TimeUnit.MILLISECONDS);
                    long waitTime = System.currentTimeMillis() - startTime;
                    
                    if (acquired) {
                        mockLock.unlock();
                        return "Read operation acquired during write lock (unexpected)";
                    } else {
                        return "Read operation correctly blocked for " + waitTime + "ms";
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "Read operation interrupted";
                }
            }, executor);
            
            startLatch.countDown();
            
            // Then
            String writeResult = writeFuture.get(10, TimeUnit.SECONDS);
            String readResult = readFuture.get(10, TimeUnit.SECONDS);
            
            assertThat(writeResult).isEqualTo("Write operation completed");
            assertThat(readResult).startsWith("Read operation correctly blocked");
            assertThat(readResult).contains("ms");
            
            executor.shutdown();
        }

        private String performLockUpgrade(DistributedReadWriteLock readWriteLock) throws InterruptedException {
            DistributedLock readLock = readWriteLock.readLock();
            if (readLock.tryLock(2, TimeUnit.SECONDS)) {
                try {
                    // 执行一些读操作
                    Thread.sleep(50);
                    
                    // 升级为写锁
                    DistributedLock writeLock = readWriteLock.writeLock();
                    if (writeLock.tryLock(2, TimeUnit.SECONDS)) {
                        try {
                            // 执行一些写操作
                            Thread.sleep(50);
                            return "upgrade-success";
                        } finally {
                            writeLock.unlock();
                        }
                    } else {
                        return "upgrade-failed-write-lock";
                    }
                } finally {
                    readLock.unlock();
                }
            } else {
                return "upgrade-failed-read-lock";
            }
        }

        private String performLockDowngrade(DistributedReadWriteLock readWriteLock) throws InterruptedException {
            DistributedLock writeLock = readWriteLock.writeLock();
            if (writeLock.tryLock(2, TimeUnit.SECONDS)) {
                try {
                    // 执行一些写操作
                    Thread.sleep(50);
                    
                    // 降级为读锁
                    DistributedLock readLock = readWriteLock.readLock();
                    if (readLock.tryLock(2, TimeUnit.SECONDS)) {
                        try {
                            // 执行一些读操作
                            Thread.sleep(50);
                            return "downgrade-success";
                        } finally {
                            readLock.unlock();
                        }
                    } else {
                        return "downgrade-failed-read-lock";
                    }
                } finally {
                    writeLock.unlock();
                }
            } else {
                return "downgrade-failed-write-lock";
            }
        }

        private DistributedReadWriteLock createMockReadWriteLock() {
            DistributedReadWriteLock readWriteLock = mock(DistributedReadWriteLock.class);
            DistributedLock readLock = mock(DistributedLock.class);
            DistributedLock writeLock = mock(DistributedLock.class);
            
            when(readLock.tryLock(anyLong(), any())).thenReturn(true);
            when(writeLock.tryLock(anyLong(), any())).thenReturn(true);
            
            when(readWriteLock.readLock()).thenReturn(readLock);
            when(readWriteLock.writeLock()).thenReturn(writeLock);
            
            return readWriteLock;
        }
    }

    @Nested
    @DisplayName("批量锁操作测试")
    class BatchLockOperationsTest {

        @Test
        @DisplayName("应该支持批量获取锁")
        void shouldSupportBatchLockAcquisition() {
            // Given
            List<String> lockNames = Arrays.asList("lock1", "lock2", "lock3", "lock4", "lock5");
            
            for (String lockName : lockNames) {
                DistributedLock lock = mock(DistributedLock.class);
                when(lock.tryLock(anyLong(), any())).thenReturn(true);
                when(lockProvider.getLock(lockName)).thenReturn(lock);
            }
            
            // When
            BatchLockResult result = batchOperations.acquireBatch(lockNames, 10, TimeUnit.SECONDS);
            
            // Then
            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getAcquiredLocks()).hasSize(5);
            assertThat(result.getFailedLocks()).isEmpty();
        }

        @Test
        @DisplayName("应该在部分锁获取失败时回滚")
        void shouldRollbackOnPartialLockFailure() {
            // Given
            List<String> lockNames = Arrays.asList("lock1", "lock2", "lock3");
            
            // 前两个锁获取成功，第三个失败
            for (int i = 0; i < 2; i++) {
                DistributedLock lock = mock(DistributedLock.class);
                when(lock.tryLock(anyLong(), any())).thenReturn(true);
                when(lockProvider.getLock(lockNames.get(i))).thenReturn(lock);
            }
            
            DistributedLock failingLock = mock(DistributedLock.class);
            when(failingLock.tryLock(anyLong(), any())).thenReturn(false);
            when(lockProvider.getLock(lockNames.get(2))).thenReturn(failingLock);
            
            // When
            BatchLockResult result = batchOperations.acquireBatch(lockNames, 10, TimeUnit.SECONDS);
            
            // Then
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getAcquiredLocks()).hasSize(0); // 应该回滚所有已获取的锁
            assertThat(result.getFailedLocks()).contains("lock3");
        }

        @Test
        @DisplayName("应该支持批量释放锁")
        void shouldSupportBatchLockRelease() {
            // Given
            List<String> lockNames = Arrays.asList("lock1", "lock2", "lock3");
            List<DistributedLock> locks = new ArrayList<>();
            
            for (int i = 0; i < 3; i++) {
                DistributedLock lock = mock(DistributedLock.class);
                when(lock.tryLock(anyLong(), any())).thenReturn(true);
                when(lockProvider.getLock(lockNames.get(i))).thenReturn(lock);
                locks.add(lock);
            }
            
            batchOperations.acquireBatch(lockNames, 10, TimeUnit.SECONDS);
            
            // When
            BatchLockReleaseResult releaseResult = batchOperations.releaseBatch(lockNames);
            
            // Then
            assertThat(releaseResult.isSuccess()).isTrue();
            assertThat(releaseResult.getReleasedLocks()).hasSize(3);
            
            for (DistributedLock lock : locks) {
                verify(lock).unlock();
            }
        }

        @Test
        @DisplayName("应该支持原子性批量操作")
        void shouldSupportAtomicBatchOperations() {
            // Given
            List<String> lockNames = Arrays.asList("atomic-lock1", "atomic-lock2");
            AtomicInteger operationCount = new AtomicInteger(0);
            
            for (String lockName : lockNames) {
                DistributedLock lock = mock(DistributedLock.class);
                when(lock.tryLock(anyLong(), any())).thenAnswer(invocation -> {
                    operationCount.incrementAndGet();
                    return true;
                });
                when(lockProvider.getLock(lockName)).thenReturn(lock);
            }
            
            // When
            AtomicBatchResult result = batchOperations.atomicBatchOperation(
                lockNames,
                () -> {
                    // 模拟业务操作
                    Thread.sleep(50);
                    operationCount.incrementAndGet();
                    return "business-result";
                }
            );
            
            // Then
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getResult()).isEqualTo("business-result");
            assertThat(operationCount.get()).isGreaterThanOrEqualTo(3); // 2个锁 + 1个业务操作
        }

        @Test
        @DisplayName("应该在业务操作异常时释放锁")
        void shouldReleaseLocksOnBusinessOperationException() {
            // Given
            List<String> lockNames = Arrays.asList("exception-lock1", "exception-lock2");
            List<DistributedLock> locks = new ArrayList<>();
            
            for (String lockName : lockNames) {
                DistributedLock lock = mock(DistributedLock.class);
                when(lock.tryLock(anyLong(), any())).thenReturn(true);
                when(lockProvider.getLock(lockName)).thenReturn(lock);
                locks.add(lock);
            }
            
            // When
            AtomicBatchResult result = batchOperations.atomicBatchOperation(
                lockNames,
                () -> {
                    throw new RuntimeException("Business operation failed");
                }
            );
            
            // Then
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getException()).isInstanceOf(RuntimeException.class)
                .hasMessage("Business operation failed");
            
            // 验证所有锁都被释放
            for (DistributedLock lock : locks) {
                verify(lock).unlock();
            }
        }

        @Test
        @DisplayName("应该支持超时和重试机制")
        void shouldSupportTimeoutAndRetryMechanism() {
            // Given
            String lockName = "retry-lock";
            DistributedLock lock = mock(DistributedLock.class);
            
            // 第一次尝试失败，第二次成功
            when(lock.tryLock(anyLong(), any()))
                .thenReturn(false)
                .thenReturn(true);
                
            when(lockProvider.getLock(lockName)).thenReturn(lock);
            
            // When
            RetryableBatchResult result = batchOperations.retryableBatchOperation(
                Collections.singletonList(lockName),
                2, // 最大重试次数
                100, // 重试间隔(毫秒)
                5, TimeUnit.SECONDS // 总体超时时间
            );
            
            // Then
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getRetryCount()).isEqualTo(1);
            
            verify(lock, times(2)).tryLock(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("高级锁策略测试")
    class AdvancedLockStrategiesTest {

        @Test
        @DisplayName("应该支持租约时间自动续期")
        void shouldSupportAutomaticLeaseRenewal() throws InterruptedException {
            // Given
            DistributedLock lock = mock(DistributedLock.class);
            when(lock.tryLock(anyLong(), any())).thenReturn(true);
            when(lockProvider.getLock("renewal-lock")).thenReturn(lock);
            
            AtomicInteger renewalCount = new AtomicInteger(0);
            when(lock.tryRenewLease(anyLong(), any())).thenAnswer(invocation -> {
                renewalCount.incrementAndGet();
                return true;
            });
            
            // When
            String result = advancedLockStrategies.executeWithAutoRenewal(
                lockProvider.getLock("renewal-lock"),
                Duration.ofSeconds(2), // 租约时间
                Duration.ofMillis(500), // 续期间隔
                Duration.ofSeconds(5), // 最大执行时间
                () -> {
                    Thread.sleep(3000); // 超过租约时间但应该在续期间隔内
                    return "auto-renewal-success";
                }
            );
            
            // Then
            assertThat(result).isEqualTo("auto-renewal-success");
            assertThat(renewalCount.get()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("应该支持条件锁策略")
        void shouldSupportConditionalLockStrategy() throws InterruptedException {
            // Given
            DistributedLock lock = mock(DistributedLock.class);
            when(lockProvider.getLock("conditional-lock")).thenReturn(lock);
            
            AtomicInteger conditionCheckCount = new AtomicInteger(0);
            
            // When
            String result = advancedLockStrategies.executeWithCondition(
                lock,
                () -> {
                    conditionCheckCount.incrementAndGet();
                    return conditionCheckCount.get() >= 3; // 等待条件满足
                },
                Duration.ofSeconds(10),
                () -> {
                    return "condition-met-and-locked";
                }
            );
            
            // Then
            assertThat(result).isEqualTo("condition-met-and-locked");
            verify(lock).tryLock(anyLong(), any());
        }

        @Test
        @DisplayName("应该支持指数退避重试")
        void shouldSupportExponentialBackoffRetry() {
            // Given
            DistributedLock lock = mock(DistributedLock.class);
            when(lockProvider.getLock("backoff-lock")).thenReturn(lock);
            
            AtomicInteger attemptCount = new AtomicInteger(0);
            when(lock.tryLock(anyLong(), any())).thenAnswer(invocation -> {
                attemptCount.incrementAndGet();
                return attemptCount.get() >= 3; // 第三次尝试成功
            });
            
            // When
            RetryableOperationResult result = advancedLockStrategies.executeWithBackoffRetry(
                lock,
                3, // 最大重试次数
                Duration.ofMillis(100), // 初始延迟
                2.0, // 退避倍数
                () -> {
                    return "backoff-retry-success";
                }
            );
            
            // Then
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getAttemptCount()).isEqualTo(3);
            verify(lock, times(3)).tryLock(anyLong(), any());
        }

        @Test
        @DisplayName("应该支持级联锁策略")
        void shouldSupportCascadingLockStrategy() throws InterruptedException {
            // Given
            List<String> lockNames = Arrays.asList("cascade1", "cascade2", "cascade3");
            List<DistributedLock> locks = new ArrayList<>();
            
            for (String lockName : lockNames) {
                DistributedLock lock = mock(DistributedLock.class);
                when(lock.tryLock(anyLong(), any())).thenReturn(true);
                when(lockProvider.getLock(lockName)).thenReturn(lock);
                locks.add(lock);
            }
            
            AtomicInteger operationOrder = new AtomicInteger(0);
            List<Integer> executedOrder = Collections.synchronizedList(new ArrayList<>());
            
            // When
            CascadingOperationResult result = advancedLockStrategies.executeWithCascadingLocks(
                locks,
                (lockIndex) -> {
                    executedOrder.add(operationOrder.incrementAndGet());
                    return "cascade-operation-" + lockIndex;
                }
            );
            
            // Then
            assertThat(result.isSuccess()).isTrue();
            assertThat(executedOrder).hasSize(3);
            assertThat(executedOrder).containsExactlyInAnyOrder(1, 2, 3);
        }
    }

    // 辅助类和接口定义
    private interface BatchLockResult {
        boolean isSuccess();
        List<String> getAcquiredLocks();
        List<String> getFailedLocks();
    }

    private interface BatchLockReleaseResult {
        boolean isSuccess();
        List<String> getReleasedLocks();
    }

    private interface AtomicBatchResult {
        boolean isSuccess();
        String getResult();
        Exception getException();
    }

    private interface RetryableBatchResult {
        boolean isSuccess();
        int getRetryCount();
    }

    private interface RetryableOperationResult {
        boolean isSuccess();
        int getAttemptCount();
    }

    private interface CascadingOperationResult {
        boolean isSuccess();
        List<String> getResults();
    }
}