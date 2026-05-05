package com.mycorp.distributedlock.benchmarks.support;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.zookeeper.ZooKeeperBackendConfiguration;
import com.mycorp.distributedlock.zookeeper.ZooKeeperBackendProvider;

import java.util.Objects;

public final class ZooKeeperBenchmarkEnvironment implements AutoCloseable {

    private static final String DEFAULT_CONNECT_STRING = "127.0.0.1:2181";
    private static final String DEFAULT_BASE_PATH = "/distributed-lock-benchmarks";

    private final String connectString;
    private final String basePath;
    private final LockRuntime runtime;

    private ZooKeeperBenchmarkEnvironment(String connectString, String basePath, LockRuntime runtime) {
        this.connectString = Objects.requireNonNull(connectString, "connectString");
        this.basePath = Objects.requireNonNull(basePath, "basePath");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public static ZooKeeperBenchmarkEnvironment start() {
        String connectString = configuredConnectString();
        String basePath = configuredBasePath();
        LockRuntime runtime = LockRuntimeBuilder.create()
            .backend("zookeeper")
            .backendProvider(new ZooKeeperBackendProvider())
            .backendConfiguration(new ZooKeeperBackendConfiguration(connectString, basePath))
            .build();
        return new ZooKeeperBenchmarkEnvironment(connectString, basePath, runtime);
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

    public String connectString() {
        return connectString;
    }

    public String basePath() {
        return basePath;
    }

    @Override
    public void close() throws Exception {
        runtime.close();
    }

    private static String configuredConnectString() {
        String systemProperty = System.getProperty("benchmark.zookeeper.connect-string");
        if (systemProperty != null && !systemProperty.isBlank()) {
            return systemProperty;
        }

        String environmentVariable = System.getenv("BENCHMARK_ZOOKEEPER_CONNECT_STRING");
        if (environmentVariable != null && !environmentVariable.isBlank()) {
            return environmentVariable;
        }

        return DEFAULT_CONNECT_STRING;
    }

    private static String configuredBasePath() {
        String systemProperty = System.getProperty("benchmark.zookeeper.base-path");
        if (systemProperty != null && !systemProperty.isBlank()) {
            return systemProperty;
        }

        String environmentVariable = System.getenv("BENCHMARK_ZOOKEEPER_BASE_PATH");
        if (environmentVariable != null && !environmentVariable.isBlank()) {
            return environmentVariable;
        }

        return DEFAULT_BASE_PATH;
    }
}
