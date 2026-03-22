package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.api.LockProvider;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
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
import java.util.function.Consumer;

/**
 * Minimal Redis lock provider that returns independent lock handles.
 */
public class SimpleRedisLockProvider implements LockProvider {

    private static final Logger logger = LoggerFactory.getLogger(SimpleRedisLockProvider.class);

    private final LockConfiguration configuration;
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;

    private volatile boolean closed;

    public SimpleRedisLockProvider() {
        this(new LockConfiguration());
    }

    public SimpleRedisLockProvider(LockConfiguration configuration) {
        this(configuration, null, null, null);
    }

    SimpleRedisLockProvider(
            LockConfiguration configuration,
            RedisClient redisClient,
            StatefulRedisConnection<String, String> connection,
            RedisCommands<String, String> commands) {
        this.configuration = configuration != null ? configuration : new LockConfiguration();
        if (commands != null) {
            this.redisClient = redisClient;
            this.connection = connection;
            this.commands = commands;
            return;
        }

        RedisClient client = createRedisClient();
        StatefulRedisConnection<String, String> redisConnection = client.connect();
        this.redisClient = client;
        this.connection = redisConnection;
        this.commands = redisConnection.sync();
        logger.info("Simple Redis lock provider initialized");
    }

    @Override
    public String getType() {
        return "redis";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public DistributedLock createLock(String key) {
        validateKey(key);
        return new SimpleRedisLock(key, commands, configuration.getDefaultLeaseTime().getSeconds());
    }

    @Override
    public DistributedReadWriteLock createReadWriteLock(String key) {
        validateKey(key);
        return new SimpleReadWriteLock(key, commands, configuration.getDefaultLeaseTime().getSeconds());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    private void validateKey(String key) {
        if (closed) {
            throw new IllegalStateException("Provider is closed");
        }
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Lock key cannot be null or empty");
        }
    }

    private RedisClient createRedisClient() {
        String hosts = configuration.getRedisHosts();
        String password = configuration.getRedisPassword();
        boolean ssl = configuration.isRedisSslEnabled();
        int database = configuration.getRedisDatabase();

        StringBuilder uri = new StringBuilder("redis://");
        if (password != null && !password.isEmpty()) {
            uri.append(":").append(password).append("@");
        }
        uri.append(hosts);
        if (database > 0) {
            uri.append("/").append(database);
        }
        if (ssl) {
            uri.append("?ssl=true");
        }
        return RedisClient.create(uri.toString());
    }

    private static final class SimpleReadWriteLock implements DistributedReadWriteLock {

        private final String lockName;
        private final DistributedLock readLock;
        private final DistributedLock writeLock;

        private SimpleReadWriteLock(String lockName, RedisCommands<String, String> commands, long defaultLeaseTimeSeconds) {
            this.lockName = lockName;
            String writeKey = lockName + ":write";
            String readersKey = lockName + ":readers";
            this.readLock = new RedisReadLock(lockName + ":read", writeKey, readersKey, commands, defaultLeaseTimeSeconds);
            this.writeLock = new RedisWriteLock(lockName + ":write", writeKey, readersKey, commands, defaultLeaseTimeSeconds);
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
    }

    private abstract static class AbstractReadWriteLockHandle implements DistributedLock {

        private static final ScheduledThreadPoolExecutor RENEWAL_EXECUTOR =
                new ScheduledThreadPoolExecutor(1, runnable -> {
                    Thread thread = new Thread(runnable, "simple-redis-rw-renewal");
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

        private AbstractReadWriteLockHandle(String name, RedisCommands<String, String> commands, long defaultLeaseTimeSeconds) {
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

        protected RedisCommands<String, String> commands() {
            return commands;
        }

        protected abstract Long tryAcquire(String candidateToken, long leaseSeconds);

        protected abstract Long release(String currentToken);

        protected abstract Long renew(String currentToken, long leaseSeconds);

        protected abstract long rawRemainingTime();

        @Override
        public long getRemainingTime(TimeUnit unit) {
            long remaining = rawRemainingTime();
            if (remaining <= 0) {
                return 0;
            }
            return unit != null ? unit.convert(remaining, TimeUnit.SECONDS) : remaining;
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
                "-- SIMPLE_RW_READ_ACQUIRE\n"
                        + "if redis.call('exists', KEYS[1]) == 1 then return 0 end\n"
                        + "redis.call('hset', KEYS[2], ARGV[1], 1)\n"
                        + "redis.call('expire', KEYS[2], tonumber(ARGV[2]))\n"
                        + "return 1";

        private static final String READ_RELEASE_SCRIPT =
                "-- SIMPLE_RW_READ_RELEASE\n"
                        + "if redis.call('hexists', KEYS[1], ARGV[1]) == 0 then return 0 end\n"
                        + "redis.call('hdel', KEYS[1], ARGV[1])\n"
                        + "if redis.call('hlen', KEYS[1]) == 0 then redis.call('del', KEYS[1]) end\n"
                        + "return 1";

        private static final String READ_RENEW_SCRIPT =
                "-- SIMPLE_RW_READ_RENEW\n"
                        + "if redis.call('hexists', KEYS[1], ARGV[1]) == 0 then return 0 end\n"
                        + "redis.call('expire', KEYS[1], tonumber(ARGV[2]))\n"
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
                "-- SIMPLE_RW_WRITE_ACQUIRE\n"
                        + "if redis.call('exists', KEYS[1]) == 1 then return 0 end\n"
                        + "if redis.call('exists', KEYS[2]) == 1 then return 0 end\n"
                        + "redis.call('set', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[2]))\n"
                        + "return 1";

        private static final String WRITE_RELEASE_SCRIPT =
                "-- SIMPLE_RW_WRITE_RELEASE\n"
                        + "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

        private static final String WRITE_RENEW_SCRIPT =
                "-- SIMPLE_RW_WRITE_RENEW\n"
                        + "if redis.call('get', KEYS[1]) == ARGV[1] then "
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
