package com.mycorp.distributedlock.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 分布式锁工厂API接口的完整单元测试
 * 测试工厂管理、健康检查、批量操作、状态监控等企业级功能
 */
@ExtendWith(MockitoExtension.class)
class DistributedLockFactoryApiTest {

    @Mock
    private LockProvider mockProvider;

    private DistributedLockFactory factory;
    private static final String LOCK_NAME = "test-lock";
    private static final String READ_WRITE_LOCK_NAME = "test-rw-lock";

    @BeforeEach
    void setUp() {
        factory = createTestFactory();
    }

    private DistributedLockFactory createTestFactory() {
        return new DistributedLockFactory() {
            private volatile boolean shutdown = false;
            private volatile ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);

            @Override
            public DistributedLock getLock(String name) {
                if (shutdown) {
                    throw new IllegalStateException("Factory is shutdown");
                }
                return createTestLock(name);
            }

            @Override
            public DistributedReadWriteLock getReadWriteLock(String name) {
                if (shutdown) {
                    throw new IllegalStateException("Factory is shutdown");
                }
                return createTestReadWriteLock(name);
            }

            @Override
            public DistributedLock getConfiguredLock(String name, LockConfigurationBuilder.LockConfiguration configuration) {
                return getLock(name); // 简化为基础方法
            }

            @Override
            public DistributedReadWriteLock getConfiguredReadWriteLock(String name, LockConfigurationBuilder.LockConfiguration configuration) {
                return getReadWriteLock(name); // 简化为基础方法
            }

            @Override
            public Map<String, DistributedLock> getLocks(List<String> lockNames) {
                return lockNames.stream()
                    .collect(java.util.stream.Collectors.toMap(
                        name -> name,
                        this::getLock
                    ));
            }

            @Override
            public Map<String, DistributedReadWriteLock> getReadWriteLocks(List<String> lockNames) {
                return lockNames.stream()
                    .collect(java.util.stream.Collectors.toMap(
                        name -> name,
                        this::getReadWriteLock
                    ));
            }

            @Override
            public CompletableFuture<DistributedLock> getLockAsync(String name) {
                return CompletableFuture.supplyAsync(() -> getLock(name));
            }

            @Override
            public CompletableFuture<DistributedReadWriteLock> getReadWriteLockAsync(String name) {
                return CompletableFuture.supplyAsync(() -> getReadWriteLock(name));
            }

            @Override
            public <R> BatchLockOperations<DistributedLock> createBatchLockOperations(List<String> lockNames, BatchLockOperations.BatchOperationExecutor<R> operation) {
                return new BatchLockOperations<DistributedLock>() {
                    @Override
                    public BatchLockResult<DistributedLock> batchLock(java.util.List<String> lockNames, long leaseTime, java.util.concurrent.TimeUnit unit) throws InterruptedException {
                        java.util.List<DistributedLock> locks = new java.util.ArrayList<>();
                        for (String lockName : lockNames) {
                            DistributedLock lock = getLock(lockName);
                            lock.lock(leaseTime, unit);
                            locks.add(lock);
                        }
                        return new BatchLockResult<DistributedLock>() {
                            @Override
                            public boolean isSuccess() {
                                return true;
                            }

                            @Override
                            public java.util.List<DistributedLock> getLocks() {
                                return locks;
                            }

                            @Override
                            public java.util.List<String> getFailedLocks() {
                                return java.util.Collections.emptyList();
                            }

                            @Override
                            public long getOperationTimeMs() {
                                return System.currentTimeMillis();
                            }
                        };
                    }

                    @Override
                    public java.util.concurrent.CompletableFuture<BatchLockResult<DistributedLock>> batchLockAsync(java.util.List<String> lockNames, long leaseTime, java.util.concurrent.TimeUnit unit) {
                        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                            try {
                                return batchLock(lockNames, leaseTime, unit);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(e);
                            }
                        });
                    }

                    @Override
                    public boolean batchUnlock(java.util.List<DistributedLock> locks) {
                        boolean result = true;
                        for (DistributedLock lock : locks) {
                            try {
                                if (lock.isHeldByCurrentThread()) {
                                    lock.unlock();
                                }
                            } catch (Exception e) {
                                result = false;
                            }
                        }
                        return result;
                    }

                    @Override
                    public java.util.concurrent.CompletableFuture<Boolean> batchUnlockAsync(java.util.List<DistributedLock> locks) {
                        return java.util.concurrent.CompletableFuture.supplyAsync(() -> batchUnlock(locks));
                    }

                    @Override
                    public <R1> R1 executeInTransaction(java.util.List<String> lockNames, long leaseTime, java.util.concurrent.TimeUnit unit, TransactionalLockCallback<R1, DistributedLock> transactionCallback) throws Exception {
                        // 简化的事务实现
                        java.util.List<DistributedLock> locks = new java.util.ArrayList<>();
                        try {
                            for (String lockName : lockNames) {
                                DistributedLock lock = getLock(lockName);
                                lock.lock(leaseTime, unit);
                                locks.add(lock);
                            }
                            return transactionCallback.execute(locks);
                        } finally {
                            for (DistributedLock lock : locks) {
                                try {
                                    if (lock.isHeldByCurrentThread()) {
                                        lock.unlock();
                                    }
                                } catch (Exception e) {
                                    // 忽略解锁异常
                                }
                            }
                        }
                    }

                    @Override
                    public <R1> java.util.concurrent.CompletableFuture<R1> executeInTransactionAsync(java.util.List<String> lockNames, long leaseTime, java.util.concurrent.TimeUnit unit, TransactionalLockCallback<R1, DistributedLock> transactionCallback) {
                        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                            try {
                                return executeInTransaction(lockNames, leaseTime, unit, transactionCallback);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
                    }

                    @Override
                    public DistributedLock getLock(String name) {
                        return DistributedLockFactory.this.getLock(name);
                    }
                };
            }

