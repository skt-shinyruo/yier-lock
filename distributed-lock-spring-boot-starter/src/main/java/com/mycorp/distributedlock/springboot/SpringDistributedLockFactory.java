package com.mycorp.distributedlock.springboot;

import com.mycorp.distributedlock.api.AsyncLockOperations;
import com.mycorp.distributedlock.api.BatchLockOperations;
import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.api.HighAvailabilityStrategy;
import com.mycorp.distributedlock.api.LockConfigurationBuilder;
import com.mycorp.distributedlock.api.LockProvider;
import com.mycorp.distributedlock.redis.EnhancedRedisReadWriteLock;
import com.mycorp.distributedlock.redis.SimpleRedisLock;
import com.mycorp.distributedlock.springboot.config.DistributedLockProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Spring 集成分布式锁工厂，基于注入的 LockProvider 委托创建锁。
 */
public class SpringDistributedLockFactory implements DistributedLockFactory {

    private static final Logger logger = LoggerFactory.getLogger(SpringDistributedLockFactory.class);

    private final LockProvider delegate;
    private final MeterRegistry meterRegistry;
    private final OpenTelemetry openTelemetry;

    private volatile FactoryState state = FactoryState.RUNNING;

    /**
     * 构造函数。
     *
     * @param delegate 实际的 LockProvider
     * @param meterRegistry 可选的计量注册表
     * @param openTelemetry 可选的 OpenTelemetry 实例
     */
    public SpringDistributedLockFactory(
        LockProvider delegate,
        MeterRegistry meterRegistry,
        OpenTelemetry openTelemetry
    ) {
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
        this.openTelemetry = openTelemetry;
        logger.info("SpringDistributedLockFactory initialized with delegate: {}", delegate.getClass().getSimpleName());
    }

    /**
     * Legacy benchmark compatibility constructor.
     */
    public SpringDistributedLockFactory(RedisClient redisClient, DistributedLockProperties properties) {
        this(createRedisLockProvider(redisClient, properties), null, null);
    }

    @Override
    public DistributedLock getLock(String name) {
        return delegate.createLock(name);
    }

    private static LockProvider createRedisLockProvider(RedisClient redisClient, DistributedLockProperties properties) {
        StatefulRedisConnection<String, String> connection = redisClient.connect();
        RedisCommands<String, String> commands = connection.sync();
        long leaseTimeSeconds = Math.max(1L, properties != null ? properties.getDefaultTimeout().getSeconds() : 30L);

        return new LockProvider() {
            @Override
            public String getType() {
                return "redis";
            }

            @Override
            public int getPriority() {
                return 100;
            }

            @Override
            public DistributedLock createLock(String key) {
                return new SimpleRedisLock(key, commands, leaseTimeSeconds);
            }

            @Override
            public DistributedReadWriteLock createReadWriteLock(String key) {
                return new EnhancedRedisReadWriteLock(key, commands, leaseTimeSeconds, leaseTimeSeconds);
            }

            @Override
            public void close() {
                connection.close();
            }
        };
    }

    @Override
    public DistributedReadWriteLock getReadWriteLock(String name) {
        return delegate.createReadWriteLock(name);
    }

    @Override
    public DistributedLock getConfiguredLock(String name, LockConfigurationBuilder.LockConfiguration configuration) {
        validateConfigurationSupport(configuration);
        return getLock(name);
    }

    @Override
    public DistributedReadWriteLock getConfiguredReadWriteLock(
        String name,
        LockConfigurationBuilder.LockConfiguration configuration
    ) {
        validateConfigurationSupport(configuration);
        return getReadWriteLock(name);
    }

    @Override
    public Map<String, DistributedLock> getLocks(List<String> lockNames) {
        return lockNames.stream().collect(Collectors.toMap(name -> name, this::getLock));
    }

