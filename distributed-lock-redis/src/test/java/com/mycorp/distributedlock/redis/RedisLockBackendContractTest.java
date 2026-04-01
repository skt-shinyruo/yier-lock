package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.testkit.LockManagerContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;

class RedisLockBackendContractTest extends LockManagerContract {

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
}