            @Override
            public AsyncLockOperations<DistributedLock> createAsyncLockOperations() {
                return new AsyncLockOperations<DistributedLock>() {
                    @Override
                    public java.util.concurrent.CompletableFuture<AsyncLockResult<DistributedLock>> acquireLockAsync(String lockName, long leaseTime, java.util.concurrent.TimeUnit timeUnit) {
                        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                            try {
                                DistributedLock lock = getLock(lockName);
                                lock.lock(leaseTime, timeUnit);
                                return new AsyncLockResult<DistributedLock>() {
                                    @Override
                                    public boolean isSuccess() {
                                        return true;
                                    }

                                    @Override
                                    public DistributedLock getLock() {
                                        return lock;
                                    }

                                    @Override
                                    public Throwable getFailureCause() {
                                        return null;
                                    }

                                    @Override
                                    public long getOperationTimeMs() {
                                        return 0;
                                    }

                                    @Override
                                    public long getAcquisitionTime() {
                                        return System.currentTimeMillis();
                                    }

                                    @Override
                                    public long getExpirationTime() {
                                        return System.currentTimeMillis() + java.util.concurrent.TimeUnit.MILLISECONDS.convert(leaseTime, timeUnit);
                                    }
                                };
                            } catch (Exception e) {
                                return new AsyncLockResult<DistributedLock>() {
                                    @Override
                                    public boolean isSuccess() {
                                        return false;
                                    }

                                    @Override
                                    public DistributedLock getLock() {
                                        return null;
                                    }

                                    @Override
                                    public Throwable getFailureCause() {
                                        return e;
                                    }

                                    @Override
                                    public long getOperationTimeMs() {
                                        return 0;
                                    }

                                    @Override
                                    public long getAcquisitionTime() {
                                        return 0;
                                    }

                                    @Override
                                    public long getExpirationTime() {
                                        return 0;
                                    }
                                };
                            }
                        });
                    }