    @Override
    public Map<String, DistributedReadWriteLock> getReadWriteLocks(List<String> lockNames) {
        return lockNames.stream().collect(Collectors.toMap(name -> name, this::getReadWriteLock));
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
    public <R> BatchLockOperations<DistributedLock> createBatchLockOperations(
        List<String> lockNames,
        BatchLockOperations.BatchOperationExecutor<R> operation
    ) {
        return new BatchLockOperations<>() {
            @Override
            public BatchLockResult<DistributedLock> batchLock(List<String> names, long leaseTime, TimeUnit unit) {
                throw new UnsupportedOperationException("Batch lock operations are not implemented for SpringDistributedLockFactory");
            }

            @Override
            public CompletableFuture<BatchLockResult<DistributedLock>> batchLockAsync(
                List<String> names,
                long leaseTime,
                TimeUnit unit
            ) {
                return CompletableFuture.supplyAsync(() -> batchLock(names, leaseTime, unit));
            }

            @Override
            public boolean batchUnlock(List<DistributedLock> locks) {
                boolean unlockedAll = true;
                for (DistributedLock lock : locks) {
                    try {
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                    } catch (RuntimeException exception) {
                        unlockedAll = false;
                    }
                }
                return unlockedAll;
            }

            @Override
            public CompletableFuture<Boolean> batchUnlockAsync(List<DistributedLock> locks) {
                return CompletableFuture.supplyAsync(() -> batchUnlock(locks));
            }

            @Override
            public <R1> R1 executeInTransaction(
                List<String> names,
                long leaseTime,
                TimeUnit unit,
                TransactionalLockCallback<R1, DistributedLock> transactionCallback
            ) {
                throw new UnsupportedOperationException("Transactional batch operations are not implemented");
            }

            @Override
            public <R1> CompletableFuture<R1> executeInTransactionAsync(
                List<String> names,
                long leaseTime,
                TimeUnit unit,
                TransactionalLockCallback<R1, DistributedLock> transactionCallback
            ) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return executeInTransaction(names, leaseTime, unit, transactionCallback);
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                });
            }

