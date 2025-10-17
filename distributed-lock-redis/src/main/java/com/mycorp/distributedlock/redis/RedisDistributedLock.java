package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.exception.LockAcquisitionException;
import com.mycorp.distributedlock.api.exception.LockReleaseException;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import com.mycorp.distributedlock.core.util.LockKeyUtils;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class RedisDistributedLock implements DistributedLock {
    
    private static final Logger logger = LoggerFactory.getLogger(RedisDistributedLock.class);
    
    private final String name;
    private final String lockKey;
    private final String channelKey;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisAsyncCommands<String, String> async;
    private final StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private final LockConfiguration configuration;
    private final LockMetrics metrics;
    private final LockTracing tracing;
    private final ScheduledExecutorService watchdogExecutor;
    
    private final ThreadLocal<LockState> lockState = new ThreadLocal<>();
    private final AtomicReference<ScheduledFuture<?>> watchdogTask = new AtomicReference<>();
    
    private static class LockState {
        final String lockValue;
        final AtomicInteger reentrantCount;
        final Timer.Sample heldTimer;
        final Duration leaseTime;
        
        LockState(String lockValue, Timer.Sample heldTimer, Duration leaseTime) {
            this.lockValue = lockValue;
            this.reentrantCount = new AtomicInteger(1);
            this.heldTimer = heldTimer;
            this.leaseTime = leaseTime;
        }
    }
    
    public RedisDistributedLock(String name,
    StatefulRedisConnection<String, String> connection,
    StatefulRedisPubSubConnection<String, String> pubSubConnection,
    LockConfiguration configuration,
    LockMetrics metrics,
    LockTracing tracing,
    ScheduledExecutorService watchdogExecutor) {
    this.name = name;
    this.lockKey = LockKeyUtils.generateLockKey(name);
    this.channelKey = LockKeyUtils.generateChannelKey(name);
    this.connection = connection;
    this.async = connection.async();
    this.pubSubConnection = pubSubConnection;
    this.configuration = configuration;
    this.metrics = metrics;
    this.tracing = tracing;
    this.watchdogExecutor = watchdogExecutor;
    }
    
    @Override
    public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
        Duration leaseDuration = Duration.ofNanos(unit.toNanos(leaseTime));
        lockInternal(null, leaseDuration, true);
    }
    
    @Override
    public void lock() throws InterruptedException {
        lock(configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
    }
    
    @Override
    public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        Duration waitDuration = Duration.ofNanos(unit.toNanos(waitTime));
        Duration leaseDuration = Duration.ofNanos(unit.toNanos(leaseTime));
        return lockInternal(waitDuration, leaseDuration, false);
    }
    
    @Override
    public boolean tryLock() throws InterruptedException {
        return tryLock(0, configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
    }
    
    @Override
    public void unlock() {
        try {
            unlockAsync().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockReleaseException("Interrupted while releasing lock: " + name, e);
        } catch (ExecutionException e) {
            throw new LockReleaseException("Error releasing lock: " + name, e.getCause());
        }
    }
    
    @Override
    public CompletableFuture<Void> lockAsync(long leaseTime, TimeUnit unit) {
        return tryLockAsyncInternal(null, Duration.ofNanos(unit.toNanos(leaseTime)))
            .thenAccept(acquired -> {
                if (!acquired) {
                    throw new LockAcquisitionException("Failed to acquire lock: " + name);
                }
            });
    }
    
    @Override
    public CompletableFuture<Void> lockAsync() {
        return lockAsync(configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
    }
    
    @Override
    public CompletableFuture<Boolean> tryLockAsync(long waitTime, long leaseTime, TimeUnit unit) {
        Duration waitDuration = waitTime > 0 ? Duration.ofNanos(unit.toNanos(waitTime)) : null;
        return tryLockAsyncInternal(waitDuration, Duration.ofNanos(unit.toNanos(leaseTime)));
    }
    
    @Override
    public CompletableFuture<Boolean> tryLockAsync() {
        return tryLockAsync(0, configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
    }
    
    @Override
    public CompletableFuture<Void> unlockAsync() {
        LockState state = lockState.get();
        if (state == null) {
            return CompletableFuture.failedFuture(new LockReleaseException("Lock not held by current thread: " + name));
        }

        return unlockInternalAsync(state);
    }

    private CompletableFuture<Void> unlockInternalAsync(LockState state) {
        try (var spanContext = tracing.startLockAcquisitionSpan(name, "unlockAsync")) {
            if (state.reentrantCount.decrementAndGet() > 0) {
                logger.debug("Decremented reentrant count for lock {}, count: {}",
                    name, state.reentrantCount.get());
                spanContext.setStatus("success");
                return CompletableFuture.completedFuture(null);
            }

            Timer.Sample releaseTimer = metrics.startAcquisitionTimer(name);
            String script =
                "if redis.call('HGET', KEYS[1], 'owner') ~= ARGV[1] then\n" +
                "    return -1\n" +
                "end;\n" +
                "local counter = redis.call('HINCRBY', KEYS[1], 'count', -1);\n" +
                "if counter > 0 then\n" +
                "    redis.call('PEXPIRE', KEYS[1], ARGV[2]);\n" +
                "    return counter;\n" +
                "else\n" +
                "    redis.call('DEL', KEYS[1]);\n" +
                "    redis.call('PUBLISH', KEYS[2], ARGV[3]);\n" +
                "    return 0;\n" +
                "end;";

            return async.eval(script, ScriptOutputType.INTEGER,
                new String[]{lockKey, channelKey},
                state.lockValue, String.valueOf(state.leaseTime.toMillis()), getUnlockMessage())
                .toCompletableFuture()
                .handle((result, ex) -> {
                    if (ex != null) {
                        metrics.recordAcquisitionTime(releaseTimer, name, "error");
                        metrics.incrementLockReleaseCounter(name, "error");
                        spanContext.setError(ex);
                        throw new LockReleaseException("Error releasing lock: " + name, ex);
                    }

                    if (result != null && (Long) result >= 0) {
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
                        throw new LockReleaseException("Failed to release lock, not owned by current thread: " + name);
                    }
                    return null;
                });
        }
    }

    private String getUnlockMessage() {
        return "unlocked:" + LockKeyUtils.generateLockValue();
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
    public String getName() {
        return name;
    }
    
    private boolean lockInternal(Duration waitTime, Duration leaseTime, boolean blocking) throws InterruptedException {
        try {
            return tryLockAsyncInternal(waitTime, leaseTime).get();
        } catch (ExecutionException e) {
            throw new LockAcquisitionException("Error acquiring lock: " + name, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }
    
    private CompletableFuture<Boolean> tryLockAsyncInternal(Duration waitTime, Duration leaseTime) {
        validateLockParameters(waitTime, leaseTime);

        LockState state = lockState.get();
        if (state != null) {
            state.reentrantCount.incrementAndGet();
            logger.debug("Incremented reentrant count for lock {}, count: {}",
                name, state.reentrantCount.get());
            metrics.recordReentrantDepth(name, state.reentrantCount.get());
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.supplyAsync(() -> {
            LockTracing.SpanContext spanContext = tracing.startLockAcquisitionSpan(name, "lockAsync");
            Timer.Sample acquisitionTimer = metrics.startAcquisitionTimer(name);
            String lockValue = LockKeyUtils.generateLockValue();
            long startTime = System.nanoTime();

            try {
                while (true) {
                    if (tryAcquireWithLua(lockValue, leaseTime)) {
                        Timer.Sample heldTimer = metrics.startHeldTimer(name);
                        lockState.set(new LockState(lockValue, heldTimer, leaseTime));
                        startWatchdog(lockValue, leaseTime);
                        metrics.recordAcquisitionTime(acquisitionTimer, name, "success");
                        metrics.incrementLockAcquisitionCounter(name, "success");
                        spanContext.setStatus("success");
                        logger.debug("Successfully acquired lock: {}", name);
                        return true;
                    }

                    if (waitTime == null || (System.nanoTime() - startTime) >= waitTime.toNanos()) {
                        metrics.recordAcquisitionTime(acquisitionTimer, name, "timeout");
                        metrics.incrementContentionCounter(name);
                        metrics.incrementLockAcquisitionCounter(name, "timeout");
                        spanContext.setStatus("timeout");
                        return false;
                    }

                    long remainingWait = waitTime.toNanos() - (System.nanoTime() - startTime);
                    if (!waitForLockRelease(remainingWait)) {
                        metrics.recordAcquisitionTime(acquisitionTimer, name, "timeout");
                        metrics.incrementContentionCounter(name);
                        metrics.incrementLockAcquisitionCounter(name, "timeout");
                        spanContext.setStatus("timeout");
                        return false;
                    }
                }
            } catch (Exception e) {
                metrics.recordAcquisitionTime(acquisitionTimer, name, "error");
                metrics.incrementLockAcquisitionCounter(name, "error");
                spanContext.setError(e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new LockAcquisitionException("Error acquiring lock: " + name, e);
            } finally {
                spanContext.close();
            }
        }, getAsyncExecutor());
    }

    private boolean tryAcquireWithLua(String lockValue, Duration leaseTime) {
        // Enhanced Lua script with better error handling and migration support
        String script =
            "-- Enhanced lock acquisition script with migration support\n" +
            "local lockKey = KEYS[1]\n" +
            "local lockValue = ARGV[1]\n" +
            "local leaseTime = ARGV[2]\n" +
            "local currentTime = ARGV[3]\n" +
            "\n" +
            "-- Check if lock exists\n" +
            "local owner = redis.call('HGET', lockKey, 'owner')\n" +
            "local expireTime = redis.call('HGET', lockKey, 'expire')\n" +
            "\n" +
            "-- Case 1: Lock doesn't exist or has expired\n" +
            "if owner == false or (expireTime and tonumber(expireTime) < tonumber(currentTime)) then\n" +
            "    redis.call('HSET', lockKey, 'owner', lockValue)\n" +
            "    redis.call('HSET', lockKey, 'count', 1)\n" +
            "    redis.call('HSET', lockKey, 'expire', currentTime + leaseTime)\n" +
            "    redis.call('HSET', lockKey, 'acquired', currentTime)\n" +
            "    redis.call('PEXPIRE', lockKey, leaseTime)\n" +
            "    return 1\n" +
            "end\n" +
            "\n" +
            "-- Case 2: Current thread owns the lock (reentrancy)\n" +
            "if owner == lockValue then\n" +
            "    redis.call('HINCRBY', lockKey, 'count', 1)\n" +
            "    redis.call('HSET', lockKey, 'expire', currentTime + leaseTime)\n" +
            "    redis.call('PEXPIRE', lockKey, leaseTime)\n" +
            "    return 1\n" +
            "end\n" +
            "\n" +
            "-- Case 3: Lock is held by another thread\n" +
            "return 0";

        try {
            RedisCommands<String, String> sync = connection.sync();
            Long result = sync.eval(script, ScriptOutputType.INTEGER, new String[]{lockKey},
                lockValue, String.valueOf(leaseTime.toMillis()), String.valueOf(System.currentTimeMillis()));
            return result != null && result == 1;
        } catch (Exception e) {
            logger.warn("Error acquiring lock with enhanced Lua script, falling back to multi-exec: {}", name, e);
            return fallbackAcquire(lockValue, leaseTime);
        }
    }

    private CompletableFuture<Boolean> tryAcquireWithLuaAsync(String lockValue, Duration leaseTime) {
        String script =
            "local owner = redis.call('HGET', KEYS[1], 'owner')\n" +
            "if owner == false then\n" +
            "    redis.call('HSET', KEYS[1], 'owner', ARGV[1])\n" +
            "    redis.call('HSET', KEYS[1], 'count', 1)\n" +
            "    redis.call('PEXPIRE', KEYS[1], ARGV[2])\n" +
            "    return 1\n" +
            "end\n" +
            "if owner == ARGV[1] then\n" +
            "    redis.call('HINCRBY', KEYS[1], 'count', 1)\n" +
            "    redis.call('PEXPIRE', KEYS[1], ARGV[2])\n" +
            "    return 1\n" +
            "end\n" +
            "return 0";

        return async.eval(script, ScriptOutputType.INTEGER, new String[]{lockKey},
            lockValue, String.valueOf(leaseTime.toMillis()))
            .toCompletableFuture()
            .thenApply(result -> result != null && (Long) result == 1);
    }

    private boolean fallbackAcquire(String lockValue, Duration leaseTime) {
        RedisCommands<String, String> sync = connection.sync();
        int maxRetries = configuration.getMaxRetries();
        Duration retryInterval = configuration.getRetryInterval();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                sync.watch(lockKey);
                String owner = sync.hget(lockKey, "owner");
                if (owner == null) {
                    sync.multi();
                    sync.hset(lockKey, "owner", lockValue);
                    sync.hset(lockKey, "count", "1");
                    sync.pexpire(lockKey, leaseTime.toMillis());
                    if (sync.exec() != null) {
                        logger.debug("Fallback acquire succeeded for lock {} on attempt {}", name, attempt + 1);
                        return true;
                    }
                } else if (owner.equals(lockValue)) {
                    sync.multi();
                    sync.hincrby(lockKey, "count", 1);
                    sync.pexpire(lockKey, leaseTime.toMillis());
                    if (sync.exec() != null) {
                        logger.debug("Fallback reentrant acquire succeeded for lock {} on attempt {}", name, attempt + 1);
                        return true;
                    }
                } else {
                    sync.unwatch();
                    return false; // Lock held by another owner
                }
            } catch (Exception e) {
                logger.warn("Fallback acquire failed for lock {} on attempt {}: {}", name, attempt + 1, e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        // Exponential backoff: retryInterval * 2^attempt
                        long sleepTime = retryInterval.toMillis() * (1L << attempt);
                        Thread.sleep(Math.min(sleepTime, 30000)); // Cap at 30 seconds
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warn("Fallback acquire retry interrupted for lock {}", name);
                        return false;
                    }
                }
            } finally {
                try {
                    sync.unwatch();
                } catch (Exception ignore) {
                    // Ignore unwatch errors
                }
            }
        }
        logger.warn("Fallback acquire failed after {} attempts for lock {}", maxRetries + 1, name);
        return false;
    }
    
    private boolean waitForLockRelease(long remainingWaitTimeNanos) {
        try {
            return waitForLockReleaseAsync(remainingWaitTimeNanos).get(remainingWaitTimeNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            logger.debug("Error waiting for lock release: {}", name, e);
            return false;
        }
    }

    private CompletableFuture<Boolean> waitForLockReleaseAsync(long remainingWaitTimeNanos) {
        if (remainingWaitTimeNanos <= 0) {
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        RedisPubSubAsyncCommands<String, String> pubSubAsync = pubSubConnection.async();

        // Use a shared listener approach to reduce listener creation overhead
        // Create a single-use listener for this wait operation
        LockReleaseListener listener = new LockReleaseListener(future, channelKey, name);

        // Add listener and subscribe atomically
        pubSubConnection.addListener(listener);
        pubSubAsync.subscribe(channelKey).thenRun(() -> {
            // Set up timeout after successful subscription
            if (!future.isDone()) {
                CompletableFuture.delayedExecutor(remainingWaitTimeNanos, TimeUnit.NANOSECONDS)
                    .execute(() -> {
                        if (future.complete(false)) {
                            logger.trace("Timeout waiting for unlock notification for lock: {}", name);
                        }
                    });
            }
        }).exceptionally(throwable -> {
            logger.warn("Failed to subscribe to channel {}: {}", channelKey, throwable.getMessage());
            future.complete(false);
            return null;
        });

        // Ensure cleanup happens when future completes
        return future.whenComplete((result, error) -> {
            try {
                pubSubAsync.unsubscribe(channelKey);
                pubSubConnection.removeListener(listener);
            } catch (Exception e) {
                logger.debug("Error cleaning up pub/sub for channel {}: {}", channelKey, e.getMessage());
            }
        });
    }

    /**
     * Dedicated listener for lock release notifications to improve memory efficiency
     */
    private static class LockReleaseListener extends io.lettuce.core.pubsub.RedisPubSubAdapter<String, String> {
        private final CompletableFuture<Boolean> future;
        private final String channelKey;
        private final String lockName;

        LockReleaseListener(CompletableFuture<Boolean> future, String channelKey, String lockName) {
            this.future = future;
            this.channelKey = channelKey;
            this.lockName = lockName;
        }

        @Override
        public void message(String channel, String message) {
            if (channelKey.equals(channel) && message.startsWith("unlocked:")) {
                // Complete the future and prevent further notifications
                if (future.complete(true)) {
                    logger.trace("Received unlock notification for lock: {}", lockName);
                }
            }
        }
    }
    
    private void startWatchdog(String lockValue, Duration leaseTime) {
        if (watchdogExecutor == null || !configuration.isWatchdogEnabled()) {
            return;
        }

        // Redisson风格：续期间隔为leaseTime的1/3，但不少于最小间隔
        long renewalIntervalMillis = Math.max(
            configuration.getWatchdogRenewalInterval().toMillis(),
            leaseTime.toMillis() / 3
        );

        ScheduledFuture<?> task = watchdogExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!renewLock(lockValue, leaseTime)) {
                    // 续期失败，停止watchdog并清理状态
                    stopWatchdog();
                    LockState state = lockState.get();
                    if (state != null && state.lockValue.equals(lockValue)) {
                        lockState.remove();
                        logger.warn("Lock renewal failed, removed local state for: {}", name);
                    }
                }
            } catch (Exception e) {
                logger.warn("Watchdog renewal error for lock {}: {}", name, e.getMessage());
                // 不停止watchdog，继续尝试
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
            String script =
                "if redis.call('HGET', KEYS[1], 'owner') == ARGV[1] then\n" +
                "    redis.call('PEXPIRE', KEYS[1], ARGV[2])\n" +
                "    return 1\n" +
                "end\n" +
                "return 0";

            Long result = sync.eval(script, ScriptOutputType.INTEGER,
                new String[]{lockKey}, lockValue, String.valueOf(leaseTime.toMillis()));

            if (result != null && result == 1) {
                metrics.incrementWatchdogRenewalCounter(name);
                logger.trace("Successfully renewed lock: {} for {}ms", name, leaseTime.toMillis());
                return true;
            } else {
                logger.warn("Failed to renew lock (possibly expired or stolen): {}", name);
                return false;
            }
        } catch (Exception e) {
            logger.warn("Error renewing lock: {}", name, e);
            return false;
        }
    }

    private Executor getAsyncExecutor() {
        // 使用专门的异步执行器，避免阻塞ForkJoinPool.commonPool()
        // 参考Redisson和Lettuce的最佳实践，使用缓存线程池处理异步操作
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "redis-lock-async-" + Thread.currentThread().getId());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 验证锁参数的合理性
     */
    private void validateLockParameters(Duration waitTime, Duration leaseTime) {
        if (leaseTime != null && leaseTime.isNegative()) {
            throw new IllegalArgumentException("Lease time cannot be negative: " + leaseTime);
        }
        if (waitTime != null && waitTime.isNegative()) {
            throw new IllegalArgumentException("Wait time cannot be negative: " + waitTime);
        }

        // 检查租约时间是否过长（超过1小时）
        if (leaseTime != null && leaseTime.compareTo(Duration.ofHours(1)) > 0) {
            logger.warn("Lease time is very long ({}), this may indicate an issue", leaseTime);
        }

        // 检查租约时间是否过短（少于1秒）
        if (leaseTime != null && leaseTime.compareTo(Duration.ofSeconds(1)) < 0) {
            logger.warn("Lease time is very short ({}), this may cause frequent lock expiries", leaseTime);
        }
    }
}