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

/**
 * Fair distributed lock implementation using Redis.
 * Locks are acquired in the order they were requested, similar to Redisson's FairLock.
 */
public class RedisFairLock implements DistributedLock {

    private static final Logger logger = LoggerFactory.getLogger(RedisFairLock.class);

    private final String name;
    private final String lockKey;
    private final String threadsKey;
    private final String timeoutKey;
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
        final long threadId;
        final AtomicInteger reentrantCount;
        final Timer.Sample heldTimer;
        final Duration leaseTime;

        LockState(long threadId, Timer.Sample heldTimer, Duration leaseTime) {
            this.threadId = threadId;
            this.reentrantCount = new AtomicInteger(1);
            this.heldTimer = heldTimer;
            this.leaseTime = leaseTime;
        }
    }

    public RedisFairLock(String name,
                        StatefulRedisConnection<String, String> connection,
                        StatefulRedisPubSubConnection<String, String> pubSubConnection,
                        LockConfiguration configuration,
                        LockMetrics metrics,
                        LockTracing tracing,
                        ScheduledExecutorService watchdogExecutor) {
        this.name = name;
        this.lockKey = LockKeyUtils.generateLockKey(name);
        this.threadsKey = lockKey + ":threads";
        this.timeoutKey = lockKey + ":timeout";
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
                logger.debug("Decremented reentrant count for fair lock {}, count: {}", name, state.reentrantCount.get());
                spanContext.setStatus("success");
                return CompletableFuture.completedFuture(null);
            }

            Timer.Sample releaseTimer = metrics.startAcquisitionTimer(name);
            String script =
                "local threadId = ARGV[1]\n" +
                "local threadsKey = KEYS[2]\n" +
                "local timeoutKey = KEYS[3]\n" +
                "\n" +
                "-- 检查是否是当前持有者\n" +
                "if redis.call('GET', KEYS[1]) ~= threadId then\n" +
                "    return -1\n" +
                "end\n" +
                "\n" +
                "-- 从等待队列中移除当前线程\n" +
                "redis.call('ZREM', threadsKey, threadId)\n" +
                "redis.call('ZREM', timeoutKey, threadId)\n" +
                "\n" +
                "-- 获取下一个等待的线程\n" +
                "local nextThread = redis.call('ZRANGE', threadsKey, 0, 0)[1]\n" +
                "if nextThread then\n" +
                "    -- 唤醒下一个线程\n" +
                "    redis.call('SET', KEYS[1], nextThread)\n" +
                "    redis.call('PEXPIRE', KEYS[1], ARGV[2])\n" +
                "    redis.call('PUBLISH', KEYS[4], 'unlocked:' .. nextThread)\n" +
                "else\n" +
                "    -- 没有等待线程，删除锁\n" +
                "    redis.call('DEL', KEYS[1])\n" +
                "    redis.call('DEL', threadsKey)\n" +
                "    redis.call('DEL', timeoutKey)\n" +
                "end\n" +
                "\n" +
                "return 1";

            return async.eval(script, ScriptOutputType.INTEGER,
                new String[]{lockKey, threadsKey, timeoutKey, LockKeyUtils.generateChannelKey(name)},
                String.valueOf(state.threadId), String.valueOf(state.leaseTime.toMillis()))
                .toCompletableFuture()
                .handle((result, ex) -> {
                    if (ex != null) {
                        metrics.recordAcquisitionTime(releaseTimer, name, "error");
                        metrics.incrementLockReleaseCounter(name, "error");
                        spanContext.setError(ex);
                        throw new LockReleaseException("Error releasing fair lock: " + name, ex);
                    }

                    if (result != null && (Long) result == 1) {
                        lockState.remove();
                        stopWatchdog();
                        metrics.recordHeldTime(state.heldTimer, name);
                        metrics.recordAcquisitionTime(releaseTimer, name, "success");
                        metrics.incrementLockReleaseCounter(name, "success");
                        spanContext.setStatus("success");
                        logger.debug("Successfully released fair lock: {}", name);
                    } else {
                        metrics.recordAcquisitionTime(releaseTimer, name, "failure");
                        metrics.incrementLockReleaseCounter(name, "failure");
                        spanContext.setStatus("failure");
                        throw new LockReleaseException("Failed to release fair lock, not owned by current thread: " + name);
                    }
                    return null;
                });
        }
    }

    @Override
    public boolean isLocked() {
        try {
            RedisCommands<String, String> sync = connection.sync();
            return sync.exists(lockKey) > 0;
        } catch (Exception e) {
            logger.warn("Error checking if fair lock exists: {}", name, e);
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
            throw new LockAcquisitionException("Error acquiring fair lock: " + name, e.getCause());
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
            logger.debug("Incremented reentrant count for fair lock {}, count: {}", name, state.reentrantCount.get());
            metrics.recordReentrantDepth(name, state.reentrantCount.get());
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.supplyAsync(() -> {
            LockTracing.SpanContext spanContext = tracing.startLockAcquisitionSpan(name, "fairLock");
            Timer.Sample acquisitionTimer = metrics.startAcquisitionTimer(name);
            long startTime = System.nanoTime();
            long threadId = Thread.currentThread().getId();

            try {
                while (true) {
                    if (tryAcquireFair(threadId, leaseTime)) {
                        Timer.Sample heldTimer = metrics.startHeldTimer(name);
                        lockState.set(new LockState(threadId, heldTimer, leaseTime));
                        startWatchdog(String.valueOf(threadId), leaseTime);
                        metrics.recordAcquisitionTime(acquisitionTimer, name, "success");
                        metrics.incrementLockAcquisitionCounter(name, "success");
                        spanContext.setStatus("success");
                        logger.debug("Successfully acquired fair lock: {}", name);
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
                    if (!waitForFairLockRelease(remainingWait)) {
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
                throw new LockAcquisitionException("Error acquiring fair lock: " + name, e);
            } finally {
                spanContext.close();
            }
        }, getAsyncExecutor());
    }

    private boolean tryAcquireFair(long threadId, Duration leaseTime) {
        String script =
            "local lockKey = KEYS[1]\n" +
            "local threadsKey = KEYS[2]\n" +
            "local timeoutKey = KEYS[3]\n" +
            "local threadId = ARGV[1]\n" +
            "local leaseTime = ARGV[2]\n" +
            "local currentTime = ARGV[3]\n" +
            "\n" +
            "-- 检查锁是否被持有\n" +
            "local owner = redis.call('GET', lockKey)\n" +
            "if owner == false then\n" +
            "    -- 锁未被持有，直接获取\n" +
            "    redis.call('SET', lockKey, threadId)\n" +
            "    redis.call('PEXPIRE', lockKey, leaseTime)\n" +
            "    return 1\n" +
            "elseif owner == threadId then\n" +
            "    -- 重入锁\n" +
            "    redis.call('PEXPIRE', lockKey, leaseTime)\n" +
            "    return 1\n" +
            "else\n" +
            "    -- 锁被其他线程持有，加入等待队列\n" +
            "    redis.call('ZADD', threadsKey, currentTime, threadId)\n" +
            "    redis.call('ZADD', timeoutKey, currentTime + leaseTime, threadId)\n" +
            "    return 0\n" +
            "end";

        try {
            RedisCommands<String, String> sync = connection.sync();
            Long result = sync.eval(script, ScriptOutputType.INTEGER,
                new String[]{lockKey, threadsKey, timeoutKey},
                String.valueOf(threadId), String.valueOf(leaseTime.toMillis()),
                String.valueOf(System.currentTimeMillis()));

            return result != null && result == 1;
        } catch (Exception e) {
            logger.warn("Error acquiring fair lock with Lua script: {}", name, e);
            return false;
        }
    }

    private boolean waitForFairLockRelease(long remainingWaitTimeNanos) {
        if (remainingWaitTimeNanos <= 0) {
            return false;
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        RedisPubSubAsyncCommands<String, String> pubSubAsync = pubSubConnection.async();
        long threadId = Thread.currentThread().getId();

        LockReleaseListener listener = new LockReleaseListener(future, LockKeyUtils.generateChannelKey(name), name, threadId);

        pubSubConnection.addListener(listener);
        pubSubAsync.subscribe(LockKeyUtils.generateChannelKey(name)).thenRun(() -> {
            if (!future.isDone()) {
                CompletableFuture.delayedExecutor(remainingWaitTimeNanos, TimeUnit.NANOSECONDS)
                    .execute(() -> {
                        if (future.complete(false)) {
                            logger.trace("Timeout waiting for fair lock release: {}", name);
                        }
                    });
            }
        }).exceptionally(throwable -> {
            logger.warn("Failed to subscribe to fair lock channel: {}", throwable.getMessage());
            future.complete(false);
            return null;
        });

        try {
            return future.whenComplete((result, error) -> {
                try {
                    pubSubAsync.unsubscribe(LockKeyUtils.generateChannelKey(name));
                    pubSubConnection.removeListener(listener);
                } catch (Exception e) {
                    logger.debug("Error cleaning up fair lock pub/sub: {}", e.getMessage());
                }
            }).get(remainingWaitTimeNanos, TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    private void startWatchdog(String threadId, Duration leaseTime) {
        if (watchdogExecutor == null || !configuration.isWatchdogEnabled()) {
            return;
        }

        long renewalIntervalMillis = Math.max(
            configuration.getWatchdogRenewalInterval().toMillis(),
            leaseTime.toMillis() / 3
        );

        ScheduledFuture<?> task = watchdogExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!renewFairLock(threadId, leaseTime)) {
                    stopWatchdog();
                    LockState state = lockState.get();
                    if (state != null && state.threadId == Long.parseLong(threadId)) {
                        lockState.remove();
                        logger.warn("Fair lock renewal failed, removed local state for: {}", name);
                    }
                }
            } catch (Exception e) {
                logger.warn("Watchdog renewal error for fair lock {}: {}", name, e.getMessage());
            }
        }, renewalIntervalMillis, renewalIntervalMillis, TimeUnit.MILLISECONDS);

        watchdogTask.set(task);
        logger.debug("Started watchdog for fair lock {} with {}ms renewal interval", name, renewalIntervalMillis);
    }

    private boolean renewFairLock(String threadId, Duration leaseTime) {
        String script =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then\n" +
            "    redis.call('PEXPIRE', KEYS[1], ARGV[2])\n" +
            "    return 1\n" +
            "end\n" +
            "return 0";

        try {
            RedisCommands<String, String> sync = connection.sync();
            Long result = sync.eval(script, ScriptOutputType.INTEGER,
                new String[]{lockKey}, threadId, String.valueOf(leaseTime.toMillis()));

            if (result != null && result == 1) {
                metrics.incrementWatchdogRenewalCounter(name);
                logger.trace("Successfully renewed fair lock: {}", name);
                return true;
            } else {
                logger.warn("Failed to renew fair lock: {}", name);
                return false;
            }
        } catch (Exception e) {
            logger.warn("Error renewing fair lock: {}", name, e);
            return false;
        }
    }

    private void stopWatchdog() {
        ScheduledFuture<?> task = watchdogTask.getAndSet(null);
        if (task != null) {
            task.cancel(false);
        }
    }

    private Executor getAsyncExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "redis-fair-lock-async-" + Thread.currentThread().getId());
            t.setDaemon(true);
            return t;
        });
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

    private static class LockReleaseListener extends io.lettuce.core.pubsub.RedisPubSubAdapter<String, String> {
        private final CompletableFuture<Boolean> future;
        private final String channelKey;
        private final String lockName;
        private final long threadId;

        LockReleaseListener(CompletableFuture<Boolean> future, String channelKey, String lockName, long threadId) {
            this.future = future;
            this.channelKey = channelKey;
            this.lockName = lockName;
            this.threadId = threadId;
        }

        @Override
        public void message(String channel, String message) {
            if (channelKey.equals(channel) && message.startsWith("unlocked:") && message.endsWith(String.valueOf(threadId))) {
                if (future.complete(true)) {
                    logger.trace("Received unlock notification for fair lock: {}", lockName);
                }
            }
        }
    }
}
