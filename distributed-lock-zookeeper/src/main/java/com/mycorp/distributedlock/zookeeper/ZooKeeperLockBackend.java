package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.spi.BackendClient;
import com.mycorp.distributedlock.spi.BackendSession;
import org.apache.curator.framework.CuratorFramework;

import java.util.Objects;
import java.util.UUID;

public class ZooKeeperLockBackend implements BackendClient {

    private final ZooKeeperBackendConfiguration configuration;
    private final ZooKeeperClientFactory clientFactory;

    public ZooKeeperLockBackend(ZooKeeperBackendConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.clientFactory = new ZooKeeperClientFactory(configuration);
    }

    ZooKeeperLockBackend(ZooKeeperBackendConfiguration configuration, ZooKeeperClientFactory clientFactory) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
    }

    @Override
    public BackendSession openSession() {
        CuratorFramework curatorFramework = clientFactory.connect();
        return new ZooKeeperBackendSession(
            UUID.randomUUID().toString(),
            curatorFramework,
            new ZooKeeperPathLayout(configuration.basePath()),
            this
        );
    }

    @Override
    public void close() {
        // No shared backend resources remain once each session owns its own client.
    }

    void beforeFenceIssued(String contenderPath) {
    }

    void beforeLeaseRegistered(String contenderPath) {
    }
}
