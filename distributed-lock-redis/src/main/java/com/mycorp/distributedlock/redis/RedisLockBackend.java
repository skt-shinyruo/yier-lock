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
        "local now = redis.call('time') "
            + "local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) "
            + "local ttlMs = tonumber(ARGV[2]) "
            + "local pendingTtlMs = tonumber(ARGV[4]) "
            + "local readerType = redis.call('type', KEYS[3]).ok "
            + "if readerType == 'hash' then return 0 end "
            + "if readerType ~= 'none' and readerType ~= 'zset' then return 0 end "
            + "if readerType == 'zset' then redis.call('zremrangebyscore', KEYS[3], '-inf', nowMs) end "
            + "local pendingType = redis.call('type', KEYS[5]).ok "
            + "if pendingType ~= 'none' and pendingType ~= 'zset' then return 0 end "
            + "if pendingType == 'zset' then redis.call('zremrangebyscore', KEYS[5], '-inf', nowMs) end "
            + "redis.call('zadd', KEYS[5], nowMs + pendingTtlMs, ARGV[3]) "
            + "local maxWriter = redis.call('zrevrange', KEYS[5], 0, 0, 'WITHSCORES') "
            + "redis.call('pexpire', KEYS[5], math.max(1, math.ceil(tonumber(maxWriter[2]) - nowMs))) "
            + "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
            + "if redis.call('exists', KEYS[2]) == 1 then return 0 end "
            + "if redis.call('zcard', KEYS[3]) > 0 then return 0 end "
            + "redis.call('zrem', KEYS[5], ARGV[3]) "
            + "if redis.call('zcard', KEYS[5]) == 0 then redis.call('del', KEYS[5]) end "
            + "if redis.call('zcard', KEYS[5]) > 0 then "
            + "local remainingWriter = redis.call('zrevrange', KEYS[5], 0, 0, 'WITHSCORES') "
            + "redis.call('pexpire', KEYS[5], math.max(1, math.ceil(tonumber(remainingWriter[2]) - nowMs))) "
            + "end "
            + "local fence = redis.call('incr', KEYS[4]) "
            + "local owner = ARGV[1] .. ':' .. fence "
            + "redis.call('set', KEYS[1], owner, 'PX', ttlMs) "
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
            + "local maxReader = redis.call('zrevrange', KEYS[3], 0, 0, 'WITHSCORES') "
            + "redis.call('pexpire', KEYS[3], math.max(1, math.ceil(tonumber(maxReader[2]) - nowMs))) "
            + "return fence";

    private static final String WRITE_ACQUIRE_SCRIPT =
        "local now = redis.call('time') "
            + "local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) "
            + "local ttlMs = tonumber(ARGV[2]) "
            + "local pendingTtlMs = tonumber(ARGV[4]) "
            + "local readerType = redis.call('type', KEYS[3]).ok "
            + "if readerType == 'hash' then return 0 end "
            + "if readerType ~= 'none' and readerType ~= 'zset' then return 0 end "
            + "if readerType == 'zset' then redis.call('zremrangebyscore', KEYS[3], '-inf', nowMs) end "
            + "local pendingType = redis.call('type', KEYS[5]).ok "
            + "if pendingType ~= 'none' and pendingType ~= 'zset' then return 0 end "
            + "if pendingType == 'zset' then redis.call('zremrangebyscore', KEYS[5], '-inf', nowMs) end "
            + "redis.call('zadd', KEYS[5], nowMs + pendingTtlMs, ARGV[3]) "
            + "local maxWriter = redis.call('zrevrange', KEYS[5], 0, 0, 'WITHSCORES') "
            + "redis.call('pexpire', KEYS[5], math.max(1, math.ceil(tonumber(maxWriter[2]) - nowMs))) "
            + "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
            + "if redis.call('exists', KEYS[2]) == 1 then return 0 end "
            + "if redis.call('zcard', KEYS[3]) > 0 then return 0 end "
            + "redis.call('zrem', KEYS[5], ARGV[3]) "
            + "if redis.call('zcard', KEYS[5]) == 0 then redis.call('del', KEYS[5]) end "
            + "if redis.call('zcard', KEYS[5]) > 0 then "
            + "local remainingWriter = redis.call('zrevrange', KEYS[5], 0, 0, 'WITHSCORES') "
            + "redis.call('pexpire', KEYS[5], math.max(1, math.ceil(tonumber(remainingWriter[2]) - nowMs))) "
            + "end "
            + "local fence = redis.call('incr', KEYS[4]) "
            + "local owner = ARGV[1] .. ':' .. fence "
            + "redis.call('set', KEYS[2], owner, 'PX', ttlMs) "
            + "return fence";

    private static final String VALUE_RELEASE_SCRIPT =
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private static final String VALUE_REFRESH_SCRIPT =
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], tonumber(ARGV[2])) else return 0 end";

    private static final String PENDING_WRITER_CLEANUP_SCRIPT =
        "local now = redis.call('time') "
            + "local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) "
            + "if redis.call('type', KEYS[1]).ok ~= 'zset' then return 0 end "
            + "local removed = redis.call('zrem', KEYS[1], ARGV[1]) "
            + "if redis.call('zcard', KEYS[1]) == 0 then redis.call('del', KEYS[1]) end "
            + "if redis.call('zcard', KEYS[1]) > 0 then "
            + "local maxWriter = redis.call('zrevrange', KEYS[1], 0, 0, 'WITHSCORES') "
            + "redis.call('pexpire', KEYS[1], math.max(1, math.ceil(tonumber(maxWriter[2]) - nowMs))) "
            + "end "
            + "return removed";

    private static final String READ_RELEASE_SCRIPT =
        "local now = redis.call('time') "
            + "local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) "
            + "if redis.call('type', KEYS[1]).ok ~= 'zset' then return 0 end "
            + "redis.call('zremrangebyscore', KEYS[1], '-inf', nowMs) "
            + "if redis.call('zrem', KEYS[1], ARGV[1]) == 0 then return 0 end "
            + "if redis.call('zcard', KEYS[1]) == 0 then redis.call('del', KEYS[1]) end "
            + "if redis.call('zcard', KEYS[1]) > 0 then "
            + "local maxReader = redis.call('zrevrange', KEYS[1], 0, 0, 'WITHSCORES') "
            + "redis.call('pexpire', KEYS[1], math.max(1, math.ceil(tonumber(maxReader[2]) - nowMs))) "
            + "end "
            + "return 1";

    private static final String READ_REFRESH_SCRIPT =
        "local now = redis.call('time') "
            + "local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) "
            + "if redis.call('type', KEYS[1]).ok ~= 'zset' then return 0 end "
            + "redis.call('zremrangebyscore', KEYS[1], '-inf', nowMs) "
            + "if redis.call('zscore', KEYS[1], ARGV[1]) == false then return 0 end "
            + "local ttlMs = tonumber(ARGV[2]) "
            + "redis.call('zadd', KEYS[1], nowMs + ttlMs, ARGV[1]) "
            + "local maxReader = redis.call('zrevrange', KEYS[1], 0, 0, 'WITHSCORES') "
            + "redis.call('pexpire', KEYS[1], math.max(1, math.ceil(tonumber(maxReader[2]) - nowMs))) "
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
        return RedisKeys.forKey(key, RedisKeyStrategy.LEGACY).ownerKey(mode);
    }

    private static String readersKey(String key) {
        return RedisKeys.forKey(key, RedisKeyStrategy.LEGACY).readersKey();
    }

    private static String pendingWritersKey(String key) {
        return RedisKeys.forKey(key, RedisKeyStrategy.LEGACY).pendingWritersKey();
    }

    private static String fenceKey(String key) {
        return RedisKeys.forKey(key, RedisKeyStrategy.LEGACY).fenceKey();
    }

    private static String sessionKey(String sessionId) {
        return "session:%s".formatted(sessionId);
    }

    private static String normalizeMode(LockMode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    static final class RedisKeys {
        private final String prefix;

        private RedisKeys(String prefix) {
            this.prefix = prefix;
        }

        static RedisKeys forKey(String key, RedisKeyStrategy strategy) {
            return switch (strategy) {
                case LEGACY -> new RedisKeys("lock:" + key);
                case HASH_TAGGED -> new RedisKeys("lock:{" + encodeKey(key) + "}");
            };
        }

        String ownerKey(LockMode mode) {
            return prefix + ":" + normalizeMode(mode) + ":owner";
        }

        String readersKey() {
            return prefix + ":read:owners";
        }

        String pendingWritersKey() {
            return prefix + ":write:pending";
        }

        String fenceKey() {
            return prefix + ":fence";
        }

        private static String encodeKey(String key) {
            return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private RedisKeys keys(String key) {
        return RedisKeys.forKey(key, configuration.keyStrategy());
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

            String exclusiveIntent = request.mode() == LockMode.WRITE || request.mode() == LockMode.MUTEX
                ? writerIntentValue(sessionId, nextSessionId())
                : null;
            boolean acquired = false;

            try {
                long deadlineNanos = deadlineNanos(request);

                do {
                    ensureActive();
                    RedisLease lease = tryAcquire(request, exclusiveIntent);
                    if (lease != null) {
                        acquired = true;
                        activeLeases.put(lease.ownerValue(), lease);
                        lease.startRenewal();
                        return lease;
                    }
                    if (request.waitPolicy().mode() == WaitMode.TRY_ONCE || System.nanoTime() >= deadlineNanos) {
                        throw new LockAcquisitionTimeoutException("Timed out acquiring Redis lock for key " + request.key().value());
                    }
                    Thread.sleep(sleepMillis(request, deadlineNanos));
                } while (true);
            } finally {
                if (!acquired && exclusiveIntent != null) {
                    clearPendingWriterIntent(request.key().value(), exclusiveIntent);
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

        private RedisLease tryAcquire(LockRequest request, String exclusiveIntent) {
            long leaseMillis = effectiveLeaseMillis(request);
            long fence = switch (request.mode()) {
                case MUTEX -> tryAcquireMutex(request.key().value(), leaseMillis, Objects.requireNonNull(exclusiveIntent, "exclusiveIntent"));
                case READ -> tryAcquireRead(request.key().value(), leaseMillis);
                case WRITE -> tryAcquireWrite(request.key().value(), leaseMillis, Objects.requireNonNull(exclusiveIntent, "exclusiveIntent"));
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

        private long tryAcquireMutex(String key, long leaseMillis, String exclusiveIntent) {
            try {
                RedisKeys keys = keys(key);
                Long result = commands.eval(
                    MUTEX_ACQUIRE_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{
                        keys.ownerKey(LockMode.MUTEX),
                        keys.ownerKey(LockMode.WRITE),
                        keys.readersKey(),
                        keys.fenceKey(),
                        keys.pendingWritersKey()
                    },
                    sessionId,
                    String.valueOf(leaseMillis),
                    exclusiveIntent,
                    String.valueOf(pendingWriterIntentMillis())
                );
                return result == null ? 0L : result;
            } catch (RuntimeException exception) {
                throw new LockBackendException("Failed to acquire Redis mutex for key " + key, exception);
            }
        }

        private long tryAcquireRead(String key, long leaseMillis) {
            try {
                RedisKeys keys = keys(key);
                Long result = commands.eval(
                    READ_ACQUIRE_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{
                        keys.ownerKey(LockMode.MUTEX),
                        keys.ownerKey(LockMode.WRITE),
                        keys.readersKey(),
                        keys.fenceKey(),
                        keys.pendingWritersKey()
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
                RedisKeys keys = keys(key);
                Long result = commands.eval(
                    WRITE_ACQUIRE_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{
                        keys.ownerKey(LockMode.MUTEX),
                        keys.ownerKey(LockMode.WRITE),
                        keys.readersKey(),
                        keys.fenceKey(),
                        keys.pendingWritersKey()
                    },
                    sessionId,
                    String.valueOf(leaseMillis),
                    writerIntent,
                    String.valueOf(pendingWriterIntentMillis())
                );
                return result == null ? 0L : result;
            } catch (RuntimeException exception) {
                throw new LockBackendException("Failed to acquire Redis write lock for key " + key, exception);
            }
        }

        private void clearPendingWriterIntent(String key, String writerIntent) {
            try {
                RedisKeys keys = keys(key);
                commands.eval(
                    PENDING_WRITER_CLEANUP_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{keys.pendingWritersKey()},
                    writerIntent
                );
            } catch (RuntimeException ignored) {
            }
        }

        private ScheduledFuture<?> scheduleRenewal() {
            long sessionLeaseMillis = TimeUnit.SECONDS.toMillis(configuration.leaseSeconds());
            long periodMillis = renewalPeriodMillis(sessionLeaseMillis);
            long initialDelayMillis = initialRenewalDelayMillis(sessionLeaseMillis, periodMillis);
            return renewalExecutor.scheduleAtFixedRate(this::renewSession, initialDelayMillis, periodMillis, TimeUnit.MILLISECONDS);
        }

        private void renewSession() {
            if (closed.get()) {
                return;
            }
            try {
                if (!renewSessionKey()) {
                    markSessionLost(new LockSessionLostException("Redis session lost: " + sessionId));
                }
            } catch (RuntimeException exception) {
                markSessionLost(new LockSessionLostException("Redis session lost: " + sessionId, exception));
            }
        }

        private void markSessionLostFromRenewal(RuntimeException cause) {
            markSessionLost(new LockSessionLostException("Redis session lost: " + sessionId, cause));
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
            return Math.max(1L, request.leasePolicy().duration().toMillis());
        }

        private long pendingWriterIntentMillis() {
            return TimeUnit.SECONDS.toMillis(configuration.leaseSeconds());
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
        private final AtomicReference<ScheduledFuture<?>> renewalTask = new AtomicReference<>();

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
                cancelRenewal();
                session.forgetLease(this);
                throw new LockOwnershipLostException("Redis lock ownership lost for key " + key.value());
            }

            try {
                RedisKeys keys = keys(key.value());
                Long result = switch (mode) {
                    case MUTEX -> commands.eval(
                        VALUE_RELEASE_SCRIPT,
                        ScriptOutputType.INTEGER,
                        new String[]{keys.ownerKey(LockMode.MUTEX)},
                        ownerValue
                    );
                    case READ -> commands.eval(
                        READ_RELEASE_SCRIPT,
                        ScriptOutputType.INTEGER,
                        new String[]{keys.readersKey()},
                        ownerValue
                    );
                    case WRITE -> commands.eval(
                        VALUE_RELEASE_SCRIPT,
                        ScriptOutputType.INTEGER,
                        new String[]{keys.ownerKey(LockMode.WRITE)},
                        ownerValue
                    );
                };
                if (result == null || result == 0L) {
                    markLost();
                    throw new LockOwnershipLostException("Redis lock ownership lost for key " + key.value());
                }
                state.set(LeaseState.RELEASED);
                cancelRenewal();
                session.forgetLease(this);
            } catch (RuntimeException exception) {
                if (exception instanceof LockOwnershipLostException || exception instanceof LockBackendException) {
                    throw exception;
                }
                throw new LockBackendException("Failed to release Redis lock for key " + key.value(), exception);
            }
        }

        private void startRenewal() {
            long periodMillis = renewalPeriodMillis(leaseMillis);
            long initialDelayMillis = initialRenewalDelayMillis(leaseMillis, periodMillis);
            ScheduledFuture<?> scheduled = renewalExecutor.scheduleAtFixedRate(
                this::renewLease,
                initialDelayMillis,
                periodMillis,
                TimeUnit.MILLISECONDS
            );
            if (!renewalTask.compareAndSet(null, scheduled)) {
                scheduled.cancel(false);
            }
        }

        private void renewLease() {
            if (state.get() != LeaseState.ACTIVE || session.state() != SessionState.ACTIVE) {
                cancelRenewal();
                return;
            }
            try {
                refresh();
            } catch (RuntimeException exception) {
                cancelRenewal();
                session.markSessionLostFromRenewal(exception);
            }
        }

        private boolean refresh() {
            if (state.get() != LeaseState.ACTIVE) {
                return false;
            }
            try {
                RedisKeys keys = keys(key.value());
                Long result = switch (mode) {
                    case MUTEX -> commands.eval(
                        VALUE_REFRESH_SCRIPT,
                        ScriptOutputType.INTEGER,
                        new String[]{keys.ownerKey(LockMode.MUTEX)},
                        ownerValue,
                        String.valueOf(leaseMillis)
                    );
                    case READ -> commands.eval(
                        READ_REFRESH_SCRIPT,
                        ScriptOutputType.INTEGER,
                        new String[]{keys.readersKey()},
                        ownerValue,
                        String.valueOf(leaseMillis)
                    );
                    case WRITE -> commands.eval(
                        VALUE_REFRESH_SCRIPT,
                        ScriptOutputType.INTEGER,
                        new String[]{keys.ownerKey(LockMode.WRITE)},
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
                RedisKeys keys = keys(key.value());
                return switch (mode) {
                    case MUTEX -> ownerValue.equals(commands.get(keys.ownerKey(LockMode.MUTEX)));
                    case READ -> {
                        Long result = commands.eval(
                            READ_OWNER_MATCH_SCRIPT,
                            ScriptOutputType.INTEGER,
                            new String[]{keys.readersKey()},
                            ownerValue
                        );
                        yield result != null && result == 1L;
                    }
                    case WRITE -> ownerValue.equals(commands.get(keys.ownerKey(LockMode.WRITE)));
                };
            } catch (RuntimeException exception) {
                throw new LockBackendException("Failed to inspect Redis lock for key " + key.value(), exception);
            }
        }

        private void markLost() {
            state.compareAndSet(LeaseState.ACTIVE, LeaseState.LOST);
            cancelRenewal();
            session.forgetLease(this);
        }

        private void cancelRenewal() {
            ScheduledFuture<?> scheduled = renewalTask.getAndSet(null);
            if (scheduled != null) {
                scheduled.cancel(false);
            }
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

    private static long renewalPeriodMillis(long leaseMillis) {
        return Math.max(1L, leaseMillis / 5L);
    }

    private static long initialRenewalDelayMillis(long leaseMillis, long periodMillis) {
        return Math.min(periodMillis, Math.max(1L, leaseMillis / 10L));
    }
}
