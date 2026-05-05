package com.mycorp.distributedlock.spi;

public interface BackendProvider<C extends BackendConfiguration> {

    BackendDescriptor<C> descriptor();

    BackendClient createClient(C configuration);
}
