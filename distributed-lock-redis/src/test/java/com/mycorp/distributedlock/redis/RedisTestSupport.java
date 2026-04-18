package com.mycorp.distributedlock.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.concurrent.TimeUnit;

final class RedisTestSupport {

    private RedisTestSupport() {
    }

    static RunningRedis startRedis() throws Exception {
        String containerId = run("docker", "run", "-d", "-P", "redis:7-alpine").trim();
        String portOutput = run("docker", "port", containerId, "6379/tcp").trim();
        int redisPort = Integer.parseInt(portOutput.substring(portOutput.lastIndexOf(':') + 1));
        awaitReady("redis://127.0.0.1:%d".formatted(redisPort));
        RunningRedis redis = new RunningRedis(containerId, redisPort);
        redis.flushAll();
        return redis;
    }

    static final class RunningRedis implements AutoCloseable {
        private final String containerId;
        private final int redisPort;
        private final RedisClient redisClient;
        private final StatefulRedisConnection<String, String> connection;
        private final RedisCommands<String, String> commands;

        private RunningRedis(String containerId, int redisPort) {
            this.containerId = containerId;
            this.redisPort = redisPort;
            this.redisClient = RedisClient.create(redisUri());
            this.connection = redisClient.connect();
            this.commands = connection.sync();
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

        void stopContainer() throws Exception {
            run("docker", "stop", containerId);
        }

        void startContainer() throws Exception {
            run("docker", "start", containerId);
            awaitReady(redisUri());
        }

        String redisUri() {
            return "redis://127.0.0.1:%d".formatted(redisPort);
        }

        @Override
        public void close() throws Exception {
            RuntimeException closeFailure = null;
            try {
                connection.close();
            } catch (RuntimeException exception) {
                closeFailure = exception;
            }
            try {
                redisClient.shutdown();
            } catch (RuntimeException exception) {
                if (closeFailure == null) {
                    closeFailure = exception;
                } else {
                    closeFailure.addSuppressed(exception);
                }
            }
            try {
                run("docker", "rm", "-f", containerId);
            } catch (Exception exception) {
                if (closeFailure == null) {
                    throw exception;
                }
                closeFailure.addSuppressed(exception);
            }
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
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

    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Command failed: " + String.join(" ", command) + "\n" + output);
        }
        return output;
    }
}
