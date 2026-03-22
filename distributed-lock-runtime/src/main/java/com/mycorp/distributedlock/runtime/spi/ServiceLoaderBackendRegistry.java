package com.mycorp.distributedlock.runtime.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

public final class ServiceLoaderBackendRegistry {

    public List<BackendModule> discover() {
        List<BackendModule> modules = new ArrayList<>();
        ServiceLoader.load(BackendModule.class).iterator().forEachRemaining(modules::add);
        return modules;
    }
}
