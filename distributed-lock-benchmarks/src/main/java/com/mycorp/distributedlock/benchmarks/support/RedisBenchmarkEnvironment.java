package com.mycorp.distributedlock.benchmarks.support;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.redis.RedisBackendConfiguration;
import com.mycorp.distributedlock.redis.RedisBackendProvider;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;

import java.util.Objects;

public final class RedisBenchmarkEnvironment implements AutoCloseable {

    private static final String DEFAULT_REDIS_URI = "redis://127.0.0.1:6379";

    private final String redisUri;
    private final LockRuntime runtime;

    private RedisBenchmarkEnvironment(String redisUri, LockRuntime runtime) {
        this.redisUri = Objects.requireNonNull(redisUri, "redisUri");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public static RedisBenchmarkEnvironment start() {
        String redisUri = configuredRedisUri();
        LockRuntime runtime = LockRuntimeBuilder.create()
            .backend("redis")
            .backendProvider(new RedisBackendProvider())
            .backendConfiguration(new RedisBackendConfiguration(redisUri, 30L))
            .build();

        return new RedisBenchmarkEnvironment(redisUri, runtime);
    }

    public LockRuntime runtime() {
        return runtime;
    }

    public LockClient lockClient() {
        return runtime.lockClient();
    }

    public SynchronousLockExecutor synchronousLockExecutor() {
        return runtime.synchronousLockExecutor();
    }

    public String redisUri() {
        return redisUri;
    }

    @Override
    public void close() throws Exception {
        runtime.close();
    }

    private static String configuredRedisUri() {
        String systemProperty = System.getProperty("benchmark.redis.uri");
        if (systemProperty != null && !systemProperty.isBlank()) {
            return systemProperty;
        }

        String environmentVariable = System.getenv("BENCHMARK_REDIS_URI");
        if (environmentVariable != null && !environmentVariable.isBlank()) {
            return environmentVariable;
        }

        return DEFAULT_REDIS_URI;
    }
}
