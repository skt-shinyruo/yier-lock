package com.mycorp.distributedlock.benchmarks;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.MutexLock;
import org.openjdk.jmh.infra.Blackhole;

import java.time.Duration;

public final class BenchmarkWorkloads {

    private BenchmarkWorkloads() {
    }

    public static void mutexLifecycle(LockManager manager, String key, Blackhole blackhole) throws InterruptedException {
        MutexLock lock = manager.mutex(key);
        lock.lock();
        try (lock) {
            blackhole.consume(lock.key());
            blackhole.consume(lock.isHeldByCurrentThread());
            Blackhole.consumeCPU(64);
        }
    }

    public static void successfulTryLock(
        LockManager manager,
        String key,
        Duration wait,
        Blackhole blackhole
    ) throws InterruptedException {
        MutexLock lock = manager.mutex(key);
        boolean acquired = lock.tryLock(wait);
        blackhole.consume(acquired);
        if (!acquired) {
            throw new IllegalStateException("Expected successful tryLock for key " + key);
        }

        try (lock) {
            blackhole.consume(lock.key());
            blackhole.consume(lock.isHeldByCurrentThread());
            Blackhole.consumeCPU(64);
        }
    }

    public static void contendedTryLock(
        LockManager manager,
        String key,
        Duration wait,
        Blackhole blackhole
    ) throws InterruptedException {
        MutexLock lock = manager.mutex(key);
        boolean acquired = lock.tryLock(wait);
        blackhole.consume(acquired);
        if (!acquired) {
            return;
        }

        try (lock) {
            blackhole.consume(lock.key());
            blackhole.consume(lock.isHeldByCurrentThread());
            Blackhole.consumeCPU(64);
        }
    }
}
