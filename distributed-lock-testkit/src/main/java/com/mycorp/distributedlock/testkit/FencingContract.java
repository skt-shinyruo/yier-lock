package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.testkit.support.FencedResource;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public abstract class FencingContract extends LockClientContract {

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
}
