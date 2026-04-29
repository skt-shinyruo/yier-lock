package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.LeasePolicy;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.WaitPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class FixedLeasePolicyContract extends LockClientContract {

    @Test
    void fixedLeaseRequestShouldAcquireLeaseWhenSupported() throws Exception {
        runtime = createRuntime();
        try (LockSession session = runtime.lockClient().openSession();
             LockLease lease = session.acquire(new LockRequest(
                 new LockKey("inventory:fixed-lease"),
                 LockMode.MUTEX,
                 WaitPolicy.tryOnce(),
                 LeasePolicy.fixed(Duration.ofSeconds(5))
             ))) {
            assertThat(lease.key()).isEqualTo(new LockKey("inventory:fixed-lease"));
            assertThat(lease.mode()).isEqualTo(LockMode.MUTEX);
            assertThat(lease.isValid()).isTrue();
        }
    }
}
