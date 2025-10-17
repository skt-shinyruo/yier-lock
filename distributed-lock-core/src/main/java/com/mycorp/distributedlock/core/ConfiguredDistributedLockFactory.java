package com.mycorp.distributedlock.core;

import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.api.ServiceLoaderDistributedLockFactory;
import com.mycorp.distributedlock.core.config.LockConfiguration;

/**
 * Convenience helper that builds a {@link DistributedLockFactory} driven by {@link LockConfiguration}.
 * This mirrors how production frameworks wire lock factories via configuration modules.
 */
public final class ConfiguredDistributedLockFactory {

    private ConfiguredDistributedLockFactory() {
    }

    public static DistributedLockFactory create() {
        return create(new LockConfiguration());
    }

    public static DistributedLockFactory create(LockConfiguration configuration) {
        if (configuration == null) {
            return new ServiceLoaderDistributedLockFactory();
        }
        return configuration.getLockType()
            .map(ServiceLoaderDistributedLockFactory::new)
            .orElseGet(ServiceLoaderDistributedLockFactory::new);
    }
}
