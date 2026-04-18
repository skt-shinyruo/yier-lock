package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.testkit.LockClientContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedisLockBackendContractTest extends LockClientContract {

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

    @Override
    protected LockRuntime createRuntime() {
        return LockRuntimeBuilder.create()
            .backend("redis")
            .backendModules(List.of(new RedisBackendModule(redis.configuration(30L))))
            .build();
    }

    @Test
    void redisShouldIssueMonotonicFencingTokens() throws Exception {
        try (LockRuntime runtime = createRuntime()) {
            long first;
            try (LockSession session = runtime.lockClient().openSession();
                 LockLease lease = session.acquire(request("redis:fence", LockMode.MUTEX, Duration.ofSeconds(1)))) {
                first = lease.fencingToken().value();
            }
            try (LockSession session = runtime.lockClient().openSession();
                 LockLease lease = session.acquire(request("redis:fence", LockMode.MUTEX, Duration.ofSeconds(1)))) {
                assertThat(lease.fencingToken().value()).isGreaterThan(first);
            }
        }
    }

    @Test
    void redisShouldIssueMonotonicFencingTokensAcrossModes() throws Exception {
        try (LockRuntime runtime = createRuntime();
             LockSession session = runtime.lockClient().openSession()) {
            long first;
            try (LockLease lease = session.acquire(request("redis:fence:mode", LockMode.READ, Duration.ofSeconds(1)))) {
                first = lease.fencingToken().value();
            }
            try (LockLease lease = session.acquire(request("redis:fence:mode", LockMode.WRITE, Duration.ofSeconds(1)))) {
                assertThat(lease.fencingToken().value()).isGreaterThan(first);
            }
        }
    }
}
