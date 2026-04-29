package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class MutexLockContract extends LockClientContract {

    @Test
    void mutexShouldExcludeConcurrentSessions() throws Exception {
        runtime = createRuntime();
        try (LockSession holder = runtime.lockClient().openSession();
             LockLease ignored = holder.acquire(request("inventory:mutex", LockMode.MUTEX, Duration.ofSeconds(1)))) {
            assertThat(executor.submit(() -> tryAcquire("inventory:mutex", LockMode.MUTEX, Duration.ofMillis(100))).get()).isFalse();
        }
    }

    @Test
    void sameSessionShouldAllowDifferentKeys() throws Exception {
        runtime = createRuntime();
        try (LockSession session = runtime.lockClient().openSession();
             LockLease first = session.acquire(request("inventory:first", LockMode.MUTEX, Duration.ofSeconds(1)));
             LockLease second = session.acquire(request("inventory:second", LockMode.MUTEX, Duration.ofSeconds(1)))) {
            assertThat(first.key()).isEqualTo(new LockKey("inventory:first"));
            assertThat(second.key()).isEqualTo(new LockKey("inventory:second"));
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
