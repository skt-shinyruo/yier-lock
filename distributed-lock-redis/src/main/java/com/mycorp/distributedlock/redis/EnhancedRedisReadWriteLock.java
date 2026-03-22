package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Minimal Redis read/write lock that preserves the public constructor shape used
 * elsewhere on this branch while following the current simple provider semantics.
 */
public class EnhancedRedisReadWriteLock implements DistributedReadWriteLock {

    private final String lockName;
    private final long defaultReadLeaseTimeSeconds;
    private final long defaultWriteLeaseTimeSeconds;
    private final DistributedLock readLock;
    private final DistributedLock writeLock;

    public EnhancedRedisReadWriteLock(
            String lockName,
            RedisCommands<String, String> commands,
            long readLeaseTimeSeconds,
            long writeLeaseTimeSeconds) {
        this.lockName = lockName;
        this.defaultReadLeaseTimeSeconds = Math.max(1, readLeaseTimeSeconds);
        this.defaultWriteLeaseTimeSeconds = Math.max(1, writeLeaseTimeSeconds);

        String writeKey = lockName + ":write";
        String readersKey = lockName + ":readers";
        this.readLock = new RedisReadLock(
                lockName + ":read",
                writeKey,
                readersKey,
                commands,
                this.defaultReadLeaseTimeSeconds
        );
        this.writeLock = new RedisWriteLock(
                lockName + ":write",
                writeKey,
                readersKey,
                commands,
                this.defaultWriteLeaseTimeSeconds
        );
    }

    @Override
    public DistributedLock readLock() {
        return readLock;
    }

    @Override
    public DistributedLock writeLock() {
        return writeLock;
    }

    @Override
    public String getName() {
        return lockName;
    }

    @Override
    public ReadWriteLockConfigurationInfo getConfigurationInfo() {
        return new ReadWriteLockConfigurationInfo() {
            @Override
            public boolean isFairLock() {
                return false;
            }

            @Override
            public boolean isReentrant() {
                return true;
            }

            @Override
            public long getDefaultReadLockLeaseTime(TimeUnit unit) {
                return unit.convert(defaultReadLeaseTimeSeconds, TimeUnit.SECONDS);
            }

            @Override
            public long getDefaultWriteLockLeaseTime(TimeUnit unit) {
                return unit.convert(defaultWriteLeaseTimeSeconds, TimeUnit.SECONDS);
            }

            @Override
            public long getDefaultReadLockWaitTime(TimeUnit unit) {
                return unit.convert(10, TimeUnit.SECONDS);
            }

            @Override
            public long getDefaultWriteLockWaitTime(TimeUnit unit) {
                return unit.convert(15, TimeUnit.SECONDS);
            }

            @Override
            public boolean isUpgradeSupported() {
                return false;
            }

            @Override
            public boolean isDowngradeSupported() {
                return true;
            }

            @Override
            public LockUpgradeMode getUpgradeMode() {
                return LockUpgradeMode.DIRECT;
            }

            @Override
            public int getMaxReadLockCount() {
                return Integer.MAX_VALUE;
            }

            @Override
            public boolean isReadLockPreemptionEnabled() {
                return false;
            }

            @Override
            public boolean isWriteLockPriority() {
                return true;
            }
        };
    }

    private abstract static class AbstractReadWriteLockHandle implements DistributedLock {

        private static final ScheduledThreadPoolExecutor RENEWAL_EXECUTOR =
                new ScheduledThreadPoolExecutor(1, runnable -> {
                    Thread thread = new Thread(runnable, "enhanced-redis-rw-renewal");
                    thread.setDaemon(true);
                    return thread;
                });

        private final String name;
        private final RedisCommands<String, String> commands;
        private final long defaultLeaseTimeSeconds;

        private final AtomicBoolean acquired = new AtomicBoolean(false);
        private final AtomicInteger reentrantCount = new AtomicInteger(0);

        private volatile Thread owningThread;
        private volatile String token;
        private volatile long leaseTimeSeconds;
        private volatile ScheduledFuture<?> autoRenewalTask;

