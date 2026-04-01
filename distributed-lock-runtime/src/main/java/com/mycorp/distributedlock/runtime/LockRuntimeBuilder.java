package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.backend.SupportedLockModes;
import com.mycorp.distributedlock.core.manager.DefaultLockManager;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import com.mycorp.distributedlock.runtime.spi.ServiceLoaderBackendRegistry;

import java.util.ArrayList;
import java.util.List;

public final class LockRuntimeBuilder {

    private final List<BackendModule> explicitBackendModules = new ArrayList<>();
    private String backendId;

    private LockRuntimeBuilder() {
    }

    public static LockRuntimeBuilder create() {
        return new LockRuntimeBuilder();
    }

    public LockRuntimeBuilder backend(String backendId) {
        this.backendId = backendId;
        return this;
    }

    public LockRuntimeBuilder backendModules(List<BackendModule> backendModules) {
        this.explicitBackendModules.clear();
        if (backendModules != null) {
            this.explicitBackendModules.addAll(backendModules);
        }
        return this;
    }

    public LockRuntime build() {
        List<BackendModule> availableModules = explicitBackendModules.isEmpty()
            ? new ServiceLoaderBackendRegistry().discover()
            : List.copyOf(explicitBackendModules);

        BackendModule selectedModule = selectBackendModule(availableModules);
        LockBackend backend = selectedModule.createBackend();
        LockManager lockManager = new DefaultLockManager(backend, toSupportedLockModes(selectedModule));
        AutoCloseable backendResource = backend instanceof AutoCloseable closeable ? closeable : null;
        return new DefaultLockRuntime(lockManager, backendResource);
    }

    private BackendModule selectBackendModule(List<BackendModule> availableModules) {
        if (backendId != null && !backendId.isBlank()) {
            return availableModules.stream()
                .filter(module -> backendId.equals(module.id()))
                .findFirst()
                .orElseThrow(() -> new LockConfigurationException("Requested backend not found: " + backendId));
        }

        if (availableModules.isEmpty()) {
            throw new LockConfigurationException("No backend modules available");
        }
        if (availableModules.size() > 1) {
            throw new LockConfigurationException("Cannot select backend automatically: multiple backends available");
        }
        return availableModules.get(0);
    }

    private SupportedLockModes toSupportedLockModes(BackendModule module) {
        BackendCapabilities capabilities = module.capabilities();
        if (capabilities == null) {
            throw new LockConfigurationException("Backend module capabilities must not be null: " + module.id());
        }
        return new SupportedLockModes(capabilities.mutexSupported(), capabilities.readWriteSupported());
    }
}
