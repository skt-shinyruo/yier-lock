package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public final class RedisLockBackend implements LockBackend {

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
        return new RedisBackendSession(this, nextSessionId());
    }

    @Override
    public void close() {
        renewalExecutor.shutdownNow();
        connection.close();
        redisClient.shutdown();
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