        private AbstractReadWriteLockHandle(
                String name,
                RedisCommands<String, String> commands,
                long defaultLeaseTimeSeconds) {
            this.name = name;
            this.commands = commands;
            this.defaultLeaseTimeSeconds = Math.max(1, defaultLeaseTimeSeconds);
            this.leaseTimeSeconds = this.defaultLeaseTimeSeconds;
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
                Long result = tryAcquire(candidateToken, leaseSeconds);
                if (result != null && result == 1L) {
                    token = candidateToken;
                    leaseTimeSeconds = leaseSeconds;
                    owningThread = Thread.currentThread();
                    reentrantCount.set(1);
                    acquired.set(true);
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
            Long result = release(token);
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
                    throw new RuntimeException(e);
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

            long newLeaseSeconds = toLeaseSeconds(newLeaseTime, unit);
            Long result = renew(token, newLeaseSeconds);
            boolean renewed = result != null && result == 1L;
            if (renewed) {
                leaseTimeSeconds = newLeaseSeconds;
                return true;
            }

            cancelAutoRenewal();
            clearOwnership();
            return false;
        }

        @Override
        public boolean isLocked() {
            return rawRemainingTime() > 0;
        }

        @Override
        public boolean isHeldByCurrentThread() {
            return acquired.get() && Thread.currentThread() == owningThread;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public ScheduledFuture<?> scheduleAutoRenewal(
                long renewInterval,
                TimeUnit unit,
                Consumer<RenewalResult> renewalCallback) {
            if (!isHeldByCurrentThread()) {
                throw new IllegalMonitorStateException("Auto-renewal requires current ownership");
            }

            long intervalMs = unit != null ? unit.toMillis(renewInterval) : renewInterval;
            if (intervalMs <= 0) {
                throw new IllegalArgumentException("Renewal interval must be positive");
            }

            cancelAutoRenewal();
            autoRenewalTask = RENEWAL_EXECUTOR.scheduleAtFixedRate(() -> {
                String currentToken = token;
                Long result = acquired.get() && currentToken != null
                        ? renew(currentToken, leaseTimeSeconds)
                        : 0L;
                boolean renewed = result != null && result == 1L;
                if (!renewed) {
                    clearOwnership();
                }
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
                            return System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(leaseTimeSeconds);
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
        public String getLockHolder() {
            return acquired.get() && owningThread != null ? owningThread.getName() : null;
        }

        @Override
        public boolean isExpired() {
            return getRemainingTime(TimeUnit.SECONDS) <= 0;
        }

        @Override
        public long getRemainingTime(TimeUnit unit) {
            long remaining = rawRemainingTime();
            if (remaining <= 0) {
                return 0;
            }
            return unit != null ? unit.convert(remaining, TimeUnit.SECONDS) : remaining;
        }

        protected RedisCommands<String, String> commands() {
            return commands;
        }

        protected abstract Long tryAcquire(String candidateToken, long leaseSeconds);

        protected abstract Long release(String currentToken);

        protected abstract Long renew(String currentToken, long leaseSeconds);

        protected abstract long rawRemainingTime();

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
            token = null;
            reentrantCount.set(0);
        }

        private long toLeaseSeconds(long leaseTime, TimeUnit unit) {
            long seconds = unit != null ? unit.toSeconds(leaseTime) : leaseTime;
            return Math.max(1, seconds);
        }

        private String nextToken() {
            return Thread.currentThread().getId() + ":" + UUID.randomUUID();
        }
    }

    private static final class RedisReadLock extends AbstractReadWriteLockHandle {

        private static final String READ_ACQUIRE_SCRIPT =
                "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
                        + "redis.call('hset', KEYS[2], ARGV[1], 1) "
                        + "redis.call('expire', KEYS[2], tonumber(ARGV[2])) "
                        + "return 1";

        private static final String READ_RELEASE_SCRIPT =
                "if redis.call('hexists', KEYS[1], ARGV[1]) == 0 then return 0 end "
                        + "redis.call('hdel', KEYS[1], ARGV[1]) "
                        + "if redis.call('hlen', KEYS[1]) == 0 then redis.call('del', KEYS[1]) end "
                        + "return 1";

        private static final String READ_RENEW_SCRIPT =
                "if redis.call('hexists', KEYS[1], ARGV[1]) == 0 then return 0 end "
                        + "redis.call('expire', KEYS[1], tonumber(ARGV[2])) "
                        + "return 1";

        private final String writerKey;
        private final String readersKey;

        private RedisReadLock(
                String name,
                String writerKey,
                String readersKey,
                RedisCommands<String, String> commands,
                long defaultLeaseTimeSeconds) {
            super(name, commands, defaultLeaseTimeSeconds);
            this.writerKey = writerKey;
            this.readersKey = readersKey;
        }

        @Override
        protected Long tryAcquire(String candidateToken, long leaseSeconds) {
            return commands().eval(
                    READ_ACQUIRE_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{writerKey, readersKey},
                    candidateToken,
                    String.valueOf(leaseSeconds)
            );
        }

        @Override
        protected Long release(String currentToken) {
            return commands().eval(
                    READ_RELEASE_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{readersKey},
                    currentToken
            );
        }

        @Override
        protected Long renew(String currentToken, long leaseSeconds) {
            return commands().eval(
                    READ_RENEW_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{readersKey},
                    currentToken,
                    String.valueOf(leaseSeconds)
            );
        }

        @Override
        protected long rawRemainingTime() {
            Long ttl = commands().ttl(readersKey);
            return ttl != null ? ttl : 0;
        }
    }

    private static final class RedisWriteLock extends AbstractReadWriteLockHandle {

        private static final String WRITE_ACQUIRE_SCRIPT =
                "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
                        + "if redis.call('exists', KEYS[2]) == 1 then return 0 end "
                        + "redis.call('set', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[2])) "
                        + "return 1";

        private static final String WRITE_RELEASE_SCRIPT =
                "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

        private static final String WRITE_RENEW_SCRIPT =
                "if redis.call('get', KEYS[1]) == ARGV[1] then "
                        + "return redis.call('expire', KEYS[1], tonumber(ARGV[2])) "
                        + "else return 0 end";

        private final String writerKey;
        private final String readersKey;

        private RedisWriteLock(
                String name,
                String writerKey,
                String readersKey,
                RedisCommands<String, String> commands,
                long defaultLeaseTimeSeconds) {
            super(name, commands, defaultLeaseTimeSeconds);
            this.writerKey = writerKey;
            this.readersKey = readersKey;
        }

        @Override
        protected Long tryAcquire(String candidateToken, long leaseSeconds) {
            return commands().eval(
                    WRITE_ACQUIRE_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{writerKey, readersKey},
                    candidateToken,
                    String.valueOf(leaseSeconds)
            );
        }

        @Override
        protected Long release(String currentToken) {
            return commands().eval(
                    WRITE_RELEASE_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{writerKey},
                    currentToken
            );
        }

        @Override
        protected Long renew(String currentToken, long leaseSeconds) {
            return commands().eval(
                    WRITE_RENEW_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{writerKey},
                    currentToken,
                    String.valueOf(leaseSeconds)
            );
        }

        @Override
        protected long rawRemainingTime() {
            Long ttl = commands().ttl(writerKey);
            return ttl != null ? ttl : 0;
        }
    }
}
