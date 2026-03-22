package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendContext;
import com.mycorp.distributedlock.runtime.spi.BackendModule;

public final class ZooKeeperBackendModule implements BackendModule {

    private final ZooKeeperBackendConfiguration explicitConfiguration;

    public ZooKeeperBackendModule() {
        this.explicitConfiguration = null;
    }

    public ZooKeeperBackendModule(String connectString) {
        this.explicitConfiguration = new ZooKeeperBackendConfiguration(connectString, "/distributed-locks");
    }

    public ZooKeeperBackendModule(ZooKeeperBackendConfiguration configuration) {
        this.explicitConfiguration = configuration;
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
    public LockBackend createBackend(BackendContext context) {
        ZooKeeperBackendConfiguration configuration = explicitConfiguration != null
            ? explicitConfiguration
            : resolveConfiguration(context);
        return new ZooKeeperLockBackend(configuration);
    }

    private ZooKeeperBackendConfiguration resolveConfiguration(BackendContext context) {
        Object configuration = context != null ? context.configuration() : null;
        if (configuration instanceof ZooKeeperBackendConfiguration zkConfiguration) {
            return zkConfiguration;
        }
        if (configuration instanceof String connectString && !connectString.isBlank()) {
            return new ZooKeeperBackendConfiguration(connectString, "/distributed-locks");
        }
        return ZooKeeperBackendConfiguration.defaultLocal();
    }
}
