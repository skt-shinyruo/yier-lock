package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.BatchLockOperations;
import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.LockEvent;
import com.mycorp.distributedlock.api.LockEventListener;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Minimal compile-safe Redis batch operations facade for the current branch.
 *
 * <p>The previous implementation had drifted well past the current
 * {@link BatchLockOperations} API. This version keeps the constructors and
 * helper methods still referenced elsewhere, but reduces behavior to a thin
 * sequential wrapper that is easy to reason about and compile.</p>
 */
public class RedisBatchLockOperations implements BatchLockOperations<DistributedLock> {

    private static final Logger logger = LoggerFactory.getLogger(RedisBatchLockOperations.class);
    private static final long DEFAULT_LEASE_TIME_SECONDS = 30L;
    private static final ScheduledExecutorService LOCAL_RENEWAL_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "redis-batch-local-renewal");
                thread.setDaemon(true);
                return thread;
            });

    private final RedisCommands<String, String> commands;
    private final long defaultLeaseTimeSeconds;
    private final Function<String, DistributedLock> lockSupplier;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean deadlockDetectionEnabled = new AtomicBoolean(false);
    private final AtomicLong sessionSequence = new AtomicLong();
    private final AtomicLong totalBatchOperations = new AtomicLong();
    private final AtomicLong successfulBatchOperations = new AtomicLong();
    private final AtomicLong failedBatchOperations = new AtomicLong();
    private final AtomicLong totalBatchLocksAcquired = new AtomicLong();
    private final AtomicLong totalBatchLocksReleased = new AtomicLong();
    private final AtomicLong totalRollbackOperations = new AtomicLong();
    private final AtomicLong totalOperationTimeMs = new AtomicLong();
    private final ConcurrentMap<String, BatchSession> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DistributedLock> heldLocksByThreadAndName = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LocalLockState> localLockStates = new ConcurrentHashMap<>();
    private final List<LockEventListener<DistributedLock>> eventListeners = new CopyOnWriteArrayList<>();
    private volatile LockStrategy batchStrategy = LockStrategy.ALL_OR_NOTHING;

    public RedisBatchLockOperations(RedisCommands<String, String> commands, long leaseTimeSeconds) {
        this.commands = commands;
        this.defaultLeaseTimeSeconds = Math.max(1L, leaseTimeSeconds);
        this.lockSupplier = this::createCommandBackedLock;
        this.executor = createExecutor("redis-batch-ops");
    }

    /**
     * Legacy constructor retained for benchmark code on this branch.
     *
     * <p>The concrete factory type has drifted and is not always available in
     * this module, so the facade accepts {@link Object} and uses reflection to
     * call {@code getLock(String)} or {@code createLock(String)} when present.</p>
     */
    public RedisBatchLockOperations(Object lockFactory) {
        this.commands = null;
        this.defaultLeaseTimeSeconds = DEFAULT_LEASE_TIME_SECONDS;
        this.lockSupplier = resolveFactorySupplier(lockFactory, this::createInMemoryLock);
        this.executor = createExecutor("redis-batch-factory");
    }

    @Override
    public BatchLockResult<DistributedLock> batchLock(List<String> lockNames, long leaseTime, TimeUnit unit)
            throws InterruptedException {
        checkNotClosed();
        validateLockNames(lockNames);

        TimeUnit resolvedUnit = unit != null ? unit : TimeUnit.SECONDS;
        long startedAt = System.currentTimeMillis();
        long expirationTime = startedAt + Math.max(0L, resolvedUnit.toMillis(leaseTime));
        BatchSession session = new BatchSession(nextSessionId(), lockNames, startedAt, expirationTime);
        activeSessions.put(session.getSessionId(), session);

        List<DistributedLock> acquiredLocks = new ArrayList<>();
        List<String> failedLockNames = new ArrayList<>();

        try {
            for (String lockName : lockNames) {
                DistributedLock lock = getLock(lockName);
                boolean acquired = lock.tryLock(0L, leaseTime, resolvedUnit);
                if (!acquired) {
                    failedLockNames.add(lockName);
                    notifyAcquisitionFailed(lockName, null);
                    if (shouldRollbackOnFailure()) {
                        rollbackAcquiredLocks(acquiredLocks);
                        session.markAsRolledBack();
                        acquiredLocks.clear();
                        break;
                    }
                    continue;
                }

                acquiredLocks.add(lock);
                session.addAcquiredLock(lock);
                notifyAcquired(lock);
            }

            boolean allSuccessful = failedLockNames.isEmpty();
            long operationTimeMs = System.currentTimeMillis() - startedAt;
            recordOperation(allSuccessful, acquiredLocks.size(), operationTimeMs);

            if (allSuccessful) {
                session.markAsCompleted();
            }

            return new SimpleBatchLockResult(acquiredLocks, failedLockNames, allSuccessful, operationTimeMs);
        } catch (InterruptedException e) {
            rollbackAcquiredLocks(acquiredLocks);
            session.markAsRolledBack();
            throw e;
        } catch (RuntimeException e) {
            rollbackAcquiredLocks(acquiredLocks);
            session.markAsRolledBack();
            throw e;
        } finally {
            activeSessions.remove(session.getSessionId());
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
            if (!releaseLockInstance(lock)) {
                allSucceeded = false;
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
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Lock name cannot be null or blank");
        }
        return lockSupplier.apply(name);
    }

    /**
     * Legacy helper retained for benchmark code on this branch.
     */
    public Map<String, Boolean> acquireMultipleLocks(List<String> lockNames,
                                                     long waitTime,
                                                     long leaseTime,
                                                     TimeUnit unit) {
        checkNotClosed();
        validateLockNames(lockNames);

        TimeUnit resolvedUnit = unit != null ? unit : TimeUnit.MILLISECONDS;
        long startedAt = System.currentTimeMillis();
        LinkedHashMap<String, Boolean> results = new LinkedHashMap<>();
        LinkedHashMap<String, DistributedLock> acquiredLocks = new LinkedHashMap<>();

        try {
            for (String lockName : lockNames) {
                DistributedLock lock = getLock(lockName);
                boolean acquired = lock.tryLock(waitTime, leaseTime, resolvedUnit);
                results.put(lockName, acquired);

                if (acquired) {
                    acquiredLocks.put(lockName, lock);
                    notifyAcquired(lock);
                    continue;
                }

                notifyAcquisitionFailed(lockName, null);
                if (shouldRollbackOnFailure()) {
                    rollbackNamedLocks(acquiredLocks);
                    markAllAsFailed(lockNames, results);
                    recordOperation(false, 0, System.currentTimeMillis() - startedAt);
                    return results;
                }
            }

            for (Map.Entry<String, DistributedLock> entry : acquiredLocks.entrySet()) {
                rememberHeldLock(entry.getKey(), entry.getValue());
            }

            int successfulCount = (int) results.values().stream().filter(Boolean::booleanValue).count();
            recordOperation(successfulCount == lockNames.size(), successfulCount, System.currentTimeMillis() - startedAt);
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rollbackNamedLocks(acquiredLocks);
            markAllAsFailed(lockNames, results);
            recordOperation(false, 0, System.currentTimeMillis() - startedAt);
            return results;
        } catch (RuntimeException e) {
            rollbackNamedLocks(acquiredLocks);
            markAllAsFailed(lockNames, results);
            recordOperation(false, 0, System.currentTimeMillis() - startedAt);
            throw e;
        }
    }

    /**
     * Legacy helper retained for benchmark code on this branch.
     */
    public Map<String, Boolean> releaseMultipleLocks(List<String> lockNames) {
        checkNotClosed();

        LinkedHashMap<String, Boolean> results = new LinkedHashMap<>();
        if (lockNames == null || lockNames.isEmpty()) {
            return results;
        }

        for (String lockName : lockNames) {
            DistributedLock lock = heldLocksByThreadAndName.remove(threadLockKey(lockName));
            boolean released = releaseLockInstance(lock);
            results.put(lockName, released);
        }
        return results;
    }

    /**
     * Legacy helper retained for benchmark code on this branch.
     */
    public Map<String, Boolean> renewMultipleLocks(List<String> lockNames) {
        checkNotClosed();

        LinkedHashMap<String, Boolean> results = new LinkedHashMap<>();
        if (lockNames == null || lockNames.isEmpty()) {
            return results;
        }

        for (String lockName : lockNames) {
            DistributedLock lock = heldLocksByThreadAndName.get(threadLockKey(lockName));
            boolean renewed = false;
            if (lock != null) {
                try {
                    renewed = lock.renewLock(defaultLeaseTimeSeconds, TimeUnit.SECONDS);
                    if (renewed) {
                        notifyRenewed(lock, Instant.now().plusSeconds(defaultLeaseTimeSeconds));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    renewed = false;
                }
            }
            results.put(lockName, renewed);
        }

        return results;
    }

    public void addLockEventListener(LockEventListener<DistributedLock> listener) {
        if (listener != null) {
            eventListeners.add(listener);
        }
    }

    public void removeLockEventListener(LockEventListener<DistributedLock> listener) {
        eventListeners.remove(listener);
    }

    public BatchOperationsStatistics getStatistics() {
        return new BatchOperationsStatistics(
                totalBatchOperations.get(),
                successfulBatchOperations.get(),
                failedBatchOperations.get(),
                totalBatchLocksAcquired.get(),
                totalBatchLocksReleased.get(),
                totalRollbackOperations.get(),
                averageOperationTimeMs(),
                activeSessions.size()
        );
    }

    public List<BatchLockSessionInfo> getActiveSessions() {
        List<BatchLockSessionInfo> sessions = new ArrayList<>();
        for (BatchSession session : activeSessions.values()) {
            sessions.add(session.toInfo());
        }
        return sessions;
    }

    public void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        for (BatchSession session : new ArrayList<>(activeSessions.values())) {
            if (!session.isExpired(now)) {
                continue;
            }

            if (activeSessions.remove(session.getSessionId(), session)) {
                session.markAsExpired();
                rollbackAcquiredLocks(session.getAcquiredLocks());
            }
        }
    }

    public void setBatchStrategy(LockStrategy strategy) {
        if (strategy != null) {
            this.batchStrategy = strategy;
        }
    }

    public void enableDeadlockDetection() {
        deadlockDetectionEnabled.set(true);
    }

    public void disableDeadlockDetection() {
        deadlockDetectionEnabled.set(false);
    }

    public boolean rollbackBatchOperation(String sessionId) {
        BatchSession session = activeSessions.remove(sessionId);
        if (session == null) {
            return false;
        }

        session.markAsRolledBack();
        rollbackAcquiredLocks(session.getAcquiredLocks());
        return true;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        for (DistributedLock lock : heldLocksByThreadAndName.values()) {
            try {
                if (lock != null && lock.isHeldByCurrentThread()) {
                    lock.close();
                }
            } catch (RuntimeException e) {
                logger.debug("Failed to close held lock {}", lock != null ? lock.getName() : "<null>", e);
            }
        }

        heldLocksByThreadAndName.clear();
        activeSessions.clear();
        eventListeners.clear();
        executor.shutdownNow();
    }

    private ExecutorService createExecutor(String threadPrefix) {
        return Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, threadPrefix);
            thread.setDaemon(true);
            return thread;
        });
    }

    private Function<String, DistributedLock> resolveFactorySupplier(Object lockFactory,
                                                                     Function<String, DistributedLock> fallback) {
        if (lockFactory == null) {
            return fallback;
        }

        return name -> {
            for (String methodName : List.of("getLock", "createLock")) {
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

    private DistributedLock createCommandBackedLock(String name) {
        if (commands == null) {
            return createInMemoryLock(name);
        }

        try {
            Class<?> lockClass = Class.forName("com.mycorp.distributedlock.redis.SimpleRedisLock");
            Object lock = lockClass
                    .getConstructor(String.class, RedisCommands.class, long.class)
                    .newInstance(name, commands, defaultLeaseTimeSeconds);
            if (lock instanceof DistributedLock distributedLock) {
                return distributedLock;
            }
        } catch (ReflectiveOperationException e) {
            logger.debug("Falling back to in-memory batch lock for {}", name, e);
        }

        return createInMemoryLock(name);
    }

    private DistributedLock createInMemoryLock(String name) {
        LocalLockState state = localLockStates.computeIfAbsent(name, ignored -> new LocalLockState());
        return new LocalDistributedLock(name, state, defaultLeaseTimeSeconds);
    }

    private void validateLockNames(List<String> lockNames) {
        if (lockNames == null || lockNames.isEmpty()) {
            throw new IllegalArgumentException("Lock names cannot be null or empty");
        }
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("RedisBatchLockOperations is closed");
        }
    }

    private void rememberHeldLock(String lockName, DistributedLock lock) {
        DistributedLock previous = heldLocksByThreadAndName.put(threadLockKey(lockName), lock);
        if (previous != null && previous != lock) {
            releaseQuietly(previous);
        }
    }

    private boolean shouldRollbackOnFailure() {
        return batchStrategy != LockStrategy.PARTIAL_ACCEPT;
    }

    private void rollbackAcquiredLocks(List<DistributedLock> acquiredLocks) {
        if (acquiredLocks == null || acquiredLocks.isEmpty()) {
            return;
        }

        totalRollbackOperations.incrementAndGet();
        for (DistributedLock lock : acquiredLocks) {
            releaseQuietly(lock);
        }
    }

    private void rollbackNamedLocks(Map<String, DistributedLock> acquiredLocks) {
        if (acquiredLocks == null || acquiredLocks.isEmpty()) {
            return;
        }

        totalRollbackOperations.incrementAndGet();
        for (Map.Entry<String, DistributedLock> entry : acquiredLocks.entrySet()) {
            releaseQuietly(entry.getValue());
        }
        acquiredLocks.clear();
    }

    private void markAllAsFailed(List<String> lockNames, Map<String, Boolean> results) {
        if (lockNames == null) {
            return;
        }
        for (String lockName : lockNames) {
            results.put(lockName, false);
        }
    }

    private boolean releaseLockInstance(DistributedLock lock) {
        if (lock == null) {
            return false;
        }

        try {
            if (!lock.isHeldByCurrentThread()) {
                return false;
            }

            lock.unlock();
            totalBatchLocksReleased.incrementAndGet();
            notifyReleased(lock);
            return true;
        } catch (RuntimeException e) {
            logger.debug("Failed to release lock {}", lock.getName(), e);
            return false;
        }
    }

    private void releaseQuietly(DistributedLock lock) {
        try {
            releaseLockInstance(lock);
        } catch (RuntimeException ignored) {
            // Best-effort rollback only.
        }
    }

    private void recordOperation(boolean allSuccessful, int acquiredCount, long operationTimeMs) {
        totalBatchOperations.incrementAndGet();
        totalOperationTimeMs.addAndGet(Math.max(0L, operationTimeMs));

        if (allSuccessful) {
            successfulBatchOperations.incrementAndGet();
            totalBatchLocksAcquired.addAndGet(acquiredCount);
            return;
        }

        failedBatchOperations.incrementAndGet();
    }

    private double averageOperationTimeMs() {
        long operationCount = totalBatchOperations.get();
        if (operationCount == 0L) {
            return 0.0d;
        }
        return (double) totalOperationTimeMs.get() / operationCount;
    }

    private String nextSessionId() {
        return "redis-batch-session-" + sessionSequence.incrementAndGet();
    }

    private String threadLockKey(String lockName) {
        return Thread.currentThread().getId() + ":" + lockName;
    }

    private void notifyAcquired(DistributedLock lock) {
        LockEvent<DistributedLock> event = LockEvent.ofLockAcquired(lock, createMetadata());
        notifyTypedEvent(event);
    }

    private void notifyReleased(DistributedLock lock) {
        LockEvent<DistributedLock> event = LockEvent.ofLockReleased(lock, createMetadata());
        notifyTypedEvent(event);
    }

    private void notifyRenewed(DistributedLock lock, Instant newExpiryTime) {
        LockEvent<DistributedLock> event = LockEvent.ofLockRenewed(lock, newExpiryTime, createMetadata());
        notifyTypedEvent(event);
    }

    private void notifyAcquisitionFailed(String failedLockName, Throwable cause) {
        LockEvent<?> event = LockEvent.ofLockAcquisitionFailed(failedLockName, cause, createMetadata());
        for (LockEventListener<DistributedLock> listener : eventListeners) {
            try {
                @SuppressWarnings("unchecked")
                LockEvent<DistributedLock> typedEvent = (LockEvent<DistributedLock>) event;
                if (listener.shouldHandleEvent(typedEvent)) {
                    listener.onEvent(typedEvent);
                }
            } catch (RuntimeException e) {
                logger.debug("Batch listener failed on acquisition failure for {}", failedLockName, e);
            }
        }
    }

    private void notifyTypedEvent(LockEvent<DistributedLock> event) {
        for (LockEventListener<DistributedLock> listener : eventListeners) {
            try {
                if (listener.shouldHandleEvent(event)) {
                    listener.onEvent(event);
                }
            } catch (RuntimeException e) {
                logger.debug("Batch listener failed for event {}", event.getType(), e);
            }
        }
    }

    private LockEventListener.LockEventMetadata createMetadata() {
        return new LockEventListener.LockEventMetadata() {
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
                for (StackTraceElement element : stack) {
                    if (!element.getClassName().equals(RedisBatchLockOperations.class.getName())) {
                        return element.toString();
                    }
                }
                return "";
            }

            @Override
            public Object getCustomData() {
                return "RedisBatchLockOperations";
            }
        };
    }

    private static final class BatchSession {
        private final String sessionId;
        private final List<String> lockNames;
        private final long creationTime;
        private final long expirationTime;
        private final List<DistributedLock> acquiredLocks = new ArrayList<>();
        private volatile BatchLockSessionStatus status = BatchLockSessionStatus.ACTIVE;

        private BatchSession(String sessionId, List<String> lockNames, long creationTime, long expirationTime) {
            this.sessionId = sessionId;
            this.lockNames = new ArrayList<>(lockNames);
            this.creationTime = creationTime;
            this.expirationTime = expirationTime;
        }

        private String getSessionId() {
            return sessionId;
        }

        private void addAcquiredLock(DistributedLock lock) {
            acquiredLocks.add(lock);
        }

        private List<DistributedLock> getAcquiredLocks() {
            return acquiredLocks;
        }

        private boolean isExpired(long currentTime) {
            return currentTime >= expirationTime;
        }

        private void markAsCompleted() {
            status = BatchLockSessionStatus.COMPLETED;
        }

        private void markAsRolledBack() {
            status = BatchLockSessionStatus.ROLLED_BACK;
        }

        private void markAsExpired() {
            status = BatchLockSessionStatus.EXPIRED;
        }

        private BatchLockSessionInfo toInfo() {
            return new BatchLockSessionInfo(sessionId, lockNames, creationTime, expirationTime, status);
        }
    }

    private enum BatchLockSessionStatus {
        ACTIVE,
        COMPLETED,
        ROLLED_BACK,
        EXPIRED
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
            this.successfulLocks = Collections.unmodifiableList(new ArrayList<>(successfulLocks));
            this.failedLockNames = Collections.unmodifiableList(new ArrayList<>(failedLockNames));
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

    public static class BatchOperationsStatistics {
        private final long totalBatchOperations;
        private final long successfulBatchOperations;
        private final long failedBatchOperations;
        private final long totalBatchLocksAcquired;
        private final long totalBatchLocksReleased;
        private final long totalRollbackOperations;
        private final double averageOperationTime;
        private final int activeSessions;

        public BatchOperationsStatistics(long totalBatchOperations,
                                        long successfulBatchOperations,
                                        long failedBatchOperations,
                                        long totalBatchLocksAcquired,
                                        long totalBatchLocksReleased,
                                        long totalRollbackOperations,
                                        double averageOperationTime,
                                        int activeSessions) {
            this.totalBatchOperations = totalBatchOperations;
            this.successfulBatchOperations = successfulBatchOperations;
            this.failedBatchOperations = failedBatchOperations;
            this.totalBatchLocksAcquired = totalBatchLocksAcquired;
            this.totalBatchLocksReleased = totalBatchLocksReleased;
            this.totalRollbackOperations = totalRollbackOperations;
            this.averageOperationTime = averageOperationTime;
            this.activeSessions = activeSessions;
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

        public long getTotalBatchLocksAcquired() {
            return totalBatchLocksAcquired;
        }

        public long getTotalBatchLocksReleased() {
            return totalBatchLocksReleased;
        }

        public long getTotalRollbackOperations() {
            return totalRollbackOperations;
        }

        public double getAverageOperationTime() {
            return averageOperationTime;
        }

        public int getActiveSessions() {
            return activeSessions;
        }

        public double getSuccessRate() {
            return totalBatchOperations == 0L
                    ? 0.0d
                    : (double) successfulBatchOperations / totalBatchOperations;
        }

        public double getFailureRate() {
            return totalBatchOperations == 0L
                    ? 0.0d
                    : (double) failedBatchOperations / totalBatchOperations;
        }
    }

    public static class BatchLockSessionInfo {
        private final String sessionId;
        private final List<String> lockNames;
        private final long creationTime;
        private final long expirationTime;
        private final BatchLockSessionStatus status;

        public BatchLockSessionInfo(String sessionId,
                                    List<String> lockNames,
                                    long creationTime,
                                    long expirationTime,
                                    BatchLockSessionStatus status) {
            this.sessionId = sessionId;
            this.lockNames = Collections.unmodifiableList(new ArrayList<>(lockNames));
            this.creationTime = creationTime;
            this.expirationTime = expirationTime;
            this.status = status;
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

        public BatchLockSessionStatus getStatus() {
            return status;
        }

        public long getRemainingTime() {
            return Math.max(0L, expirationTime - System.currentTimeMillis());
        }
    }

    private static final class LocalLockState {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicLong leaseTimeMs = new AtomicLong();
        private final AtomicLong expirationTimeMs = new AtomicLong();
        private final AtomicLong acquireTimeMs = new AtomicLong();
        private volatile Thread owner;
    }

    private static final class LocalDistributedLock implements DistributedLock {
        private final String name;
        private final LocalLockState state;
        private final long defaultLeaseTimeSeconds;
        private volatile ScheduledFuture<?> renewalTask;

        private LocalDistributedLock(String name, LocalLockState state, long defaultLeaseTimeSeconds) {
            this.name = name;
            this.state = state;
            this.defaultLeaseTimeSeconds = defaultLeaseTimeSeconds;
        }

        @Override
        public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
            state.lock.lockInterruptibly();
            markAcquired(leaseTime, unit);
        }

        @Override
        public void lock() throws InterruptedException {
            lock(defaultLeaseTimeSeconds, TimeUnit.SECONDS);
        }

        @Override
        public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
            TimeUnit resolvedUnit = unit != null ? unit : TimeUnit.MILLISECONDS;
            boolean acquired = state.lock.tryLock(waitTime, resolvedUnit);
            if (acquired) {
                markAcquired(leaseTime, unit);
            }
            return acquired;
        }

        @Override
        public boolean tryLock() throws InterruptedException {
            return tryLock(0L, defaultLeaseTimeSeconds, TimeUnit.SECONDS);
        }

        @Override
        public void unlock() {
            if (!state.lock.isHeldByCurrentThread()) {
                throw new IllegalMonitorStateException("Lock not held by current thread: " + name);
            }

            cancelRenewalTask();
            state.lock.unlock();
            if (!state.lock.isHeldByCurrentThread()) {
                state.owner = null;
                state.acquireTimeMs.set(0L);
                state.expirationTimeMs.set(0L);
            }
        }

        @Override
        public CompletableFuture<Void> lockAsync(long leaseTime, TimeUnit unit) {
            return CompletableFuture.runAsync(() -> {
                try {
                    lock(leaseTime, unit);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Local batch lock interrupted", e);
                }
            });
        }

        @Override
        public CompletableFuture<Void> lockAsync() {
            return lockAsync(defaultLeaseTimeSeconds, TimeUnit.SECONDS);
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
            return tryLockAsync(0L, defaultLeaseTimeSeconds, TimeUnit.SECONDS);
        }

        @Override
        public CompletableFuture<Void> unlockAsync() {
            return CompletableFuture.runAsync(this::unlock);
        }

        @Override
        public boolean renewLock(long newLeaseTime, TimeUnit unit) {
            if (!isHeldByCurrentThread()) {
                return false;
            }
            long leaseMs = Math.max(1L, resolvedMillis(newLeaseTime, unit));
            state.leaseTimeMs.set(leaseMs);
            state.expirationTimeMs.set(System.currentTimeMillis() + leaseMs);
            return true;
        }

        @Override
        public boolean isLocked() {
            return state.lock.isLocked();
        }

        @Override
        public boolean isHeldByCurrentThread() {
            return state.lock.isHeldByCurrentThread();
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public ScheduledFuture<?> scheduleAutoRenewal(long renewInterval,
                                                      TimeUnit unit,
                                                      java.util.function.Consumer<RenewalResult> renewalCallback) {
            if (!isHeldByCurrentThread()) {
                throw new IllegalMonitorStateException("Auto-renewal requires the current thread to own the lock");
            }

            long intervalMs = Math.max(1L, resolvedMillis(renewInterval, unit));
            cancelRenewalTask();
            renewalTask = LOCAL_RENEWAL_EXECUTOR.scheduleAtFixedRate(() -> {
                boolean renewed = renewLock(state.leaseTimeMs.get(), TimeUnit.MILLISECONDS);
                if (renewalCallback != null) {
                    renewalCallback.accept(new RenewalResult() {
                        @Override
                        public boolean isSuccess() {
                            return renewed;
                        }

                        @Override
                        public Throwable getFailureCause() {
                            return renewed ? null : new IllegalStateException("Renewal failed");
                        }

                        @Override
                        public long getRenewalTime() {
                            return System.currentTimeMillis();
                        }

                        @Override
                        public long getNewExpirationTime() {
                            return state.expirationTimeMs.get();
                        }
                    });
                }
            }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
            return renewalTask;
        }

        @Override
        public int getReentrantCount() {
            return state.lock.isHeldByCurrentThread() ? state.lock.getHoldCount() : 0;
        }

        @Override
        public boolean isExpired() {
            long expirationTime = state.expirationTimeMs.get();
            return expirationTime > 0L && System.currentTimeMillis() > expirationTime;
        }

        @Override
        public long getRemainingTime(TimeUnit unit) {
            long remainingMs = Math.max(0L, state.expirationTimeMs.get() - System.currentTimeMillis());
            TimeUnit resolvedUnit = unit != null ? unit : TimeUnit.MILLISECONDS;
            return resolvedUnit.convert(remainingMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public String getLockHolder() {
            Thread owner = state.owner;
            return owner != null ? owner.getName() : null;
        }

        private void markAcquired(long leaseTime, TimeUnit unit) {
            long leaseMs = Math.max(1L, resolvedMillis(leaseTime, unit));
            state.owner = Thread.currentThread();
            state.acquireTimeMs.set(System.currentTimeMillis());
            state.leaseTimeMs.set(leaseMs);
            state.expirationTimeMs.set(System.currentTimeMillis() + leaseMs);
        }

        private long resolvedMillis(long value, TimeUnit unit) {
            TimeUnit resolvedUnit = unit != null ? unit : TimeUnit.MILLISECONDS;
            return resolvedUnit.toMillis(value);
        }

        private void cancelRenewalTask() {
            ScheduledFuture<?> task = renewalTask;
            if (task != null) {
                task.cancel(false);
                renewalTask = null;
            }
        }
    }
}
