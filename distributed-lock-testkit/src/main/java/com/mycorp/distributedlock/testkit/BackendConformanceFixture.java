package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.spi.BackendConfiguration;
import com.mycorp.distributedlock.spi.BackendProvider;

public interface BackendConformanceFixture<C extends BackendConfiguration> extends AutoCloseable {

    BackendProvider<C> provider();

    C configuration();

    default BackendBehavior behavior() {
        return provider().descriptor().behavior();
    }

    default LockRuntimeBuilder runtimeBuilder() {
        return LockRuntimeBuilder.create()
            .backend(provider().descriptor().id())
            .backendProvider(provider())
            .backendConfiguration(configuration());
    }

    @Override
    default void close() {
    }
}
