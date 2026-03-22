package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.exception.LockAcquisitionException;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import com.mycorp.distributedlock.zookeeper.connection.ZooKeeperConnectionManager;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Minimal ZooKeeper distributed lock backed by Curator's {@link InterProcessMutex}.
 */
public class ZooKeeperDistributedLock implements DistributedLock {

    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperDistributedLock.class);

    private static final ScheduledThreadPoolExecutor RENEWAL_EXECUTOR =
            new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "zookeeper-lock-renewal");
                thread.setDaemon(true);
                return thread;
            });

    private final String name;
    private final InterProcessMutex mutex;
    private final LockConfiguration configuration;
    private final LockMetrics metrics;
    private final LockTracing tracing;
    private final ZooKeeperConnectionManager connectionManager;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<Thread> ownerThread = new AtomicReference<>();
    private final AtomicInteger reentrantCount = new AtomicInteger(0);
    private final AtomicLong lastHealthCheck = new AtomicLong(0);

    public ZooKeeperDistributedLock(String name,
                                    CuratorFramework curatorFramework,
                                    LockConfiguration configuration,
                                    LockMetrics metrics,
                                    LockTracing tracing,
                                    String lockPath) {
        this(name, curatorFramework, configuration, metrics, tracing, lockPath, null);
    }

    public ZooKeeperDistributedLock(String name,
                                    CuratorFramework curatorFramework,
                                    LockConfiguration configuration,
                                    LockMetrics metrics,
                                    LockTracing tracing,
                                    String lockPath,
                                    ZooKeeperConnectionManager connectionManager) {
        this.name = Objects.requireNonNull(name, "name");
        this.mutex = new InterProcessMutex(Objects.requireNonNull(curatorFramework, "curatorFramework"), lockPath);
        this.configuration = configuration != null ? configuration : new LockConfiguration();
        this.metrics = metrics;
        this.tracing = tracing;
        this.connectionManager = connectionManager;
    }

    @Override
    public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
        ensureOpen();
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
        ensureOpen();
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
            if (metrics != null) {
                metrics.incrementLockReleaseCounter(name, "success");
            }
        } catch (Exception e) {
            if (metrics != null) {
                metrics.incrementLockReleaseCounter(name, "error");
            }
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
            boolean success = isLocked() && ownerThread.get() != null && !closed.get();
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
                return ZooKeeperDistributedLock.this.isLocked();
            }

            @Override
            public boolean isHeldByCurrentThread() {
                return ZooKeeperDistributedLock.this.isHeldByCurrentThread();
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
                return ZooKeeperDistributedLock.this.isHeldByCurrentThread() ? reentrantCount.get() : 0;
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
                return LockType.REENTRANT;
            }

            @Override
            public String getMetadata() {
                return "{\"backend\":\"zookeeper\"}";
            }
        };
    }

    public LockInfo getLockInfo() {
        return new LockInfo(
                name,
                isLocked(),
                isHeldByCurrentThread(),
                reentrantCount.get(),
                lastHealthCheck.get(),
                isConnectionHealthy());
    }

    public boolean tryLockWithRetry(Duration maxWaitTime, int maxRetries) throws InterruptedException {
        Duration budget = maxWaitTime != null ? maxWaitTime : configuration.getDefaultWaitTime();
        int attempts = Math.max(1, maxRetries);
        long perAttemptMillis = Math.max(1L, budget.toMillis() / attempts);

        for (int attempt = 0; attempt < attempts; attempt++) {
            if (tryLock(perAttemptMillis, configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS)) {
                return true;
            }
        }
        return false;
    }

    public boolean tryLockWithRetry(Duration maxWaitTime, Duration leaseTime, int maxRetries) throws InterruptedException {
        Duration budget = maxWaitTime != null ? maxWaitTime : configuration.getDefaultWaitTime();
        Duration lease = leaseTime != null ? leaseTime : configuration.getDefaultLeaseTime();
        int attempts = Math.max(1, maxRetries);
        long perAttemptMillis = Math.max(1L, budget.toMillis() / attempts);

        for (int attempt = 0; attempt < attempts; attempt++) {
            if (tryLock(perAttemptMillis, lease.toMillis(), TimeUnit.MILLISECONDS)) {
                return true;
            }
        }
        return false;
    }

    public void forceUnlock() {
        if (!isLocked()) {
            return;
        }
        if (isHeldByCurrentThread()) {
            while (reentrantCount.get() > 0) {
                unlock();
            }
        }
    }

    public void close() {
        boolean heldByCurrentThread = mutex.isOwnedByCurrentThread();
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (heldByCurrentThread) {
            forceUnlock();
        }
    }

    private boolean isConnectionHealthy() {
        lastHealthCheck.set(System.currentTimeMillis());
        return connectionManager == null || connectionManager.isHealthy();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Lock " + name + " has been closed");
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
        if (metrics != null) {
            metrics.incrementLockAcquisitionCounter(name, "success");
        }
        logger.debug("ZooKeeper lock acquired by thread {}: {}", currentThread.getName(), name);
    }

    private LockTracing.SpanContext startSpan(String operation) {
        return tracing != null ? tracing.startLockAcquisitionSpan(name, operation) : new NoOpSpanContext();
    }

    public record LockInfo(
            String name,
            boolean locked,
            boolean heldByCurrentThread,
            int reentrantCount,
            long lastHealthCheckTimestamp,
            boolean connectionHealthy
    ) {
    }

    private static final class ImmutableRenewalResult implements RenewalResult {
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
