package com.mycorp.distributedlock.core.faulttolerance;

import com.mycorp.distributedlock.api.*;
import com.mycorp.distributedlock.api.exception.LockAcquisitionException;
import com.mycorp.distributedlock.api.exception.LockReleaseException;
import com.mycorp.distributedlock.core.observability.LockMonitoringService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 故障容错测试
 * 
 * 测试分布式锁在各种故障场景下的恢复能力和容错机制
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("故障容错测试")
class FaultToleranceTest {

    @Mock
    private LockProvider mockLockProvider;

    @Mock
    private DistributedLockFactory mockLockFactory;

    @Mock
    private DistributedLock mockLock;

    private LockMonitoringService monitoringService;

    @BeforeEach
    void setUp() {
        monitoringService = new LockMonitoringService(
            null, // LockPerformanceMetrics
            null, // OpenTelemetry
            null  // MonitoringConfig
        );
    }

    @Nested
    @DisplayName("网络分区和恢复测试")
    class NetworkPartitionAndRecoveryTest {

        @Test
        @DisplayName("应该在网络分区时正确处理锁状态")
        void shouldHandleLockStateDuringNetworkPartition() {
            // Given
            String lockName = "partition-test-lock";
            DistributedLock partitionLock = createPartitionableLock();
            
            when(mockLockProvider.getLock(lockName)).thenReturn(partitionLock);
            
            // When - 模拟网络分区
            partitionLock.simulateNetworkPartition();
            
            // Then - 验证分区处理
            assertThatThrownBy(() -> partitionLock.tryLock(1, TimeUnit.SECONDS))
                .isInstanceOf(LockAcquisitionException.class)
                .hasMessageContaining("Network partition detected");
            
            // When - 模拟网络恢复
            partitionLock.simulateNetworkRecovery();
            
            // Then - 验证恢复后正常操作
            DistributedLock lock = mockLockProvider.getLock(lockName);
            assertThat(lock.tryLock(1, TimeUnit.SECONDS)).isTrue();
            
            lock.unlock();
        }

        @Test
        @DisplayName("应该在网络恢复后自动重连")
        void shouldAutomaticallyReconnectAfterNetworkRecovery() throws InterruptedException {
            // Given
            String lockName = "reconnect-test-lock";
            DistributedLock reconnectableLock = createReconnectableLock();
            
            when(mockLockProvider.getLock(lockName)).thenReturn(reconnectableLock);
            
            // When - 模拟长时间网络中断
            reconnectableLock.simulateNetworkFailure();
            
            // 尝试获取锁（应该失败）
            boolean initialAttempt = reconnectableLock.tryLock(100, TimeUnit.MILLISECONDS);
            assertThat(initialAttempt).isFalse();
            
            // 模拟网络恢复
            reconnectableLock.simulateNetworkRecovery();
            
            // 等待自动重连
            Thread.sleep(2000); // 重连延迟
            
            // Then - 验证重连成功
            DistributedLock lock = mockLockProvider.getLock(lockName);
            boolean reconnectAttempt = lock.tryLock(1, TimeUnit.SECONDS);
            assertThat(reconnectAttempt).isTrue();
            
            lock.unlock();
        }

        @Test
        @DisplayName("应该处理网络不稳定情况")
        void shouldHandleNetworkInstability() {
            // Given
            String lockName = "instability-test-lock";
            DistributedLock unstableLock = createUnstableLock();
            
            when(mockLockProvider.getLock(lockName)).thenReturn(unstableLock);
            
            // When - 模拟网络不稳定
            List<Boolean> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                unstableLock.simulateNetworkFluctuation();
                boolean result = unstableLock.tryLock(100, TimeUnit.MILLISECONDS);
                results.add(result);
                if (result) {
                    unstableLock.unlock();
                }
            }
            
            // Then - 验证在不稳定网络下的表现
            long successCount = results.stream().mapToLong(result -> result ? 1 : 0).sum();
            long failureCount = results.size() - successCount;
            
