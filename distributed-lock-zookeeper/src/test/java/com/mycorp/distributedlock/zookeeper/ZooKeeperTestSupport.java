package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import org.apache.curator.test.TestingServer;

import java.util.UUID;

final class ZooKeeperTestSupport implements AutoCloseable {

    private final TestingServer server;
    private final String basePath;

    ZooKeeperTestSupport() throws Exception {
        this.server = new TestingServer();
        this.basePath = "/locks-" + UUID.randomUUID();
    }

    TestingServer server() {
        return server;
    }

    ZooKeeperBackendConfiguration configuration() {
        return new ZooKeeperBackendConfiguration(server.getConnectString(), basePath);
    }

    LockRuntime runtime() {
        ZooKeeperBackendConfiguration configuration = configuration();
        return LockRuntimeBuilder.create()
            .backend("zookeeper")
            .backendProvider(new ZooKeeperBackendProvider())
            .backendConfiguration(configuration)
            .build();
    }

    @Override
    public void close() throws Exception {
        server.close();
    }
}
