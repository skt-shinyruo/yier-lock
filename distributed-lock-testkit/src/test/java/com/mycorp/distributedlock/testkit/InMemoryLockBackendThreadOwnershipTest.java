package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.testkit.support.InMemoryLockBackend;
import com.mycorp.distributedlock.testkit.support.InMemoryBackendModule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryLockBackendThreadOwnershipTest extends LockClientContract {

    @Override
    protected LockRuntime createRuntime() {
        return LockRuntimeBuilder.create()
            .backend("in-memory")
            .backendModules(java.util.List.of(new InMemoryBackendModule("in-memory")))
            .build();
    }

    @Test
    void leaseCanBeReleasedFromADifferentThread() throws Exception {
        runtime = createRuntime();
        ExecutorService releaseExecutor = Executors.newSingleThreadExecutor();
        try (LockSession session = runtime.lockClient().openSession()) {
            LockLease lease = session.acquire(request("in-memory:cross-thread-release", LockMode.MUTEX, Duration.ofSeconds(1)));
            Future<?> release = releaseExecutor.submit(lease::release);
            release.get(1, TimeUnit.SECONDS);

            assertThat(executor.submit(() -> tryAcquire("in-memory:cross-thread-release", LockMode.MUTEX, Duration.ofMillis(200))).get())
                .isTrue();
        } finally {
            releaseExecutor.shutdownNow();
        }
    }

    @Test
    void sessionCanBeClosedFromADifferentThread() throws Exception {
        runtime = createRuntime();
        ExecutorService closeExecutor = Executors.newSingleThreadExecutor();
        LockSession session = runtime.lockClient().openSession();
        session.acquire(request("in-memory:cross-thread-close", LockMode.MUTEX, Duration.ofSeconds(1)));
        try {
            Future<?> close = closeExecutor.submit(() -> {
                session.close();
                return null;
            });
            close.get(1, TimeUnit.SECONDS);

            assertThat(executor.submit(() -> tryAcquire("in-memory:cross-thread-close", LockMode.MUTEX, Duration.ofMillis(200))).get())
                .isTrue();
        } finally {
            closeExecutor.shutdownNow();
        }
    }

    @Test
    void backendSessionCloseReleasesActiveLeasesFromADifferentThread() throws Exception {
        InMemoryLockBackend backend = new InMemoryLockBackend();
        BackendSession session = backend.openSession();
        session.acquire(backendRequest("in-memory:backend-cross-thread-close", LockMode.MUTEX, Duration.ofSeconds(1)));
        ExecutorService closeExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<?> close = closeExecutor.submit(() -> {
                session.close();
                return null;
            });
            close.get(1, TimeUnit.SECONDS);

            try (BackendSession contender = backend.openSession();
                 BackendLockLease ignored = contender.acquire(backendRequest("in-memory:backend-cross-thread-close", LockMode.MUTEX, Duration.ofMillis(200)))) {
                assertThat(ignored.isValid()).isTrue();
            }
        } finally {
            closeExecutor.shutdownNow();
        }
    }

    private boolean tryAcquire(String key, LockMode mode, Duration waitTime) throws Exception {
        try (LockSession contender = runtime.lockClient().openSession();
             LockLease ignored = contender.acquire(request(key, mode, waitTime))) {
            return true;
        } catch (LockAcquisitionTimeoutException exception) {
            return false;
        }
    }

    private static LockRequest backendRequest(String key, LockMode mode, Duration waitTime) {
        return new LockRequest(new LockKey(key), mode, WaitPolicy.timed(waitTime));
    }
}
