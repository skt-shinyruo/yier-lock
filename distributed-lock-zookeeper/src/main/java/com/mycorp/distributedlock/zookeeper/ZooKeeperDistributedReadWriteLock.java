package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.api.exception.LockAcquisitionException;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import com.mycorp.distributedlock.zookeeper.connection.ZooKeeperConnectionManager;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.framework.recipes.locks.InterProcessReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Minimal ZooKeeper read-write lock backed by Curator's {@link InterProcessReadWriteLock}.
 */
public class ZooKeeperDistributedReadWriteLock implements DistributedReadWriteLock {

    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperDistributedReadWriteLock.class);

    private final String name;
    private final LockConfiguration configuration;
    private final ReadLock readLock;
    private final WriteLock writeLock;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger upgradeCount = new AtomicInteger();
    private final AtomicInteger downgradeCount = new AtomicInteger();

    public ZooKeeperDistributedReadWriteLock(String name,
                                             CuratorFramework curatorFramework,
                                             LockConfiguration configuration,
                                             LockMetrics metrics,
                                             LockTracing tracing,
                                             String lockPath) {
        this(name, curatorFramework, configuration, metrics, tracing, lockPath, null);
    }

    public ZooKeeperDistributedReadWriteLock(String name,
                                             CuratorFramework curatorFramework,
                                             LockConfiguration configuration,
                                             LockMetrics metrics,
                                             LockTracing tracing,
                                             String lockPath,
                                             ZooKeeperConnectionManager connectionManager) {
        this.name = Objects.requireNonNull(name, "name");
        this.configuration = configuration != null ? configuration : new LockConfiguration();
        InterProcessReadWriteLock lock = new InterProcessReadWriteLock(
                Objects.requireNonNull(curatorFramework, "curatorFramework"),
                lockPath);
        this.readLock = new ReadLock(name + ":read", lock.readLock(), this.configuration, metrics, tracing, connectionManager);
        this.writeLock = new WriteLock(name + ":write", lock.writeLock(), this.configuration, metrics, tracing, connectionManager);
    }

    @Override
    public DistributedLock readLock() {
        ensureOpen();
        return readLock;
    }

    @Override
    public DistributedLock writeLock() {
        ensureOpen();
        return writeLock;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean tryUpgradeToWriteLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        ensureOpen();
        if (!readLock.isHeldByCurrentThread()) {
            return false;
        }

        readLock.unlock();
        boolean upgraded = false;
        try {
            upgraded = writeLock.tryLock(waitTime, leaseTime, unit);
            if (upgraded) {
                upgradeCount.incrementAndGet();
            }
            return upgraded;
        } finally {
            if (!upgraded) {
                readLock.lock(leaseTime, unit);
            }
        }
    }

    @Override
    public boolean tryDowngradeToReadLock(long leaseTime, TimeUnit unit) {
        ensureOpen();
        if (!writeLock.isHeldByCurrentThread()) {
            return false;
        }
        try {
            readLock.lock(leaseTime, unit);
            writeLock.unlock();
            downgradeCount.incrementAndGet();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (readLock.isHeldByCurrentThread()) {
            readLock.forceUnlock();
        }
        if (writeLock.isHeldByCurrentThread()) {
            writeLock.forceUnlock();
        }
    }

    public CompletableFuture<WriteLockUpgradeResult> upgradeReadLock(Duration timeout) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                boolean upgraded = tryUpgradeToWriteLock(timeout.toMillis(),
                        configuration.getDefaultLeaseTime().toMillis(),
                        TimeUnit.MILLISECONDS);
                return new WriteLockUpgradeResult(upgraded,
                        upgraded ? "Successfully upgraded" : "Failed to acquire write lock",
                        null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new WriteLockUpgradeResult(false, "Upgrade interrupted", e);
            }
        });
    }

    public CompletableFuture<WriteLockDowngradeResult> downgradeWriteLock() {
        return CompletableFuture.supplyAsync(() -> {
            boolean downgraded = tryDowngradeToReadLock(configuration.getDefaultLeaseTime().toMillis(),
                    TimeUnit.MILLISECONDS);
            return new WriteLockDowngradeResult(downgraded,
                    downgraded ? "Successfully downgraded" : "Failed to downgrade",
                    null);
        });
    }

    public LockUpgradeInfo getUpgradeInfo() {
        return new LockUpgradeInfo(
                upgradeCount.get(),
                downgradeCount.get(),
                readLock.isLocked(),
                writeLock.isLocked(),
                readLock.isHeldByCurrentThread(),
                writeLock.isHeldByCurrentThread());
    }

    public CompletableFuture<UpgradeWithRollbackResult> upgradeWithRollback(Duration timeout, Runnable rollbackAction) {
        return upgradeReadLock(timeout).thenApply(result -> new UpgradeWithRollbackResult(
                result.successful(),
                result.message(),
                result.error(),
                rollbackAction));
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Read-write lock " + name + " has been closed");
        }
    }

    private abstract static class EnhancedLock implements DistributedLock {

        private static final ScheduledThreadPoolExecutor RENEWAL_EXECUTOR =
                new ScheduledThreadPoolExecutor(1, runnable -> {
                    Thread thread = new Thread(runnable, "zookeeper-rw-renewal");
                    thread.setDaemon(true);
                    return thread;
                });

        private final String name;
        private final InterProcessMutex mutex;
        private final LockConfiguration configuration;
        private final LockMetrics metrics;
        private final LockTracing tracing;
        private final ZooKeeperConnectionManager connectionManager;

        private final AtomicReference<Thread> ownerThread = new AtomicReference<>();
        private final AtomicInteger reentrantCount = new AtomicInteger(0);

        private EnhancedLock(String name,
                             InterProcessMutex mutex,
                             LockConfiguration configuration,
                             LockMetrics metrics,
                             LockTracing tracing,
                             ZooKeeperConnectionManager connectionManager) {
            this.name = name;
            this.mutex = mutex;
            this.configuration = configuration != null ? configuration : new LockConfiguration();
            this.metrics = metrics;
            this.tracing = tracing;
            this.connectionManager = connectionManager;
        }

        @Override
        public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
            try (LockTracing.SpanContext span = startSpan("lock")) {
                mutex.acquire();
                recordAcquired();
                span.setStatus("acquired");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                throw new LockAcquisitionException("Failed to acquire ZooKeeper lock: " + name, e);
            }
        }

        @Override
        public void lock() throws InterruptedException {
            lock(configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
            try (LockTracing.SpanContext span = startSpan("tryLock")) {
                boolean acquired = mutex.acquire(waitTime, unit);
                if (acquired) {
                    recordAcquired();
                    span.setStatus("acquired");
                } else {
                    if (metrics != null) {
                        metrics.incrementContentionCounter(name);
                    }
                    span.setStatus("contention");
                }
                return acquired;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                throw new LockAcquisitionException("Failed to acquire ZooKeeper lock: " + name, e);
            }
        }

        @Override
        public boolean tryLock() throws InterruptedException {
            return tryLock(configuration.getDefaultWaitTime().toMillis(),
                    configuration.getDefaultLeaseTime().toMillis(),
                    TimeUnit.MILLISECONDS);
        }

        @Override
        public void unlock() {
            if (!isHeldByCurrentThread()) {
                throw new IllegalMonitorStateException("Current thread does not hold lock " + name);
            }
            try {
                mutex.release();
                int remaining = reentrantCount.decrementAndGet();
                if (remaining <= 0) {
                    ownerThread.set(null);
                    reentrantCount.set(0);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to release ZooKeeper lock: " + name, e);
            }
        }

        @Override
        public CompletableFuture<Void> lockAsync(long leaseTime, TimeUnit unit) {
            return CompletableFuture.runAsync(() -> {
                try {
                    lock(leaseTime, unit);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LockAcquisitionException("Interrupted while acquiring ZooKeeper lock: " + name, e);
                }
            });
        }

        @Override
        public CompletableFuture<Void> lockAsync() {
            return lockAsync(configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public CompletableFuture<Boolean> tryLockAsync(long waitTime, long leaseTime, TimeUnit unit) {
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
            return tryLockAsync(configuration.getDefaultWaitTime().toMillis(),
                    configuration.getDefaultLeaseTime().toMillis(),
                    TimeUnit.MILLISECONDS);
        }

        @Override
        public CompletableFuture<Void> unlockAsync() {
            return CompletableFuture.runAsync(this::unlock);
        }

        @Override
        public ScheduledFuture<?> scheduleAutoRenewal(long renewInterval,
                                                      TimeUnit unit,
                                                      Consumer<RenewalResult> renewalCallback) {
            long period = Math.max(1L, unit.toMillis(renewInterval));
            return RENEWAL_EXECUTOR.scheduleAtFixedRate(() -> {
                boolean success = isLocked() && ownerThread.get() != null
                        && (connectionManager == null || connectionManager.isHealthy());
                if (renewalCallback != null) {
                    renewalCallback.accept(new ImmutableRenewalResult(success, null,
                            System.currentTimeMillis(), success ? Long.MAX_VALUE : 0L));
                }
            }, period, period, TimeUnit.MILLISECONDS);
        }

        @Override
        public boolean isLocked() {
            return mutex.isAcquiredInThisProcess();
        }

        @Override
        public boolean isHeldByCurrentThread() {
            return mutex.isOwnedByCurrentThread();
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getLockHolder() {
            Thread thread = ownerThread.get();
            return thread != null ? thread.getName() : null;
        }

        @Override
        public LockStateInfo getLockStateInfo() {
            return new LockStateInfo() {
                @Override
                public boolean isLocked() {
                    return EnhancedLock.this.isLocked();
                }

                @Override
                public boolean isHeldByCurrentThread() {
                    return EnhancedLock.this.isHeldByCurrentThread();
                }

                @Override
                public String getHolder() {
                    return getLockHolder();
                }

                @Override
                public long getRemainingTime(TimeUnit unit) {
                    return 0;
                }

                @Override
                public int getReentrantCount() {
                    return EnhancedLock.this.isHeldByCurrentThread() ? reentrantCount.get() : 0;
                }

                @Override
                public java.time.Instant getCreationTime() {
                    return null;
                }

                @Override
                public java.time.Instant getExpirationTime() {
                    return null;
                }

                @Override
                public LockType getLockType() {
                    return LockType.READ_WRITE;
                }

                @Override
                public String getMetadata() {
                    return "{\"backend\":\"zookeeper\"}";
                }
            };
        }

        public void forceUnlock() {
            if (!isHeldByCurrentThread()) {
                return;
            }
            while (reentrantCount.get() > 0) {
                unlock();
            }
        }

        private void recordAcquired() {
            Thread currentThread = Thread.currentThread();
            Thread currentOwner = ownerThread.get();
            if (currentOwner == currentThread) {
                reentrantCount.incrementAndGet();
            } else {
                ownerThread.set(currentThread);
                reentrantCount.set(1);
            }
            logger.debug("ZooKeeper read/write lock acquired by thread {}: {}", currentThread.getName(), name);
        }

        private LockTracing.SpanContext startSpan(String operation) {
            return tracing != null ? tracing.startLockAcquisitionSpan(name, operation) : new NoOpSpanContext();
        }
    }

    static final class ReadLock extends EnhancedLock {
        private ReadLock(String name,
                         InterProcessMutex mutex,
                         LockConfiguration configuration,
                         LockMetrics metrics,
                         LockTracing tracing,
                         ZooKeeperConnectionManager connectionManager) {
            super(name, mutex, configuration, metrics, tracing, connectionManager);
        }
    }

    static final class WriteLock extends EnhancedLock {
        private WriteLock(String name,
                          InterProcessMutex mutex,
                          LockConfiguration configuration,
                          LockMetrics metrics,
                          LockTracing tracing,
                          ZooKeeperConnectionManager connectionManager) {
            super(name, mutex, configuration, metrics, tracing, connectionManager);
        }
    }

    public record WriteLockUpgradeResult(
            boolean successful,
            String message,
            Throwable error
    ) {
    }

    public record WriteLockDowngradeResult(
            boolean successful,
            String message,
            Throwable error
    ) {
    }

    public record LockUpgradeInfo(
            int totalUpgrades,
            int totalDowngrades,
            boolean readLockActive,
            boolean writeLockActive,
            boolean readLockHeld,
            boolean writeLockHeld
    ) {
    }

    public record UpgradeWithRollbackResult(
            boolean successful,
            String message,
            Throwable error,
            Runnable rollbackAction
    ) {
        public void executeRollback() {
            if (rollbackAction != null) {
                rollbackAction.run();
            }
        }
    }

    private static final class ImmutableRenewalResult implements DistributedLock.RenewalResult {
        private final boolean success;
        private final Throwable failureCause;
        private final long renewalTime;
        private final long newExpirationTime;

        private ImmutableRenewalResult(boolean success,
                                       Throwable failureCause,
                                       long renewalTime,
                                       long newExpirationTime) {
            this.success = success;
            this.failureCause = failureCause;
            this.renewalTime = renewalTime;
            this.newExpirationTime = newExpirationTime;
        }

        @Override
        public boolean isSuccess() {
            return success;
        }

        @Override
        public Throwable getFailureCause() {
            return failureCause;
        }

        @Override
        public long getRenewalTime() {
            return renewalTime;
        }

        @Override
        public long getNewExpirationTime() {
            return newExpirationTime;
        }
    }

    private static final class NoOpSpanContext implements LockTracing.SpanContext {
        @Override
        public void setStatus(String status) {
        }

        @Override
        public void setError(Throwable throwable) {
        }

        @Override
        public void setAttribute(String key, String value) {
        }

        @Override
        public void setAttribute(String key, long value) {
        }

        @Override
        public void setAttribute(String key, boolean value) {
        }

        @Override
        public void close() {
        }
    }
}
