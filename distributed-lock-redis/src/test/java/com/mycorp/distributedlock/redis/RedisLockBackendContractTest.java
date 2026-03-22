package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.testkit.LockManagerContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

class RedisLockBackendContractTest extends LockManagerContract {

    private static String containerId;
    private static int redisPort;

    @BeforeAll
    static void startRedis() throws Exception {
        containerId = run("docker", "run", "-d", "-P", "redis:7-alpine").trim();
        String portOutput = run("docker", "port", containerId, "6379/tcp").trim();
        redisPort = Integer.parseInt(portOutput.substring(portOutput.lastIndexOf(':') + 1));
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (containerId != null && !containerId.isBlank()) {
            run("docker", "rm", "-f", containerId);
        }
    }

    @Override
    protected LockRuntime createRuntime() {
        String redisUri = "redis://127.0.0.1:%d".formatted(redisPort);
        return LockRuntimeBuilder.create()
            .backend("redis")
            .backendModules(java.util.List.of(new RedisBackendModule(redisUri)))
            .build();
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
