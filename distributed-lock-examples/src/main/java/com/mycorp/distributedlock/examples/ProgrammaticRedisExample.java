package com.mycorp.distributedlock.examples;

import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.redis.RedisBackendConfiguration;
import com.mycorp.distributedlock.redis.RedisBackendProvider;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;

import java.time.Duration;

public final class ProgrammaticRedisExample {

    private ProgrammaticRedisExample() {
    }

    public static void main(String[] args) throws Exception {
        try (LockRuntime runtime = LockRuntimeBuilder.create()
            .backend("redis")
            .backendProvider(new RedisBackendProvider())
            .backendConfiguration(new RedisBackendConfiguration("redis://127.0.0.1:6379", 30L))
            .build()) {
            String result = runtime.synchronousLockExecutor().withLock(
                sampleRequest("example:redis:order-42"),
                lease -> "Redis lease acquired with fencing token " + lease.fencingToken().value()
            );
            System.out.println(result);
        }
    }

    private static LockRequest sampleRequest(String key) {
        return LockRequest.mutex(key, WaitPolicy.timed(Duration.ofSeconds(2)));
    }
}
