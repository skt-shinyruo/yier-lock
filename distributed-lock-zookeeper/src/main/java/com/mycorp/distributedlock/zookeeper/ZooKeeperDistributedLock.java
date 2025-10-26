package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.*;
import com.mycorp.distributedlock.api.exception.*;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enhanced ZooKeeper-based distributed lock implementation with enterprise-grade features.
 * 
 * This implementation provides:
 * - Connection health monitoring
 * - Session management and recovery
 * - Enhanced performance metrics
 * - Retry mechanisms
 * - Graceful degradation during network issues
 * - Advanced error classification
 */
public class ZooKeeperDistributedLock implements DistributedLock {
    
    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperDistributedLock.class);
    
    private final String name;
    private final InterProcessMutex mutex;
    private final LockConfiguration configuration;
    private final LockMetrics metrics;
    private final LockTracing tracing;
    private final CuratorFramework curatorFramework;
    private final ZooKeeperConnectionManager connectionManager;
    
    private final ThreadLocal<Timer.Sample> heldTimer = new ThreadLocal<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger retryCount = new AtomicInteger(0);
    private final AtomicLong lastHealthCheck = new AtomicLong(0);
    
    // Performance metrics
    private final Counter connectionRetryCounter;
    private final Counter sessionRecoveryCounter;
    private final Timer retryOperationTimer;
    
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
        this.name = name;
        this.curatorFramework = curatorFramework;
        this.connectionManager = connectionManager;
        this.mutex = new InterProcessMutex(curatorFramework, lockPath);
        this.configuration = configuration;
        this.metrics = metrics;
        this.tracing = tracing;
        
        // Initialize enhanced metrics
        this.connectionRetryCounter = metrics.registry().counter(
            "zk.lock.connection.retries", "lock", name);
        this.sessionRecoveryCounter = metrics.registry().counter(
            "zk.lock.session.recoveries", "lock", name);
        this.retryOperationTimer = metrics.registry().timer(
            "zk.lock.retry.operations", "lock", name);
    }
    
    // Enhanced lock acquisition with retry logic and connection health checking
    private <T> T executeWithRetry(RetryableOperation<T> operation, Duration maxRetryDuration) 
            throws InterruptedException, DistributedLockException {
        Instant startTime = Instant.now();
        int maxRetries = configuration.getMaxRetries();
        Duration retryInterval = configuration.getRetryInterval();
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // Check connection health before operation
                if (!isConnectionHealthy() && attempt > 1) {
                    logger.warn("Connection not healthy for lock {}, attempt {} of {}", name, attempt, maxRetries);
                    connectionRetryCounter.increment();
                }
                
                T result = operation.execute();
                retryCount.set(0); // Reset retry counter on success
                return result;
                
            } catch (KeeperException.ConnectionLossException | 
                     KeeperException.SessionExpiredException |
                     KeeperException.OperationTimeoutException e) {
                
                retryCount.increment();
                Instant currentTime = Instant.now();
                
                if (Duration.between(startTime, currentTime).compareTo(maxRetryDuration) > 0 ||
                    attempt >= maxRetries) {
                    
                    logger.error("Failed to acquire lock {} after {} attempts in {}", 
                        name, attempt, Duration.between(startTime, currentTime), e);
                    throw new LockAcquisitionException(
                        "Failed to acquire ZooKeeper lock after " + attempt + " attempts: " + name, e);
                }
                
                // Attempt session recovery if applicable
                if (e instanceof KeeperException.SessionExpiredException && connectionManager != null) {
                    try {
                        sessionRecoveryCounter.increment();
                        connectionManager.recoverSession();
                        logger.info("Recovered ZooKeeper session for lock {}", name);
                    } catch (Exception recoveryException) {
                        logger.warn("Session recovery failed for lock {}", name, recoveryException);
                    }
                }
                
                // Wait before retry with exponential backoff
                try {
                    Duration backoffDelay = retryInterval.multipliedBy(attempt);
                    logger.debug("Retrying lock acquisition {} in {}", name, backoffDelay);
                    Thread.sleep(backoffDelay.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LockAcquisitionException("Lock acquisition interrupted during retry", ie);
                }
                
            } catch (Exception e) {
                // Non-retryable exception
                logger.error("Non-retryable error acquiring lock {}", name, e);
                throw new LockAcquisitionException("Error acquiring ZooKeeper lock: " + name, e);
            }
        }
        
        throw new LockAcquisitionException("Failed to acquire lock after all retries: " + name);
    }
    
    private boolean isConnectionHealthy() {
        if (curatorFramework == null) {
            return false;
        }
        
        // Check if we can perform basic operations
        try {
            curatorFramework.getZookeeperClient().getConnectionState();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
        ensureNotClosed();
        
        try (var spanContext = tracing.startLockAcquisitionSpan(name, "lock")) {
            Timer.Sample acquisitionTimer = metrics.startAcquisitionTimer(name);
            
            try {
                executeWithRetry(() -> {
                    mutex.acquire(leaseTime, unit);
                    return null;
                }, configuration.getDefaultWaitTime());
                
                Timer.Sample heldTimerSample = metrics.startHeldTimer(name);
                heldTimer.set(heldTimerSample);
                
                metrics.recordAcquisitionTime(acquisitionTimer, name, "success");
                metrics.incrementLockAcquisitionCounter(name, "success");
                spanContext.setStatus("success");
                logger.debug("Successfully acquired ZooKeeper lock: {}", name);
                
            } catch (InterruptedException e) {
                metrics.recordAcquisitionTime(acquisitionTimer, name, "interrupted");
                metrics.incrementLockAcquisitionCounter(name, "interrupted");
                spanContext.setError(e);
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                metrics.recordAcquisitionTime(acquisitionTimer, name, "error");
                metrics.incrementLockAcquisitionCounter(name, "error");
                spanContext.setError(e);
                throw new LockAcquisitionException("Error acquiring ZooKeeper lock: " + name, e);
            }
        }
    }
    
    @Override
    public void lock() throws InterruptedException {
        lock(configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
    }
    
    @Override
    public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        ensureNotClosed();
        
        try (var spanContext = tracing.startLockAcquisitionSpan(name, "tryLock")) {
            Timer.Sample acquisitionTimer = metrics.startAcquisitionTimer(name);
            
            try {
                Duration waitDuration = Duration.of(waitTime, unit.toChronoUnit());
                Boolean result = executeWithRetry(() -> {
                    return mutex.acquire(waitDuration.toMillis(), TimeUnit.MILLISECONDS);
                }, waitDuration);
                
                boolean acquired = result != null && result;
                
                if (acquired) {
                    Timer.Sample heldTimerSample = metrics.startHeldTimer(name);
                    heldTimer.set(heldTimerSample);
                    
                    metrics.recordAcquisitionTime(acquisitionTimer, name, "success");
                    metrics.incrementLockAcquisitionCounter(name, "success");
                    spanContext.setStatus("success");
                    logger.debug("Successfully acquired ZooKeeper lock: {}", name);
                } else {
                    metrics.recordAcquisitionTime(acquisitionTimer, name, "timeout");
                    metrics.incrementContentionCounter(name);
                    metrics.incrementLockAcquisitionCounter(name, "timeout");
                    spanContext.setStatus("timeout");
                }
                
                return acquired;
                
            } catch (InterruptedException e) {
                metrics.recordAcquisitionTime(acquisitionTimer, name, "interrupted");
                metrics.incrementLockAcquisitionCounter(name, "interrupted");
                spanContext.setError(e);
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                metrics.recordAcquisitionTime(acquisitionTimer, name, "error");
                metrics.incrementLockAcquisitionCounter(name, "error");
                spanContext.setError(e);
                throw new LockAcquisitionException("Error trying to acquire ZooKeeper lock: " + name, e);
            }
        }
    }
    
    @Override
    public boolean tryLock() throws InterruptedException {
        return tryLock(0, configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
    }
    
    @Override
    public void unlock() {
        ensureNotClosed();
        
        try (var spanContext = tracing.startLockAcquisitionSpan(name, "unlock")) {
            Timer.Sample releaseTimer = metrics.startAcquisitionTimer(name);
            
            try {
                executeWithRetry(() -> {
                    mutex.release();
                    return null;
                }, Duration.ofSeconds(5)); // Shorter timeout for unlock operations
                
                Timer.Sample heldTimerSample = heldTimer.get();
                if (heldTimerSample != null) {
                    metrics.recordHeldTime(heldTimerSample, name);
                    heldTimer.remove();
                }
                
                metrics.recordAcquisitionTime(releaseTimer, name, "success");
                metrics.incrementLockReleaseCounter(name, "success");
                spanContext.setStatus("success");
                logger.debug("Successfully released ZooKeeper lock: {}", name);
                
            } catch (Exception e) {
                metrics.recordAcquisitionTime(releaseTimer, name, "error");
                metrics.incrementLockReleaseCounter(name, "error");
                spanContext.setError(e);
                
                // Enhanced error classification
                if (e instanceof KeeperException.NoNodeException) {
                    throw new LockReleaseException("Lock path does not exist: " + name, 
                        LockReleaseException.LockReleaseFailureReason.LOCK_PATH_NOT_FOUND);
                } else if (e instanceof KeeperException.BadVersionException) {
                    throw new LockReleaseException("Lock version conflict: " + name, 
                        LockReleaseException.LockReleaseFailureReason.VERSION_CONFLICT);
                } else {
                    throw new LockReleaseException("Error releasing ZooKeeper lock: " + name, e);
                }
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
                throw new LockAcquisitionException("Interrupted while acquiring ZooKeeper lock: " + name, e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Boolean> tryLockAsync() {
        return tryLockAsync(0, configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
    }
    
    @Override
    public CompletableFuture<Void> unlockAsync() {
        return CompletableFuture.runAsync(this::unlock);
    }
    
    @Override
    public boolean isLocked() {
        return mutex.isAcquiredInThisProcess() && !closed.get();
    }
    
    @Override
    public boolean isHeldByCurrentThread() {
        return !closed.get() && mutex.isOwnedByCurrentThread();
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    // Enhanced features
    
    /**
     * Get enhanced lock information including performance metrics
     */
    public LockInfo getLockInfo() {
        return new LockInfo(
            name,
            isLocked(),
            isHeldByCurrentThread(),
            getRetryCount(),
            getLastHealthCheckTimestamp(),
            isConnectionHealthy()
        );
    }
    
    /**
     * Attempt to acquire lock with specified retry strategy
     */
    public boolean tryLockWithRetry(Duration maxWaitTime, int maxRetries) throws InterruptedException {
        return tryLockWithRetry(maxWaitTime, configuration.getDefaultLeaseTime(), maxRetries);
    }
    
    /**
     * Attempt to acquire lock with custom retry parameters
     */
    public boolean tryLockWithRetry(Duration maxWaitTime, Duration leaseTime, int maxRetries) 
            throws InterruptedException {
        Duration remainingWaitTime = maxWaitTime;
        int retries = 0;
        
        while (retries < maxRetries && remainingWaitTime.toMillis() > 0) {
            try {
                long waitTime = Math.min(remainingWaitTime.toMillis(), 1000); // Max 1 second per attempt
                if (tryLock(waitTime, leaseTime.toMillis(), TimeUnit.MILLISECONDS)) {
                    return true;
                }
                
                retries++;
                remainingWaitTime = remainingWaitTime.minus(Duration.ofMillis(waitTime));
                
                if (retries < maxRetries) {
                    Duration backoffDelay = Duration.ofMillis(Math.min(retries * 100, 1000));
                    Thread.sleep(backoffDelay.toMillis());
                    remainingWaitTime = remainingWaitTime.minus(backoffDelay);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        
        return false;
    }
    
    /**
     * Force release the lock (use with caution)
     */
    public void forceUnlock() {
        try {
            if (isLocked()) {
                mutex.release();
                logger.warn("Force unlocked ZooKeeper lock: {}", name);
            }
        } catch (Exception e) {
            logger.warn("Error during force unlock of lock {}", name, e);
        }
    }
    
    /**
     * Close the lock and release resources
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                if (isHeldByCurrentThread()) {
                    unlock();
                }
            } catch (Exception e) {
                logger.warn("Error releasing lock during close: {}", name, e);
            }
            
            heldTimer.remove();
            logger.info("Closed ZooKeeper lock: {}", name);
        }
    }
    
    // Private helper methods
    private void ensureNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("Lock " + name + " has been closed");
        }
    }
    
    private int getRetryCount() {
        return retryCount.get();
    }
    
    private long getLastHealthCheckTimestamp() {
        return lastHealthCheck.get();
    }
    
    // Functional interface for retryable operations
    @FunctionalInterface
    private interface RetryableOperation<T> {
        T execute() throws Exception;
    }
    
    // Enhanced lock information record
    public record LockInfo(
        String name,
        boolean locked,
        boolean heldByCurrentThread,
        int retryCount,
        long lastHealthCheck,
        boolean connectionHealthy
    ) {
    }
}