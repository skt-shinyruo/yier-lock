package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockSessionLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisSessionLossTest {

    @Test
    void renewalFailureShouldMarkSessionAndLeaseLost() throws Exception {
        try (RedisTestSupport.RunningRedis redis = RedisTestSupport.startRedis();
             RedisLockBackend backend = redis.newBackend(1L);
             BackendSession session = backend.openSession();
             BackendLockLease lease = session.acquire(new LockRequest(
                 new LockKey("redis:session-loss"),
                 LockMode.MUTEX,
                 WaitPolicy.indefinite()
             ))) {
            redis.stopContainer();

            waitUntilLost(session, lease);

            assertThat(session.state()).isEqualTo(SessionState.LOST);
            assertThat(lease.state()).isEqualTo(LeaseState.LOST);
            assertThatThrownBy(() -> session.acquire(new LockRequest(
                new LockKey("redis:session-loss"),
                LockMode.MUTEX,
                WaitPolicy.timed(Duration.ofMillis(50))
            ))).isInstanceOf(LockSessionLostException.class);
        }
    }

    private static void waitUntilLost(BackendSession session, BackendLockLease lease) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (session.state() == SessionState.LOST && lease.state() == LeaseState.LOST) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("session and lease did not transition to LOST");
    }
}
