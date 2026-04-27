package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.client.DefaultLockClient;
import com.mycorp.distributedlock.core.client.DefaultSynchronousLockExecutor;
import com.mycorp.distributedlock.core.client.SupportedLockModes;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import com.mycorp.distributedlock.runtime.spi.ServiceLoaderBackendRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        BackendCapabilities capabilities = validateCapabilities(selectedModule);
        LockBackend backend = selectedModule.createBackend();
        SupportedLockModes supportedLockModes = new SupportedLockModes(
            capabilities.mutexSupported(),
            capabilities.readWriteSupported(),
            capabilities.fixedLeaseDurationSupported()
        );
        LockClient lockClient = new DefaultLockClient(backend, supportedLockModes);
        SynchronousLockExecutor synchronousLockExecutor = new DefaultSynchronousLockExecutor(lockClient);
        return new DefaultLockRuntime(lockClient, synchronousLockExecutor);
    }

    private BackendModule selectBackendModule(List<BackendModule> availableModules) {
        validateUniqueBackendIds(availableModules);

        if (backendId == null || backendId.isBlank()) {
            throw new LockConfigurationException("A backend id must be configured before building the lock runtime");
        }

        return availableModules.stream()
            .filter(module -> backendId.equals(module.id()))
            .findFirst()
            .orElseThrow(() -> new LockConfigurationException("Requested backend not found: " + backendId));
    }

    private BackendCapabilities validateCapabilities(BackendModule module) {
        BackendCapabilities capabilities = module.capabilities();
        if (capabilities == null) {
            throw new LockConfigurationException("Backend module capabilities must not be null: " + module.id());
        }

        List<String> missingRequirements = new ArrayList<>();
        if (!capabilities.mutexSupported()) {
            missingRequirements.add("mutexSupported");
        }
        if (!capabilities.fencingSupported()) {
            missingRequirements.add("fencingSupported");
        }
        if (!capabilities.renewableSessionsSupported()) {
            missingRequirements.add("renewableSessionsSupported");
        }

        if (!missingRequirements.isEmpty()) {
            throw new LockConfigurationException(
                "Backend module does not satisfy runtime requirements: " + module.id() + " missing " + missingRequirements
            );
        }

        return capabilities;
    }

    private void validateUniqueBackendIds(List<BackendModule> availableModules) {
        Map<String, Long> moduleCountsById = availableModules.stream()
            .collect(Collectors.groupingBy(BackendModule::id, Collectors.counting()));

        moduleCountsById.entrySet().stream()
            .filter(entry -> entry.getValue() > 1)
            .findFirst()
            .ifPresent(entry -> {
                throw new LockConfigurationException("Duplicate backend modules registered for id: " + entry.getKey());
            });
    }
}
