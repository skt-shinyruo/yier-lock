package com.mycorp.distributedlock.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * Default {@link DistributedLockFactory} implementation that discovers {@link LockProvider}s
 * via {@link ServiceLoader}. Inspired by the plugin architecture used in frameworks like Redisson.
 */
public class ServiceLoaderDistributedLockFactory implements DistributedLockFactory {

    private static final Logger logger = LoggerFactory.getLogger(ServiceLoaderDistributedLockFactory.class);

    private static final String[] PROVIDER_PROPERTY_KEYS = new String[]{
        "distributed.lock.provider",
        "distributed-lock.provider",
        "distributed_lock_provider",
        "com.mycorp.distributedlock.provider",
        "distributed-lock.type",
        "distributed.lock.type",
        "spring.distributed-lock.type"
    };
    private static final String[] PROVIDER_ENV_KEYS = new String[]{
        "DISTRIBUTED_LOCK_PROVIDER",
        "DISTRIBUTED_LOCK_TYPE",
        "SPRING_DISTRIBUTED_LOCK_TYPE"
    };

    private final List<LockProvider> providers;
    private final LockProvider selectedProvider;

    public ServiceLoaderDistributedLockFactory() {
        this(null);
    }

    public ServiceLoaderDistributedLockFactory(String requestedProviderType) {
        List<LockProvider> loaded = new ArrayList<>();
        ServiceLoader<LockProvider> loader = ServiceLoader.load(LockProvider.class);
        loader.iterator().forEachRemaining(loaded::add);

        if (loaded.isEmpty()) {
            throw new IllegalStateException(
                "No LockProvider implementations found on classpath. " +
                "Include a provider module (e.g., distributed-lock-redis or distributed-lock-zookeeper).");
        }

        loaded.sort(Comparator.comparingInt(LockProvider::getPriority).reversed());
        this.providers = Collections.unmodifiableList(loaded);

        String desiredType = requestedProviderType != null ? requestedProviderType : resolveProviderTypeFromSystem();
        this.selectedProvider = selectProvider(desiredType, providers);
        logger.info("Using distributed lock provider: {}", this.selectedProvider.getType());
    }

