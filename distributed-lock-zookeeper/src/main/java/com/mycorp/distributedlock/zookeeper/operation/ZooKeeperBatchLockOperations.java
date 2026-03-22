package com.mycorp.distributedlock.zookeeper.operation;

import com.mycorp.distributedlock.api.BatchLockOperations;
import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.LockEvent;
import com.mycorp.distributedlock.api.LockEventListener;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import com.mycorp.distributedlock.zookeeper.ZooKeeperDistributedLock;
import com.mycorp.distributedlock.zookeeper.ZooKeeperDistributedLockFactory;
import com.mycorp.distributedlock.zookeeper.connection.ZooKeeperConnectionManager;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.curator.framework.CuratorFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Minimal compile-safe batch operations facade for the current branch.
 *
 * <p>The previous enterprise implementation drifted far from the current API
 * surface. This version keeps the public constructors and small helper methods
 * that existing call sites use, while reducing behavior to a simple
 * all-or-nothing batch wrapper.</p>
 */
public class ZooKeeperBatchLockOperations implements BatchLockOperations<DistributedLock> {

    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperBatchLockOperations.class);
    private static final String DEFAULT_LOCK_NAME = "zookeeper-batch";
    private static final long DEFAULT_LEASE_TIME_MS = TimeUnit.SECONDS.toMillis(30);

    private final String lockName;
    private final ZooKeeperConnectionManager connectionManager;
    private final LockConfiguration configuration;
    private final LockMetrics metrics;
    private final LockTracing tracing;
    private final Function<String, DistributedLock> lockSupplier;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean deadlockDetectionEnabled = new AtomicBoolean(false);
    private final AtomicLong sessionSequence = new AtomicLong();
    private final AtomicLong totalBatchOperations = new AtomicLong();
    private final AtomicLong successfulBatchOperations = new AtomicLong();
    private final AtomicLong failedBatchOperations = new AtomicLong();
    private final AtomicLong totalLocksAcquired = new AtomicLong();
    private final AtomicLong totalLocksReleased = new AtomicLong();
    private final Map<String, ZooKeeperBatchSessionInfo> activeSessions = new ConcurrentHashMap<>();
    private final List<LockEventListener<DistributedLock>> eventListeners = new CopyOnWriteArrayList<>();

    public ZooKeeperBatchLockOperations(String lockName,
                                        ZooKeeperConnectionManager connectionManager,
                                        LockConfiguration configuration,
                                        LockMetrics metrics,
                                        LockTracing tracing) {
        this.lockName = normalizeLockName(lockName);
        this.connectionManager = connectionManager;
        this.configuration = configuration != null ? configuration : new LockConfiguration();
        this.metrics = metrics != null ? metrics : new LockMetrics(null, false);
        this.tracing = tracing != null ? tracing : new LockTracing(null, false);
        this.lockSupplier = this::createLock;
        this.executor = createExecutor("zk-batch-" + this.lockName);
    }

    public ZooKeeperBatchLockOperations(ZooKeeperConnectionManager connectionManager,
                                        LockConfiguration configuration,
                                        LockMetrics metrics,
                                        LockTracing tracing) {
        this(DEFAULT_LOCK_NAME, connectionManager, configuration, metrics, tracing);
    }

    public ZooKeeperBatchLockOperations(CuratorFramework curatorFramework,
                                        Object lockFactory,
                                        ZooKeeperConnectionManager connectionManager,
                                        LockConfiguration configuration,
                                        MeterRegistry meterRegistry) {
        this.lockName = DEFAULT_LOCK_NAME;
        this.connectionManager = connectionManager;
        this.configuration = configuration != null ? configuration : new LockConfiguration();
        this.metrics = new LockMetrics(meterRegistry, this.configuration.isMetricsEnabled());
        this.tracing = new LockTracing(null, false);
        this.lockSupplier = resolveFactorySupplier(lockFactory, name -> createCuratorBackedLock(curatorFramework, name));
        this.executor = createExecutor("zk-batch-legacy");
    }

    public ZooKeeperBatchLockOperations(ZooKeeperDistributedLockFactory lockFactory) {
        this.lockName = DEFAULT_LOCK_NAME;
        this.connectionManager = null;
        this.configuration = new LockConfiguration();
        this.metrics = new LockMetrics(null, false);
        this.tracing = new LockTracing(null, false);
        this.lockSupplier = name -> {
            if (lockFactory == null) {
                return createInMemoryLock(name);
            }
            return lockFactory.getLock(name);
        };
        this.executor = createExecutor("zk-batch-factory");
    }

    @Override
    public BatchLockResult<DistributedLock> batchLock(List<String> lockNames, long leaseTime, TimeUnit unit)
            throws InterruptedException {
        checkNotClosed();
        validateLockNames(lockNames);

        TimeUnit resolvedUnit = unit != null ? unit : TimeUnit.MILLISECONDS;
        long startedAt = System.currentTimeMillis();
        String sessionId = lockName + "-" + sessionSequence.incrementAndGet();
        long expirationTime = startedAt + Math.max(0L, resolvedUnit.toMillis(leaseTime));

        ZooKeeperBatchSessionInfo sessionInfo = new ZooKeeperBatchSessionInfo(
                sessionId,
                lockNames,
                startedAt,
                expirationTime,
                "RUNNING",
                0,
                0
        );
        activeSessions.put(sessionId, sessionInfo);

        try (LockTracing.SpanContext span = tracing.startLockAcquisitionSpan(lockName, "batchLock")) {
            List<DistributedLock> acquiredLocks = new ArrayList<>();
            List<String> failedLockNames = new ArrayList<>();

            for (String name : lockNames) {
                DistributedLock lock = getLock(name);
                boolean acquired = lock.tryLock(0L, leaseTime, resolvedUnit);
                if (!acquired) {
                    failedLockNames.add(name);
                    notifyAcquisitionFailed(name, null);
                    break;
                }
                acquiredLocks.add(lock);
                notifyAcquired(lock);
            }

            if (!failedLockNames.isEmpty()) {
                releaseQuietly(acquiredLocks);
                acquiredLocks.clear();
            }

            long operationTimeMs = System.currentTimeMillis() - startedAt;
            totalBatchOperations.incrementAndGet();

            if (failedLockNames.isEmpty()) {
                successfulBatchOperations.incrementAndGet();
                totalLocksAcquired.addAndGet(acquiredLocks.size());
                span.setStatus("success");
            } else {
                failedBatchOperations.incrementAndGet();
                span.setStatus("failed");
            }

            return new SimpleBatchLockResult(acquiredLocks, failedLockNames, failedLockNames.isEmpty(), operationTimeMs);
        } finally {
            activeSessions.remove(sessionId);
        }
    }

    @Override
    public CompletableFuture<BatchLockResult<DistributedLock>> batchLockAsync(List<String> lockNames,
                                                                               long leaseTime,
                                                                               TimeUnit unit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return batchLock(lockNames, leaseTime, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Batch lock interrupted", e);
            }
        }, executor);
    }

    @Override
    public boolean batchUnlock(List<DistributedLock> locks) {
        checkNotClosed();
        if (locks == null || locks.isEmpty()) {
            return true;
        }

        boolean allSucceeded = true;
        for (DistributedLock lock : locks) {
            if (lock == null) {
                continue;
            }
            try {
                lock.unlock();
                totalLocksReleased.incrementAndGet();
                notifyReleased(lock);
            } catch (RuntimeException e) {
                allSucceeded = false;
                logger.debug("Batch unlock failed for {}", lock.getName(), e);
            }
        }

        return allSucceeded;
    }

    @Override
    public CompletableFuture<Boolean> batchUnlockAsync(List<DistributedLock> locks) {
        return CompletableFuture.supplyAsync(() -> batchUnlock(locks), executor);
    }

    @Override
    public <R> R executeInTransaction(List<String> lockNames,
                                      long leaseTime,
                                      TimeUnit unit,
                                      TransactionalLockCallback<R, DistributedLock> transactionCallback) throws Exception {
        Objects.requireNonNull(transactionCallback, "transactionCallback");

        BatchLockResult<DistributedLock> result = batchLock(lockNames, leaseTime, unit);
        if (!result.isAllSuccessful()) {
            throw new IllegalStateException("Failed to acquire all requested locks: " + result.getFailedLockNames());
        }

        try {
            return transactionCallback.execute(result.getSuccessfulLocks());
        } finally {
            batchUnlock(result.getSuccessfulLocks());
        }
    }

    @Override
    public <R> CompletableFuture<R> executeInTransactionAsync(List<String> lockNames,
                                                              long leaseTime,
                                                              TimeUnit unit,
                                                              TransactionalLockCallback<R, DistributedLock> transactionCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeInTransaction(lockNames, leaseTime, unit, transactionCallback);
            } catch (Exception e) {
                throw new IllegalStateException("Batch transaction failed", e);
            }
        }, executor);
    }

    @Override
    public DistributedLock getLock(String name) {
        checkNotClosed();
        return lockSupplier.apply(name);
    }

    public void addLockEventListener(LockEventListener<DistributedLock> listener) {
        if (listener != null) {
            eventListeners.add(listener);
        }
    }

    public void removeLockEventListener(LockEventListener<DistributedLock> listener) {
        eventListeners.remove(listener);
    }

    public ZooKeeperBatchOperationsStatistics getStatistics() {
        return new ZooKeeperBatchOperationsStatistics(
                totalBatchOperations.get(),
                successfulBatchOperations.get(),
                failedBatchOperations.get(),
                totalLocksAcquired.get(),
                totalLocksReleased.get(),
                activeSessions.size(),
                deadlockDetectionEnabled.get()
        );
    }

    public List<ZooKeeperBatchSessionInfo> getActiveSessions() {
        return new ArrayList<>(activeSessions.values());
    }

    public void enableDeadlockDetection() {
        deadlockDetectionEnabled.set(true);
    }

    public void disableDeadlockDetection() {
        deadlockDetectionEnabled.set(false);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            activeSessions.clear();
            eventListeners.clear();
            executor.shutdownNow();
        }
    }

    private void validateLockNames(List<String> lockNames) {
        if (lockNames == null || lockNames.isEmpty()) {
            throw new IllegalArgumentException("Lock names cannot be null or empty");
        }
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("ZooKeeperBatchLockOperations is closed");
        }
    }

    private DistributedLock createLock(String name) {
        if (connectionManager != null) {
            try {
                return new ZooKeeperDistributedLock(
                        name,
                        connectionManager.createCuratorFramework(),
                        configuration,
                        metrics,
                        tracing,
                        "/distributed-locks/" + name,
                        connectionManager
                );
            } catch (Exception e) {
                logger.debug("Falling back to in-memory batch lock for {}", name, e);
            }
        }
        return createInMemoryLock(name);
    }

    private DistributedLock createCuratorBackedLock(CuratorFramework curatorFramework, String name) {
        if (curatorFramework != null) {
            return new ZooKeeperDistributedLock(
                    name,
                    curatorFramework,
                    configuration,
                    metrics,
                    tracing,
                    "/distributed-locks/" + name
            );
        }
        return createInMemoryLock(name);
    }

    private DistributedLock createInMemoryLock(String name) {
        return new InMemoryDistributedLock(name, configuration.getDefaultLeaseTime().toMillis());
    }

    private Function<String, DistributedLock> resolveFactorySupplier(Object lockFactory,
                                                                     Function<String, DistributedLock> fallback) {
        if (lockFactory == null) {
            return fallback;
        }

        return name -> {
            for (String methodName : List.of("createLock", "getLock")) {
                try {
                    Object value = lockFactory.getClass().getMethod(methodName, String.class).invoke(lockFactory, name);
                    if (value instanceof DistributedLock distributedLock) {
                        return distributedLock;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // Try the next known method name.
                }
            }
            return fallback.apply(name);
        };
    }

    private ExecutorService createExecutor(String threadPrefix) {
        return Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, threadPrefix);
            thread.setDaemon(true);
            return thread;
        });
    }

    private void releaseQuietly(List<DistributedLock> locks) {
        if (locks == null) {
            return;
        }
        for (DistributedLock lock : locks) {
            try {
                lock.unlock();
                totalLocksReleased.incrementAndGet();
                notifyReleased(lock);
            } catch (RuntimeException ignored) {
                // Best effort rollback only.
            }
        }
    }

    private void notifyAcquired(DistributedLock lock) {
        LockEvent<DistributedLock> event = LockEvent.ofLockAcquired(lock, createMetadata());
        for (LockEventListener<DistributedLock> listener : eventListeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException e) {
                logger.debug("Batch lock listener failed on acquire for {}", lock.getName(), e);
            }
        }
    }

    private void notifyReleased(DistributedLock lock) {
        LockEvent<DistributedLock> event = LockEvent.ofLockReleased(lock, createMetadata());
        for (LockEventListener<DistributedLock> listener : eventListeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException e) {
                logger.debug("Batch lock listener failed on release for {}", lock.getName(), e);
            }
        }
    }

    private void notifyAcquisitionFailed(String failedLockName, Throwable cause) {
        LockEvent<?> event = LockEvent.ofLockAcquisitionFailed(failedLockName, cause, createMetadata());
        for (LockEventListener<DistributedLock> listener : eventListeners) {
            try {
                @SuppressWarnings("unchecked")
                LockEvent<DistributedLock> typedEvent = (LockEvent<DistributedLock>) event;
                listener.onEvent(typedEvent);
            } catch (RuntimeException e) {
                logger.debug("Batch lock listener failed on acquisition failure for {}", failedLockName, e);
            }
        }
    }

    private LockEventListener.LockEventMetadata createMetadata() {
        return new SimpleMetadata();
    }

    private static String normalizeLockName(String lockName) {
        return lockName == null || lockName.isBlank() ? DEFAULT_LOCK_NAME : lockName;
    }

    private static final class SimpleBatchLockResult implements BatchLockResult<DistributedLock> {
        private final List<DistributedLock> successfulLocks;
        private final List<String> failedLockNames;
        private final boolean allSuccessful;
        private final long operationTimeMs;

        private SimpleBatchLockResult(List<DistributedLock> successfulLocks,
                                      List<String> failedLockNames,
                                      boolean allSuccessful,
                                      long operationTimeMs) {
            this.successfulLocks = List.copyOf(successfulLocks);
            this.failedLockNames = List.copyOf(failedLockNames);
            this.allSuccessful = allSuccessful;
            this.operationTimeMs = operationTimeMs;
        }

        @Override
        public List<DistributedLock> getSuccessfulLocks() {
            return successfulLocks;
        }

        @Override
        public List<String> getFailedLockNames() {
            return failedLockNames;
        }

        @Override
        public boolean isAllSuccessful() {
            return allSuccessful;
        }

        @Override
        public long getOperationTimeMs() {
            return operationTimeMs;
        }
    }

    private static final class SimpleMetadata implements LockEventListener.LockEventMetadata {
        @Override
        public long getThreadId() {
            return Thread.currentThread().getId();
        }

        @Override
        public String getThreadName() {
            return Thread.currentThread().getName();
        }

        @Override
        public String getCallStack() {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            return stack.length > 0 ? stack[Math.min(4, stack.length - 1)].toString() : "";
        }

        @Override
        public Object getCustomData() {
            return "ZooKeeperBatchLockOperations";
        }
    }

    private static final class InMemoryDistributedLock implements DistributedLock {
        private static final ScheduledExecutorService RENEWAL_EXECUTOR =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread thread = new Thread(r, "zk-batch-renewal");
                    thread.setDaemon(true);
                    return thread;
                });

        private final String name;
        private final ReentrantLock delegate = new ReentrantLock();
        private final AtomicLong expirationTime = new AtomicLong();
        private final long defaultLeaseTimeMs;
        private final AtomicLong creationTime = new AtomicLong(System.currentTimeMillis());
        private volatile String holder;

        private InMemoryDistributedLock(String name, long defaultLeaseTimeMs) {
            this.name = name;
            this.defaultLeaseTimeMs = defaultLeaseTimeMs > 0 ? defaultLeaseTimeMs : DEFAULT_LEASE_TIME_MS;
        }

        @Override
        public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
            delegate.lockInterruptibly();
            markAcquired(resolveLeaseTime(leaseTime, unit));
        }

        @Override
        public void lock() throws InterruptedException {
            lock(defaultLeaseTimeMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
            boolean acquired = delegate.tryLock(waitTime, unit != null ? unit : TimeUnit.MILLISECONDS);
            if (acquired) {
                markAcquired(resolveLeaseTime(leaseTime, unit));
            }
            return acquired;
        }

        @Override
        public boolean tryLock() {
            boolean acquired = delegate.tryLock();
            if (acquired) {
                markAcquired(defaultLeaseTimeMs);
            }
            return acquired;
        }

        @Override
        public void unlock() {
            if (!delegate.isHeldByCurrentThread()) {
                return;
            }
            try {
                delegate.unlock();
            } finally {
                if (!delegate.isHeldByCurrentThread()) {
                    holder = null;
                    expirationTime.set(0L);
                }
            }
        }

        @Override
        public CompletableFuture<Void> lockAsync(long leaseTime, TimeUnit unit) {
            return CompletableFuture.runAsync(() -> {
                try {
                    lock(leaseTime, unit);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Async lock interrupted", e);
                }
            });
        }

        @Override
        public CompletableFuture<Void> lockAsync() {
            return lockAsync(defaultLeaseTimeMs, TimeUnit.MILLISECONDS);
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
            return CompletableFuture.completedFuture(tryLock());
        }

        @Override
        public CompletableFuture<Void> unlockAsync() {
            return CompletableFuture.runAsync(this::unlock);
        }

        @Override
        public boolean isLocked() {
            return delegate.isLocked();
        }

        @Override
        public boolean isHeldByCurrentThread() {
            return delegate.isHeldByCurrentThread();
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean renewLock(long newLeaseTime, TimeUnit unit) {
            if (!delegate.isHeldByCurrentThread()) {
                return false;
            }
            expirationTime.set(System.currentTimeMillis() + resolveLeaseTime(newLeaseTime, unit));
            return true;
        }

        @Override
        public ScheduledFuture<?> scheduleAutoRenewal(long renewInterval,
                                                      TimeUnit unit,
                                                      java.util.function.Consumer<RenewalResult> renewalCallback) {
            long intervalMs = resolveLeaseTime(renewInterval, unit);
            return RENEWAL_EXECUTOR.scheduleAtFixedRate(() -> {
                boolean renewed = renewLock(intervalMs, TimeUnit.MILLISECONDS);
                if (renewalCallback != null) {
                    renewalCallback.accept(new SimpleRenewalResult(renewed, System.currentTimeMillis()));
                }
            }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public LockStateInfo getLockStateInfo() {
            return new LockStateInfo() {
                @Override
                public boolean isLocked() {
                    return delegate.isLocked();
                }

                @Override
                public boolean isHeldByCurrentThread() {
                    return delegate.isHeldByCurrentThread();
                }

                @Override
                public String getHolder() {
                    return holder;
                }

                @Override
                public long getRemainingTime(TimeUnit unit) {
                    long remainingMs = Math.max(0L, expirationTime.get() - System.currentTimeMillis());
                    return unit.convert(remainingMs, TimeUnit.MILLISECONDS);
                }

                @Override
                public int getReentrantCount() {
                    return delegate.getHoldCount();
                }

                @Override
                public Instant getCreationTime() {
                    return Instant.ofEpochMilli(creationTime.get());
                }

                @Override
                public Instant getExpirationTime() {
                    long value = expirationTime.get();
                    return value > 0 ? Instant.ofEpochMilli(value) : null;
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

        private long resolveLeaseTime(long leaseTime, TimeUnit unit) {
            TimeUnit resolvedUnit = unit != null ? unit : TimeUnit.MILLISECONDS;
            long candidate = leaseTime > 0 ? resolvedUnit.toMillis(leaseTime) : defaultLeaseTimeMs;
            return Math.max(1L, candidate);
        }

        private void markAcquired(long leaseTimeMs) {
            holder = Thread.currentThread().getName();
            expirationTime.set(System.currentTimeMillis() + leaseTimeMs);
        }
    }

    private static final class SimpleRenewalResult implements DistributedLock.RenewalResult {
        private final boolean success;
        private final long renewalTime;
        private final long newExpirationTime;

        private SimpleRenewalResult(boolean success, long renewalTime) {
            this.success = success;
            this.renewalTime = renewalTime;
            this.newExpirationTime = renewalTime;
        }

        @Override
        public boolean isSuccess() {
            return success;
        }

        @Override
        public long getRenewalTime() {
            return renewalTime;
        }

        @Override
        public Throwable getFailureCause() {
            return success ? null : new IllegalStateException("Renewal failed");
        }

        @Override
        public long getNewExpirationTime() {
            return newExpirationTime;
        }
    }

    public static final class ZooKeeperBatchOperationsStatistics {
        private final long totalBatchOperations;
        private final long successfulBatchOperations;
        private final long failedBatchOperations;
        private final long totalLocksAcquired;
        private final long totalLocksReleased;
        private final int activeSessions;
        private final boolean deadlockDetectionEnabled;

        public ZooKeeperBatchOperationsStatistics(long totalBatchOperations,
                                                  long successfulBatchOperations,
                                                  long failedBatchOperations,
                                                  long totalLocksAcquired,
                                                  long totalLocksReleased,
                                                  int activeSessions,
                                                  boolean deadlockDetectionEnabled) {
            this.totalBatchOperations = totalBatchOperations;
            this.successfulBatchOperations = successfulBatchOperations;
            this.failedBatchOperations = failedBatchOperations;
            this.totalLocksAcquired = totalLocksAcquired;
            this.totalLocksReleased = totalLocksReleased;
            this.activeSessions = activeSessions;
            this.deadlockDetectionEnabled = deadlockDetectionEnabled;
        }

        public long getTotalBatchOperations() {
            return totalBatchOperations;
        }

        public long getSuccessfulBatchOperations() {
            return successfulBatchOperations;
        }

        public long getFailedBatchOperations() {
            return failedBatchOperations;
        }

        public long getTotalLocksAcquired() {
            return totalLocksAcquired;
        }

        public long getTotalLocksReleased() {
            return totalLocksReleased;
        }

        public int getActiveSessions() {
            return activeSessions;
        }

        public boolean isDeadlockDetectionEnabled() {
            return deadlockDetectionEnabled;
        }
    }

    public static final class ZooKeeperBatchSessionInfo {
        private final String sessionId;
        private final List<String> lockNames;
        private final long creationTime;
        private final long expirationTime;
        private final String status;
        private final int acquiredLocks;
        private final int failedLocks;

        public ZooKeeperBatchSessionInfo(String sessionId,
                                         List<String> lockNames,
                                         long creationTime,
                                         long expirationTime,
                                         String status,
                                         int acquiredLocks,
                                         int failedLocks) {
            this.sessionId = sessionId;
            this.lockNames = lockNames == null ? Collections.emptyList() : List.copyOf(lockNames);
            this.creationTime = creationTime;
            this.expirationTime = expirationTime;
            this.status = status;
            this.acquiredLocks = acquiredLocks;
            this.failedLocks = failedLocks;
        }

        public String getSessionId() {
            return sessionId;
        }

        public List<String> getLockNames() {
            return lockNames;
        }

        public long getCreationTime() {
            return creationTime;
        }

        public long getExpirationTime() {
            return expirationTime;
        }

        public String getStatus() {
            return status;
        }

        public int getAcquiredLocks() {
            return acquiredLocks;
        }

        public int getFailedLocks() {
            return failedLocks;
        }
    }
}
