package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.testkit.support.FencedResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public abstract class LockClientContract {

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
    void mutexShouldExcludeConcurrentSessions() throws Exception {
        runtime = createRuntime();
        try (LockSession holder = runtime.lockClient().openSession();
             LockLease ignored = holder.acquire(request("inventory:mutex", LockMode.MUTEX, Duration.ofSeconds(1)))) {
            assertThat(executor.submit(() -> tryAcquire("inventory:mutex", LockMode.MUTEX, Duration.ofMillis(100))).get()).isFalse();
        }
    }

    @Test
    void closingSessionShouldReleaseUnclosedLease() throws Exception {
        runtime = createRuntime();
        LockSession holder = runtime.lockClient().openSession();

        try {
            holder.acquire(request("inventory:session-close", LockMode.MUTEX, Duration.ofSeconds(1)));
        } finally {
            holder.close();
        }

        assertThat(executor.submit(() -> tryAcquire("inventory:session-close", LockMode.MUTEX, Duration.ofMillis(200))).get())
            .isTrue();
    }

    @Test
    void fencingTokenShouldIncreaseAcrossSequentialLeases() throws Exception {
        runtime = createRuntime();
        try (LockSession session = runtime.lockClient().openSession()) {
            long first;
            try (LockLease lease = session.acquire(request("inventory:1", LockMode.MUTEX, Duration.ofSeconds(1)))) {
                first = lease.fencingToken().value();
            }
            try (LockLease lease = session.acquire(request("inventory:1", LockMode.MUTEX, Duration.ofSeconds(1)))) {
                assertThat(lease.fencingToken().value()).isGreaterThan(first);
            }
        }
    }

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

    @Test
    void fencingTokenShouldIncreaseAcrossModesForTheSameKey() throws Exception {
        runtime = createRuntime();
        try (LockSession session = runtime.lockClient().openSession()) {
            long first;
            try (LockLease lease = session.acquire(request("inventory:fence", LockMode.READ, Duration.ofSeconds(1)))) {
                first = lease.fencingToken().value();
            }
            try (LockLease lease = session.acquire(request("inventory:fence", LockMode.WRITE, Duration.ofSeconds(1)))) {
                assertThat(lease.fencingToken().value()).isGreaterThan(first);
            }
        }
    }

    @Test
    void staleTokenShouldBeRejectedByGuardedResource() {
        FencedResource resource = new FencedResource();

        resource.write(new FencingToken(2L), "new");

        assertThatThrownBy(() -> resource.write(new FencingToken(1L), "old"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("stale fencing token");
    }

    protected LockRequest request(String key, LockMode mode, Duration waitTime) {
        return new LockRequest(
            new LockKey(key),
            mode,
            WaitPolicy.timed(waitTime)
        );
    }

    private boolean tryAcquire(String key, LockMode mode, Duration waitTime) throws Exception {
        try (LockSession contender = runtime.lockClient().openSession();
             LockLease ignored = contender.acquire(request(key, mode, waitTime))) {
            return true;
        } catch (LockAcquisitionTimeoutException exception) {
            return false;
        }
    }
}
