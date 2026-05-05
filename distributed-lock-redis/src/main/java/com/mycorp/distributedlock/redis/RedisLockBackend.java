package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.LockMode;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import com.mycorp.distributedlock.spi.BackendClient;
import com.mycorp.distributedlock.spi.BackendSession;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class RedisLockBackend implements BackendClient {

    private final RedisBackendConfiguration configuration;
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final ScheduledExecutorService renewalExecutor;

    public RedisLockBackend(RedisBackendConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        RedisClient createdClient = RedisClient.create(this.configuration.redisUri());
        StatefulRedisConnection<String, String> createdConnection = null;
        ScheduledExecutorService createdRenewalExecutor = null;
        try {
            createdClient.setOptions(ClientOptions.builder()
                .autoReconnect(false)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
            createdConnection = createdClient.connect();
            RedisCommands<String, String> createdCommands = createdConnection.sync();
            createdRenewalExecutor = Executors.newScheduledThreadPool(
                this.configuration.effectiveRenewalPoolSize(),
                renewalThreadFactory()
            );

            this.redisClient = createdClient;
            this.connection = createdConnection;
            this.commands = createdCommands;
            this.renewalExecutor = createdRenewalExecutor;
        } catch (RuntimeException exception) {
            throw cleanupConstructionFailure(createdRenewalExecutor, createdConnection, createdClient, exception);
        }
    }

    @Override
    public BackendSession openSession() {
        return new RedisBackendSession(this, nextSessionId());
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            renewalExecutor.shutdownNow();
        } catch (RuntimeException exception) {
            failure = exception;
        }
        try {
            connection.close();
        } catch (RuntimeException exception) {
            failure = recordFailure(failure, exception);
        }
        try {
            redisClient.shutdown();
        } catch (RuntimeException exception) {
            failure = recordFailure(failure, exception);
        }
        if (failure != null) {
            throw failure;
        }
    }

    static String ownerKey(String key, LockMode mode) {
        return RedisLockKeys.forKey(key, RedisKeyStrategy.LEGACY).ownerKey(mode);
    }

    static String readersKey(String key) {
        return RedisLockKeys.forKey(key, RedisKeyStrategy.LEGACY).readersKey();
    }

    static String pendingWritersKey(String key) {
        return RedisLockKeys.forKey(key, RedisKeyStrategy.LEGACY).pendingWritersKey();
    }

    static String fenceKey(String key) {
        return RedisLockKeys.forKey(key, RedisKeyStrategy.LEGACY).fenceKey();
    }

    static String sessionKey(String sessionId) {
        return "session:%s".formatted(sessionId);
    }

    static final class RedisKeys {
        private final RedisLockKeys delegate;

        private RedisKeys(RedisLockKeys delegate) {
            this.delegate = delegate;
        }

        static RedisKeys forKey(String key, RedisKeyStrategy strategy) {
            return new RedisKeys(RedisLockKeys.forKey(key, strategy));
        }

        String ownerKey(LockMode mode) {
            return delegate.ownerKey(mode);
        }

        String readersKey() {
            return delegate.readersKey();
        }

        String pendingWritersKey() {
            return delegate.pendingWritersKey();
        }

        String fenceKey() {
            return delegate.fenceKey();
        }
    }

    RedisLockKeys keys(String key) {
        return RedisLockKeys.forKey(key, configuration.keyStrategy());
    }

    RedisCommands<String, String> commands() {
        return commands;
    }

    RedisBackendConfiguration configuration() {
        return configuration;
    }

    ScheduledExecutorService renewalExecutor() {
        return renewalExecutor;
    }

    private static RuntimeException cleanupConstructionFailure(
        ScheduledExecutorService renewalExecutor,
        StatefulRedisConnection<String, String> connection,
        RedisClient redisClient,
        RuntimeException failure
    ) {
        RuntimeException recorded = failure;
        if (renewalExecutor != null) {
            try {
                renewalExecutor.shutdownNow();
            } catch (RuntimeException exception) {
                recorded = recordFailure(recorded, exception);
            }
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (RuntimeException exception) {
                recorded = recordFailure(recorded, exception);
            }
        }
        if (redisClient != null) {
            try {
                redisClient.shutdown();
            } catch (RuntimeException exception) {
                recorded = recordFailure(recorded, exception);
            }
        }
        return recorded;
    }

    private static RuntimeException recordFailure(RuntimeException current, RuntimeException next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    private static ThreadFactory renewalThreadFactory() {
        AtomicInteger threadNumber = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "redis-lock-renewal-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    static String nextSessionId() {
        return UUID.randomUUID().toString();
    }

    static String ownerValue(String sessionId, long fence) {
        return sessionId + ":" + fence;
    }

    static String writerIntentValue(String sessionId, String attemptId) {
        return sessionId + ":writer:" + attemptId;
    }

    static long renewalPeriodMillis(long leaseMillis) {
        return Math.max(1L, leaseMillis / 5L);
    }

    static long initialRenewalDelayMillis(long leaseMillis, long periodMillis) {
        return Math.min(periodMillis, Math.max(1L, leaseMillis / 10L));
    }
}
