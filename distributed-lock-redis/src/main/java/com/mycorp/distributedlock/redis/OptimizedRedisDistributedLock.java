package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.exception.LockAcquisitionException;
import com.mycorp.distributedlock.api.exception.LockReleaseException;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import com.mycorp.distributedlock.core.strategy.LockAcquisitionStrategy;
import com.mycorp.distributedlock.core.threadpool.SharedExecutorService;
import com.mycorp.distributedlock.core.util.LockKeyUtils;
import com.mycorp.distributedlock.redis.pubsub.SharedPubSubManager;
import com.mycorp.distributedlock.redis.script.RedisLockScripts;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Optimized Redis distributed lock implementation.
 * 
 * Key optimizations over the original:
 * 1. Shared executor services instead of per-lock instances
 * 2. Shared pub/sub manager with multiplexed subscriptions
 * 3. Fencing token support for stronger consistency
 * 4. Improved Lua scripts with better atomicity
 * 5. Exponential backoff for lock acquisition
 * 6. Better resource cleanup and lifecycle management
 * 
 * Based on patterns from Redisson, Netflix, and Martin Kleppmann's research.
 */
public class OptimizedRedisDistributedLock implements DistributedLock {

    private static final Logger logger = LoggerFactory.getLogger(OptimizedRedisDistributedLock.class);

    private final String name;
    private final String lockKey;
    private final String channelKey;
    private final StatefulRedisConnection<String, String> connection;
    private final SharedPubSubManager pubSubManager;
    private final LockConfiguration configuration;
    private final LockMetrics metrics;
    private final LockTracing tracing;
    private final SharedExecutorService executorService;
    private final LockAcquisitionStrategy acquisitionStrategy;

    private final ThreadLocal<LockState> lockState = new ThreadLocal<>();
    private final AtomicReference<ScheduledFuture<?>> watchdogTask = new AtomicReference<>();

    private static class LockState {
        final String lockValue;
        final AtomicLong reentrantCount;
        final Timer.Sample heldTimer;
        final Duration leaseTime;
        final FencingToken fencingToken;

        LockState(String lockValue, Timer.Sample heldTimer, Duration leaseTime, long tokenValue) {
            this.lockValue = lockValue;
            this.reentrantCount = new AtomicLong(1);
            this.heldTimer = heldTimer;
            this.leaseTime = leaseTime;
            this.fencingToken = new FencingToken(tokenValue, lockValue);
        }
    }

    public OptimizedRedisDistributedLock(
            String name,
            StatefulRedisConnection<String, String> connection,
            SharedPubSubManager pubSubManager,
            LockConfiguration configuration,
            LockMetrics metrics,
            LockTracing tracing,
            SharedExecutorService executorService) {
        this.name = name;
        this.lockKey = LockKeyUtils.generateLockKey(name);
        this.channelKey = LockKeyUtils.generateChannelKey(name);
        this.connection = connection;
        this.pubSubManager = pubSubManager;
        this.configuration = configuration;
        this.metrics = metrics;
        this.tracing = tracing;
        this.executorService = executorService;
        
        // Use exponential backoff strategy
        this.acquisitionStrategy = LockAcquisitionStrategy.exponentialBackoff(
            Duration.ofMillis(50),   // Start with 50ms
            1.5,                      // Multiply by 1.5 each time
            Duration.ofSeconds(2)     // Max 2 seconds between attempts
        );
    }

