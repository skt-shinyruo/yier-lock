package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class SessionLifecycleContract extends LockClientContract {

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

    private boolean tryAcquire(String key, LockMode mode, Duration waitTime) throws Exception {
        try (LockSession contender = runtime.lockClient().openSession();
             LockLease ignored = contender.acquire(request(key, mode, waitTime))) {
            return true;
        } catch (LockAcquisitionTimeoutException exception) {
            return false;
        }
    }
}
