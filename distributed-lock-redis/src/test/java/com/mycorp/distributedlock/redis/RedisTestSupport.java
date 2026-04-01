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
        RunningRedis redis = new RunningRedis(containerId, redisPort);
        redis.awaitReady();
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

        String redisUri() {
            return "redis://127.0.0.1:%d".formatted(redisPort);
        }

        private void awaitReady() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            RuntimeException lastFailure = null;
            while (System.nanoTime() < deadline) {
                try {
                    if ("PONG".equals(commands.ping())) {
                        return;
                    }
                } catch (RuntimeException exception) {
                    lastFailure = exception;
                }
                Thread.sleep(100L);
            }
            throw new IllegalStateException("Redis container did not become ready", lastFailure);
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