    @Override
    public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
        Duration leaseDuration = Duration.ofNanos(unit.toNanos(leaseTime));
        if (!tryLockInternal(null, leaseDuration)) {
            throw new LockAcquisitionException("Failed to acquire lock: " + name);
        }
    }

    @Override
    public void lock() throws InterruptedException {
        lock(configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        Duration waitDuration = waitTime > 0 ? Duration.ofNanos(unit.toNanos(waitTime)) : Duration.ZERO;
        Duration leaseDuration = Duration.ofNanos(unit.toNanos(leaseTime));
        return tryLockInternal(waitDuration, leaseDuration);
    }

    @Override
    public boolean tryLock() throws InterruptedException {
        return tryLock(0, configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void unlock() {
        LockState state = lockState.get();
        if (state == null) {
            throw new LockReleaseException("Lock not held by current thread: " + name);
        }

        try (var spanContext = tracing.startLockAcquisitionSpan(name, "unlock")) {
            // Handle reentrancy
            if (state.reentrantCount.decrementAndGet() > 0) {
                logger.debug("Decremented reentrant count for lock {}, remaining: {}",
                    name, state.reentrantCount.get());
                spanContext.setStatus("success");
                return;
            }

            Timer.Sample releaseTimer = metrics.startAcquisitionTimer(name);
            RedisCommands<String, String> sync = connection.sync();

            try {
                Long result = sync.eval(
                    RedisLockScripts.UNLOCK_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{lockKey, channelKey},
                    state.lockValue,
                    String.valueOf(state.leaseTime.toMillis()),
                    getUnlockMessage()
                );

                if (result != null && result >= 0) {
                    lockState.remove();
                    stopWatchdog();
                    metrics.recordHeldTime(state.heldTimer, name);
                    metrics.recordAcquisitionTime(releaseTimer, name, "success");
                    metrics.incrementLockReleaseCounter(name, "success");
                    spanContext.setStatus("success");
                    logger.debug("Successfully released lock: {}", name);
                } else {
                    metrics.recordAcquisitionTime(releaseTimer, name, "failure");
                    metrics.incrementLockReleaseCounter(name, "failure");
                    spanContext.setStatus("failure");
                    throw new LockReleaseException("Failed to release lock, not owned: " + name);
                }
            } catch (Exception e) {
                metrics.recordAcquisitionTime(releaseTimer, name, "error");
                metrics.incrementLockReleaseCounter(name, "error");
                spanContext.setError(e);
                throw new LockReleaseException("Error releasing lock: " + name, e);
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
                throw new LockAcquisitionException("Interrupted while acquiring lock: " + name, e);
            }
        }, executorService.getAsyncExecutor());
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
                throw new LockAcquisitionException("Interrupted while acquiring lock: " + name, e);
            }
        }, executorService.getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Boolean> tryLockAsync() {
        return tryLockAsync(0, configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public CompletableFuture<Void> unlockAsync() {
        return CompletableFuture.runAsync(this::unlock, executorService.getAsyncExecutor());
    }

    @Override
    public boolean isLocked() {
        try {
            RedisCommands<String, String> sync = connection.sync();
            return sync.exists(lockKey) > 0;
        } catch (Exception e) {
            logger.warn("Error checking if lock exists: {}", name, e);
            return false;
        }
    }

    @Override
    public boolean isHeldByCurrentThread() {
        LockState state = lockState.get();
        return state != null && state.reentrantCount.get() > 0;
    }

    @Override
    public FencingToken getFencingToken() {
        LockState state = lockState.get();
        return state != null ? state.fencingToken : FencingToken.NONE;
    }

    @Override
    public String getName() {
        return name;
    }

    private boolean tryLockInternal(Duration waitTime, Duration leaseTime) throws InterruptedException {
        validateLockParameters(waitTime, leaseTime);

        // Check for reentrancy
        LockState state = lockState.get();
        if (state != null) {
            state.reentrantCount.incrementAndGet();
            logger.debug("Incremented reentrant count for lock {}, count: {}",
                name, state.reentrantCount.get());
            metrics.recordReentrantDepth(name, (int) state.reentrantCount.get());
            return true;
        }

        try (var spanContext = tracing.startLockAcquisitionSpan(name, "lock")) {
            Timer.Sample acquisitionTimer = metrics.startAcquisitionTimer(name);
            String lockValue = LockKeyUtils.generateLockValue();

            try {
                boolean acquired = acquisitionStrategy.acquire(
                    () -> tryAcquireWithScript(lockValue, leaseTime),
                    waitTime
                );

                if (acquired) {
                    // Lock was acquired, state was set by tryAcquireWithScript
                    metrics.recordAcquisitionTime(acquisitionTimer, name, "success");
                    metrics.incrementLockAcquisitionCounter(name, "success");
                    spanContext.setStatus("success");
                    logger.debug("Successfully acquired lock: {} with token: {}",
                        name, lockState.get().fencingToken.getValue());
                    return true;
                } else {
                    metrics.recordAcquisitionTime(acquisitionTimer, name, "timeout");
                    metrics.incrementContentionCounter(name);
                    metrics.incrementLockAcquisitionCounter(name, "timeout");
                    spanContext.setStatus("timeout");
                    return false;
                }
            } catch (Exception e) {
                metrics.recordAcquisitionTime(acquisitionTimer, name, "error");
                metrics.incrementLockAcquisitionCounter(name, "error");
                spanContext.setError(e);
                throw new LockAcquisitionException("Error acquiring lock: " + name, e);
            }
        }
    }

    private boolean tryAcquireWithScript(String lockValue, Duration leaseTime) {
        try {
            RedisCommands<String, String> sync = connection.sync();
            Long result = sync.eval(
                RedisLockScripts.LOCK_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{lockKey},
                lockValue,
                String.valueOf(leaseTime.toMillis()),
                String.valueOf(System.currentTimeMillis())
            );

            if (result != null && result != 0) {
                long tokenValue = Math.abs(result);
                Timer.Sample heldTimer = metrics.startHeldTimer(name);
                lockState.set(new LockState(lockValue, heldTimer, leaseTime, tokenValue));
                startWatchdog(lockValue, leaseTime);
                return true;
            }
            return false;
        } catch (Exception e) {
            logger.warn("Error acquiring lock with script: {}", name, e);
            return false;
        }
    }

    private void startWatchdog(String lockValue, Duration leaseTime) {
        if (!configuration.isWatchdogEnabled()) {
            return;
        }

        long renewalIntervalMillis = Math.max(
            configuration.getWatchdogRenewalInterval().toMillis(),
            leaseTime.toMillis() / 3
        );

        ScheduledFuture<?> task = executorService.getWatchdogExecutor().scheduleAtFixedRate(() -> {
            try {
                if (!renewLock(lockValue, leaseTime)) {
                    stopWatchdog();
                    LockState state = lockState.get();
                    if (state != null && state.lockValue.equals(lockValue)) {
                        lockState.remove();
                        logger.warn("Lock renewal failed, removed local state for: {}", name);
                    }
                }
            } catch (Exception e) {
                logger.warn("Watchdog renewal error for lock {}: {}", name, e.getMessage());
            }
        }, renewalIntervalMillis, renewalIntervalMillis, TimeUnit.MILLISECONDS);

        watchdogTask.set(task);
        logger.debug("Started watchdog for lock {} with {}ms renewal interval", name, renewalIntervalMillis);
    }

    private void stopWatchdog() {
        ScheduledFuture<?> task = watchdogTask.getAndSet(null);
        if (task != null) {
            task.cancel(false);
        }
    }

    private boolean renewLock(String lockValue, Duration leaseTime) {
        try {
            RedisCommands<String, String> sync = connection.sync();
            Long result = sync.eval(
                RedisLockScripts.RENEW_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{lockKey},
                lockValue,
                String.valueOf(leaseTime.toMillis()),
                String.valueOf(System.currentTimeMillis())
            );

            if (result != null && result == 1) {
                metrics.incrementWatchdogRenewalCounter(name);
                logger.trace("Successfully renewed lock: {} for {}ms", name, leaseTime.toMillis());
                return true;
            } else {
                logger.warn("Failed to renew lock: {}", name);
                return false;
            }
        } catch (Exception e) {
            logger.warn("Error renewing lock: {}", name, e);
            return false;
        }
    }

    private String getUnlockMessage() {
        return "unlocked:" + System.currentTimeMillis();
    }

    private void validateLockParameters(Duration waitTime, Duration leaseTime) {
        if (leaseTime != null && leaseTime.isNegative()) {
            throw new IllegalArgumentException("Lease time cannot be negative: " + leaseTime);
        }
        if (waitTime != null && waitTime.isNegative()) {
            throw new IllegalArgumentException("Wait time cannot be negative: " + waitTime);
        }
        if (leaseTime != null && leaseTime.compareTo(Duration.ofHours(1)) > 0) {
            logger.warn("Lease time is very long ({}), this may indicate an issue", leaseTime);
        }
        if (leaseTime != null && leaseTime.compareTo(Duration.ofSeconds(1)) < 0) {
            logger.warn("Lease time is very short ({}), this may cause frequent lock expiries", leaseTime);
        }
    }
}
