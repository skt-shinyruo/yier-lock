package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.testkit.FencingContract;
import com.mycorp.distributedlock.testkit.FixedLeasePolicyContract;
import com.mycorp.distributedlock.testkit.LeasePolicyContract;
import com.mycorp.distributedlock.testkit.MutexLockContract;
import com.mycorp.distributedlock.testkit.ReadWriteLockContract;
import com.mycorp.distributedlock.testkit.ReentryContract;
import com.mycorp.distributedlock.testkit.SessionLifecycleContract;
import com.mycorp.distributedlock.testkit.WaitPolicyContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

import java.util.List;

@Tag("redis-integration")
class RedisMutexLockContractTest extends MutexLockContract implements RedisContractRuntime {
    @Override
    public LockRuntime createRuntime() {
        return RedisContractRuntime.super.createRuntime();
    }
}

@Tag("redis-integration")
class RedisWaitPolicyContractTest extends WaitPolicyContract implements RedisContractRuntime {
    @Override
    public LockRuntime createRuntime() {
        return RedisContractRuntime.super.createRuntime();
    }
}

@Tag("redis-integration")
class RedisFencingContractTest extends FencingContract implements RedisContractRuntime {
    @Override
    public LockRuntime createRuntime() {
        return RedisContractRuntime.super.createRuntime();
    }
}

@Tag("redis-integration")
class RedisReadWriteLockContractTest extends ReadWriteLockContract implements RedisContractRuntime {
    @Override
    public LockRuntime createRuntime() {
        return RedisContractRuntime.super.createRuntime();
    }
}

@Tag("redis-integration")
class RedisLeasePolicyContractTest extends LeasePolicyContract implements RedisContractRuntime {
    @Override
    public LockRuntime createRuntime() {
        return RedisContractRuntime.super.createRuntime();
    }
}

@Tag("redis-integration")
class RedisFixedLeasePolicyContractTest extends FixedLeasePolicyContract implements RedisContractRuntime {
    @Override
    public LockRuntime createRuntime() {
        return RedisContractRuntime.super.createRuntime();
    }
}

@Tag("redis-integration")
class RedisReentryContractTest extends ReentryContract implements RedisContractRuntime {
    @Override
    public LockRuntime createRuntime() {
        return RedisContractRuntime.super.createRuntime();
    }
}

@Tag("redis-integration")
class RedisSessionLifecycleContractTest extends SessionLifecycleContract implements RedisContractRuntime {
    @Override
    public LockRuntime createRuntime() {
        return RedisContractRuntime.super.createRuntime();
    }
}

interface RedisContractRuntime {
    @BeforeAll
    static void startRedis() throws Exception {
        RedisContractRuntimes.start();
    }

    @AfterAll
    static void stopRedis() throws Exception {
        RedisContractRuntimes.stop();
    }

    @BeforeEach
    default void resetRedis() {
        RedisContractRuntimes.reset();
    }

    default LockRuntime createRuntime() {
        return RedisContractRuntimes.createRuntime();
    }
}

final class RedisContractRuntimes {
    private static RedisTestSupport.RunningRedis redis;

    private RedisContractRuntimes() {
    }

    static void start() throws Exception {
        if (redis == null) {
            redis = RedisTestSupport.startRedis();
        }
    }

    static void stop() throws Exception {
        if (redis != null) {
            redis.close();
            redis = null;
        }
    }

    static void reset() {
        redis.flushAll();
    }

    static LockRuntime createRuntime() {
        return LockRuntimeBuilder.create()
            .backend("redis")
            .backendModules(List.of(new RedisBackendModule(redis.configuration(30L))))
            .build();
    }
}
