package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.core.client.DefaultLockClient;
import com.mycorp.distributedlock.core.client.DefaultSynchronousLockExecutor;
import com.mycorp.distributedlock.core.client.SupportedLockModes;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisExecutorOwnershipLossTest {

    @Test
    void withLockShouldFailIfOwnershipIsLostDuringAction() throws Exception {
        try (RedisTestSupport.RunningRedis redis = RedisTestSupport.startRedis();
             RedisLockBackend backend = redis.newBackend(1L)) {
            SynchronousLockExecutor executor = new DefaultSynchronousLockExecutor(new DefaultLockClient(
                backend,
                new SupportedLockModes(true, true, true)
            ));

            assertThatThrownBy(() -> executor.withLock(request("redis:executor-loss"), lease -> {
                redis.stopContainer();
                Thread.sleep(Duration.ofSeconds(2).toMillis());
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
}
