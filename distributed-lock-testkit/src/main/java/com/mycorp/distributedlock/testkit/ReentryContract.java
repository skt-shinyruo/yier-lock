package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockReentryException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public abstract class ReentryContract extends LockClientContract {

    @Test
    void sameSessionShouldRejectSameKeyReentry() throws Exception {
        runtime = createRuntime();
        try (LockSession session = runtime.lockClient().openSession();
             LockLease ignored = session.acquire(request("inventory:reentry", LockMode.MUTEX, Duration.ofSeconds(1)))) {
            assertThatThrownBy(() -> session.acquire(request("inventory:reentry", LockMode.MUTEX, WaitPolicy.tryOnce())))
                .isInstanceOf(LockReentryException.class);
        }
    }
}
