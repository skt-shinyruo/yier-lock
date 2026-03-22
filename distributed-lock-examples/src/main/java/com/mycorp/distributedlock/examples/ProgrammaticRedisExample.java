package com.mycorp.distributedlock.examples;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;

import java.time.Duration;
import java.util.Map;

public final class ProgrammaticRedisExample {

    private ProgrammaticRedisExample() {
    }

    public static void main(String[] args) throws Exception {
        try (LockRuntime runtime = LockRuntimeBuilder.create()
            .backend("redis")
            .configuration(Map.of(
                "uri", "redis://127.0.0.1:6379",
                "lease-seconds", 30L
            ))
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
