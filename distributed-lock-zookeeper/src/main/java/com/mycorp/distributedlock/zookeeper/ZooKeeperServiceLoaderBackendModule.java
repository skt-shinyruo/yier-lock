package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;

public final class ZooKeeperServiceLoaderBackendModule implements BackendModule {

    public ZooKeeperServiceLoaderBackendModule() {
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
        throw new LockConfigurationException(
            "ZooKeeper requires explicit typed configuration. "
                + "Instantiate it with new ZooKeeperBackendModule(new ZooKeeperBackendConfiguration(\"127.0.0.1:2181\", \"/distributed-locks\"))."
        );
    }
}
