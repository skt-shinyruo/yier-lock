package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class RedisLockBackend implements LockBackend, AutoCloseable {

    private static final String MUTEX_RELEASE_SCRIPT =
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private static final String MUTEX_REFRESH_SCRIPT =
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('expire', KEYS[1], tonumber(ARGV[2])) else return 0 end";

    private static final String READ_ACQUIRE_SCRIPT =
        "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
            + "redis.call('hset', KEYS[2], ARGV[1], '1') "
            + "redis.call('expire', KEYS[2], tonumber(ARGV[2])) "
            + "return 1";

    private static final String READ_RELEASE_SCRIPT =
        "if redis.call('hexists', KEYS[1], ARGV[1]) == 0 then return 0 end "
            + "redis.call('hdel', KEYS[1], ARGV[1]) "
            + "if redis.call('hlen', KEYS[1]) == 0 then redis.call('del', KEYS[1]) end "
            + "return 1";

    private static final String READ_REFRESH_SCRIPT =
        "if redis.call('hexists', KEYS[1], ARGV[1]) == 1 then return redis.call('expire', KEYS[1], tonumber(ARGV[2])) else return 0 end";

    private static final String WRITE_ACQUIRE_SCRIPT =
        "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
            + "if redis.call('exists', KEYS[2]) == 1 then return 0 end "
            + "redis.call('set', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[2])) "
            + "return 1";

    private static final String WRITE_RELEASE_SCRIPT = MUTEX_RELEASE_SCRIPT;
    private static final String WRITE_REFRESH_SCRIPT = MUTEX_REFRESH_SCRIPT;

    private final RedisBackendConfiguration configuration;
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final ScheduledExecutorService renewalExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "redis-lock-renewal");
        thread.setDaemon(true);
        return thread;
    });

    public RedisLockBackend(RedisBackendConfiguration configuration) {
        this.configuration = configuration;
        this.redisClient = RedisClient.create(configuration.redisUri());
        this.connection = redisClient.connect();
        this.commands = connection.sync();
    }

    @Override
    public BackendLockLease acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) throws InterruptedException {
        String token = nextToken();
        long leaseSeconds = configuration.leaseSeconds();
        long deadline = waitPolicy.unbounded()
            ? Long.MAX_VALUE
            : System.nanoTime() + waitPolicy.waitTime().toNanos();

        do {
            boolean acquired = switch (mode) {
                case MUTEX -> tryAcquireMutex(resource.key(), token, leaseSeconds);
                case READ -> tryAcquireRead(resource.key(), token, leaseSeconds);
                case WRITE -> tryAcquireWrite(resource.key(), token, leaseSeconds);
            };

            if (acquired) {
                return new RedisLease(
                    resource.key(),
                    mode,
                    token,
                    Thread.currentThread().getId(),
                    scheduleRenewal(resource.key(), mode, token, leaseSeconds)
                );
            }

            if (!waitPolicy.unbounded() && System.nanoTime() >= deadline) {
                return null;
            }

            Thread.sleep(waitPolicy.unbounded() ? 25L : Math.min(25L, Math.max(1L,
                TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
        } while (true);
    }

    private void releaseLease(RedisLease lease) {
        lease.renewalTask.cancel(false);
        try {
            Long result = switch (lease.mode()) {
                case MUTEX -> commands.eval(
                    MUTEX_RELEASE_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{lease.key()},
                    lease.token()
                );
                case READ -> commands.eval(
                    READ_RELEASE_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{readersKey(lease.key())},
                    lease.token()
                );
                case WRITE -> commands.eval(
                    WRITE_RELEASE_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{writerKey(lease.key())},
                    lease.token()
                );
            };

            if (result == null || result == 0L) {
                throw new LockOwnershipLostException("Redis lock ownership lost for key " + lease.key());
            }
        } catch (RuntimeException exception) {
            if (exception instanceof LockBackendException || exception instanceof LockOwnershipLostException) {
                throw exception;
            }
            throw new LockBackendException("Failed to release Redis lock for key " + lease.key(), exception);
        }
    }

    private boolean isLeaseValid(RedisLease lease) {
        if (lease.threadId() != Thread.currentThread().getId()) {
            return false;
        }

        try {
            return switch (lease.mode()) {
                case MUTEX -> lease.token().equals(commands.get(lease.key()));
                case READ -> commands.hexists(readersKey(lease.key()), lease.token());
                case WRITE -> lease.token().equals(commands.get(writerKey(lease.key())));
            };
        } catch (RuntimeException exception) {
            throw new LockBackendException("Failed to inspect Redis lock state for key " + lease.key(), exception);
        }
    }

    @Override
    public void close() {
        renewalExecutor.shutdownNow();
        connection.close();
        redisClient.shutdown();
    }

    private ScheduledFuture<?> scheduleRenewal(String key, LockMode mode, String token, long leaseSeconds) {
        long periodMillis = Math.max(250L, TimeUnit.SECONDS.toMillis(leaseSeconds) / 3L);
        return renewalExecutor.scheduleAtFixedRate(
            () -> renewLease(key, mode, token, leaseSeconds),
            periodMillis,
            periodMillis,
            TimeUnit.MILLISECONDS
        );
    }

    private boolean tryAcquireMutex(String key, String token, long leaseSeconds) {
        try {
            return "OK".equals(commands.set(key, token, SetArgs.Builder.nx().ex(leaseSeconds)));
        } catch (RuntimeException exception) {
            throw new LockBackendException("Failed to acquire Redis mutex for key " + key, exception);
        }
    }

    private boolean tryAcquireRead(String key, String token, long leaseSeconds) {
        try {
            Long result = commands.eval(
                READ_ACQUIRE_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{writerKey(key), readersKey(key)},
                token,
                String.valueOf(leaseSeconds)
            );
            return result != null && result == 1L;
        } catch (RuntimeException exception) {
            throw new LockBackendException("Failed to acquire Redis read lock for key " + key, exception);
        }
    }

    private boolean tryAcquireWrite(String key, String token, long leaseSeconds) {
        try {
            Long result = commands.eval(
                WRITE_ACQUIRE_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{writerKey(key), readersKey(key)},
                token,
                String.valueOf(leaseSeconds)
            );
            return result != null && result == 1L;
        } catch (RuntimeException exception) {
            throw new LockBackendException("Failed to acquire Redis write lock for key " + key, exception);
        }
    }

    private void renewLease(String key, LockMode mode, String token, long leaseSeconds) {
        try {
            Long result = switch (mode) {
                case MUTEX -> commands.eval(
                    MUTEX_REFRESH_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{key},
                    token,
                    String.valueOf(leaseSeconds)
                );
                case READ -> commands.eval(
                    READ_REFRESH_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{readersKey(key)},
                    token,
                    String.valueOf(leaseSeconds)
                );
                case WRITE -> commands.eval(
                    WRITE_REFRESH_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{writerKey(key)},
                    token,
                    String.valueOf(leaseSeconds)
                );
            };
            if (result == null || result == 0L) {
                throw new LockOwnershipLostException("Redis lock ownership lost for key " + key);
            }
        } catch (RuntimeException exception) {
            if (exception instanceof LockOwnershipLostException) {
                throw exception;
            }
            throw new LockBackendException("Failed to renew Redis lock for key " + key, exception);
        }
    }

    private static String writerKey(String key) {
        return key + ":write";
    }

    private static String readersKey(String key) {
        return key + ":readers";
    }

    private static String nextToken() {
        return Thread.currentThread().getId() + ":" + UUID.randomUUID();
    }

    private final class RedisLease implements BackendLockLease {

        private final String key;
        private final LockMode mode;
        private final String token;
        private final long threadId;
        private final ScheduledFuture<?> renewalTask;

        private RedisLease(String key, LockMode mode, String token, long threadId, ScheduledFuture<?> renewalTask) {
            this.key = key;
            this.mode = mode;
            this.token = token;
            this.threadId = threadId;
            this.renewalTask = renewalTask;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public LockMode mode() {
            return mode;
        }

        private String token() {
            return token;
        }

        private long threadId() {
            return threadId;
        }

        @Override
        public boolean isValidForCurrentExecution() {
            return isLeaseValid(this);
        }

        @Override
        public void release() {
            releaseLease(this);
        }
    }
}