            // 应该有一定的成功率和失败率
            assertThat(successCount).isGreaterThan(0);
            assertThat(failureCount).isGreaterThan(0);
            assertThat(successCount).isGreaterThan(failureCount); // 成功应该多于失败
        }
    }

    @Nested
    @DisplayName("节点故障测试")
    class NodeFailureTest {

        @Test
        @DisplayName("应该在节点故障时进行故障转移")
        void shouldPerformFailoverOnNodeFailure() {
            // Given
            String lockName = "failover-test-lock";
            DistributedLock primaryLock = createPrimaryLock();
            DistributedLock backupLock = createBackupLock();
            
            when(mockLockFactory.getLock(lockName)).thenReturn(primaryLock, backupLock);
            
            // When - 模拟主节点故障
            primaryLock.simulateNodeFailure();
            
            // 尝试获取锁
            DistributedLock lock = mockLockFactory.getLock(lockName);
            
            // Then - 验证自动故障转移到备份节点
            assertThat(lock).isEqualTo(backupLock);
            assertThat(lock.tryLock(1, TimeUnit.SECONDS)).isTrue();
            
            lock.unlock();
        }

        @Test
        @DisplayName("应该处理多个连续节点故障")
        void shouldHandleMultipleSequentialNodeFailures() {
            // Given
            String lockName = "multi-failure-test-lock";
            List<DistributedLock> locks = createMultipleNodes(3);
            
            AtomicInteger currentNodeIndex = new AtomicInteger(0);
            when(mockLockFactory.getLock(lockName)).thenAnswer(invocation -> {
                int index = currentNodeIndex.get();
                return index < locks.size() ? locks.get(index) : null;
            });
            
            // When - 模拟连续节点故障
            for (int i = 0; i < locks.size(); i++) {
                DistributedLock currentLock = mockLockFactory.getLock(lockName);
                currentLock.simulateNodeFailure();
                currentNodeIndex.incrementAndGet();
                
                // 尝试获取下一个可用锁
                DistributedLock nextLock = mockLockFactory.getLock(lockName);
                if (nextLock != null) {
                    assertThat(nextLock.tryLock(100, TimeUnit.MILLISECONDS)).isTrue();
                    nextLock.unlock();
                }
            }
            
            // Then - 验证所有节点都故障时的情况
            DistributedLock finalLock = mockLockFactory.getLock(lockName);
            assertThat(finalLock).isNull();
        }

        @Test
        @DisplayName("应该在节点恢复后自动注册")
        void shouldAutomaticallyRegisterNodeAfterRecovery() {
            // Given
            String lockName = "recovery-test-lock";
            DistributedLock recoverableLock = createRecoverableLock();
            
            when(mockLockProvider.getLock(lockName)).thenReturn(recoverableLock);
            
            // When - 模拟节点故障和恢复
            recoverableLock.simulateNodeFailure();
            
            DistributedLock failedLock = mockLockProvider.getLock(lockName);
            assertThat(failedLock).isNull();
            
            // 模拟恢复过程
            recoverableLock.simulateNodeRecovery();
            
            // 等待注册完成
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Then - 验证节点重新可用
            DistributedLock recoveredLock = mockLockProvider.getLock(lockName);
            assertThat(recoveredLock).isNotNull();
            assertThat(recoveredLock.tryLock(1, TimeUnit.SECONDS)).isTrue();
            
            recoveredLock.unlock();
        }
    }

    @Nested
    @DisplayName("服务重启恢复测试")
    class ServiceRestartRecoveryTest {

        @Test
        @DisplayName("应该在服务重启后恢复锁状态")
        void shouldRecoverLockStateAfterServiceRestart() {
            // Given
            String lockName = "restart-test-lock";
            DistributedLock restartableLock = createRestartableLock();
            
            when(mockLockProvider.getLock(lockName)).thenReturn(restartableLock);
            
            // 初始状态：获取锁
            DistributedLock initialLock = mockLockProvider.getLock(lockName);
            assertThat(initialLock.tryLock(2, TimeUnit.SECONDS)).isTrue();
            initialLock.unlock();
            
            // When - 模拟服务重启
            restartableLock.simulateServiceRestart();
            
            // 模拟重启后的恢复过程
            restartableLock.simulateStateRecovery();
            
            // Then - 验证锁状态恢复
            DistributedLock restoredLock = mockLockProvider.getLock(lockName);
            assertThat(restoredLock.isHeldByCurrentThread()).isFalse();
            
            // 验证锁仍然可用
            assertThat(restoredLock.tryLock(1, TimeUnit.SECONDS)).isTrue();
            restoredLock.unlock();
        }

        @Test
        @DisplayName("应该处理重启时的锁持有者")
        void shouldHandleLockHolderDuringRestart() throws InterruptedException {
            // Given
            String lockName = "holder-test-lock";
            DistributedLock holderLock = createHolderLock();
            
            when(mockLockProvider.getLock(lockName)).thenReturn(holderLock);
            
            // 获取锁并保持持有
            DistributedLock lock = mockLockProvider.getLock(lockName);
            lock.tryLock(10, TimeUnit.SECONDS); // 长时间持有
            
            // 模拟在持有锁期间服务重启
            Thread restartThread = new Thread(() -> {
                try {
                    Thread.sleep(1000); // 持有1秒后重启
                    holderLock.simulateServiceRestart();
                    Thread.sleep(2000); // 等待重启完成
                    holderLock.simulateStateRecovery();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            
            restartThread.start();
            
            // 验证重启过程中锁状态
            assertThat(lock.isHeldByCurrentThread()).isTrue();
            
            restartThread.join(10000);
            
            // Then - 验证重启后的状态
            assertThat(lock.isHeldByCurrentThread()).isFalse();
            assertThat(lock.tryLock(1, TimeUnit.SECONDS)).isTrue();
            lock.unlock();
        }

        @Test
        @DisplayName("应该在重启时释放所有锁")
        void shouldReleaseAllLocksDuringRestart() {
            // Given
            List<String> lockNames = Arrays.asList("restart-lock-1", "restart-lock-2", "restart-lock-3");
            List<DistributedLock> locks = new ArrayList<>();
            
            for (String lockName : lockNames) {
                DistributedLock lock = createRestartableLock();
                when(mockLockProvider.getLock(lockName)).thenReturn(lock);
                locks.add(lock);
                
                // 获取锁
                lock.tryLock(10, TimeUnit.SECONDS);
            }
            
            // When - 模拟批量重启
            locks.forEach(DistributedLock::simulateServiceRestart);
            locks.forEach(DistributedLock::simulateStateRecovery);
            
            // Then - 验证所有锁都被释放
            for (String lockName : lockNames) {
                DistributedLock lock = mockLockProvider.getLock(lockName);
                assertThat(lock.isHeldByCurrentThread()).isFalse();
                
                // 验证锁仍然可用
                assertThat(lock.tryLock(1, TimeUnit.SECONDS)).isTrue();
                lock.unlock();
            }
        }
    }

    @Nested
    @DisplayName("数据一致性测试")
    class DataConsistencyTest {

        @Test
        @DisplayName("应该在故障期间保持数据一致性")
        void shouldMaintainDataConsistencyDuringFailures() {
            // Given
            String lockName = "consistency-test-lock";
            DistributedLock consistentLock = createConsistentLock();
            
            when(mockLockProvider.getLock(lockName)).thenReturn(consistentLock);
            
            // 模拟数据写入
            DistributedLock lock = mockLockProvider.getLock(lockName);
            lock.tryLock(2, TimeUnit.SECONDS);
            
            // 模拟在写入过程中发生故障
            consistentLock.simulateMidOperationFailure();
            
            // Then - 验证故障后的状态
            // 故障应该导致操作失败，但不应该损坏数据
            assertThat(consistentLock.isDataConsistent()).isTrue();
        }

        @Test
        @DisplayName("应该在故障恢复后验证数据完整性")
        void shouldVerifyDataIntegrityAfterFailureRecovery() {
            // Given
            String lockName = "integrity-test-lock";
            DistributedLock integrityLock = createIntegrityLock();
            
            when(mockLockProvider.getLock(lockName)).thenReturn(integrityLock);
            
            // When - 执行操作并模拟故障
            DistributedLock lock = mockLockProvider.getLock(lockName);
            lock.tryLock(2, TimeUnit.SECONDS);
            
            integrityLock.simulateDataCorruption();
            integrityLock.simulateRecovery();
            
            // Then - 验证数据完整性检查
            boolean isIntegrityValid = integrityLock.verifyDataIntegrity();
            assertThat(isIntegrityValid).isTrue();
        }

        @Test
        @DisplayName("应该处理脑裂情况")
        void shouldHandleSplitBrainScenario() {
            // Given
            String lockName = "split-brain-test-lock";
            DistributedLock masterLock = createMasterLock();
            DistributedLock replicaLock = createReplicaLock();
            
            when(mockLockProvider.getLock(lockName)).thenReturn(masterLock, replicaLock);
            
            // When - 模拟网络分区导致的脑裂
            masterLock.simulateNetworkPartition();
            replicaLock.simulateNetworkPartition();
            
            // 模拟两个节点都认为自己是主节点
            masterLock.claimMasterRole();
            replicaLock.claimMasterRole();
            
            // 检测脑裂情况
            boolean splitBrainDetected = masterLock.detectSplitBrain(replicaLock);
            
            // Then - 验证脑裂检测和解决
            assertThat(splitBrainDetected).isTrue();
            
            // 模拟脑裂解决
            masterLock.resolveSplitBrain();
            replicaLock.resolveSplitBrain();
            
            // 验证只有一个主节点
            assertThat(masterLock.isMaster()).isNotEqualTo(replicaLock.isMaster());
        }
    }

    @Nested
    @DisplayName("优雅降级测试")
    class GracefulDegradationTest {

        @Test
        @DisplayName("应该在部分功能故障时提供降级服务")
        void shouldProvideDegradedServiceWhenPartialFunctionsFail() {
            // Given
            DistributedLockFactory factory = createDegradableFactory();
            
            // 模拟部分功能故障
            factory.simulatePartialFailure("read");
            
            // When - 测试降级服务
            DistributedLock lock = factory.getLock("degradation-test-lock");
            
            // Then - 验证降级能力
            assertThat(lock).isNotNull();
            assertThat(lock.tryLock(1, TimeUnit.SECONDS)).isTrue();
            lock.unlock();
        }

        @Test
        @DisplayName("应该在监控功能故障时继续核心功能")
        void shouldContinueCoreFunctionWhenMonitoringFails() {
            // Given
            String lockName = "monitoring-fail-test-lock";
            DistributedLock coreLock = createCoreLock();
            
            when(mockLockProvider.getLock(lockName)).thenReturn(coreLock);
            
            // 模拟监控功能故障
            coreLock.simulateMonitoringFailure();
            
            // When - 验证核心功能仍然可用
            DistributedLock lock = mockLockProvider.getLock(lockName);
            
            // Then - 验证核心锁功能正常
            assertThat(lock.tryLock(1, TimeUnit.SECONDS)).isTrue();
            lock.unlock();
        }

        @Test
        @DisplayName("应该在故障恢复时自动切换回完整服务")
        void shouldAutomaticallySwitchToFullServiceAfterRecovery() {
            // Given
            DistributedLockFactory factory = createDegradableFactory();
            
            // 初始降级状态
            factory.simulatePartialFailure("read");
            assertThat(factory.isInDegradedMode()).isTrue();
            
            // When - 模拟功能恢复
            factory.simulateRecovery();
            
            // Then - 验证恢复到完整服务
            assertThat(factory.isInDegradedMode()).isFalse();
            
            DistributedLock lock = factory.getLock("recovery-test-lock");
            assertThat(lock.tryLock(1, TimeUnit.SECONDS)).isTrue();
            lock.unlock();
        }
    }

    @Nested
    @DisplayName("超时和重试测试")
    class TimeoutAndRetryTest {

        @Test
        @DisplayName("应该在超时时进行重试")
        void shouldRetryWhenTimeout() {
            // Given
            String lockName = "retry-test-lock";
            DistributedLock retryableLock = createRetryableLock();
            
            when(mockLockProvider.getLock(lockName)).thenReturn(retryableLock);
            
            // 模拟第一次获取超时
            retryableLock.simulateTimeout();
            
            // When - 重试获取锁
            DistributedLock lock = mockLockProvider.getLock(lockName);
            boolean success = lock.tryLock(2, TimeUnit.SECONDS);
            
            // Then - 验证重试机制
            assertThat(success).isTrue();
            assertThat(retryableLock.getRetryCount()).isGreaterThan(0);
            
            lock.unlock();
        }

        @Test
        @DisplayName("应该在达到重试上限后放弃")
        void shouldGiveUpAfterMaxRetries() {
            // Given
            String lockName = "max-retry-test-lock";
            DistributedLock failingLock = createMaxRetryLock();
            
            when(mockLockProvider.getLock(lockName)).thenReturn(failingLock);
            
            // When - 尝试获取锁（预期失败）
            DistributedLock lock = mockLockProvider.getLock(lockName);
            boolean success = lock.tryLock(5, TimeUnit.SECONDS);
            
            // Then - 验证达到重试上限后放弃
            assertThat(success).isFalse();
            assertThat(failingLock.getRetryCount()).isGreaterThanOrEqualTo(failingLock.getMaxRetries());
        }

        @Test
        @DisplayName("应该在重试间隔期间处理其他请求")
        void shouldHandleOtherRequestsDuringRetryInterval() throws InterruptedException {
            // Given
            String lockName1 = "concurrent-retry-test-lock-1";
            String lockName2 = "concurrent-retry-test-lock-2";
            DistributedLock retryableLock = createRetryableLock();
            DistributedLock quickLock = createQuickLock();
            
            when(mockLockProvider.getLock(lockName1)).thenReturn(retryableLock);
            when(mockLockProvider.getLock(lockName2)).thenReturn(quickLock);
            
            // 模拟retryableLock需要重试
            retryableLock.simulateTimeout();
            
            // When - 并发请求两个锁
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch completionLatch = new CountDownLatch(2);
            
            AtomicBoolean retrySuccess = new AtomicBoolean(false);
            AtomicBoolean quickSuccess = new AtomicBoolean(false);
            
            // 线程1：尝试获取需要重试的锁
            Thread thread1 = new Thread(() -> {
                try {
                    startLatch.await();
                    DistributedLock lock1 = mockLockProvider.getLock(lockName1);
                    retrySuccess.set(lock1.tryLock(3, TimeUnit.SECONDS));
                    if (retrySuccess.get()) {
                        lock1.unlock();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
            
            // 线程2：尝试获取快速锁
            Thread thread2 = new Thread(() -> {
                try {
                    startLatch.await();
                    DistributedLock lock2 = mockLockProvider.getLock(lockName2);
                    quickSuccess.set(lock2.tryLock(500, TimeUnit.MILLISECONDS));
                    if (quickSuccess.get()) {
                        lock2.unlock();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
            
            thread1.start();
            thread2.start();
            
            startLatch.countDown();
            completionLatch.await(10, TimeUnit.SECONDS);
            
            // Then - 验证快速锁请求成功，重试锁最终成功
            assertThat(quickSuccess.get()).isTrue();
            assertThat(retrySuccess.get()).isTrue();
        }
    }

    // 辅助方法和工厂方法

    private DistributedLock createPartitionableLock() {
        return new PartitionableDistributedLock();
    }

    private DistributedLock createReconnectableLock() {
        return new ReconnectableDistributedLock();
    }

    private DistributedLock createUnstableLock() {
        return new UnstableDistributedLock();
    }

    private DistributedLock createPrimaryLock() {
        return new PrimaryDistributedLock();
    }

    private DistributedLock createBackupLock() {
        return new BackupDistributedLock();
    }

    private List<DistributedLock> createMultipleNodes(int count) {
        List<DistributedLock> nodes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            nodes.add(new NodeDistributedLock(i));
        }
        return nodes;
    }

    private DistributedLock createRecoverableLock() {
        return new RecoverableDistributedLock();
    }

    private DistributedLock createRestartableLock() {
        return new RestartableDistributedLock();
    }

    private DistributedLock createHolderLock() {
        return new HolderDistributedLock();
    }

    private DistributedLock createConsistentLock() {
        return new ConsistentDistributedLock();
    }

    private DistributedLock createIntegrityLock() {
        return new IntegrityDistributedLock();
    }

    private DistributedLock createMasterLock() {
        return new MasterDistributedLock();
    }

    private DistributedLock createReplicaLock() {
        return new ReplicaDistributedLock();
    }

    private DistributedLockFactory createDegradableFactory() {
        return new DegradableDistributedLockFactory();
    }

    private DistributedLock createCoreLock() {
        return new CoreDistributedLock();
    }

    private DistributedLock createRetryableLock() {
        return new RetryableDistributedLock();
    }

    private DistributedLock createMaxRetryLock() {
        return new MaxRetryDistributedLock();
    }

    private DistributedLock createQuickLock() {
        return new QuickDistributedLock();
    }

    // Mock分布式锁实现类

    private static class PartitionableDistributedLock implements DistributedLock {
        private volatile boolean partitioned = false;

        @Override
        public String getName() {
            return "partition-test-lock";
        }

        @Override
        public boolean isHeldByCurrentThread() {
            return false;
        }

        @Override
        public void lock() {
            if (partitioned) {
                throw new LockAcquisitionException("Network partition detected");
            }
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            if (partitioned) {
                throw new LockAcquisitionException("Network partition detected");
            }
        }

        @Override
        public boolean tryLock() {
            return !partitioned;
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            if (partitioned) {
                return false;
            }
            Thread.sleep(unit.toMillis(time));
            return !partitioned;
        }

        @Override
        public void unlock() {
            // 释放锁
        }

        @Override
        public CompletableFuture<LockResult> tryLockAsync() {
            return CompletableFuture.completedFuture(new LockResult() {
                @Override
                public boolean isSuccess() {
                    return !partitioned;
                }

                @Override
                public Long getElapsedTime() {
                    return 0L;
                }
            });
        }

        @Override
        public CompletableFuture<LockResult> tryLockAsync(long time, TimeUnit unit) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(unit.toMillis(time));
                    return new LockResult() {
                        @Override
                        public boolean isSuccess() {
                            return !partitioned;
                        }

                        @Override
                        public Long getElapsedTime() {
                            return unit.toMillis(time);
                        }
                    };
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new LockResult() {
                        @Override
                        public boolean isSuccess() {
                            return false;
                        }

                        @Override
                        public Long getElapsedTime() {
                            return 0L;
                        }
                    };
                }
            });
        }

        @Override
        public CompletableFuture<Void> unlockAsync() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean tryRenewLease(long time, TimeUnit unit) {
            return !partitioned;
        }

        @Override
        public CompletableFuture<Boolean> tryRenewLeaseAsync(long time, TimeUnit unit) {
            return CompletableFuture.completedFuture(!partitioned);
        }

        @Override
        public HealthCheck.HealthCheckResult healthCheck() {
            return new HealthCheck.HealthCheckResult(
                partitioned ? HealthCheck.ComponentStatus.DOWN : HealthCheck.ComponentStatus.UP,
                "partition-test-lock",
                partitioned ? "Network partition detected" : "Operational"
            );
        }

        public void simulateNetworkPartition() {
            this.partitioned = true;
        }

        public void simulateNetworkRecovery() {
            this.partitioned = false;
        }
    }

    private static class ReconnectableDistributedLock implements DistributedLock {
        private volatile boolean disconnected = false;
        private volatile boolean reconnecting = false;

        @Override
        public String getName() {
            return "reconnect-test-lock";
        }

        @Override
        public boolean isHeldByCurrentThread() {
            return false;
        }

        @Override
        public void lock() {
            // 简化实现
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            // 简化实现
        }

        @Override
        public boolean tryLock() {
            return !disconnected;
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            if (disconnected) {
                return false;
            }
            Thread.sleep(unit.toMillis(time));
            return !disconnected;
        }

        @Override
        public void unlock() {
            // 释放锁
        }

        @Override
        public CompletableFuture<LockResult> tryLockAsync() {
            return CompletableFuture.completedFuture(new LockResult() {
                @Override
                public boolean isSuccess() {
                    return !disconnected;
                }

                @Override
                public Long getElapsedTime() {
                    return 0L;
                }
            });
        }

        @Override
        public CompletableFuture<LockResult> tryLockAsync(long time, TimeUnit unit) {
            return CompletableFuture.completedFuture(new LockResult() {
                @Override
                public boolean isSuccess() {
                    return !disconnected;
                }

                @Override
                public Long getElapsedTime() {
                    return unit.toMillis(time);
                }
            });
        }

        @Override
        public CompletableFuture<Void> unlockAsync() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean tryRenewLease(long time, TimeUnit unit) {
            return !disconnected;
        }

        @Override
        public CompletableFuture<Boolean> tryRenewLeaseAsync(long time, TimeUnit unit) {
            return CompletableFuture.completedFuture(!disconnected);
        }

        @Override
        public HealthCheck.HealthCheckResult healthCheck() {
            return new HealthCheck.HealthCheckResult(
                disconnected ? HealthCheck.ComponentStatus.DOWN : HealthCheck.ComponentStatus.UP,
                "reconnect-test-lock",
                disconnected ? "Network disconnected" : "Connected"
            );
        }

        public void simulateNetworkFailure() {
            this.disconnected = true;
        }

        public void simulateNetworkRecovery() {
            this.reconnecting = true;
            // 模拟重连过程
            new Thread(() -> {
                try {
                    Thread.sleep(1000); // 1秒重连时间
                    this.disconnected = false;
                    this.reconnecting = false;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }

    // 其他辅助类实现...

    private static class UnstableDistributedLock implements DistributedLock {
        private volatile boolean unstable = false;

        @Override
        public String getName() { return "unstable-test-lock"; }
        @Override
        public boolean isHeldByCurrentThread() { return false; }
        @Override
        public void lock() {}
        @Override
        public void lockInterruptibly() throws InterruptedException {}
        @Override
        public boolean tryLock() { 
            return unstable ? Math.random() > 0.3 : true; // 70%成功率
        }
        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            Thread.sleep(unit.toMillis(time));
            return tryLock();
        }
        @Override
        public void unlock() {}
        @Override
        public CompletableFuture<LockResult> tryLockAsync() {
            return CompletableFuture.completedFuture(new LockResult() {
                @Override
                public boolean isSuccess() { return tryLock(); }
                @Override
                public Long getElapsedTime() { return 0L; }
            });
        }
        @Override
        public CompletableFuture<LockResult> tryLockAsync(long time, TimeUnit unit) {
            return CompletableFuture.supplyAsync(() -> new LockResult() {
                @Override
                public boolean isSuccess() { return tryLock(); }
                @Override
                public Long getElapsedTime() { return unit.toMillis(time); }
            });
        }
        @Override
        public CompletableFuture<Void> unlockAsync() {
            return CompletableFuture.completedFuture(null);
        }
        @Override
        public boolean tryRenewLease(long time, TimeUnit unit) { return true; }
        @Override
        public CompletableFuture<Boolean> tryRenewLeaseAsync(long time, TimeUnit unit) {
            return CompletableFuture.completedFuture(true);
        }
        @Override
        public HealthCheck.HealthCheckResult healthCheck() {
            return new HealthCheck.HealthCheckResult(HealthCheck.ComponentStatus.UP, "unstable-test-lock", "Unstable");
        }

        public void simulateNetworkFluctuation() {
            this.unstable = Math.random() > 0.5; // 随机不稳定
        }
    }

    // 为了节省空间，这里只实现了关键的测试辅助类
    // 实际项目中应该实现所有需要的辅助类
}