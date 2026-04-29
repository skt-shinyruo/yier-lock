package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("redis-integration")
class RedisLeaseRenewalTest {

    private static RedisTestSupport.RunningRedis redis;

    @BeforeAll
    static void startRedis() throws Exception {
        redis = RedisTestSupport.startRedis();
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (redis != null) {
            redis.close();
        }
    }

    @BeforeEach
    void resetRedis() {
        redis.flushAll();
    }

    @Test
    void leaseShouldRemainValidPastBaseTtlWhenHeld() throws Exception {
        try (RedisLockBackend backend = redis.newBackend(1L);
             BackendSession session = backend.openSession();
             BackendLockLease lease = session.acquire(new LockRequest(
                 new LockKey("renew:1"),
                 LockMode.MUTEX,
                 WaitPolicy.indefinite()
             ))) {

            Thread.sleep(Duration.ofSeconds(3).toMillis());

            assertThat(lease.isValid()).isTrue();
        }
    }
}
