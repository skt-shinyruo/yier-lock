package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

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
        try (RedisLockBackend backend = redis.newBackend(1L)) {
            BackendLockLease lease = backend.acquire(
                new LockResource("renew:1"),
                LockMode.MUTEX,
                WaitPolicy.indefinite()
            );

            Thread.sleep(Duration.ofSeconds(3).toMillis());

            assertThat(lease.isValidForCurrentExecution()).isTrue();
            lease.release();
        }
    }
}