            @Override
            public DistributedLock getLock(String name) {
                return SpringDistributedLockFactory.this.getLock(name);
            }
        };
    }

    @Override
    public AsyncLockOperations<DistributedLock> createAsyncLockOperations() {
        return new AsyncLockOperations<>() {
            private final AsyncOperationConfiguration configuration = new AsyncOperationConfiguration() {
                private long defaultTimeoutMs = TimeUnit.SECONDS.toMillis(10);
                private int defaultRetryCount = 3;
                private long defaultRetryDelayMs = TimeUnit.SECONDS.toMillis(1);
                private double renewalIntervalRatio = 0.5d;
                private int maxConcurrency = 16;
                private boolean autoRenewalEnabled = false;

                @Override
                public long getDefaultTimeoutMs() {
                    return defaultTimeoutMs;
                }

                @Override
                public int getDefaultRetryCount() {
                    return defaultRetryCount;
                }

                @Override
                public long getDefaultRetryDelayMs() {
                    return defaultRetryDelayMs;
                }

                @Override
                public double getRenewalIntervalRatio() {
                    return renewalIntervalRatio;
                }

                @Override
                public int getMaxConcurrency() {
                    return maxConcurrency;
                }

                @Override
                public boolean isAutoRenewalEnabled() {
                    return autoRenewalEnabled;
                }

                @Override
                public AsyncOperationConfiguration setDefaultTimeoutMs(long timeoutMs) {
                    this.defaultTimeoutMs = timeoutMs;
                    return this;
                }

                @Override
                public AsyncOperationConfiguration setDefaultRetryCount(int retryCount) {
                    this.defaultRetryCount = retryCount;
                    return this;
                }

                @Override
                public AsyncOperationConfiguration setDefaultRetryDelayMs(long retryDelayMs) {
                    this.defaultRetryDelayMs = retryDelayMs;
                    return this;
                }

                @Override
                public AsyncOperationConfiguration setRenewalIntervalRatio(double ratio) {
                    this.renewalIntervalRatio = ratio;
                    return this;
                }

                @Override
                public AsyncOperationConfiguration setMaxConcurrency(int maxConcurrency) {
                    this.maxConcurrency = maxConcurrency;
                    return this;
                }

                @Override
                public AsyncOperationConfiguration setAutoRenewalEnabled(boolean autoRenewalEnabled) {
                    this.autoRenewalEnabled = autoRenewalEnabled;
                    return this;
                }
            };

            @Override
            public CompletableFuture<AsyncLockResult<DistributedLock>> acquireLockAsync(
                String lockName,
                long leaseTime,
                TimeUnit timeUnit
            ) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        DistributedLock lock = SpringDistributedLockFactory.this.getLock(lockName);
                        lock.lock(leaseTime, timeUnit);
                        return successResult(lock, leaseTime, timeUnit);
                    } catch (Exception exception) {
                        return failedResult(exception);
                    }
                });
            }

            @Override
            public CompletableFuture<AsyncLockResult<DistributedLock>> tryAcquireLockAsync(
                String lockName,
                long waitTime,
                long leaseTime,
                TimeUnit timeUnit
            ) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        DistributedLock lock = SpringDistributedLockFactory.this.getLock(lockName);
                        boolean success = lock.tryLock(waitTime, leaseTime, timeUnit);
                        return success ? successResult(lock, leaseTime, timeUnit) : failedResult(null);
                    } catch (Exception exception) {
                        return failedResult(exception);
                    }
                });
            }

            @Override
            public CompletableFuture<AsyncLockReleaseResult> releaseLockAsync(DistributedLock lock) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        if (lock != null && lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                        return releaseResult(true, null);
                    } catch (RuntimeException exception) {
                        return releaseResult(false, exception);
                    }
                });
            }

            @Override
            public CompletableFuture<AsyncLockRenewalResult> renewLockAsync(
                DistributedLock lock,
                long newLeaseTime,
                TimeUnit timeUnit
            ) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        boolean renewed = lock != null && lock.renewLock(newLeaseTime, timeUnit);
                        return renewalResult(
                            renewed,
                            renewed ? System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert(newLeaseTime, timeUnit) : 0,
                            null
                        );
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return renewalResult(false, 0, exception);
                    }
                });
            }

            @Override
            public CompletableFuture<AsyncLockInfoResult> getLockInfoAsync(String lockName) {
                return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Lock info is not implemented for SpringDistributedLockFactory")
                );
            }

            @Override
            public CompletableFuture<AsyncLockStateResult> checkLockStateAsync(String lockName) {
                return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Lock state inspection is not implemented for SpringDistributedLockFactory")
                );
            }

            @Override
            public CompletableFuture<AsyncLockReleaseResult> releaseLockByNameAsync(String lockName) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        boolean released = SpringDistributedLockFactory.this.releaseLock(lockName);
                        return releaseResult(released, null);
                    } catch (RuntimeException exception) {
                        return releaseResult(false, exception);
                    }
                });
            }

            @Override
            public CompletableFuture<AsyncWaitResult> waitForLockReleaseAsync(
                String lockName,
                long timeout,
                TimeUnit timeUnit
            ) {
                return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Waiting for lock release is not implemented for SpringDistributedLockFactory")
                );
            }

            @Override
            public <R> CompletableFuture<AsyncBusinessOperationResult<R>> executeWithLockAsync(
                String lockName,
                long leaseTime,
                TimeUnit timeUnit,
                AsyncBusinessOperation<R, DistributedLock> businessOperation
            ) {
                return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Async business operations are not implemented for SpringDistributedLockFactory")
                );
            }

            @Override
            public <R> CompletableFuture<AsyncRetryOperationResult<R>> executeWithRetryAsync(
                String lockName,
                long leaseTime,
                TimeUnit timeUnit,
                int maxRetries,
                long retryDelay,
                TimeUnit retryDelayTimeUnit,
                AsyncRetryableLockOperation<R, DistributedLock> operation
            ) {
                return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Async retry operations are not implemented for SpringDistributedLockFactory")
                );
            }

            @Override
            public LockRenewalTask autoRenewLock(DistributedLock lock, long renewInterval, TimeUnit timeUnit) {
                return new LockRenewalTask() {
                    private volatile boolean running = true;

                    @Override
                    public boolean isRunning() {
                        return running;
                    }

                    @Override
                    public long getRenewalCount() {
                        return 0;
                    }

                    @Override
                    public long getLastRenewalTime() {
                        return 0;
                    }

                    @Override
                    public void cancel() {
                        running = false;
                    }

                    @Override
                    public DistributedLock getLock() {
                        return lock;
                    }
                };
            }

            @Override
            public void cancelAutoRenew(LockRenewalTask renewalTask) {
                if (renewalTask != null) {
                    renewalTask.cancel();
                }
            }

            @Override
            public AsyncOperationConfiguration getConfiguration() {
                return configuration;
            }

            @Override
            public void updateConfiguration(AsyncOperationConfiguration configuration) {
                if (configuration == null) {
                    return;
                }
                this.configuration
                    .setDefaultTimeoutMs(configuration.getDefaultTimeoutMs())
                    .setDefaultRetryCount(configuration.getDefaultRetryCount())
                    .setDefaultRetryDelayMs(configuration.getDefaultRetryDelayMs())
                    .setRenewalIntervalRatio(configuration.getRenewalIntervalRatio())
                    .setMaxConcurrency(configuration.getMaxConcurrency())
                    .setAutoRenewalEnabled(configuration.isAutoRenewalEnabled());
            }

            private AsyncLockResult<DistributedLock> successResult(DistributedLock lock, long leaseTime, TimeUnit timeUnit) {
                return new AsyncLockResult<>() {
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
                        return System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert(leaseTime, timeUnit);
                    }
                };
            }

            private AsyncLockResult<DistributedLock> failedResult(Throwable failureCause) {
                return new AsyncLockResult<>() {
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
                        return failureCause;
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

            private AsyncLockReleaseResult releaseResult(boolean success, Throwable failureCause) {
                return new AsyncLockReleaseResult() {
                    @Override
                    public boolean isSuccess() {
                        return success;
                    }

                    @Override
                    public Throwable getFailureCause() {
                        return failureCause;
                    }

                    @Override
                    public long getOperationTimeMs() {
                        return 0;
                    }
                };
            }

            private AsyncLockRenewalResult renewalResult(boolean success, long newExpirationTime, Throwable failureCause) {
                return new AsyncLockRenewalResult() {
                    @Override
                    public boolean isSuccess() {
                        return success;
                    }

                    @Override
                    public long getNewExpirationTime() {
                        return newExpirationTime;
                    }

                    @Override
                    public Throwable getFailureCause() {
                        return failureCause;
                    }

                    @Override
                    public long getOperationTimeMs() {
                        return 0;
                    }
                };
            }
        };
    }

    @Override
    public FactoryHealthStatus healthCheck() {
        long startedAt = System.currentTimeMillis();
        ProbeResult probeResult = probeDelegateHealth();
        long checkTime = System.currentTimeMillis();
        long responseTimeMs = Math.max(0L, checkTime - startedAt);
        boolean healthy = state != FactoryState.ERROR
                && state != FactoryState.TERMINATED
                && probeResult.healthy();
        return new FactoryHealthStatus() {
            @Override
            public boolean isHealthy() {
                return healthy;
            }

            @Override
            public String getDetails() {
                return "delegate=" + delegate.getType()
                        + ", state=" + state
                        + ", probe=" + probeResult.details();
            }

            @Override
            public long getCheckTime() {
                return checkTime;
            }

            @Override
            public PerformanceMetrics getPerformanceMetrics() {
                return new PerformanceMetrics() {
                    @Override
                    public long getResponseTimeMs() {
                        return responseTimeMs;
                    }

                    @Override
                    public double getThroughput() {
                        return 0;
                    }

                    @Override
                    public double getErrorRate() {
                        return healthy ? 0 : 1;
                    }

                    @Override
                    public int getActiveConnections() {
                        return 0;
                    }
                };
            }

            @Override
            public String getErrorMessage() {
                if (state != FactoryState.RUNNING) {
                    return "Factory state is " + state;
                }
                return healthy ? null : probeResult.errorMessage();
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
            return getLock(name) != null;
        } catch (RuntimeException exception) {
            logger.debug("Lock {} is not available", name, exception);
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
                return 0;
            }

            @Override
            public long getActiveLocks() {
                return 0;
            }

            @Override
            public long getTotalLockAcquisitions() {
                return 0;
            }

            @Override
            public long getFailedLockAcquisitions() {
                return 0;
            }

            @Override
            public long getTotalLockReleases() {
                return 0;
            }

            @Override
            public double getAverageLockAcquisitionTime() {
                return 0;
            }

            @Override
            public int getPeakConcurrency() {
                return 0;
            }

            @Override
            public long getUptime() {
                return 0;
            }

            @Override
            public MemoryUsage getMemoryUsage() {
                return new MemoryUsage() {
                    @Override
                    public long getUsedMemory() {
                        return 0;
                    }

                    @Override
                    public long getTotalMemory() {
                        return 0;
                    }

                    @Override
                    public long getAvailableMemory() {
                        return 0;
                    }

                    @Override
                    public double getUsageRate() {
                        return 0;
                    }
                };
            }
        };
    }

    @Override
    public List<String> getActiveLocks() {
        return List.of();
    }

    @Override
    public FactoryConfiguration getConfiguration() {
        return new FactoryConfiguration() {
            @Override
            public int getMaxLocks() {
                return 1000;
            }

            @Override
            public long getConnectionTimeout(TimeUnit unit) {
                return unit.convert(5, TimeUnit.SECONDS);
            }

            @Override
            public long getOperationTimeout(TimeUnit unit) {
                return unit.convert(10, TimeUnit.SECONDS);
            }

            @Override
            public int getRetryCount() {
                return 3;
            }

            @Override
            public long getRetryInterval(TimeUnit unit) {
                return unit.convert(1, TimeUnit.SECONDS);
            }

            @Override
            public boolean isCacheEnabled() {
                return true;
            }

            @Override
            public boolean isMonitoringEnabled() {
                return meterRegistry != null;
            }

            @Override
            public boolean isMetricsEnabled() {
                return meterRegistry != null;
            }

            @Override
            public FactoryConfiguration setMaxLocks(int maxLocks) {
                return this;
            }

            @Override
            public FactoryConfiguration setConnectionTimeout(long timeout, TimeUnit unit) {
                return this;
            }

            @Override
            public FactoryConfiguration setOperationTimeout(long timeout, TimeUnit unit) {
                return this;
            }

            @Override
            public FactoryConfiguration setRetryCount(int retryCount) {
                return this;
            }

            @Override
            public FactoryConfiguration setRetryInterval(long interval, TimeUnit unit) {
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
    public FactoryState getState() {
        return state;
    }

    @Override
    public List<HighAvailabilityStrategy<?>> getSupportedHighAvailabilityStrategies() {
        return List.of();
    }

    @Override
    public void close() {
        shutdown();
    }

    @Override
    public void shutdown() {
        logger.info("Shutting down SpringDistributedLockFactory");
        state = FactoryState.SHUTDOWN;
        try {
            delegate.close();
            state = FactoryState.TERMINATED;
        } catch (RuntimeException exception) {
            state = FactoryState.ERROR;
            logger.warn("Error closing delegate", exception);
            throw exception;
        }
    }

    public LockProvider getDelegate() {
        return delegate;
    }

    public Optional<MeterRegistry> getMeterRegistry() {
        return Optional.ofNullable(meterRegistry);
    }

    public Optional<OpenTelemetry> getOpenTelemetry() {
        return Optional.ofNullable(openTelemetry);
    }

    private void validateConfigurationSupport(LockConfigurationBuilder.LockConfiguration configuration) {
        if (configuration == null) {
            return;
        }
        if (configuration.isFairLock() || configuration.getLockType() == LockConfigurationBuilder.LockType.FAIR) {
            throw new UnsupportedOperationException("Fair locks are not supported by SpringDistributedLockFactory");
        }
    }

    private ProbeResult probeDelegateHealth() {
        String healthKey = "__distributed_lock_health__:" + UUID.randomUUID();
        try {
            DistributedLock probe = delegate.createLock(healthKey);
            if (probe == null) {
                return new ProbeResult(false, "delegate returned null lock", "delegate returned null lock");
            }

            DistributedLock.HealthCheckResult result = probe.healthCheck();
            if (result == null) {
                return new ProbeResult(false, "lock health check returned null", "lock health check returned null");
            }

            String details = result.getDetails() != null ? result.getDetails() : "no details";
            String errorMessage = result.isHealthy() ? null : details;
            return new ProbeResult(result.isHealthy(), details, errorMessage);
        } catch (RuntimeException exception) {
            logger.debug("Delegate health probe failed", exception);
            return new ProbeResult(false, "delegate health probe threw", exception.getMessage());
        }
    }

    private record ProbeResult(boolean healthy, String details, String errorMessage) {
    }
}
