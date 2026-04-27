package com.mycorp.distributedlock.examples;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.redis.RedisBackendConfiguration;
import com.mycorp.distributedlock.redis.RedisBackendModule;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;

import java.time.Duration;
import java.util.List;

public final class ProgrammaticRedisExample {

    private ProgrammaticRedisExample() {
    }

    public static void main(String[] args) throws Exception {
        try (LockRuntime runtime = LockRuntimeBuilder.create()
            .backend("redis")
            .backendModules(List.of(new RedisBackendModule(new RedisBackendConfiguration(
                "redis://127.0.0.1:6379",
                30L
            ))))
            .build()) {
            String result = runtime.synchronousLockExecutor().withLock(
                sampleRequest("example:redis:order-42"),
                lease -> "Redis lease acquired with fencing token " + lease.fencingToken().value()
            );
            System.out.println(result);
        }
    }

    private static LockRequest sampleRequest(String key) {
        return new LockRequest(
            new LockKey(key),
            LockMode.MUTEX,
            WaitPolicy.timed(Duration.ofSeconds(2))
        );
    }
}
