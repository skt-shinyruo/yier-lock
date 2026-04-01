package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisOwnershipLossTest {

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
    void releaseShouldFailExplicitlyAfterTokenRemoval() throws Exception {
        try (RedisLockBackend backend = redis.newBackend(30L)) {
            BackendLockLease lease = backend.acquire(
                new LockResource("lost:1"),
                LockMode.MUTEX,
                WaitPolicy.indefinite()
            );
            redis.commands().del("lost:1");

            assertThatThrownBy(lease::release)
                .isInstanceOf(LockOwnershipLostException.class);
        }
    }
}
