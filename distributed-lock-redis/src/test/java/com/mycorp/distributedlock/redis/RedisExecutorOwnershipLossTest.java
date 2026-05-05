package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.core.client.DefaultLockClient;
import com.mycorp.distributedlock.core.client.DefaultSynchronousLockExecutor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("redis-integration")
class RedisExecutorOwnershipLossTest {

    @Test
    void withLockShouldFailIfOwnershipIsLostDuringAction() throws Exception {
            try (RedisTestSupport.RunningRedis redis = RedisTestSupport.startRedis();
             RedisLockBackend backend = redis.newBackend(1L)) {
            SynchronousLockExecutor executor = new DefaultSynchronousLockExecutor(new DefaultLockClient(
                backend,
                new RedisBackendProvider().descriptor().behavior()
            ));

            assertThatThrownBy(() -> executor.withLock(request("redis:executor-loss"), lease -> {
                redis.stopContainer();
                waitUntilLost(lease);
                return "ok";
            }))
                .isInstanceOf(LockOwnershipLostException.class)
                .hasMessageContaining("redis:executor-loss");
        }
    }

    private static LockRequest request(String key) {
        return new LockRequest(
            new LockKey(key),
            LockMode.MUTEX,
            WaitPolicy.timed(Duration.ofSeconds(1))
        );
    }

    private static void waitUntilLost(LockLease lease) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (lease.state() == LeaseState.LOST) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("Redis lease was not marked lost");
    }
}
