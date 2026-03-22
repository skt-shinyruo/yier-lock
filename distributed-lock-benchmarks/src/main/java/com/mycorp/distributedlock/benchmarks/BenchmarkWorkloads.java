package com.mycorp.distributedlock.benchmarks;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.runtime.LockRuntime;
import org.openjdk.jmh.infra.Blackhole;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

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

    public static void readSection(LockManager manager, String key, Blackhole blackhole) throws InterruptedException {
        MutexLock lock = manager.readWrite(key).readLock();
        lock.lock();
        try (lock) {
            blackhole.consume(lock.key());
            blackhole.consume(lock.isHeldByCurrentThread());
            Blackhole.consumeCPU(64);
        }
    }

    public static void writeSection(LockManager manager, String key, Blackhole blackhole) throws InterruptedException {
        MutexLock lock = manager.readWrite(key).writeLock();
        lock.lock();
        try (lock) {
            blackhole.consume(lock.key());
            blackhole.consume(lock.isHeldByCurrentThread());
            Blackhole.consumeCPU(64);
        }
    }

    public static void runtimeLifecycle(
        Supplier<LockRuntime> runtimeFactory,
        String backend,
        Blackhole blackhole
    ) throws Exception {
        Objects.requireNonNull(runtimeFactory, "runtimeFactory");
        Objects.requireNonNull(backend, "backend");

        try (LockRuntime runtime = runtimeFactory.get()) {
            LockManager manager = runtime.lockManager();
            blackhole.consume(manager);
            blackhole.consume(manager.mutex(BenchmarkKeys.unique("runtime-lifecycle-mutex", backend, Thread.currentThread().getId())));
            blackhole.consume(manager.readWrite(BenchmarkKeys.unique("runtime-lifecycle-rw", backend, Thread.currentThread().getId())));
            Blackhole.consumeCPU(64);
        }
    }
}
