package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.spi.BackendLease;
import com.mycorp.distributedlock.spi.BackendSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("redis-integration")
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
    void redisShouldInvalidateLeaseAfterTokenDeletion() throws Exception {
        try (RedisLockBackend backend = redis.newBackend(30L)) {
            BackendSession session = backend.openSession();
            BackendLease lease = session.acquire(new LockRequest(
                new LockKey("lost:1"),
                LockMode.MUTEX,
                WaitPolicy.indefinite()
            ));
            try {
                redis.commands().del(RedisLockBackend.ownerKey("lost:1", LockMode.MUTEX));

                assertThat(lease.isValid()).isFalse();
                assertThatThrownBy(lease::release)
                    .isInstanceOfSatisfying(LockOwnershipLostException.class, exception -> {
                        assertThat(exception.context().backendId()).isEqualTo("redis");
                        assertThat(exception.context().key()).isEqualTo(new LockKey("lost:1"));
                        assertThat(exception.context().mode()).isEqualTo(LockMode.MUTEX);
                    });
            } finally {
                try {
                    lease.close();
                } catch (RuntimeException ignored) {
                }
                try {
                    session.close();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    @Test
    void redisBackendExceptionShouldIncludeLeaseContext() throws Exception {
        RedisLockBackend backend = redis.newBackend(30L);
        BackendSession session = backend.openSession();
        BackendLease lease = session.acquire(new LockRequest(
            new LockKey("redis:backend:context"),
            LockMode.MUTEX,
            WaitPolicy.indefinite()
        ));
        try {
            backend.close();

            assertThatThrownBy(lease::isValid)
                .isInstanceOfSatisfying(LockBackendException.class, exception -> {
                    assertThat(exception.context().backendId()).isEqualTo("redis");
                    assertThat(exception.context().key()).isEqualTo(new LockKey("redis:backend:context"));
                    assertThat(exception.context().mode()).isEqualTo(LockMode.MUTEX);
                });
        } finally {
            try {
                lease.close();
            } catch (RuntimeException ignored) {
            }
            try {
                session.close();
            } catch (RuntimeException ignored) {
            }
            try {
                backend.close();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
