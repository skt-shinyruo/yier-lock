package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class ReadWriteLockContract extends LockClientContract {

    @Test
    void readersShouldShareTheSameKeyAcrossSessions() throws Exception {
        runtime = createRuntime();
        try (LockSession first = runtime.lockClient().openSession();
             LockLease ignored = first.acquire(request("inventory:rw", LockMode.READ, Duration.ofSeconds(1)))) {
            assertThat(executor.submit(() -> tryAcquire("inventory:rw", LockMode.READ, Duration.ofMillis(200))).get())
                .isTrue();
        }
    }

    @Test
    void writerShouldTimeOutWhileReaderIsHeld() throws Exception {
        runtime = createRuntime();
        try (LockSession reader = runtime.lockClient().openSession();
             LockLease ignored = reader.acquire(request("inventory:rw", LockMode.READ, Duration.ofSeconds(1)))) {
            assertThat(executor.submit(() -> tryAcquire("inventory:rw", LockMode.WRITE, Duration.ofMillis(100))).get())
                .isFalse();
        }
    }

    @Test
    void mutexShouldTimeOutWhileReaderIsHeld() throws Exception {
        runtime = createRuntime();
        try (LockSession reader = runtime.lockClient().openSession();
             LockLease ignored = reader.acquire(request("inventory:cross-mode", LockMode.READ, Duration.ofSeconds(1)))) {
            assertThat(executor.submit(() -> tryAcquire("inventory:cross-mode", LockMode.MUTEX, Duration.ofMillis(100))).get())
                .isFalse();
        }
    }

    @Test
    void readerShouldTimeOutWhileWriterIsHeld() throws Exception {
        runtime = createRuntime();
        try (LockSession writer = runtime.lockClient().openSession();
             LockLease ignored = writer.acquire(request("inventory:rw", LockMode.WRITE, Duration.ofSeconds(1)))) {
            assertThat(executor.submit(() -> tryAcquire("inventory:rw", LockMode.READ, Duration.ofMillis(100))).get())
                .isFalse();
        }
    }

    @Test
    void mutexShouldTimeOutWhileWriterIsHeld() throws Exception {
        runtime = createRuntime();
        try (LockSession writer = runtime.lockClient().openSession();
             LockLease ignored = writer.acquire(request("inventory:cross-mode", LockMode.WRITE, Duration.ofSeconds(1)))) {
            assertThat(executor.submit(() -> tryAcquire("inventory:cross-mode", LockMode.MUTEX, Duration.ofMillis(100))).get())
                .isFalse();
        }
    }

    @Test
    void readerShouldTimeOutWhileMutexIsHeld() throws Exception {
        runtime = createRuntime();
        try (LockSession mutex = runtime.lockClient().openSession();
             LockLease ignored = mutex.acquire(request("inventory:cross-mode", LockMode.MUTEX, Duration.ofSeconds(1)))) {
            assertThat(executor.submit(() -> tryAcquire("inventory:cross-mode", LockMode.READ, Duration.ofMillis(100))).get())
                .isFalse();
        }
    }

    @Test
    void writerShouldTimeOutWhileMutexIsHeld() throws Exception {
        runtime = createRuntime();
        try (LockSession mutex = runtime.lockClient().openSession();
             LockLease ignored = mutex.acquire(request("inventory:cross-mode", LockMode.MUTEX, Duration.ofSeconds(1)))) {
            assertThat(executor.submit(() -> tryAcquire("inventory:cross-mode", LockMode.WRITE, Duration.ofMillis(100))).get())
                .isFalse();
        }
    }

    private boolean tryAcquire(String key, LockMode mode, Duration waitTime) throws Exception {
        return tryAcquire(key, mode, WaitPolicy.timed(waitTime));
    }

    private boolean tryAcquire(String key, LockMode mode, WaitPolicy waitPolicy) throws Exception {
        try (LockSession contender = runtime.lockClient().openSession();
             LockLease ignored = contender.acquire(request(key, mode, waitPolicy))) {
            return true;
        } catch (LockAcquisitionTimeoutException exception) {
            return false;
        }
    }
}