    private static String resolveProviderTypeFromSystem() {
        for (String key : PROVIDER_PROPERTY_KEYS) {
            String value = System.getProperty(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        for (String key : PROVIDER_ENV_KEYS) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static LockProvider selectProvider(String desiredType, List<LockProvider> candidates) {
        if (desiredType != null) {
            return candidates.stream()
                .filter(provider -> desiredType.equalsIgnoreCase(provider.getType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "Requested distributed lock provider '" + desiredType + "' not found. Available providers: " +
                        availableTypes(candidates)));
        }
        return candidates.get(0);
    }

    private static String availableTypes(List<LockProvider> candidates) {
        return candidates.stream()
            .map(LockProvider::getType)
            .sorted()
            .collect(Collectors.joining(", "));
    }

    public String getActiveProviderType() {
        return selectedProvider.getType();
    }

    @Override
    public DistributedLock getLock(String name) {
        return selectedProvider.createLock(name);
    }

    @Override
    public DistributedReadWriteLock getReadWriteLock(String name) {
        return selectedProvider.createReadWriteLock(name);
    }

    @Override
    public void shutdown() {
        for (LockProvider provider : providers) {
            try {
                provider.close();
            } catch (RuntimeException e) {
                logger.warn("Failed to shutdown lock provider {}", provider.getType(), e);
            }
        }
    }

    @Override
    public FactoryState getState() {
        return FactoryState.RUNNING;
    }

    @Override
    public List<String> getActiveLocks() {
        return List.of(); // 默认空实现
    }

    @Override
    public FactoryConfiguration getConfiguration() {
        return new FactoryConfiguration() {
            @Override
            public int getMaxLocks() {
                return 1000; // 默认值
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
    public List<HighAvailabilityStrategy<?>> getSupportedHighAvailabilityStrategies() {
        return List.of(); // 默认空实现
    }

    @Override
    public DistributedLock getConfiguredLock(String name, LockConfigurationBuilder.LockConfiguration configuration) {
        return getLock(name); // 默认委托到基础方法
    }

    @Override
    public DistributedReadWriteLock getConfiguredReadWriteLock(String name, LockConfigurationBuilder.LockConfiguration configuration) {
        return getReadWriteLock(name); // 默认委托到基础方法
    }

    @Override
    public java.util.Map<String, DistributedLock> getLocks(java.util.List<String> lockNames) {
        return lockNames.stream()
                .collect(java.util.stream.Collectors.toMap(
                        name -> name,
                        this::getLock
                ));
    }

    @Override
    public java.util.Map<String, DistributedReadWriteLock> getReadWriteLocks(java.util.List<String> lockNames) {
        return lockNames.stream()
                .collect(java.util.stream.Collectors.toMap(
                        name -> name,
                        this::getReadWriteLock
                ));
    }

    @Override
    public java.util.concurrent.CompletableFuture<DistributedLock> getLockAsync(String name) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> getLock(name));
    }

    @Override
    public java.util.concurrent.CompletableFuture<DistributedReadWriteLock> getReadWriteLockAsync(String name) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> getReadWriteLock(name));
    }

    @Override
    public <R> BatchLockOperations<DistributedLock> createBatchLockOperations(java.util.List<String> lockNames, 
                                                                              BatchLockOperations.BatchOperationExecutor<R> operation) {
        // 默认空实现
        return new BatchLockOperations<DistributedLock>() {
            @Override
            public BatchLockResult<DistributedLock> batchLock(java.util.List<String> lockNames, long leaseTime, java.util.concurrent.TimeUnit unit) 
                    throws InterruptedException {
                throw new UnsupportedOperationException("Batch operations not implemented in default factory");
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
            public <R1> R1 executeInTransaction(java.util.List<String> lockNames, long leaseTime, java.util.concurrent.TimeUnit unit,
                                               TransactionalLockCallback<R1, DistributedLock> transactionCallback) throws Exception {
                throw new UnsupportedOperationException("Transaction not implemented in default factory");
            }

            @Override
            public <R1> java.util.concurrent.CompletableFuture<R1> executeInTransactionAsync(java.util.List<String> lockNames, long leaseTime, java.util.concurrent.TimeUnit unit,
                                                                                           TransactionalLockCallback<R1, DistributedLock> transactionCallback) {
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
                return ServiceLoaderDistributedLockFactory.this.getLock(name);
            }
        };
    }

    @Override
    public AsyncLockOperations<DistributedLock> createAsyncLockOperations() {
        // 默认空实现
        return new AsyncLockOperations<DistributedLock>() {
            @Override
            public java.util.concurrent.CompletableFuture<AsyncLockResult<DistributedLock>> acquireLockAsync(String lockName, long leaseTime, java.util.concurrent.TimeUnit timeUnit) {
                return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        DistributedLock lock = ServiceLoaderDistributedLockFactory.this.getLock(lockName);
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
                        DistributedLock lock = ServiceLoaderDistributedLockFactory.this.getLock(lockName);
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

            // 其他方法的默认实现...
            @Override
            public java.util.concurrent.CompletableFuture<AsyncLockRenewalResult> renewLockAsync(DistributedLock lock, long newLeaseTime, java.util.concurrent.TimeUnit timeUnit) {
                throw new UnsupportedOperationException("Renew lock not implemented in default factory");
            }

            @Override
            public java.util.concurrent.CompletableFuture<AsyncLockInfoResult> getLockInfoAsync(String lockName) {
                throw new UnsupportedOperationException("Get lock info not implemented in default factory");
            }

            @Override
            public java.util.concurrent.CompletableFuture<AsyncLockStateResult> checkLockStateAsync(String lockName) {
                throw new UnsupportedOperationException("Check lock state not implemented in default factory");
            }

            @Override
            public java.util.concurrent.CompletableFuture<AsyncLockReleaseResult> releaseLockByNameAsync(String lockName) {
                throw new UnsupportedOperationException("Release lock by name not implemented in default factory");
            }

            @Override
            public java.util.concurrent.CompletableFuture<AsyncWaitResult> waitForLockReleaseAsync(String lockName, long timeout, java.util.concurrent.TimeUnit timeUnit) {
                throw new UnsupportedOperationException("Wait for lock release not implemented in default factory");
            }

            @Override
            public <R> java.util.concurrent.CompletableFuture<AsyncBusinessOperationResult<R>> executeWithLockAsync(String lockName, long leaseTime, java.util.concurrent.TimeUnit timeUnit, AsyncBusinessOperation<R, DistributedLock> businessOperation) {
                throw new UnsupportedOperationException("Execute with lock not implemented in default factory");
            }

            @Override
            public <R> java.util.concurrent.CompletableFuture<AsyncRetryOperationResult<R>> executeWithRetryAsync(String lockName, long leaseTime, java.util.concurrent.TimeUnit timeUnit, int maxRetries, long retryDelay, java.util.concurrent.TimeUnit retryDelayTimeUnit, AsyncRetryableLockOperation<R, DistributedLock> operation) {
                throw new UnsupportedOperationException("Execute with retry not implemented in default factory");
            }

            @Override
            public LockRenewalTask autoRenewLock(DistributedLock lock, long renewInterval, java.util.concurrent.TimeUnit timeUnit) {
                throw new UnsupportedOperationException("Auto renew lock not implemented in default factory");
            }

            @Override
            public void cancelAutoRenew(LockRenewalTask renewalTask) {
                throw new UnsupportedOperationException("Cancel auto renew not implemented in default factory");
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
                // 默认空实现
            }

            @Override
            public void close() {
                // 默认空实现
            }
        };
    }

    @Override
    public FactoryHealthStatus healthCheck() {
        return new FactoryHealthStatus() {
            @Override
            public boolean isHealthy() {
                return selectedProvider != null;
            }

            @Override
            public String getDetails() {
                return selectedProvider != null ? 
                    "Provider " + selectedProvider.getType() + " is available" : 
                    "No provider available";
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
                        return 0;
                    }

                    @Override
                    public double getThroughput() {
                        return 0.0;
                    }

                    @Override
                    public double getErrorRate() {
                        return 0.0;
                    }

                    @Override
                    public int getActiveConnections() {
                        return 1;
                    }
                };
            }

            @Override
            public String getErrorMessage() {
                return null;
            }
        };
    }

    @Override
    public java.util.concurrent.CompletableFuture<FactoryHealthStatus> healthCheckAsync() {
        return java.util.concurrent.CompletableFuture.supplyAsync(this::healthCheck);
    }

    @Override
    public boolean isLockAvailable(String lockName) {
        try {
            DistributedLock lock = getLock(lockName);
            return lock.tryLock(0, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> isLockAvailableAsync(String lockName) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> isLockAvailable(lockName));
    }

    @Override
    public FactoryStatistics getStatistics() {
        return new FactoryStatistics() {
            @Override
            public long getTotalLocks() {
                return providers.size();
            }

            @Override
            public long getActiveLocks() {
                return 0; // 默认实现
            }

            @Override
            public long getTotalLockAcquisitions() {
                return 0; // 默认实现
            }

            @Override
            public long getFailedLockAcquisitions() {
                return 0; // 默认实现
            }

            @Override
            public long getTotalLockReleases() {
                return 0; // 默认实现
            }

            @Override
            public double getAverageLockAcquisitionTime() {
                return 0.0; // 默认实现
            }

            @Override
            public int getPeakConcurrency() {
                return 0; // 默认实现
            }

            @Override
            public long getUptime() {
                return 0; // 默认实现
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
                        return 0.0;
                    }
                };
            }
        };
    }

    @Override
    public void close() {
        shutdown();
    }
}
