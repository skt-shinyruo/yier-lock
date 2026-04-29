package com.mycorp.distributedlock.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.TimeUnit;

final class RedisTestSupport {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

    private RedisTestSupport() {
    }

    static RunningRedis startRedis() throws Exception {
        GenericContainer<?> container = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(6379);
        container.start();
        awaitReady(redisUri(container));
        RunningRedis redis = new RunningRedis(container);
        redis.flushAll();
        return redis;
    }

    static final class RunningRedis implements AutoCloseable {
        private final GenericContainer<?> container;
        private RedisClient redisClient;
        private StatefulRedisConnection<String, String> connection;
        private RedisCommands<String, String> commands;

        private RunningRedis(GenericContainer<?> container) {
            this.container = container;
            connectClient();
        }

        RedisBackendConfiguration configuration(long leaseSeconds) {
            return new RedisBackendConfiguration(redisUri(), leaseSeconds);
        }

        RedisLockBackend newBackend(long leaseSeconds) {
            return new RedisLockBackend(configuration(leaseSeconds));
        }

        RedisCommands<String, String> commands() {
            return commands;
        }

        void flushAll() {
            commands.flushall();
        }

        void stopContainer() {
            container.stop();
        }

        void startContainer() throws InterruptedException {
            closeClient();
            container.start();
            awaitReady(redisUri());
            connectClient();
        }

        String redisUri() {
            return RedisTestSupport.redisUri(container);
        }

        @Override
        public void close() throws Exception {
            RuntimeException closeFailure = null;
            try {
                closeClient();
            } catch (RuntimeException exception) {
                closeFailure = exception;
            }
            try {
                container.close();
            } catch (RuntimeException exception) {
                if (closeFailure == null) {
                    closeFailure = exception;
                } else {
                    closeFailure.addSuppressed(exception);
                }
            }
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        private void connectClient() {
            this.redisClient = RedisClient.create(redisUri());
            this.connection = redisClient.connect();
            this.commands = connection.sync();
        }

        private void closeClient() {
            if (connection != null) {
                connection.close();
                connection = null;
                commands = null;
            }
            if (redisClient != null) {
                redisClient.shutdown();
                redisClient = null;
            }
        }
    }

    private static String redisUri(GenericContainer<?> container) {
        return "redis://%s:%d".formatted(container.getHost(), container.getMappedPort(6379));
    }

    private static void awaitReady(String redisUri) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        RuntimeException lastFailure = null;
        while (System.nanoTime() < deadline) {
            RedisClient client = null;
            StatefulRedisConnection<String, String> testConnection = null;
            try {
                client = RedisClient.create(redisUri);
                testConnection = client.connect();
                if ("PONG".equals(testConnection.sync().ping())) {
                    return;
                }
            } catch (RuntimeException exception) {
                lastFailure = exception;
            } finally {
                if (testConnection != null) {
                    testConnection.close();
                }
                if (client != null) {
                    client.shutdown();
                }
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException("Redis container did not become ready", lastFailure);
    }
}
