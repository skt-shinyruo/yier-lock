package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;

import java.util.Objects;

public final class ZooKeeperBackendModule implements BackendModule {

    private final ZooKeeperBackendConfiguration configuration;

    public ZooKeeperBackendModule() {
        this(ZooKeeperBackendConfiguration.defaultLocal());
    }

    public ZooKeeperBackendModule(String connectString) {
        this(new ZooKeeperBackendConfiguration(connectString, "/distributed-locks"));
    }

    public ZooKeeperBackendModule(ZooKeeperBackendConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    @Override
    public String id() {
        return "zookeeper";
    }

    @Override
    public BackendCapabilities capabilities() {
        return BackendCapabilities.standard();
    }

    @Override
    public LockBackend createBackend() {
        return new ZooKeeperLockBackend(configuration);
    }
}
