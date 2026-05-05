package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.spi.BackendConfiguration;
import com.mycorp.distributedlock.spi.BackendProvider;
import com.mycorp.distributedlock.testkit.support.InMemoryBackendConfiguration;
import com.mycorp.distributedlock.testkit.support.InMemoryBackendProvider;

public final class InMemoryConformanceFixture implements BackendConformanceFixture<InMemoryBackendConfiguration> {

    private static final InMemoryBackendProvider PROVIDER = new InMemoryBackendProvider();
    private static final InMemoryBackendConfiguration CONFIGURATION = new InMemoryBackendConfiguration();

    @Override
    public BackendProvider<InMemoryBackendConfiguration> provider() {
        return PROVIDER;
    }

    @Override
    public InMemoryBackendConfiguration configuration() {
        return CONFIGURATION;
    }
}