                    @Override
                    public java.util.concurrent.CompletableFuture<AsyncLockResult<DistributedLock>> tryAcquireLockAsync(String lockName, long waitTime, long leaseTime, java.util.concurrent.TimeUnit timeUnit) {
                        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                            try {
                                DistributedLock lock = getLock(lockName);
                                boolean success = lock.tryLock(waitTime, leaseTime, timeUnit);
                                return new AsyncLockResult<DistributedLock>() {
                                    @Override
                                    public boolean isSuccess() {
                                        return success;
                                    }

                                    @Override
                                    public DistributedLock getLock() {
                                        return success ? lock : null;
                                    }

                                    @Override
                                    public Throwable getFailureCause() {
                                        return success ? null : new RuntimeException("Lock acquisition failed");
                                    }

                                    @Override
                                    public long getOperationTimeMs() {
                                        return 0;
                                    }

                                    @Override
                                    public long getAcquisitionTime() {
                                        return success ? System.currentTimeMillis() : 0;
                                    }

                                    @Override
                                    public long getExpirationTime() {
                                        return success ? System.currentTimeMillis() + java.util.concurrent.TimeUnit.MILLISECONDS.convert(leaseTime, timeUnit) : 0;
                                    }
                                };
                            } catch (Exception e) {
                                return new AsyncLockResult<DistributedLock>() {
                                    @Override
                                    public boolean isSuccess() {
                                        return false;
                                    }

                                    @Override
                                    public DistributedLock getLock() {
                                        return null;
                                    }

                                    @Override
                                    public Throwable getFailureCause() {
                                        return e;
                                    }

                                    @Override
                                    public long getOperationTimeMs() {
                                        return 0;
                                    }

                                    @Override
                                    public long getAcquisitionTime() {
                                        return 0;
                                    }

                                    @Override
                                    public long getExpirationTime() {
                                        return 0;
                                    }
                                };
                            }
                        });
                    }

                    @Override
                    public java.util.concurrent.CompletableFuture<AsyncLockReleaseResult> releaseLockAsync(DistributedLock lock) {
                        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                            try {
                                if (lock.isHeldByCurrentThread()) {
                                    lock.unlock();
                                }
                                return new AsyncLockReleaseResult() {
                                    @Override
                                    public boolean isSuccess() {
                                        return true;
                                    }

                                    @Override
                                    public Throwable getFailureCause() {
                                        return null;
                                    }

                                    @Override
                                    public long getOperationTimeMs() {
                                        return 0;
                                    }
                                };
                            } catch (Exception e) {
                                return new AsyncLockReleaseResult() {
                                    @Override
                                    public boolean isSuccess() {
                                        return false;
                                    }

                                    @Override
                                    public Throwable getFailureCause() {
                                        return e;
                                    }

                                    @Override
                                    public long getOperationTimeMs() {
                                        return 0;
                                    }
                                };
                            }
                        });
                    }

                    // 简化其他方法的实现
                    @Override
                    public java.util.concurrent.CompletableFuture<AsyncLockRenewalResult> renewLockAsync(DistributedLock lock, long newLeaseTime, java.util.concurrent.TimeUnit timeUnit) {
                        throw new UnsupportedOperationException("Renew lock not implemented");
                    }

                    @Override
                    public java.util.concurrent.CompletableFuture<AsyncLockInfoResult> getLockInfoAsync(String lockName) {
                        throw new UnsupportedOperationException("Get lock info not implemented");
                    }

                    @Override
                    public java.util.concurrent.CompletableFuture<AsyncLockStateResult> checkLockStateAsync(String lockName) {
                        throw new UnsupportedOperationException("Check lock state not implemented");
                    }

                    @Override
                    public java.util.concurrent.CompletableFuture<AsyncLockReleaseResult> releaseLockByNameAsync(String lockName) {
                        throw new UnsupportedOperationException("Release lock by name not implemented");
                    }

                    @Override
                    public java.util.concurrent.CompletableFuture<AsyncWaitResult> waitForLockReleaseAsync(String lockName, long timeout, java.util.concurrent.TimeUnit timeUnit) {
                        throw new UnsupportedOperationException("Wait for lock release not implemented");
                    }

                    @Override
                    public <R> java.util.concurrent.CompletableFuture<AsyncBusinessOperationResult<R>> executeWithLockAsync(String lockName, long leaseTime, java.util.concurrent.TimeUnit timeUnit, AsyncBusinessOperation<R, DistributedLock> businessOperation) {
                        throw new UnsupportedOperationException("Execute with lock not implemented");
                    }

                    @Override
                    public <R> java.util.concurrent.CompletableFuture<AsyncRetryOperationResult<R>> executeWithRetryAsync(String lockName, long leaseTime, java.util.concurrent.TimeUnit timeUnit, int maxRetries, long retryDelay, java.util.concurrent.TimeUnit retryDelayTimeUnit, AsyncRetryableLockOperation<R, DistributedLock> operation) {
                        throw new UnsupportedOperationException("Execute with retry not implemented");
                    }

                    @Override
                    public LockRenewalTask autoRenewLock(DistributedLock lock, long renewInterval, java.util.concurrent.TimeUnit timeUnit) {
                        throw new UnsupportedOperationException("Auto renew lock not implemented");
                    }

                    @Override
                    public void cancelAutoRenew(LockRenewalTask renewalTask) {
                        throw new UnsupportedOperationException("Cancel auto renew not implemented");
                    }

                    @Override
                    public AsyncOperationConfiguration getConfiguration() {
                        return new AsyncOperationConfiguration() {
                            @Override
                            public long getDefaultTimeoutMs() {
                                return 10000;
                            }

                            @Override
                            public int getDefaultRetryCount() {
                                return 3;
                            }

                            @Override
                            public long getDefaultRetryDelayMs() {
                                return 1000;
                            }

                            @Override
                            public double getRenewalIntervalRatio() {
                                return 0.7;
                            }

                            @Override
                            public int getMaxConcurrency() {
                                return 100;
                            }

                            @Override
                            public boolean isAutoRenewalEnabled() {
                                return false;
                            }

                            @Override
                            public AsyncOperationConfiguration setDefaultTimeoutMs(long timeoutMs) {
                                return this;
                            }

                            @Override
                            public AsyncOperationConfiguration setDefaultRetryCount(int retryCount) {
                                return this;
                            }

                            @Override
                            public AsyncOperationConfiguration setDefaultRetryDelayMs(long retryDelayMs) {
                                return this;
                            }

                            @Override
                            public AsyncOperationConfiguration setRenewalIntervalRatio(double ratio) {
                                return this;
                            }

                            @Override
                            public AsyncOperationConfiguration setMaxConcurrency(int maxConcurrency) {
                                return this;
                            }

                            @Override
                            public AsyncOperationConfiguration setAutoRenewalEnabled(boolean autoRenewalEnabled) {
                                return this;
                            }
                        };
                    }

                    @Override
                    public void updateConfiguration(AsyncOperationConfiguration configuration) {
                        // 空实现
                    }

                    @Override
                    public void close() {
                        // 空实现
                    }
                };
            }

            @Override
            public FactoryHealthStatus healthCheck() {
                return new FactoryHealthStatus() {
                    @Override
                    public boolean isHealthy() {
                        return !shutdown;
                    }

                    @Override
                    public String getDetails() {
                        return shutdown ? "Factory is shutdown" : "Factory is healthy";
                    }

                    @Override
                    public long getCheckTime() {
                        return System.currentTimeMillis();
                    }

                    @Override
                    public PerformanceMetrics getPerformanceMetrics() {
                        return new PerformanceMetrics() {
                            @Override
                            public long getResponseTimeMs() {
                                return shutdown ? -1 : 5;
                            }

                            @Override
                            public double getThroughput() {
                                return shutdown ? 0.0 : 1000.0;
                            }

                            @Override
                            public double getErrorRate() {
                                return shutdown ? 1.0 : 0.01;
                            }

                            @Override
                            public int getActiveConnections() {
                                return shutdown ? 0 : 1;
                            }
                        };
                    }

                    @Override
                    public String getErrorMessage() {
                        return shutdown ? "Factory is shutdown" : null;
                    }
                };
            }

            @Override
            public CompletableFuture<FactoryHealthStatus> healthCheckAsync() {
                return CompletableFuture.supplyAsync(this::healthCheck);
            }

            @Override
            public boolean isLockAvailable(String name) {
                try {
                    DistributedLock lock = getLock(name);
                    return lock.tryLock(0, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public CompletableFuture<Boolean> isLockAvailableAsync(String name) {
                return CompletableFuture.supplyAsync(() -> isLockAvailable(name));
            }

            @Override
            public FactoryStatistics getStatistics() {
                return new FactoryStatistics() {
                    @Override
                    public long getTotalLocks() {
                        return 1;
                    }

                    @Override
                    public long getActiveLocks() {
                        return shutdown ? 0 : 0;
                    }

                    @Override
                    public long getTotalLockAcquisitions() {
                        return shutdown ? 0 : 100;
                    }

                    @Override
                    public long getFailedLockAcquisitions() {
                        return shutdown ? 0 : 1;
                    }

                    @Override
                    public long getTotalLockReleases() {
                        return shutdown ? 0 : 99;
                    }

                    @Override
                    public double getAverageLockAcquisitionTime() {
                        return shutdown ? 0.0 : 5.2;
                    }

                    @Override
                    public int getPeakConcurrency() {
                        return shutdown ? 0 : 10;
                    }

                    @Override
                    public long getUptime() {
                        return shutdown ? 0 : System.currentTimeMillis() - 1000000;
                    }

                    @Override
                    public MemoryUsage getMemoryUsage() {
                        return new MemoryUsage() {
                            @Override
                            public long getUsedMemory() {
                                return shutdown ? 0 : 1024 * 1024;
                            }

                            @Override
                            public long getTotalMemory() {
                                return shutdown ? 0 : 10 * 1024 * 1024;
                            }

                            @Override
                            public long getAvailableMemory() {
                                return shutdown ? 0 : 9 * 1024 * 1024;
                            }

                            @Override
                            public double getUsageRate() {
                                return shutdown ? 0.0 : 0.1024;
                            }
                        };
                    }
                };
            }

            @Override
            public void resetStatistics() {
                // 空实现
            }

            @Override
            public List<String> getActiveLocks() {
                return shutdown ? java.util.Collections.emptyList() : java.util.Collections.emptyList();
            }

            @Override
            public boolean releaseLock(String name) {
                DistributedLock lock = getLock(name);
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                    return true;
                }
                return false;
            }

            @Override
            public CompletableFuture<Boolean> releaseLockAsync(String name) {
                return CompletableFuture.supplyAsync(() -> releaseLock(name));
            }

            @Override
            public int cleanupExpiredLocks() {
                return shutdown ? 0 : 0;
            }

            @Override
            public CompletableFuture<Integer> cleanupExpiredLocksAsync() {
                return CompletableFuture.supplyAsync(this::cleanupExpiredLocks);
            }

            @Override
            public FactoryConfiguration getConfiguration() {
                return new FactoryConfiguration() {
                    @Override
                    public int getMaxLocks() {
                        return 1000;
                    }

                    @Override
                    public long getConnectionTimeout(java.util.concurrent.TimeUnit unit) {
                        return unit.convert(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
                    }

                    @Override
                    public long getOperationTimeout(java.util.concurrent.TimeUnit unit) {
                        return unit.convert(10000, java.util.concurrent.TimeUnit.MILLISECONDS);
                    }

                    @Override
                    public int getRetryCount() {
                        return 3;
                    }

                    @Override
                    public long getRetryInterval(java.util.concurrent.TimeUnit unit) {
                        return unit.convert(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                    }

                    @Override
                    public boolean isCacheEnabled() {
                        return true;
                    }

                    @Override
                    public boolean isMonitoringEnabled() {
                        return false;
                    }

                    @Override
                    public boolean isMetricsEnabled() {
                        return false;
                    }

                    @Override
                    public FactoryConfiguration setMaxLocks(int maxLocks) {
                        return this;
                    }

                    @Override
                    public FactoryConfiguration setConnectionTimeout(long timeout, java.util.concurrent.TimeUnit unit) {
                        return this;
                    }

                    @Override
                    public FactoryConfiguration setOperationTimeout(long timeout, java.util.concurrent.TimeUnit unit) {
                        return this;
                    }

                    @Override
                    public FactoryConfiguration setRetryCount(int retryCount) {
                        return this;
                    }

                    @Override
                    public FactoryConfiguration setRetryInterval(long interval, java.util.concurrent.TimeUnit unit) {
                        return this;
                    }

                    @Override
                    public FactoryConfiguration setCacheEnabled(boolean enabled) {
                        return this;
                    }

                    @Override
                    public FactoryConfiguration setMonitoringEnabled(boolean enabled) {
                        return this;
                    }

                    @Override
                    public FactoryConfiguration setMetricsEnabled(boolean enabled) {
                        return this;
                    }
                };
            }

            @Override
            public void updateConfiguration(FactoryConfiguration configuration) {
                // 空实现
            }

            @Override
            public FactoryState getState() {
                return shutdown ? FactoryState.SHUTDOWN : FactoryState.RUNNING;
            }

            @Override
            public void setState(FactoryState state) {
                if (state == FactoryState.SHUTDOWN) {
                    shutdown = true;
                }
            }

            @Override
            public void registerEventListener(LockEventListener<DistributedLock> listener) {
                // 空实现
            }

            @Override
            public void unregisterEventListener(LockEventListener<DistributedLock> listener) {
                // 空实现
            }

            @Override
            public List<HighAvailabilityStrategy<?>> getSupportedHighAvailabilityStrategies() {
                return java.util.Collections.emptyList();
            }

            @Override
            public void setHighAvailabilityStrategy(HighAvailabilityStrategy<?> strategy) {
                // 空实现
            }

            @Override
            public ScheduledExecutorService getScheduledExecutorService() {
                return executor;
            }

            @Override
            public void setScheduledExecutorService(ScheduledExecutorService executor) {
                this.executor = executor;
            }

            @Override
            public String getFactoryName() {
                return "TestDistributedLockFactory";
            }

            @Override
            public String getFactoryVersion() {
                return "1.0.0-test";
            }

            @Override
            public boolean gracefulShutdown(long timeout, java.util.concurrent.TimeUnit unit) {
                shutdown = true;
                if (executor != null) {
                    executor.shutdown();
                }
                return true;
            }

            @Override
            public boolean isShutdown() {
                return shutdown;
            }

            @Override
            public boolean isTerminated() {
                return shutdown && (executor == null || executor.isTerminated());
            }

            @Override
            public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
                if (executor != null) {
                    return executor.awaitTermination(timeout, unit);
                }
                return isTerminated();
            }

            @Override
            public void shutdown() {
                shutdown = true;
                if (executor != null) {
                    executor.shutdown();
                }
            }

            @Override
            public void close() {
                shutdown();
            }

            private DistributedLock createTestLock(String name) {
                return new DistributedLock() {
                    private volatile boolean locked = false;
                    private volatile String holder = null;

                    @Override
                    public void lock(long leaseTime, java.util.concurrent.TimeUnit unit) throws InterruptedException {
                        locked = true;
                        holder = Thread.currentThread().getName();
                    }

                    @Override
                    public void lock() throws InterruptedException {
                        lock(30, java.util.concurrent.TimeUnit.SECONDS);
                    }

                    @Override
                    public boolean tryLock(long waitTime, long leaseTime, java.util.concurrent.TimeUnit unit) throws InterruptedException {
                        if (locked) {
                            return false;
                        }
                        lock(leaseTime, unit);
                        return true;
                    }

                    @Override
                    public boolean tryLock() throws InterruptedException {
                        return tryLock(0, 30, java.util.concurrent.TimeUnit.SECONDS);
                    }

                    @Override
                    public void unlock() {
                        locked = false;
                        holder = null;
                    }

                    @Override
                    public CompletableFuture<Void> lockAsync(long leaseTime, java.util.concurrent.TimeUnit unit) {
                        return CompletableFuture.runAsync(() -> {
                            try {
                                lock(leaseTime, unit);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
                    }

                    @Override
                    public CompletableFuture<Void> lockAsync() {
                        return lockAsync(30, java.util.concurrent.TimeUnit.SECONDS);
                    }

                    @Override
                    public CompletableFuture<Boolean> tryLockAsync(long waitTime, long leaseTime, java.util.concurrent.TimeUnit unit) {
                        return CompletableFuture.supplyAsync(() -> {
                            try {
                                return tryLock(waitTime, leaseTime, unit);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return false;
                            }
                        });
                    }

                    @Override
                    public CompletableFuture<Boolean> tryLockAsync() {
                        return tryLockAsync(0, 30, java.util.concurrent.TimeUnit.SECONDS);
                    }

                    @Override
                    public CompletableFuture<Void> unlockAsync() {
                        return CompletableFuture.runAsync(this::unlock);
                    }

                    @Override
                    public boolean isLocked() {
                        return locked;
                    }

                    @Override
                    public boolean isHeldByCurrentThread() {
                        return locked && holder != null && holder.equals(Thread.currentThread().getName());
                    }

                    @Override
                    public String getName() {
                        return name;
                    }

                    @Override
                    public ScheduledFuture<?> scheduleAutoRenewal(long renewInterval, java.util.concurrent.TimeUnit unit, Consumer<RenewalResult> renewalCallback) {
                        return null;
                    }
                };
            }

            private DistributedReadWriteLock createTestReadWriteLock(String name) {
                return new DistributedReadWriteLock() {
                    private volatile boolean readLocked = false;
                    private volatile boolean writeLocked = false;

                    @Override
                    public DistributedLock readLock() {
                        return createTestLock(name + ":read");
                    }

                    @Override
                    public DistributedLock writeLock() {
                        return createTestLock(name + ":write");
                    }

                    @Override
                    public String getName() {
                        return name;
                    }

                    @Override
                    public boolean tryUpgradeToWriteLock(long waitTime, long leaseTime, java.util.concurrent.TimeUnit unit) throws InterruptedException {
                        return false;
                    }

                    @Override
                    public CompletableFuture<Boolean> tryUpgradeToWriteLockAsync(long waitTime, long leaseTime, java.util.concurrent.TimeUnit unit) {
                        return CompletableFuture.supplyAsync(() -> false);
                    }

                    @Override
                    public boolean tryDowngradeToReadLock(long leaseTime, java.util.concurrent.TimeUnit unit) {
                        return false;
                    }

                    @Override
                    public CompletableFuture<Boolean> tryDowngradeToReadLockAsync(long leaseTime, java.util.concurrent.TimeUnit unit) {
                        return CompletableFuture.supplyAsync(() -> false);
                    }

                    @Override
                    public ReadWriteLockStateInfo getReadWriteLockStateInfo() {
                        return new ReadWriteLockStateInfo() {
                            @Override
                            public boolean isReadLocked() {
                                return readLocked;
                            }

                            @Override
                            public boolean isWriteLocked() {
                                return writeLocked;
                            }

                            @Override
                            public boolean isReadLockedByCurrentThread() {
                                return false;
                            }

                            @Override
                            public boolean isWriteLockedByCurrentThread() {
                                return false;
                            }

                            @Override
                            public int getReadLockCount() {
                                return readLocked ? 1 : 0;
                            }

                            @Override
                            public int getReadLockCountByCurrentThread() {
                                return 0;
                            }

                            @Override
                            public String getWriteLockHolder() {
                                return null;
                            }

                            @Override
                            public String getReadLockHolders() {
                                return "none";
                            }

                            @Override
                            public long getReadLockRemainingTime(java.util.concurrent.TimeUnit unit) {
                                return 0;
                            }

                            @Override
                            public long getWriteLockRemainingTime(java.util.concurrent.TimeUnit unit) {
                                return 0;
                            }

                            @Override
                            public boolean isFairMode() {
                                return false;
                            }

                            @Override
                            public LockUpgradeMode getUpgradeMode() {
                                return LockUpgradeMode.NOT_SUPPORTED;
                            }

                            @Override
                            public ReadWriteLockType getLockType() {
                                return ReadWriteLockType.STANDARD;
                            }
                        };
                    }

                    @Override
                    public ReadWriteLockConfigurationInfo getConfigurationInfo() {
                        return new ReadWriteLockConfigurationInfo() {
                            @Override
                            public boolean isFairLock() {
                                return false;
                            }

                            @Override
                            public boolean isReentrant() {
                                return true;
                            }

                            @Override
                            public long getDefaultReadLockLeaseTime(java.util.concurrent.TimeUnit unit) {
                                return unit.convert(60, java.util.concurrent.TimeUnit.SECONDS);
                            }

                            @Override
                            public long getDefaultWriteLockLeaseTime(java.util.concurrent.TimeUnit unit) {
                                return unit.convert(30, java.util.concurrent.TimeUnit.SECONDS);
                            }

                            @Override
                            public long getDefaultReadLockWaitTime(java.util.concurrent.TimeUnit unit) {
                                return unit.convert(10, java.util.concurrent.TimeUnit.SECONDS);
                            }

                            @Override
                            public long getDefaultWriteLockWaitTime(java.util.concurrent.TimeUnit unit) {
                                return unit.convert(15, java.util.concurrent.TimeUnit.SECONDS);
                            }

                            @Override
                            public boolean isUpgradeSupported() {
                                return false;
                            }

                            @Override
                            public boolean isDowngradeSupported() {
                                return true;
                            }

                            @Override
                            public LockUpgradeMode getUpgradeMode() {
                                return LockUpgradeMode.NOT_SUPPORTED;
                            }

                            @Override
                            public int getMaxReadLockCount() {
                                return Integer.MAX_VALUE;
                            }

                            @Override
                            public boolean isReadLockPreemptionEnabled() {
                                return false;
                            }

                            @Override
                            public boolean isWriteLockPriority() {
                                return true;
                            }
                        };
                    }

                    @Override
                    public ReadWriteLockHealthCheckResult healthCheck() {
                        return new ReadWriteLockHealthCheckResult() {
                            @Override
                            public boolean isHealthy() {
                                return true;
                            }

                            @Override
                            public boolean isReadLockHealthy() {
                                return true;
                            }

                            @Override
                            public boolean isWriteLockHealthy() {
                                return true;
                            }

                            @Override
                            public String getDetails() {
                                return "Healthy";
                            }

                            @Override
                            public long getCheckTime() {
                                return System.currentTimeMillis();
                            }
                        };
                    }
                };
            }
        };
    }

    @Test
    void shouldCreateLockSuccessfully() {
        assertDoesNotThrow(() -> {
            DistributedLock lock = factory.getLock(LOCK_NAME);
            assertNotNull(lock);
            assertEquals(LOCK_NAME, lock.getName());
        });
    }

    @Test
    void shouldCreateReadWriteLockSuccessfully() {
        assertDoesNotThrow(() -> {
            DistributedReadWriteLock rwLock = factory.getReadWriteLock(READ_WRITE_LOCK_NAME);
            assertNotNull(rwLock);
            assertEquals(READ_WRITE_LOCK_NAME, rwLock.getName());
        });
    }

    @Test
    void shouldCreateConfiguredLock() {
        LockConfigurationBuilder.LockConfiguration config = new LockConfigurationBuilder.LockConfiguration() {
            @Override
            public long getLeaseTime(java.util.concurrent.TimeUnit unit) {
                return unit.convert(60, java.util.concurrent.TimeUnit.SECONDS);
            }

            @Override
            public long getWaitTime(java.util.concurrent.TimeUnit unit) {
                return unit.convert(10, java.util.concurrent.TimeUnit.SECONDS);
            }

            @Override
            public boolean isFairLock() {
                return false;
            }

            @Override
            public boolean isReentrant() {
                return true;
            }

            @Override
            public boolean isAutoRenewalEnabled() {
                return false;
            }
        };

        assertDoesNotThrow(() -> {
            DistributedLock lock = factory.getConfiguredLock(LOCK_NAME, config);
            assertNotNull(lock);
            assertEquals(LOCK_NAME, lock.getName());
        });
    }

    @Test
    void shouldCreateConfiguredReadWriteLock() {
        LockConfigurationBuilder.LockConfiguration config = new LockConfigurationBuilder.LockConfiguration() {
            @Override
            public long getLeaseTime(java.util.concurrent.TimeUnit unit) {
                return unit.convert(60, java.util.concurrent.TimeUnit.SECONDS);
            }

            @Override
            public long getWaitTime(java.util.concurrent.TimeUnit unit) {
                return unit.convert(10, java.util.concurrent.TimeUnit.SECONDS);
            }

            @Override
            public boolean isFairLock() {
                return false;
            }

            @Override
            public boolean isReentrant() {
                return true;
            }

            @Override
            public boolean isAutoRenewalEnabled() {
                return false;
            }
        };

        assertDoesNotThrow(() -> {
            DistributedReadWriteLock rwLock = factory.getConfiguredReadWriteLock(READ_WRITE_LOCK_NAME, config);
            assertNotNull(rwLock);
            assertEquals(READ_WRITE_LOCK_NAME, rwLock.getName());
        });
    }

    @Test
    void shouldCreateMultipleLocks() {
        List<String> lockNames = List.of("lock1", "lock2", "lock3");
        
        Map<String, DistributedLock> locks = factory.getLocks(lockNames);
        
        assertEquals(3, locks.size());
        for (String lockName : lockNames) {
            assertTrue(locks.containsKey(lockName));
            assertEquals(lockName, locks.get(lockName).getName());
        }
    }

    @Test
    void shouldCreateMultipleReadWriteLocks() {
        List<String> lockNames = List.of("rwlock1", "rwlock2", "rwlock3");
        
        Map<String, DistributedReadWriteLock> rwLocks = factory.getReadWriteLocks(lockNames);
        
        assertEquals(3, rwLocks.size());
        for (String lockName : lockNames) {
            assertTrue(rwLocks.containsKey(lockName));
            assertEquals(lockName, rwLocks.get(lockName).getName());
        }
    }

    @Test
    void shouldGetLockAsync() {
        CompletableFuture<DistributedLock> future = factory.getLockAsync(LOCK_NAME);
        
        DistributedLock lock = future.join();
        assertNotNull(lock);
        assertEquals(LOCK_NAME, lock.getName());
    }

    @Test
    void shouldGetReadWriteLockAsync() {
        CompletableFuture<DistributedReadWriteLock> future = factory.getReadWriteLockAsync(READ_WRITE_LOCK_NAME);
        
        DistributedReadWriteLock rwLock = future.join();
        assertNotNull(rwLock);
        assertEquals(READ_WRITE_LOCK_NAME, rwLock.getName());
    }

    @Test
    void shouldCreateBatchLockOperations() {
        List<String> lockNames = List.of("batch1", "batch2");
        BatchLockOperations.BatchOperationExecutor<String> operation = locks -> "success";
        
        BatchLockOperations<DistributedLock> batchOps = factory.createBatchLockOperations(lockNames, operation);
        
        assertNotNull(batchOps);
    }

    @Test
    void shouldPerformBatchLockOperation() throws InterruptedException {
        List<String> lockNames = List.of("batch1", "batch2");
        BatchLockOperations.BatchOperationExecutor<String> operation = locks -> "success";
        
        BatchLockOperations<DistributedLock> batchOps = factory.createBatchLockOperations(lockNames, operation);
        BatchLockResult<DistributedLock> result = batchOps.batchLock(lockNames, 1, java.util.concurrent.TimeUnit.SECONDS);
        
        assertTrue(result.isSuccess());
        assertEquals(2, result.getLocks().size());
        assertTrue(result.getFailedLocks().isEmpty());
        assertTrue(result.getOperationTimeMs() >= 0);
    }

    @Test
    void shouldPerformBatchLockOperationAsync() {
        List<String> lockNames = List.of("batch1", "batch2");
        BatchLockOperations.BatchOperationExecutor<String> operation = locks -> "success";
        
        BatchLockOperations<DistributedLock> batchOps = factory.createBatchLockOperations(lockNames, operation);
        CompletableFuture<BatchLockResult<DistributedLock>> future = batchOps.batchLockAsync(lockNames, 1, java.util.concurrent.TimeUnit.SECONDS);
        
        BatchLockResult<DistributedLock> result = future.join();
        assertTrue(result.isSuccess());
        assertEquals(2, result.getLocks().size());
    }

    @Test
    void shouldBatchUnlock() throws InterruptedException {
        List<String> lockNames = List.of("batch1", "batch2");
        BatchLockOperations.BatchOperationExecutor<String> operation = locks -> "success";
        
        BatchLockOperations<DistributedLock> batchOps = factory.createBatchLockOperations(lockNames, operation);
        BatchLockResult<DistributedLock> result = batchOps.batchLock(lockNames, 5, java.util.concurrent.TimeUnit.SECONDS);
        
        assertTrue(batchOps.batchUnlock(result.getLocks()));
    }

    @Test
    void shouldBatchUnlockAsync() throws InterruptedException {
        List<String> lockNames = List.of("batch1", "batch2");
        BatchLockOperations.BatchOperationExecutor<String> operation = locks -> "success";
        
        BatchLockOperations<DistributedLock> batchOps = factory.createBatchLockOperations(lockNames, operation);
        BatchLockResult<DistributedLock> result = batchOps.batchLock(lockNames, 5, java.util.concurrent.TimeUnit.SECONDS);
        
        CompletableFuture<Boolean> future = batchOps.batchUnlockAsync(result.getLocks());
        assertTrue(future.join());
    }

    @Test
    void shouldExecuteInTransaction() throws Exception {
        List<String> lockNames = List.of("tx1", "tx2");
        BatchLockOperations.TransactionalLockCallback<String, DistributedLock> callback = locks -> "transaction-result";
        
        BatchLockOperations<DistributedLock> batchOps = factory.createBatchLockOperations(lockNames, null);
        String result = batchOps.executeInTransaction(lockNames, 1, java.util.concurrent.TimeUnit.SECONDS, callback);
        
        assertEquals("transaction-result", result);
    }

    @Test
    void shouldExecuteInTransactionAsync() {
        List<String> lockNames = List.of("tx1", "tx2");
        BatchLockOperations.TransactionalLockCallback<String, DistributedLock> callback = locks -> "transaction-result";
        
        BatchLockOperations<DistributedLock> batchOps = factory.createBatchLockOperations(lockNames, null);
        CompletableFuture<String> future = batchOps.executeInTransactionAsync(lockNames, 1, java.util.concurrent.TimeUnit.SECONDS, callback);
        
        assertEquals("transaction-result", future.join());
    }

    @Test
    void shouldCreateAsyncLockOperations() {
        AsyncLockOperations<DistributedLock> asyncOps = factory.createAsyncLockOperations();
        assertNotNull(asyncOps);
    }

    @Test
    void shouldAcquireLockAsync() {
        AsyncLockOperations<DistributedLock> asyncOps = factory.createAsyncLockOperations();
        CompletableFuture<AsyncLockResult<DistributedLock>> future = 
            asyncOps.acquireLockAsync("async-lock", 1, java.util.concurrent.TimeUnit.SECONDS);
        
        AsyncLockResult<DistributedLock> result = future.join();
        assertTrue(result.isSuccess());
        assertNotNull(result.getLock());
        assertEquals("async-lock", result.getLock().getName());
        assertNull(result.getFailureCause());
        assertTrue(result.getOperationTimeMs() >= 0);
        assertTrue(result.getAcquisitionTime() > 0);
        assertTrue(result.getExpirationTime() > 0);
    }

    @Test
    void shouldTryAcquireLockAsync() {
        AsyncLockOperations<DistributedLock> asyncOps = factory.createAsyncLockOperations();
        CompletableFuture<AsyncLockResult<DistributedLock>> future = 
            asyncOps.tryAcquireLockAsync("async-lock", 0, 1, java.util.concurrent.TimeUnit.SECONDS);
        
        AsyncLockResult<DistributedLock> result = future.join();
        assertTrue(result.isSuccess());
        assertNotNull(result.getLock());
        assertEquals("async-lock", result.getLock().getName());
    }

    @Test
    void shouldReleaseLockAsync() throws InterruptedException {
        AsyncLockOperations<DistributedLock> asyncOps = factory.createAsyncLockOperations();
        
        // 先获取锁
        DistributedLock lock = factory.getLock("release-test");
        lock.lock(5, java.util.concurrent.TimeUnit.SECONDS);
        
        CompletableFuture<AsyncLockReleaseResult> future = asyncOps.releaseLockAsync(lock);
        AsyncLockReleaseResult result = future.join();
        
        assertTrue(result.isSuccess());
        assertNull(result.getFailureCause());
        assertTrue(result.getOperationTimeMs() >= 0);
    }

    @Test
    void shouldPerformHealthCheck() {
        FactoryHealthStatus health = factory.healthCheck();
        
        assertTrue(health.isHealthy());
        assertNotNull(health.getDetails());
        assertTrue(health.getCheckTime() > 0);
        
        PerformanceMetrics metrics = health.getPerformanceMetrics();
        assertNotNull(metrics);
        assertTrue(metrics.getResponseTimeMs() >= 0);
        assertTrue(metrics.getThroughput() >= 0);
        assertTrue(metrics.getErrorRate() >= 0);
        assertTrue(metrics.getActiveConnections() >= 0);
        
        assertNull(health.getErrorMessage());
    }

    @Test
    void shouldPerformHealthCheckAsync() {
        CompletableFuture<FactoryHealthStatus> future = factory.healthCheckAsync();
        FactoryHealthStatus health = future.join();
        
        assertTrue(health.isHealthy());
        assertNotNull(health.getDetails());
    }

    @Test
    void shouldCheckLockAvailability() {
        boolean available = factory.isLockAvailable("availability-test");
        assertTrue(available); // 测试锁应该是可用的
    }

    @Test
    void shouldCheckLockAvailabilityAsync() {
        CompletableFuture<Boolean> future = factory.isLockAvailableAsync("availability-test");
        Boolean available = future.join();
        assertTrue(available);
    }

    @Test
    void shouldGetStatistics() {
        FactoryStatistics stats = factory.getStatistics();
        
        assertNotNull(stats);
        assertTrue(stats.getTotalLocks() >= 0);
        assertTrue(stats.getActiveLocks() >= 0);
        assertTrue(stats.getTotalLockAcquisitions() >= 0);
        assertTrue(stats.getFailedLockAcquisitions() >= 0);
        assertTrue(stats.getTotalLockReleases() >= 0);
        assertTrue(stats.getAverageLockAcquisitionTime() >= 0);
        assertTrue(stats.getPeakConcurrency() >= 0);
        assertTrue(stats.getUptime() >= 0);
        
        MemoryUsage memoryUsage = stats.getMemoryUsage();
        assertNotNull(memoryUsage);
        assertTrue(memoryUsage.getUsedMemory() >= 0);
        assertTrue(memoryUsage.getTotalMemory() >= 0);
        assertTrue(memoryUsage.getAvailableMemory() >= 0);
        assertTrue(memoryUsage.getUsageRate() >= 0);
    }

    @Test
    void shouldResetStatistics() {
        assertDoesNotThrow(() -> factory.resetStatistics());
    }

    @Test
    void shouldGetActiveLocks() {
        List<String> activeLocks = factory.getActiveLocks();
        assertNotNull(activeLocks);
    }

    @Test
    void shouldReleaseLock() throws InterruptedException {
        DistributedLock lock = factory.getLock("release-test");
        lock.lock(1, java.util.concurrent.TimeUnit.SECONDS);
        
        assertTrue(factory.releaseLock("release-test"));
    }

    @Test
    void shouldReleaseLockAsync() throws InterruptedException {
        DistributedLock lock = factory.getLock("release-async-test");
        lock.lock(1, java.util.concurrent.TimeUnit.SECONDS);
        
        CompletableFuture<Boolean> future = factory.releaseLockAsync("release-async-test");
        assertTrue(future.join());
    }

    @Test
    void shouldCleanupExpiredLocks() {
        int cleaned = factory.cleanupExpiredLocks();
        assertTrue(cleaned >= 0);
    }

    @Test
    void shouldCleanupExpiredLocksAsync() {
        CompletableFuture<Integer> future = factory.cleanupExpiredLocksAsync();
        Integer cleaned = future.join();
        assertTrue(cleaned >= 0);
    }

    @Test
    void shouldGetConfiguration() {
        FactoryConfiguration config = factory.getConfiguration();
        
        assertNotNull(config);
        assertTrue(config.getMaxLocks() > 0);
        assertTrue(config.getConnectionTimeout(java.util.concurrent.TimeUnit.MILLISECONDS) > 0);
        assertTrue(config.getOperationTimeout(java.util.concurrent.TimeUnit.MILLISECONDS) > 0);
        assertTrue(config.getRetryCount() >= 0);
        assertTrue(config.getRetryInterval(java.util.concurrent.TimeUnit.MILLISECONDS) >= 0);
        assertTrue(config.isCacheEnabled() != null);
        assertTrue(config.isMonitoringEnabled() != null);
        assertTrue(config.isMetricsEnabled() != null);
    }

    @Test
    void shouldUpdateConfiguration() {
        FactoryConfiguration config = factory.getConfiguration();
        assertDoesNotThrow(() -> factory.updateConfiguration(config));
    }

    @Test
    void shouldGetFactoryState() {
        FactoryState state = factory.getState();
        assertNotNull(state);
        assertEquals(FactoryState.RUNNING, state);
    }

    @Test
    void shouldSetFactoryState() {
        factory.setState(FactoryState.PAUSED);
        assertEquals(FactoryState.PAUSED, factory.getState());
    }

    @Test
    void shouldRegisterEventListener() {
        LockEventListener<DistributedLock> listener = new LockEventListener<DistributedLock>() {
            @Override
            public void onLockAcquired(DistributedLock lock, LockEvent event) {
                // 空实现
            }

            @Override
            public void onLockReleased(DistributedLock lock, LockEvent event) {
                // 空实现
            }

            @Override
            public void onLockExpired(DistributedLock lock, LockEvent event) {
                // 空实现
            }

            @Override
            public void onLockRenewed(DistributedLock lock, LockEvent event) {
                // 空实现
            }

            @Override
            public void onHealthStatusChanged(LockEventListener.HealthStatus status) {
                // 空实现
            }

            @Override
            public LockEventListener.LockEventMetadata getMetadata() {
                return new LockEventListener.LockEventMetadata() {
                    @Override
                    public String getListenerId() {
                        return "test-listener";
                    }

                    @Override
                    public long getRegistrationTime() {
                        return System.currentTimeMillis();
                    }

                    @Override
                    public java.util.Set<String> getSupportedEvents() {
                        return java.util.Set.of("ACQUIRED", "RELEASED");
                    }
                };
            }
        };

        assertDoesNotThrow(() -> factory.registerEventListener(listener));
    }

    @Test
    void shouldUnregisterEventListener() {
        LockEventListener<DistributedLock> listener = new LockEventListener<DistributedLock>() {
            @Override
            public void onLockAcquired(DistributedLock lock, LockEvent event) {}

            @Override
            public void onLockReleased(DistributedLock lock, LockEvent event) {}

            @Override
            public void onLockExpired(DistributedLock lock, LockEvent event) {}

            @Override
            public void onLockRenewed(DistributedLock lock, LockEvent event) {}

            @Override
            public void onHealthStatusChanged(LockEventListener.HealthStatus status) {}

            @Override
            public LockEventListener.LockEventMetadata getMetadata() {
                return new LockEventListener.LockEventMetadata() {
                    @Override
                    public String getListenerId() {
                        return "test-listener";
                    }

                    @Override
                    public long getRegistrationTime() {
                        return System.currentTimeMillis();
                    }

                    @Override
                    public java.util.Set<String> getSupportedEvents() {
                        return java.util.Set.of("ACQUIRED", "RELEASED");
                    }
                };
            }
        };

        factory.registerEventListener(listener);
        assertDoesNotThrow(() -> factory.unregisterEventListener(listener));
    }

    @Test
    void shouldGetSupportedHighAvailabilityStrategies() {
        List<HighAvailabilityStrategy<?>> strategies = factory.getSupportedHighAvailabilityStrategies();
        assertNotNull(strategies);
    }

    @Test
    void shouldSetHighAvailabilityStrategy() {
        HighAvailabilityStrategy<String> strategy = new HighAvailabilityStrategy<String>() {
            @Override
            public String getStrategyName() {
                return "test-strategy";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String executeWithFallback(String operation, java.util.concurrent.Callable<String> fallback) {
                try {
                    return operation;
                } catch (Exception e) {
                    return "fallback";
                }
            }
        };

        assertDoesNotThrow(() -> factory.setHighAvailabilityStrategy(strategy));
    }

    @Test
    void shouldGetScheduledExecutorService() {
        ScheduledExecutorService executor = factory.getScheduledExecutorService();
        assertNotNull(executor);
    }

    @Test
    void shouldSetScheduledExecutorService() {
        ScheduledExecutorService newExecutor = Executors.newScheduledThreadPool(1);
        factory.setScheduledExecutorService(newExecutor);
        assertEquals(newExecutor, factory.getScheduledExecutorService());
    }

    @Test
    void shouldGetFactoryName() {
        String name = factory.getFactoryName();
        assertNotNull(name);
        assertEquals("TestDistributedLockFactory", name);
    }

    @Test
    void shouldGetFactoryVersion() {
        String version = factory.getFactoryVersion();
        assertNotNull(version);
        assertEquals("1.0.0-test", version);
    }

    @Test
    void shouldPerformGracefulShutdown() throws InterruptedException {
        assertFalse(factory.isShutdown());
        
        boolean success = factory.gracefulShutdown(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(success);
        
        // 等待关闭完成
        Thread.sleep(100);
        assertTrue(factory.isShutdown());
        assertEquals(FactoryState.SHUTDOWN, factory.getState());
    }

    @Test
    void shouldCheckIfShutdown() {
        assertFalse(factory.isShutdown());
        
        factory.shutdown();
        assertTrue(factory.isShutdown());
    }

    @Test
    void shouldCheckIfTerminated() {
        assertFalse(factory.isTerminated());
        
        factory.shutdown();
        assertTrue(factory.isTerminated());
    }

    @Test
    void shouldAwaitTermination() throws InterruptedException {
        factory.shutdown();
        boolean terminated = factory.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(terminated);
    }

    @Test
    void shouldHandleShutdownAfterGetLock() {
        factory.shutdown();
        
        assertThrows(IllegalStateException.class, () -> {
            factory.getLock("test");
        });
    }

    @Test
    void shouldHandleShutdownAfterGetReadWriteLock() {
        factory.shutdown();
        
        assertThrows(IllegalStateException.class, () -> {
            factory.getReadWriteLock("test");
        });
    }

    @Test
    void shouldHandleAutoCloseable() throws Exception {
        try (DistributedLockFactory f = factory) {
            DistributedLock lock = f.getLock("autoclose-test");
            assertNotNull(lock);
        }
        assertTrue(factory.isShutdown());
    }
}