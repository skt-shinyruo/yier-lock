package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeasePolicy;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.SessionPolicy;
import com.mycorp.distributedlock.api.SessionRequest;
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
        try (LockSession holder = runtime.lockClient().openSession(defaultSession());
             LockLease ignored = holder.acquire(sampleRequest("inventory:mutex"))) {
            assertThat(executor.submit(() -> tryAcquire("inventory:mutex")).get()).isFalse();
        }
    }

    @Test
    void fencingTokenShouldIncreaseAcrossSequentialLeases() throws Exception {
        runtime = createRuntime();
        try (LockSession session = runtime.lockClient().openSession(defaultSession())) {
            long first;
            try (LockLease lease = session.acquire(sampleRequest("inventory:1"))) {
                first = lease.fencingToken().value();
            }
            try (LockLease lease = session.acquire(sampleRequest("inventory:1"))) {
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

    protected SessionRequest defaultSession() {
        return new SessionRequest(SessionPolicy.MANUAL_CLOSE);
    }

    protected LockRequest sampleRequest(String key) {
        return new LockRequest(
            new LockKey(key),
            LockMode.MUTEX,
            WaitPolicy.timed(Duration.ofSeconds(1)),
            LeasePolicy.RELEASE_ON_CLOSE
        );
    }

    private boolean tryAcquire(String key) throws Exception {
        try (LockSession contender = runtime.lockClient().openSession(defaultSession());
             LockLease ignored = contender.acquire(new LockRequest(
                 new LockKey(key),
                 LockMode.MUTEX,
                 WaitPolicy.timed(Duration.ofMillis(100)),
                 LeasePolicy.RELEASE_ON_CLOSE
             ))) {
            return true;
        } catch (LockAcquisitionTimeoutException exception) {
            return false;
        }
    }
}
