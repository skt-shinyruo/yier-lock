package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseMode;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitMode;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.api.exception.LockSessionLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class RedisLockBackend implements LockBackend {

    private static final String MUTEX_ACQUIRE_SCRIPT =
        "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
            + "if redis.call('exists', KEYS[2]) == 1 then return 0 end "
            + "local now = redis.call('time') "
            + "local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) "
            + "local readerType = redis.call('type', KEYS[3]).ok "
            + "if readerType == 'hash' then return 0 end "
            + "if readerType ~= 'none' and readerType ~= 'zset' then return 0 end "
            + "if readerType == 'zset' then redis.call('zremrangebyscore', KEYS[3], '-inf', nowMs) end "
            + "if redis.call('zcard', KEYS[3]) > 0 then return 0 end "
            + "local fence = redis.call('incr', KEYS[4]) "
            + "local owner = ARGV[1] .. ':' .. fence "
            + "redis.call('set', KEYS[1], owner, 'PX', tonumber(ARGV[2])) "
            + "return fence";

    private static final String READ_ACQUIRE_SCRIPT =
        "local now = redis.call('time') "
            + "local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) "
            + "local readerType = redis.call('type', KEYS[3]).ok "
            + "if readerType == 'hash' then return 0 end "
            + "if readerType ~= 'none' and readerType ~= 'zset' then return 0 end "
            + "if readerType == 'zset' then redis.call('zremrangebyscore', KEYS[3], '-inf', nowMs) end "
            + "local pendingType = redis.call('type', KEYS[5]).ok "
            + "if pendingType ~= 'none' and pendingType ~= 'zset' then return 0 end "
            + "if pendingType == 'zset' then redis.call('zremrangebyscore', KEYS[5], '-inf', nowMs) end "
            + "if pendingType == 'zset' and redis.call('zcard', KEYS[5]) == 0 then redis.call('del', KEYS[5]) end "
            + "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
            + "if redis.call('exists', KEYS[2]) == 1 then return 0 end "
            + "if redis.call('exists', KEYS[5]) == 1 then return 0 end "
            + "local fence = redis.call('incr', KEYS[4]) "
            + "local owner = ARGV[1] .. ':' .. fence "
            + "local ttlMs = tonumber(ARGV[2]) "
            + "redis.call('zadd', KEYS[3], nowMs + ttlMs, owner) "
            + "redis.call('pexpire', KEYS[3], ttlMs) "
            + "return fence";

    private static final String WRITE_ACQUIRE_SCRIPT =
        "local now = redis.call('time') "
            + "local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) "
            + "local ttlMs = tonumber(ARGV[2]) "
            + "local readerType = redis.call('type', KEYS[3]).ok "
            + "if readerType == 'hash' then return 0 end "
            + "if readerType ~= 'none' and readerType ~= 'zset' then return 0 end "
            + "if readerType == 'zset' then redis.call('zremrangebyscore', KEYS[3], '-inf', nowMs) end "
            + "local pendingType = redis.call('type', KEYS[5]).ok "
            + "if pendingType ~= 'none' and pendingType ~= 'zset' then return 0 end "
            + "if pendingType == 'zset' then redis.call('zremrangebyscore', KEYS[5], '-inf', nowMs) end "
            + "redis.call('zadd', KEYS[5], nowMs + ttlMs, ARGV[3]) "
            + "redis.call('pexpire', KEYS[5], ttlMs) "
            + "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
            + "if redis.call('exists', KEYS[2]) == 1 then return 0 end "
            + "if redis.call('zcard', KEYS[3]) > 0 then return 0 end "
            + "redis.call('zrem', KEYS[5], ARGV[3]) "
            + "if redis.call('zcard', KEYS[5]) == 0 then redis.call('del', KEYS[5]) end "
            + "local fence = redis.call('incr', KEYS[4]) "
            + "local owner = ARGV[1] .. ':' .. fence "
            + "redis.call('set', KEYS[2], owner, 'PX', ttlMs) "
            + "return fence";

    private static final String VALUE_RELEASE_SCRIPT =
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private static final String VALUE_REFRESH_SCRIPT =
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], tonumber(ARGV[2])) else return 0 end";

    private static final String PENDING_WRITER_CLEANUP_SCRIPT =
        "if redis.call('type', KEYS[1]).ok ~= 'zset' then return 0 end "
            + "local removed = redis.call('zrem', KEYS[1], ARGV[1]) "
            + "if redis.call('zcard', KEYS[1]) == 0 then redis.call('del', KEYS[1]) end "
            + "return removed";

    private static final String READ_RELEASE_SCRIPT =
        "local now = redis.call('time') "
            + "local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) "
            + "if redis.call('type', KEYS[1]).ok ~= 'zset' then return 0 end "
            + "redis.call('zremrangebyscore', KEYS[1], '-inf', nowMs) "
            + "if redis.call('zrem', KEYS[1], ARGV[1]) == 0 then return 0 end "
            + "if redis.call('zcard', KEYS[1]) == 0 then redis.call('del', KEYS[1]) end "
            + "return 1";

    private static final String READ_REFRESH_SCRIPT =
        "local now = redis.call('time') "
            + "local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) "
            + "if redis.call('type', KEYS[1]).ok ~= 'zset' then return 0 end "
            + "redis.call('zremrangebyscore', KEYS[1], '-inf', nowMs) "
            + "if redis.call('zscore', KEYS[1], ARGV[1]) == false then return 0 end "
            + "local ttlMs = tonumber(ARGV[2]) "
            + "redis.call('zadd', KEYS[1], nowMs + ttlMs, ARGV[1]) "
            + "redis.call('pexpire', KEYS[1], ttlMs) "
            + "return 1";

    private static final String READ_OWNER_MATCH_SCRIPT =
        "local now = redis.call('time') "
            + "local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) "
            + "if redis.call('type', KEYS[1]).ok ~= 'zset' then return 0 end "
            + "redis.call('zremrangebyscore', KEYS[1], '-inf', nowMs) "
            + "if redis.call('zscore', KEYS[1], ARGV[1]) == false then return 0 end "
            + "return 1";

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
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.redisClient = RedisClient.create(configuration.redisUri());
        this.redisClient.setOptions(ClientOptions.builder()
            .autoReconnect(false)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .build());
        this.connection = redisClient.connect();
        this.commands = connection.sync();
    }

    @Override
    public BackendSession openSession() {
        return new RedisBackendSession(nextSessionId());
    }

    @Override
    public void close() {
        renewalExecutor.shutdownNow();
        connection.close();
        redisClient.shutdown();
    }

    static String ownerKey(String key, LockMode mode) {
        return "lock:%s:%s:owner".formatted(key, normalizeMode(mode));
    }

    private static String readersKey(String key) {
        return "lock:%s:read:owners".formatted(key);
    }

    private static String pendingWritersKey(String key) {
        return "lock:%s:write:pending".formatted(key);
    }

    private static String fenceKey(String key) {
        return "lock:%s:fence".formatted(key);
    }

    private static String sessionKey(String sessionId) {
        return "session:%s".formatted(sessionId);
    }

    private static String normalizeMode(LockMode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    private static String nextSessionId() {
        return UUID.randomUUID().toString();
    }

    private final class RedisBackendSession implements BackendSession {

        private final String sessionId;
        private final ConcurrentMap<String, RedisLease> activeLeases = new ConcurrentHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean valid = new AtomicBoolean(true);
        private final AtomicReference<RuntimeException> lossCause = new AtomicReference<>();
        private final ScheduledFuture<?> renewalTask;

        private RedisBackendSession(String sessionId) {
            this.sessionId = sessionId;
            touchSessionKey();
            this.renewalTask = scheduleRenewal();
        }

        @Override
        public BackendLockLease acquire(LockRequest request) throws InterruptedException {
            ensureActive();

            String writerIntent = request.mode() == LockMode.WRITE
                ? writerIntentValue(sessionId, nextSessionId())
                : null;
            boolean acquired = false;

            try {
                long deadlineNanos = deadlineNanos(request);

                do {
                    ensureActive();
                    RedisLease lease = tryAcquire(request, writerIntent);
                    if (lease != null) {
                        acquired = true;
                        activeLeases.put(lease.ownerValue(), lease);
                        return lease;
                    }
                    if (request.waitPolicy().mode() == WaitMode.TRY_ONCE || System.nanoTime() >= deadlineNanos) {
                        throw new LockAcquisitionTimeoutException("Timed out acquiring Redis lock for key " + request.key().value());
                    }
                    Thread.sleep(sleepMillis(request, deadlineNanos));
                } while (true);
            } finally {
                if (!acquired && writerIntent != null) {
                    clearPendingWriterIntent(request.key().value(), writerIntent);
                }
            }
        }

        @Override
        public SessionState state() {
            if (closed.get()) {
                return SessionState.CLOSED;
            }
            return valid.get() ? SessionState.ACTIVE : SessionState.LOST;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            renewalTask.cancel(false);
            RuntimeException terminalLoss = !valid.get() ? lossCause() : null;
            RuntimeException failure = null;
            for (RedisLease lease : new ArrayList<>(activeLeases.values())) {
                try {
                    lease.release();
                } catch (RuntimeException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }

            try {
                commands.del(sessionKey(sessionId));
            } catch (RuntimeException exception) {
                if (terminalLoss != null) {
                    terminalLoss.addSuppressed(exception);
                } else if (failure == null) {
                    failure = new LockBackendException("Failed to close Redis session " + sessionId, exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }

            if (failure != null) {
                throw failure;
            }
            if (terminalLoss != null) {
                throw terminalLoss;
            }
        }

        private RedisLease tryAcquire(LockRequest request, String writerIntent) {
            long leaseMillis = effectiveLeaseMillis(request);
            long fence = switch (request.mode()) {
                case MUTEX -> tryAcquireMutex(request.key().value(), leaseMillis);
                case READ -> tryAcquireRead(request.key().value(), leaseMillis);
                case WRITE -> tryAcquireWrite(request.key().value(), leaseMillis, Objects.requireNonNull(writerIntent, "writerIntent"));
            };
            if (fence <= 0) {
                return null;
            }

            return new RedisLease(
                request.key(),
                request.mode(),
                new FencingToken(fence),
                ownerValue(sessionId, fence),
                this,
                leaseMillis
            );
        }

        private long tryAcquireMutex(String key, long leaseMillis) {
            try {
                Long result = commands.eval(
                    MUTEX_ACQUIRE_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{
                        ownerKey(key, LockMode.MUTEX),
                        ownerKey(key, LockMode.WRITE),
                        readersKey(key),
                        fenceKey(key),
                        pendingWritersKey(key)
                    },
                    sessionId,
                    String.valueOf(leaseMillis)
                );
                return result == null ? 0L : result;
            } catch (RuntimeException exception) {
                throw new LockBackendException("Failed to acquire Redis mutex for key " + key, exception);
            }
        }

        private long tryAcquireRead(String key, long leaseMillis) {
            try {
                Long result = commands.eval(
                    READ_ACQUIRE_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{
                        ownerKey(key, LockMode.MUTEX),
                        ownerKey(key, LockMode.WRITE),
                        readersKey(key),
                        fenceKey(key),
                        pendingWritersKey(key)
                    },
                    sessionId,
                    String.valueOf(leaseMillis)
                );
                return result == null ? 0L : result;
            } catch (RuntimeException exception) {
                throw new LockBackendException("Failed to acquire Redis read lock for key " + key, exception);
            }
        }

        private long tryAcquireWrite(String key, long leaseMillis, String writerIntent) {
            try {
                Long result = commands.eval(
                    WRITE_ACQUIRE_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{
                        ownerKey(key, LockMode.MUTEX),
                        ownerKey(key, LockMode.WRITE),
                        readersKey(key),
                        fenceKey(key),
                        pendingWritersKey(key)
                    },
                    sessionId,
                    String.valueOf(leaseMillis),
                    writerIntent
                );
                return result == null ? 0L : result;
            } catch (RuntimeException exception) {
                throw new LockBackendException("Failed to acquire Redis write lock for key " + key, exception);
            }
        }

        private void clearPendingWriterIntent(String key, String writerIntent) {
            try {
                commands.eval(
                    PENDING_WRITER_CLEANUP_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{pendingWritersKey(key)},
                    writerIntent
                );
            } catch (RuntimeException ignored) {
            }
        }

        private ScheduledFuture<?> scheduleRenewal() {
            return renewalExecutor.scheduleAtFixedRate(this::renew, 250L, 250L, TimeUnit.MILLISECONDS);
        }

        private void renew() {
            if (closed.get()) {
                return;
            }
            try {
                if (!renewSessionKey()) {
                    markSessionLost(new LockSessionLostException("Redis session lost: " + sessionId));
                    return;
                }
                for (RedisLease lease : activeLeases.values()) {
                    lease.refresh();
                }
            } catch (RuntimeException exception) {
                markSessionLost(new LockSessionLostException("Redis session lost: " + sessionId, exception));
            }
        }

        private boolean renewSessionKey() {
            try {
                return Boolean.TRUE.equals(commands.expire(sessionKey(sessionId), configuration.leaseSeconds()));
            } catch (RuntimeException exception) {
                throw new LockBackendException("Failed to renew Redis session " + sessionId, exception);
            }
        }

        private void touchSessionKey() {
            try {
                commands.setex(sessionKey(sessionId), configuration.leaseSeconds(), "1");
            } catch (RuntimeException exception) {
                throw new LockBackendException("Failed to create Redis session " + sessionId, exception);
            }
        }

        private void forgetLease(RedisLease lease) {
            activeLeases.remove(lease.ownerValue());
        }

        private void markSessionLost(RuntimeException cause) {
            lossCause.compareAndSet(null, cause);
            if (!valid.compareAndSet(true, false)) {
                return;
            }
            for (RedisLease lease : new ArrayList<>(activeLeases.values())) {
                lease.markLost();
            }
            renewalTask.cancel(false);
        }

        private RuntimeException lossCause() {
            RuntimeException cause = lossCause.get();
            return cause != null ? cause : new LockSessionLostException("Redis session lost: " + sessionId);
        }

        private void ensureActive() {
            if (closed.get()) {
                throw new IllegalStateException("Redis session is already closed");
            }
            if (!valid.get()) {
                throw new LockSessionLostException("Redis session lost: " + sessionId);
            }
        }

        private long deadlineNanos(LockRequest request) {
            return switch (request.waitPolicy().mode()) {
                case TRY_ONCE -> System.nanoTime();
                case TIMED -> System.nanoTime() + request.waitPolicy().timeout().toNanos();
                case INDEFINITE -> Long.MAX_VALUE;
            };
        }

        private long sleepMillis(LockRequest request, long deadlineNanos) {
            if (request.waitPolicy().mode() == WaitMode.INDEFINITE) {
                return 25L;
            }
            return Math.min(25L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())));
        }

        private long effectiveLeaseMillis(LockRequest request) {
            if (request.leasePolicy().mode() == LeaseMode.BACKEND_DEFAULT) {
                return TimeUnit.SECONDS.toMillis(configuration.leaseSeconds());
            }
            return request.leasePolicy().duration().toMillis();
        }
    }

    private final class RedisLease implements BackendLockLease {

        private final LockKey key;
        private final LockMode mode;
        private final FencingToken fencingToken;
        private final String ownerValue;
        private final RedisBackendSession session;
        private final long leaseMillis;
        private final AtomicReference<LeaseState> state = new AtomicReference<>(LeaseState.ACTIVE);

        private RedisLease(
            LockKey key,
            LockMode mode,
            FencingToken fencingToken,
            String ownerValue,
            RedisBackendSession session,
            long leaseMillis
        ) {
            this.key = key;
            this.mode = mode;
            this.fencingToken = fencingToken;
            this.ownerValue = ownerValue;
            this.session = session;
            this.leaseMillis = leaseMillis;
        }

        @Override
        public LockKey key() {
            return key;
        }

        @Override
        public LockMode mode() {
            return mode;
        }

        @Override
        public FencingToken fencingToken() {
            return fencingToken;
        }

        @Override
        public LeaseState state() {
            return state.get();
        }

        @Override
        public boolean isValid() {
            if (state.get() != LeaseState.ACTIVE) {
                return false;
            }
            if (session.state() != SessionState.ACTIVE) {
                return false;
            }
            return ownerValueMatches();
        }

        @Override
        public void release() {
            LeaseState current = state.get();
            if (current == LeaseState.RELEASED) {
                return;
            }
            if (current == LeaseState.LOST) {
                session.forgetLease(this);
                throw new LockOwnershipLostException("Redis lock ownership lost for key " + key.value());
            }

            try {
                Long result = switch (mode) {
                    case MUTEX -> commands.eval(
                        VALUE_RELEASE_SCRIPT,
                        ScriptOutputType.INTEGER,
                        new String[]{ownerKey(key.value(), LockMode.MUTEX)},
                        ownerValue
                    );
                    case READ -> commands.eval(
                        READ_RELEASE_SCRIPT,
                        ScriptOutputType.INTEGER,
                        new String[]{readersKey(key.value())},
                        ownerValue
                    );
                    case WRITE -> commands.eval(
                        VALUE_RELEASE_SCRIPT,
                        ScriptOutputType.INTEGER,
                        new String[]{ownerKey(key.value(), LockMode.WRITE)},
                        ownerValue
                    );
                };
                if (result == null || result == 0L) {
                    markLost();
                    throw new LockOwnershipLostException("Redis lock ownership lost for key " + key.value());
                }
                state.set(LeaseState.RELEASED);
                session.forgetLease(this);
            } catch (RuntimeException exception) {
                if (exception instanceof LockOwnershipLostException || exception instanceof LockBackendException) {
                    throw exception;
                }
                throw new LockBackendException("Failed to release Redis lock for key " + key.value(), exception);
            }
        }

        private boolean refresh() {
            if (state.get() != LeaseState.ACTIVE) {
                return false;
            }
            try {
                Long result = switch (mode) {
                    case MUTEX -> commands.eval(
                        VALUE_REFRESH_SCRIPT,
                        ScriptOutputType.INTEGER,
                        new String[]{ownerKey(key.value(), LockMode.MUTEX)},
                        ownerValue,
                        String.valueOf(leaseMillis)
                    );
                    case READ -> commands.eval(
                        READ_REFRESH_SCRIPT,
                        ScriptOutputType.INTEGER,
                        new String[]{readersKey(key.value())},
                        ownerValue,
                        String.valueOf(leaseMillis)
                    );
                    case WRITE -> commands.eval(
                        VALUE_REFRESH_SCRIPT,
                        ScriptOutputType.INTEGER,
                        new String[]{ownerKey(key.value(), LockMode.WRITE)},
                        ownerValue,
                        String.valueOf(leaseMillis)
                    );
                };
                if (result == null || result == 0L) {
                    markLost();
                    return false;
                }
                return true;
            } catch (RuntimeException exception) {
                throw new LockBackendException("Failed to renew Redis lock for key " + key.value(), exception);
            }
        }

        private boolean ownerValueMatches() {
            try {
                return switch (mode) {
                    case MUTEX -> ownerValue.equals(commands.get(ownerKey(key.value(), LockMode.MUTEX)));
                    case READ -> {
                        Long result = commands.eval(
                            READ_OWNER_MATCH_SCRIPT,
                            ScriptOutputType.INTEGER,
                            new String[]{readersKey(key.value())},
                            ownerValue
                        );
                        yield result != null && result == 1L;
                    }
                    case WRITE -> ownerValue.equals(commands.get(ownerKey(key.value(), LockMode.WRITE)));
                };
            } catch (RuntimeException exception) {
                throw new LockBackendException("Failed to inspect Redis lock for key " + key.value(), exception);
            }
        }

        private void markLost() {
            state.compareAndSet(LeaseState.ACTIVE, LeaseState.LOST);
            session.forgetLease(this);
        }

        private String ownerValue() {
            return ownerValue;
        }
    }

    private static String ownerValue(String sessionId, long fence) {
        return sessionId + ":" + fence;
    }

    private static String writerIntentValue(String sessionId, String attemptId) {
        return sessionId + ":writer:" + attemptId;
    }
}
