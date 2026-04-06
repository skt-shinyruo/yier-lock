package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockCapabilities;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.api.exception.LockSessionLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;
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

    private static final LockCapabilities CAPABILITIES = new LockCapabilities(true, true, true, true);

    private static final String MUTEX_ACQUIRE_SCRIPT =
        "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
            + "local fence = redis.call('incr', KEYS[2]) "
            + "local owner = ARGV[1] .. ':' .. fence "
            + "redis.call('set', KEYS[1], owner, 'EX', tonumber(ARGV[2])) "
            + "return fence";

    private static final String READ_ACQUIRE_SCRIPT =
        "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
            + "local fence = redis.call('incr', KEYS[3]) "
            + "local owner = ARGV[1] .. ':' .. fence "
            + "redis.call('hset', KEYS[2], owner, '1') "
            + "redis.call('expire', KEYS[2], tonumber(ARGV[2])) "
            + "return fence";

    private static final String WRITE_ACQUIRE_SCRIPT =
        "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
            + "if redis.call('exists', KEYS[2]) == 1 then return 0 end "
            + "local fence = redis.call('incr', KEYS[3]) "
            + "local owner = ARGV[1] .. ':' .. fence "
            + "redis.call('set', KEYS[1], owner, 'EX', tonumber(ARGV[2])) "
            + "return fence";

    private static final String VALUE_RELEASE_SCRIPT =
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private static final String VALUE_REFRESH_SCRIPT =
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('expire', KEYS[1], tonumber(ARGV[2])) else return 0 end";

    private static final String HASH_RELEASE_SCRIPT =
        "if redis.call('hexists', KEYS[1], ARGV[1]) == 0 then return 0 end "
            + "redis.call('hdel', KEYS[1], ARGV[1]) "
            + "if redis.call('hlen', KEYS[1]) == 0 then redis.call('del', KEYS[1]) end "
            + "return 1";

    private static final String HASH_REFRESH_SCRIPT =
        "if redis.call('hexists', KEYS[1], ARGV[1]) == 1 then return redis.call('expire', KEYS[1], tonumber(ARGV[2])) else return 0 end";

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
        this.connection = redisClient.connect();
        this.commands = connection.sync();
    }

    @Override
    public LockCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public BackendSession openSession(SessionRequest request) {
        Objects.requireNonNull(request, "request");
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

    private static String fenceKey(String key, LockMode mode) {
        return "lock:%s:%s:fence".formatted(key, normalizeMode(mode));
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
        private final ScheduledFuture<?> renewalTask;

        private RedisBackendSession(String sessionId) {
            this.sessionId = sessionId;
            touchSessionKey();
            this.renewalTask = scheduleRenewal();
        }

        @Override
        public BackendLockLease acquire(LockRequest request) throws InterruptedException {
            ensureActive();

            long deadlineNanos = request.waitPolicy().unbounded()
                ? Long.MAX_VALUE
                : System.nanoTime() + request.waitPolicy().waitTime().toNanos();

            do {
                RedisLease lease = tryAcquire(request);
                if (lease != null) {
                    activeLeases.put(lease.ownerValue(), lease);
                    return lease;
                }
                if (!request.waitPolicy().unbounded() && System.nanoTime() >= deadlineNanos) {
                    throw new LockAcquisitionTimeoutException("Timed out acquiring Redis lock for key " + request.key().value());
                }
                Thread.sleep(request.waitPolicy().unbounded()
                    ? 25L
                    : Math.min(25L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()))));
            } while (true);
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
                if (failure == null) {
                    failure = new LockBackendException("Failed to close Redis session " + sessionId, exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }

            if (failure != null) {
                throw failure;
            }
        }

        private RedisLease tryAcquire(LockRequest request) {
            long fence = switch (request.mode()) {
                case MUTEX -> tryAcquireMutex(request.key().value());
                case READ -> tryAcquireRead(request.key().value());
                case WRITE -> tryAcquireWrite(request.key().value());
            };
            if (fence <= 0) {
                return null;
            }

            return new RedisLease(
                request.key(),
                request.mode(),
                new FencingToken(fence),
                ownerValue(sessionId, fence),
                this
            );
        }

        private long tryAcquireMutex(String key) {
            try {
                Long result = commands.eval(
                    MUTEX_ACQUIRE_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{ownerKey(key, LockMode.MUTEX), fenceKey(key, LockMode.MUTEX)},
                    sessionId,
                    String.valueOf(configuration.leaseSeconds())
                );
                return result == null ? 0L : result;
            } catch (RuntimeException exception) {
                throw new LockBackendException("Failed to acquire Redis mutex for key " + key, exception);
            }
        }

        private long tryAcquireRead(String key) {
            try {
                Long result = commands.eval(
                    READ_ACQUIRE_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{ownerKey(key, LockMode.WRITE), readersKey(key), fenceKey(key, LockMode.READ)},
                    sessionId,
                    String.valueOf(configuration.leaseSeconds())
                );
                return result == null ? 0L : result;
            } catch (RuntimeException exception) {
                throw new LockBackendException("Failed to acquire Redis read lock for key " + key, exception);
            }
        }

        private long tryAcquireWrite(String key) {
            try {
                Long result = commands.eval(
                    WRITE_ACQUIRE_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{ownerKey(key, LockMode.WRITE), readersKey(key), fenceKey(key, LockMode.WRITE)},
                    sessionId,
                    String.valueOf(configuration.leaseSeconds())
                );
                return result == null ? 0L : result;
            } catch (RuntimeException exception) {
                throw new LockBackendException("Failed to acquire Redis write lock for key " + key, exception);
            }
        }

        private ScheduledFuture<?> scheduleRenewal() {
            long periodMillis = Math.max(250L, TimeUnit.SECONDS.toMillis(configuration.leaseSeconds()) / 3L);
            return renewalExecutor.scheduleAtFixedRate(this::renew, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        }

        private void renew() {
            if (closed.get()) {
                return;
            }
            if (!renewSessionKey()) {
                valid.set(false);
                for (RedisLease lease : activeLeases.values()) {
                    lease.markLost();
                }
                renewalTask.cancel(false);
                return;
            }
            for (RedisLease lease : activeLeases.values()) {
                lease.refresh();
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

        private void ensureActive() {
            if (closed.get()) {
                throw new IllegalStateException("Redis session is already closed");
            }
            if (!valid.get()) {
                throw new LockSessionLostException("Redis session lost: " + sessionId);
            }
        }
    }

    private final class RedisLease implements BackendLockLease {

        private final LockKey key;
        private final LockMode mode;
        private final FencingToken fencingToken;
        private final String ownerValue;
        private final RedisBackendSession session;
        private final AtomicReference<LeaseState> state = new AtomicReference<>(LeaseState.ACTIVE);
        private final AtomicBoolean lostReleaseReported = new AtomicBoolean();

        private RedisLease(
            LockKey key,
            LockMode mode,
            FencingToken fencingToken,
            String ownerValue,
            RedisBackendSession session
        ) {
            this.key = key;
            this.mode = mode;
            this.fencingToken = fencingToken;
            this.ownerValue = ownerValue;
            this.session = session;
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
            if (current == LeaseState.LOST && lostReleaseReported.get()) {
                return;
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
                        HASH_RELEASE_SCRIPT,
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
                    state.compareAndSet(LeaseState.ACTIVE, LeaseState.LOST);
                    session.forgetLease(this);
                    lostReleaseReported.set(true);
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
                        String.valueOf(configuration.leaseSeconds())
                    );
                    case READ -> commands.eval(
                        HASH_REFRESH_SCRIPT,
                        ScriptOutputType.INTEGER,
                        new String[]{readersKey(key.value())},
                        ownerValue,
                        String.valueOf(configuration.leaseSeconds())
                    );
                    case WRITE -> commands.eval(
                        VALUE_REFRESH_SCRIPT,
                        ScriptOutputType.INTEGER,
                        new String[]{ownerKey(key.value(), LockMode.WRITE)},
                        ownerValue,
                        String.valueOf(configuration.leaseSeconds())
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
                    case READ -> Boolean.TRUE.equals(commands.hexists(readersKey(key.value()), ownerValue));
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
}
