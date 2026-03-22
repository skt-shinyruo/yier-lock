package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.runtime.LockRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

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
}
