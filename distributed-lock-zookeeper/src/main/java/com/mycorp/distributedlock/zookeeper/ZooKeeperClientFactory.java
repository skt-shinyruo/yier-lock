package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.exception.LockBackendException;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class ZooKeeperClientFactory {

    private final ZooKeeperBackendConfiguration configuration;
    private final Supplier<CuratorFramework> clientSupplier;

    ZooKeeperClientFactory(ZooKeeperBackendConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.clientSupplier = this::newClient;
    }

    ZooKeeperClientFactory(ZooKeeperBackendConfiguration configuration, Supplier<CuratorFramework> clientSupplier) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
    }

    CuratorFramework connect() {
        CuratorFramework curatorFramework = clientSupplier.get();
        boolean success = false;
        try {
            curatorFramework.start();
            boolean connected = curatorFramework.blockUntilConnected(10, TimeUnit.SECONDS);
            if (!connected) {
                throw new LockBackendException(
                    "Failed to connect to ZooKeeper within 10 seconds: " + configuration.connectString()
                );
            }
            success = true;
            return curatorFramework;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LockBackendException("Interrupted while connecting to ZooKeeper", exception);
        } finally {
            if (!success) {
                curatorFramework.close();
            }
        }
    }

    private CuratorFramework newClient() {
        return CuratorFrameworkFactory.newClient(
            configuration.connectString(),
            new ExponentialBackoffRetry(1_000, 3)
        );
    }
}
