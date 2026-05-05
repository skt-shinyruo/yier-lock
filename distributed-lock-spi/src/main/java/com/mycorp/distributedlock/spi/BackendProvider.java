package com.mycorp.distributedlock.spi;

import java.util.Objects;

public interface BackendProvider<C extends BackendConfiguration> {

    BackendDescriptor<C> descriptor();

    default BackendClient createClient(C configuration) {
        return createBackendClient(Objects.requireNonNull(configuration, "configuration"));
    }

    BackendClient createBackendClient(C configuration);
}
