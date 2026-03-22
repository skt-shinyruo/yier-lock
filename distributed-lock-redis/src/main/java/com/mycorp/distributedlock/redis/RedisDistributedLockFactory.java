package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.AsyncLockOperations;
import com.mycorp.distributedlock.api.BatchLockOperations;
import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.api.HighAvailabilityStrategy;
import com.mycorp.distributedlock.api.LockConfigurationBuilder;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Legacy compatibility factory used by benchmarks and examples.
 */
public class RedisDistributedLockFactory implements DistributedLockFactory {

    private static final Logger logger = LoggerFactory.getLogger(RedisDistributedLockFactory.class);

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final long defaultLeaseTimeSeconds;

    private volatile FactoryState state = FactoryState.RUNNING;

    public RedisDistributedLockFactory(RedisClient redisClient) {
        this(redisClient, 30L);
    }

    public RedisDistributedLockFactory(RedisClient redisClient, long defaultLeaseTimeSeconds) {
        this.redisClient = redisClient;
        this.connection = redisClient.connect();
        this.commands = connection.sync();
        this.defaultLeaseTimeSeconds = Math.max(1L, defaultLeaseTimeSeconds);
    }

    @Override
    public DistributedLock getLock(String name) {
        return new SimpleRedisLock(name, commands, defaultLeaseTimeSeconds);
    }

    @Override
    public DistributedReadWriteLock getReadWriteLock(String name) {
        return new EnhancedRedisReadWriteLock(name, commands, defaultLeaseTimeSeconds, defaultLeaseTimeSeconds);
    }

    @Override
    public DistributedLock getConfiguredLock(String name, LockConfigurationBuilder.LockConfiguration configuration) {
        validateConfigurationSupport(configuration);
        return getLock(name);
    }

    @Override
    public DistributedReadWriteLock getConfiguredReadWriteLock(String name, LockConfigurationBuilder.LockConfiguration configuration) {
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
            BatchLockOperations.BatchOperationExecutor<R> operation) {
        return new RedisBatchLockOperations(commands, defaultLeaseTimeSeconds);
    }

    @Override
    public AsyncLockOperations<DistributedLock> createAsyncLockOperations() {
        return new com.mycorp.distributedlock.api.ServiceLoaderDistributedLockFactory("redis").createAsyncLockOperations();
    }

    @Override
    public FactoryHealthStatus healthCheck() {
        long startedAt = System.currentTimeMillis();
        Throwable probeFailure = null;
        boolean probeHealthy = false;

        try {
            probeHealthy = "PONG".equalsIgnoreCase(commands.ping());
        } catch (RuntimeException exception) {
            probeFailure = exception;
            logger.debug("Redis health probe failed", exception);
        }

        long checkTime = System.currentTimeMillis();
        long responseTimeMs = Math.max(0L, checkTime - startedAt);
        final boolean backendHealthy = probeHealthy;
        final Throwable failure = probeFailure;
        final long measuredResponseTimeMs = responseTimeMs;
        return new FactoryHealthStatus() {
            @Override
            public boolean isHealthy() {
                return state == FactoryState.RUNNING && backendHealthy;
            }

            @Override
            public String getDetails() {
                return backendHealthy
                        ? "RedisDistributedLockFactory compatibility facade"
                        : "Redis backend probe failed";
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
                        return measuredResponseTimeMs;
                    }

                    @Override
                    public double getThroughput() {
                        return 0;
                    }

                    @Override
                    public double getErrorRate() {
                        return 0;
                    }

                    @Override
                    public int getActiveConnections() {
                        return 1;
                    }
                };
            }

            @Override
            public String getErrorMessage() {
                if (state != FactoryState.RUNNING) {
                    return "Factory state is " + state;
                }
                return failure != null ? failure.getMessage() : null;
            }
        };
    }

    private void validateConfigurationSupport(LockConfigurationBuilder.LockConfiguration configuration) {
        if (configuration == null) {
            return;
        }
        if (configuration.isFairLock() || configuration.getLockType() == LockConfigurationBuilder.LockType.FAIR) {
            throw new UnsupportedOperationException("Fair locks are not supported by RedisDistributedLockFactory");
        }
    }

    @Override
    public CompletableFuture<FactoryHealthStatus> healthCheckAsync() {
        return CompletableFuture.completedFuture(healthCheck());
    }

    @Override
    public boolean isLockAvailable(String name) {
        return !getLock(name).isLocked();
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
                return false;
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
    public void shutdown() {
        state = FactoryState.SHUTDOWN;
        connection.close();
        redisClient.shutdown();
        state = FactoryState.TERMINATED;
    }

    @Override
    public void close() {
        shutdown();
    }
}
