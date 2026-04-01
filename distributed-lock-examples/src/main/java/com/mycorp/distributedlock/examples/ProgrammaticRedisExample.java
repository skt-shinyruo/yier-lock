package com.mycorp.distributedlock.examples;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.MutexLock;
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
            LockManager lockManager = runtime.lockManager();
            MutexLock lock = lockManager.mutex("example:redis:order-42");
            if (!lock.tryLock(Duration.ofSeconds(2))) {
                throw new IllegalStateException("Could not acquire Redis lock");
            }

            try (lock) {
                System.out.println("Redis lock acquired for " + lock.key());
            }
        }
    }
}
