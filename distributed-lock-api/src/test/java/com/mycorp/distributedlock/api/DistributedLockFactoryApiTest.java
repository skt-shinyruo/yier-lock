package com.mycorp.distributedlock.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class DistributedLockFactoryApiTest {

    private TestFactory factory;

    @BeforeEach
    void setUp() {
        factory = new TestFactory();
    }

    @Test
    void shouldCreateNamedLock() {
        DistributedLock lock = factory.getLock("alpha");

        assertEquals("alpha", lock.getName());
    }

    @Test
    void shouldCreateReadWriteLock() {
        DistributedReadWriteLock readWriteLock = factory.getReadWriteLock("orders");

        assertEquals("orders", readWriteLock.getName());
        assertEquals("orders:read", readWriteLock.readLock().getName());
        assertEquals("orders:write", readWriteLock.writeLock().getName());
    }

    @Test
    void shouldGetLockAsync() {
        DistributedLock lock = factory.getLockAsync("async").join();

        assertEquals("async", lock.getName());
    }

    @Test
    void shouldReleaseLockHeldByCurrentThread() throws InterruptedException {
        DistributedLock lock = factory.getLock("release-me");
        lock.lock(1, TimeUnit.SECONDS);

        assertTrue(factory.releaseLock("release-me"));
        assertFalse(lock.isLocked());
    }

    @Test
    void shouldExposeHealthCheckAndStatistics() {
        DistributedLockFactory.FactoryHealthStatus health = factory.healthCheck();
        DistributedLockFactory.FactoryStatistics statistics = factory.getStatistics();

        assertTrue(health.isHealthy());
        assertEquals("Factory is healthy", health.getDetails());
        assertEquals(0, statistics.getActiveLocks());
    }

    @Test
    void shouldShutdownFactory() {
        factory.shutdown();

        assertEquals(DistributedLockFactory.FactoryState.SHUTDOWN, factory.getState());
        assertThrows(IllegalStateException.class, () -> factory.getLock("after-shutdown"));
    }

    private static final class TestFactory implements DistributedLockFactory {
        private final Map<String, TestLock> locks = new java.util.concurrent.ConcurrentHashMap<>();
        private volatile FactoryState state = FactoryState.RUNNING;

        @Override
        public DistributedLock getLock(String name) {
            ensureRunning();
            return locks.computeIfAbsent(name, TestLock::new);
        }

        @Override
        public DistributedReadWriteLock getReadWriteLock(String name) {
            ensureRunning();
            return new TestReadWriteLock(name);
        }

        @Override
        public DistributedLock getConfiguredLock(String name, LockConfigurationBuilder.LockConfiguration configuration) {
            return getLock(name);
        }

        @Override
        public DistributedReadWriteLock getConfiguredReadWriteLock(String name, LockConfigurationBuilder.LockConfiguration configuration) {
            return getReadWriteLock(name);
        }

        @Override
        public Map<String, DistributedLock> getLocks(List<String> lockNames) {
            return lockNames.stream().collect(java.util.stream.Collectors.toMap(name -> name, this::getLock));
        }

        @Override
        public Map<String, DistributedReadWriteLock> getReadWriteLocks(List<String> lockNames) {
            return lockNames.stream().collect(java.util.stream.Collectors.toMap(name -> name, this::getReadWriteLock));
        }

        @Override
        public CompletableFuture<DistributedLock> getLockAsync(String name) {
            return CompletableFuture.completedFuture(getLock(name));
        }

        @Override
        public CompletableFuture<DistributedReadWriteLock> getReadWriteLockAsync(String name) {
            return CompletableFuture.completedFuture(getReadWriteLock(name));
        }

        @Override
        public <R> BatchLockOperations<DistributedLock> createBatchLockOperations(List<String> lockNames,
                                                                                  BatchLockOperations.BatchOperationExecutor<R> operation) {
            return null;
        }

        @Override
        public AsyncLockOperations<DistributedLock> createAsyncLockOperations() {
            return null;
        }

        @Override
        public FactoryHealthStatus healthCheck() {
            return new FactoryHealthStatus() {
                @Override
                public boolean isHealthy() {
                    return state == FactoryState.RUNNING;
                }

                @Override
                public String getDetails() {
                    return "Factory is healthy";
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
                            return 1;
                        }

                        @Override
                        public double getThroughput() {
                            return 1.0;
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
                    return null;
                }
            };
        }

        @Override
        public CompletableFuture<FactoryHealthStatus> healthCheckAsync() {
            return CompletableFuture.completedFuture(healthCheck());
        }

        @Override
        public boolean isLockAvailable(String name) {
            return !locks.containsKey(name) || !locks.get(name).isLocked();
        }

        @Override
        public CompletableFuture<Boolean> isLockAvailableAsync(String name) {
            return CompletableFuture.completedFuture(isLockAvailable(name));
        }

        @Override
        public FactoryStatistics getStatistics() {
            return new FactoryStatistics() {
                @Override
                public long getTotalLocks() {
                    return locks.size();
                }

                @Override
                public long getActiveLocks() {
                    return locks.values().stream().filter(DistributedLock::isLocked).count();
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
                    return 1;
                }

                @Override
                public long getUptime() {
                    return 1;
                }

                @Override
                public MemoryUsage getMemoryUsage() {
                    return new MemoryUsage() {
                        @Override
                        public long getUsedMemory() {
                            return 1;
                        }

                        @Override
                        public long getTotalMemory() {
                            return 1;
                        }

                        @Override
                        public long getAvailableMemory() {
                            return 1;
                        }

                        @Override
                        public double getUsageRate() {
                            return 1.0;
                        }
                    };
                }
            };
        }

        @Override
        public List<String> getActiveLocks() {
            return locks.values().stream()
                .filter(DistributedLock::isLocked)
                .map(DistributedLock::getName)
                .toList();
        }

        @Override
        public FactoryConfiguration getConfiguration() {
            return new FactoryConfiguration() {
                @Override
                public int getMaxLocks() {
                    return 100;
                }

                @Override
                public long getConnectionTimeout(TimeUnit unit) {
                    return unit.convert(1, TimeUnit.SECONDS);
                }

                @Override
                public long getOperationTimeout(TimeUnit unit) {
                    return unit.convert(1, TimeUnit.SECONDS);
                }

                @Override
                public int getRetryCount() {
                    return 0;
                }

                @Override
                public long getRetryInterval(TimeUnit unit) {
                    return 0;
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
        }

        @Override
        public void close() {
            shutdown();
        }

        private void ensureRunning() {
            if (state != FactoryState.RUNNING) {
                throw new IllegalStateException("Factory is shutdown");
            }
        }
    }

    private static final class TestReadWriteLock implements DistributedReadWriteLock {
        private final String name;

        private TestReadWriteLock(String name) {
            this.name = name;
        }

        @Override
        public DistributedLock readLock() {
            return new TestLock(name + ":read");
        }

        @Override
        public DistributedLock writeLock() {
            return new TestLock(name + ":write");
        }

        @Override
        public String getName() {
            return name;
        }
    }

    private static final class TestLock implements DistributedLock {
        private final String name;
        private boolean locked;
        private Thread owner;
        private Instant expirationTime;

        private TestLock(String name) {
            this.name = name;
        }

        @Override
        public void lock(long leaseTime, TimeUnit unit) {
            locked = true;
            owner = Thread.currentThread();
            expirationTime = Instant.now().plusMillis(unit.toMillis(leaseTime));
        }

        @Override
        public void lock() {
            lock(30, TimeUnit.SECONDS);
        }

        @Override
        public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) {
            if (locked && owner != Thread.currentThread()) {
                return false;
            }
            lock(leaseTime, unit);
            return true;
        }

        @Override
        public boolean tryLock() {
            return tryLock(0, 30, TimeUnit.SECONDS);
        }

        @Override
        public void unlock() {
            locked = false;
            owner = null;
            expirationTime = null;
        }

        @Override
        public CompletableFuture<Void> lockAsync(long leaseTime, TimeUnit unit) {
            return CompletableFuture.runAsync(() -> lock(leaseTime, unit));
        }

        @Override
        public CompletableFuture<Void> lockAsync() {
            return CompletableFuture.runAsync(this::lock);
        }

        @Override
        public CompletableFuture<Boolean> tryLockAsync(long waitTime, long leaseTime, TimeUnit unit) {
            return CompletableFuture.completedFuture(tryLock(waitTime, leaseTime, unit));
        }

        @Override
        public CompletableFuture<Boolean> tryLockAsync() {
            return CompletableFuture.completedFuture(tryLock());
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
            return locked && owner == Thread.currentThread();
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public ScheduledFuture<?> scheduleAutoRenewal(long renewInterval, TimeUnit unit,
                                                      Consumer<RenewalResult> renewalCallback) {
            return new NoopScheduledFuture();
        }

        @Override
        public LockStateInfo getLockStateInfo() {
            return new LockStateInfo() {
                @Override
                public boolean isLocked() {
                    return locked;
                }

                @Override
                public boolean isHeldByCurrentThread() {
                    return TestLock.this.isHeldByCurrentThread();
                }

                @Override
                public String getHolder() {
                    return owner != null ? owner.getName() : null;
                }

                @Override
                public long getRemainingTime(TimeUnit unit) {
                    if (expirationTime == null) {
                        return 0;
                    }
                    long remainingMillis = Math.max(0, expirationTime.toEpochMilli() - System.currentTimeMillis());
                    return unit.convert(remainingMillis, TimeUnit.MILLISECONDS);
                }

                @Override
                public int getReentrantCount() {
                    return isHeldByCurrentThread() ? 1 : 0;
                }

                @Override
                public Instant getCreationTime() {
                    return Instant.now();
                }

                @Override
                public Instant getExpirationTime() {
                    return expirationTime;
                }

                @Override
                public LockType getLockType() {
                    return LockType.MUTEX;
                }

                @Override
                public String getMetadata() {
                    return "{}";
                }
            };
        }
    }

    private static final class NoopScheduledFuture implements ScheduledFuture<Object> {
        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException {
            return null;
        }
    }
}
