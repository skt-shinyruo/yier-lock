package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.DistributedLock;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Redis-based distributed lock with per-acquisition ownership tokens.
 */
public class SimpleRedisLock implements DistributedLock {

    private static final Logger logger = LoggerFactory.getLogger(SimpleRedisLock.class);

    private static final String RELEASE_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) "
                    + "else return 0 end";

    private static final String RENEW_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('expire', KEYS[1], tonumber(ARGV[2])) "
                    + "else return 0 end";

    private static final ScheduledThreadPoolExecutor RENEWAL_EXECUTOR =
            new ScheduledThreadPoolExecutor(2, runnable -> {
                Thread thread = new Thread(runnable, "simple-redis-lock-renewal");
                thread.setDaemon(true);
                return thread;
            });

    private final String lockKey;
    private final RedisCommands<String, String> commands;
    private final long defaultLeaseTimeSeconds;

    private final AtomicBoolean acquired = new AtomicBoolean(false);
    private final AtomicInteger reentrantCount = new AtomicInteger(0);
    private final AtomicLong acquireTime = new AtomicLong(0);

    private volatile Thread owningThread;
    private volatile String lockValue;
    private volatile long currentLeaseTimeSeconds;
    private volatile ScheduledFuture<?> autoRenewalTask;

    public SimpleRedisLock(String lockKey, RedisCommands<String, String> commands, long leaseTimeSeconds) {
        this.lockKey = lockKey;
        this.commands = commands;
        this.defaultLeaseTimeSeconds = Math.max(1, leaseTimeSeconds);
        this.currentLeaseTimeSeconds = this.defaultLeaseTimeSeconds;
    }

    @Override
    public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
        if (!tryLock(30, leaseTime, unit)) {
            throw new IllegalStateException("Failed to acquire lock within timeout");
        }
    }

    @Override
    public void lock() throws InterruptedException {
        lock(defaultLeaseTimeSeconds, TimeUnit.SECONDS);
    }

    @Override
    public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        if (isHeldByCurrentThread()) {
            reentrantCount.incrementAndGet();
            return true;
        }

        long timeoutMs = unit != null ? unit.toMillis(waitTime) : waitTime;
        long leaseSeconds = toLeaseSeconds(leaseTime, unit);
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 0);

        do {
            String candidateToken = nextToken();
            String result = commands.set(lockKey, candidateToken, SetArgs.Builder.nx().ex(leaseSeconds));
            if ("OK".equals(result)) {
                lockValue = candidateToken;
                currentLeaseTimeSeconds = leaseSeconds;
                owningThread = Thread.currentThread();
                acquireTime.set(System.currentTimeMillis());
                reentrantCount.set(1);
                acquired.set(true);
                logger.debug("Lock {} acquired by thread {}", lockKey, Thread.currentThread().getName());
                return true;
            }

            if (timeoutMs <= 0 || System.currentTimeMillis() >= deadline) {
                break;
            }

            Thread.sleep(Math.min(100, Math.max(1, deadline - System.currentTimeMillis())));
        } while (true);

        return false;
    }

    @Override
    public boolean tryLock() throws InterruptedException {
        return tryLock(0, defaultLeaseTimeSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void unlock() {
        if (!isHeldByCurrentThread()) {
            throw new IllegalMonitorStateException("Lock not held by current thread");
        }

        int remaining = reentrantCount.decrementAndGet();
        if (remaining > 0) {
            return;
        }

        cancelAutoRenewal();

        Long result = commands.eval(
                RELEASE_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{lockKey},
                lockValue
        );

        clearOwnership();
        if (result == null || result == 0L) {
            throw new IllegalMonitorStateException("Lock ownership was lost before unlock");
        }
    }

    @Override
    public CompletableFuture<Void> lockAsync(long leaseTime, TimeUnit unit) {
        return CompletableFuture.runAsync(() -> {
            try {
                lock(leaseTime, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Lock acquisition interrupted", e);
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
        return tryLockAsync(0, defaultLeaseTimeSeconds, TimeUnit.SECONDS);
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
        return renewInternal(lockValue, toLeaseSeconds(newLeaseTime, unit));
    }

    @Override
    public boolean isLocked() {
        try {
            Long exists = commands.exists(lockKey);
            return exists != null && exists > 0;
        } catch (RuntimeException exception) {
            logger.debug("Failed to inspect Redis lock state for {}", lockKey, exception);
            return acquired.get();
        }
    }

    @Override
    public boolean isHeldByCurrentThread() {
        return acquired.get() && Thread.currentThread() == owningThread;
    }

    @Override
    public String getName() {
        return lockKey;
    }

    @Override
    public ScheduledFuture<?> scheduleAutoRenewal(
            long renewInterval,
            TimeUnit unit,
            Consumer<DistributedLock.RenewalResult> renewalCallback) {
        if (!isHeldByCurrentThread()) {
            throw new IllegalMonitorStateException("Auto-renewal requires the current thread to own the lock");
        }

        long intervalMs = unit != null ? unit.toMillis(renewInterval) : renewInterval;
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("Renewal interval must be positive");
        }

        cancelAutoRenewal();

        autoRenewalTask = RENEWAL_EXECUTOR.scheduleAtFixedRate(() -> {
            String token = lockValue;
            long leaseSeconds = currentLeaseTimeSeconds;
            if (!acquired.get() || token == null) {
                cancelAutoRenewal();
                return;
            }

            boolean renewed = renewInternal(token, leaseSeconds);
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
                        return System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(leaseSeconds);
                    }
                });
            }

            if (!renewed) {
                cancelAutoRenewal();
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        return autoRenewalTask;
    }

    @Override
    public int getReentrantCount() {
        return isHeldByCurrentThread() ? reentrantCount.get() : 0;
    }

    @Override
    public boolean isExpired() {
        Long ttl = commands.ttl(lockKey);
        return ttl == null || ttl <= 0;
    }

    @Override
    public long getRemainingTime(TimeUnit unit) {
        Long ttl = commands.ttl(lockKey);
        if (ttl == null || ttl < 0) {
            return 0;
        }
        return unit != null ? unit.convert(ttl, TimeUnit.SECONDS) : ttl;
    }

    @Override
    public String getLockHolder() {
        return acquired.get() && owningThread != null ? owningThread.getName() : null;
    }

    private boolean renewInternal(String token, long leaseSeconds) {
        Long result = commands.eval(
                RENEW_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{lockKey},
                token,
                String.valueOf(leaseSeconds)
        );

        boolean renewed = result != null && result == 1L;
        if (renewed) {
            currentLeaseTimeSeconds = leaseSeconds;
            return true;
        }

        cancelAutoRenewal();
        clearOwnership();
        return false;
    }

    private void cancelAutoRenewal() {
        ScheduledFuture<?> task = autoRenewalTask;
        if (task != null) {
            task.cancel(false);
            autoRenewalTask = null;
        }
    }

    private void clearOwnership() {
        acquired.set(false);
        owningThread = null;
        lockValue = null;
        acquireTime.set(0);
        reentrantCount.set(0);
    }

    private long toLeaseSeconds(long leaseTime, TimeUnit unit) {
        long leaseSeconds = unit != null ? unit.toSeconds(leaseTime) : leaseTime;
        return Math.max(1, leaseSeconds);
    }

    private String nextToken() {
        return Thread.currentThread().getId() + ":" + UUID.randomUUID();
    }
}
