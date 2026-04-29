package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import org.apache.curator.test.TestingServer;

import java.util.List;
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
        return LockRuntimeBuilder.create()
            .backend("zookeeper")
            .backendModules(List.of(new ZooKeeperBackendModule(configuration())))
            .build();
    }

    @Override
    public void close() throws Exception {
        server.close();
    }
}
