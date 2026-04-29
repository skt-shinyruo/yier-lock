package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.client.DefaultLockClient;
import com.mycorp.distributedlock.core.client.DefaultSynchronousLockExecutor;
import com.mycorp.distributedlock.core.client.SupportedLockModes;
import com.mycorp.distributedlock.runtime.spi.ServiceLoaderBackendRegistry;
import com.mycorp.distributedlock.spi.BackendCapabilities;
import com.mycorp.distributedlock.spi.BackendModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class LockRuntimeBuilder {

    private final List<BackendModule> explicitBackendModules = new ArrayList<>();
    private String backendId;

    private record ModuleMetadata(BackendModule module, String id, BackendCapabilities capabilities) {
    }

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
        List<ModuleMetadata> availableModules = validateModules(explicitBackendModules.isEmpty()
            ? new ServiceLoaderBackendRegistry().discover()
            : new ArrayList<>(explicitBackendModules));

        ModuleMetadata selectedModule = selectBackendModule(availableModules);
        validateCapabilities(selectedModule);
        LockBackend backend = selectedModule.module().createBackend();
        if (backend == null) {
            throw new LockConfigurationException("Backend module returned null backend: " + selectedModule.id());
        }
        SupportedLockModes supportedLockModes = new SupportedLockModes(
            selectedModule.capabilities().mutexSupported(),
            selectedModule.capabilities().readWriteSupported(),
            selectedModule.capabilities().fixedLeaseDurationSupported()
        );
        LockClient lockClient = new DefaultLockClient(backend, supportedLockModes);
        SynchronousLockExecutor synchronousLockExecutor = new DefaultSynchronousLockExecutor(lockClient);
        return new DefaultLockRuntime(
            selectedModule.id(),
            selectedModule.capabilities(),
            lockClient,
            synchronousLockExecutor
        );
    }

    private ModuleMetadata selectBackendModule(List<ModuleMetadata> availableModules) {
        if (backendId == null || backendId.isBlank()) {
            throw new LockConfigurationException("A backend id must be configured before building the lock runtime");
        }

        return availableModules.stream()
            .filter(module -> backendId.equals(module.id()))
            .findFirst()
            .orElseThrow(() -> new LockConfigurationException("Requested backend not found: " + backendId));
    }

    private void validateCapabilities(ModuleMetadata module) {
        List<String> missingRequirements = new ArrayList<>();
        if (!module.capabilities().mutexSupported()) {
            missingRequirements.add("mutexSupported");
        }
        if (!module.capabilities().fencingSupported()) {
            missingRequirements.add("fencingSupported");
        }
        if (!module.capabilities().renewableSessionsSupported()) {
            missingRequirements.add("renewableSessionsSupported");
        }

        if (!missingRequirements.isEmpty()) {
            throw new LockConfigurationException(
                "Backend module does not satisfy runtime requirements: " + module.id() + " missing " + missingRequirements
            );
        }
    }

    private List<ModuleMetadata> validateModules(List<BackendModule> modules) {
        List<ModuleMetadata> metadata = new ArrayList<>();
        for (BackendModule module : modules) {
            if (module == null) {
                throw new LockConfigurationException("Backend module must not be null");
            }
            String id = module.id();
            if (id == null || id.isBlank()) {
                throw new LockConfigurationException("Backend module id must not be blank");
            }
            BackendCapabilities capabilities = module.capabilities();
            if (capabilities == null) {
                throw new LockConfigurationException("Backend module capabilities must not be null: " + id);
            }
            metadata.add(new ModuleMetadata(module, id, capabilities));
        }
        validateUniqueBackendIds(metadata);
        return metadata;
    }

    private void validateUniqueBackendIds(List<ModuleMetadata> availableModules) {
        Map<String, Long> moduleCountsById = availableModules.stream()
            .collect(Collectors.groupingBy(ModuleMetadata::id, Collectors.counting()));

        moduleCountsById.entrySet().stream()
            .filter(entry -> entry.getValue() > 1)
            .findFirst()
            .ifPresent(entry -> {
                throw new LockConfigurationException("Duplicate backend modules registered for id: " + entry.getKey());
            });
    }
}
