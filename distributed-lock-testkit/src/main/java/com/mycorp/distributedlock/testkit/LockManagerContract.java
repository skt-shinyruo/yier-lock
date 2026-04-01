package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.api.ReadWriteLock;
import com.mycorp.distributedlock.runtime.LockRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public abstract class LockManagerContract {

    protected final ExecutorService executor = Executors.newSingleThreadExecutor();
    protected LockRuntime runtime;

    protected abstract LockRuntime createRuntime() throws Exception;

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void mutexShouldExcludeConcurrentHolders() throws Exception {
        runtime = createRuntime();
        LockManager manager = runtime.lockManager();
        MutexLock lock = manager.mutex("inventory:1");

        lock.lock();
        try {
            assertThat(executor.submit(() -> manager.mutex("inventory:1").tryLock(Duration.ofMillis(100))).get()).isFalse();
        } finally {
            lock.unlock();
        }
    }

    @Test
    void sameThreadReentryShouldSucceed() throws Exception {
        runtime = createRuntime();
        LockManager manager = runtime.lockManager();
        MutexLock first = manager.mutex("inventory:2");
        MutexLock second = manager.mutex("inventory:2");

        first.lock();
        try {
            assertThat(second.tryLock(Duration.ZERO)).isTrue();
        } finally {
            second.unlock();
            first.unlock();
        }
    }

    @Test
    void unlockFromDifferentThreadShouldFail() throws Exception {
        runtime = createRuntime();
        LockManager manager = runtime.lockManager();
        MutexLock lock = manager.mutex("inventory:3");
        lock.lock();
        try {
            assertThatThrownBy(() -> executor.submit(() -> manager.mutex("inventory:3").unlock()).get())
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalMonitorStateException.class);
        } finally {
            lock.unlock();
        }
    }

    @Test
    void closeShouldReleaseOneReentryLevel() throws Exception {
        runtime = createRuntime();
        LockManager manager = runtime.lockManager();
        MutexLock first = manager.mutex("inventory:4");
        MutexLock second = manager.mutex("inventory:4");
        MutexLock third = manager.mutex("inventory:4");

        first.lock();
        try {
            assertThat(second.tryLock(Duration.ZERO)).isTrue();
            assertThat(third.tryLock(Duration.ZERO)).isTrue();

            third.close();
            assertThat(executor.submit(() -> manager.mutex("inventory:4").tryLock(Duration.ofMillis(100))).get()).isFalse();

            second.unlock();
            assertThat(executor.submit(() -> manager.mutex("inventory:4").tryLock(Duration.ofMillis(100))).get()).isFalse();

            first.unlock();
            assertThat(executor.submit(() -> tryLockAndRelease(manager.mutex("inventory:4"), Duration.ofMillis(100))).get())
                .isTrue();
        } finally {
            if (third.isHeldByCurrentThread()) {
                third.unlock();
            }
            if (second.isHeldByCurrentThread()) {
                second.unlock();
            }
            if (first.isHeldByCurrentThread()) {
                first.unlock();
            }
        }
    }

    @Test
    void readLocksShouldShareButWriteLocksShouldExclude() throws Exception {
        runtime = createRuntime();
        LockManager manager = runtime.lockManager();
        ReadWriteLock readWrite = manager.readWrite("inventory:5");
        MutexLock readLock = readWrite.readLock();
        MutexLock writeLock = readWrite.writeLock();

        readLock.lock();
        try {
            assertThat(executor.submit(() -> tryLockAndRelease(manager.readWrite("inventory:5").readLock(), Duration.ofMillis(100))).get())
                .isTrue();
            assertThat(executor.submit(() -> tryLockAndRelease(manager.readWrite("inventory:5").writeLock(), Duration.ofMillis(100))).get())
                .isFalse();
        } finally {
            readLock.unlock();
        }

        writeLock.lock();
        try {
            assertThat(executor.submit(() -> tryLockAndRelease(manager.readWrite("inventory:5").readLock(), Duration.ofMillis(100))).get())
                .isFalse();
            assertThat(executor.submit(() -> tryLockAndRelease(manager.readWrite("inventory:5").writeLock(), Duration.ofMillis(100))).get())
                .isFalse();
        } finally {
            writeLock.unlock();
        }
    }

    private static boolean tryLockAndRelease(MutexLock lock, Duration waitTime) throws InterruptedException {
        boolean acquired = lock.tryLock(waitTime);
        if (acquired) {
            lock.unlock();
        }
        return acquired;
    }
}
