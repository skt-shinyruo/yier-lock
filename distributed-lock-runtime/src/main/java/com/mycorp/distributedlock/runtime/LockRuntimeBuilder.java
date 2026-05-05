package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.api.RuntimeInfo;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.core.client.DefaultLockClient;
import com.mycorp.distributedlock.core.client.DefaultSynchronousLockExecutor;
import com.mycorp.distributedlock.spi.BackendClient;
import com.mycorp.distributedlock.spi.BackendConfiguration;
import com.mycorp.distributedlock.spi.BackendDescriptor;
import com.mycorp.distributedlock.spi.BackendProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class LockRuntimeBuilder {

    private final List<BackendProvider<?>> backendProviders = new ArrayList<>();
    private final Map<Class<? extends BackendConfiguration>, BackendConfiguration> configurations = new LinkedHashMap<>();
    private final List<LockRuntimeDecorator> decorators = new ArrayList<>();
    private String backendId;

    private record ProviderMetadata(BackendProvider<?> provider, BackendDescriptor<?> descriptor) {
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

    public LockRuntimeBuilder backendProvider(BackendProvider<?> provider) {
        backendProviders.add(provider);
        return this;
    }

    public LockRuntimeBuilder backendProviders(List<? extends BackendProvider<?>> providers) {
        backendProviders.clear();
        if (providers != null) {
            providers.forEach(this::backendProvider);
        }
        return this;
    }

    public <C extends BackendConfiguration> LockRuntimeBuilder backendConfiguration(C configuration) {
        Objects.requireNonNull(configuration, "configuration");
        return backendConfiguration(configuration.getClass().asSubclass(BackendConfiguration.class), configuration);
    }

    public <C extends BackendConfiguration> LockRuntimeBuilder backendConfiguration(Class<C> type, C configuration) {
        configurations.put(Objects.requireNonNull(type, "type"), Objects.requireNonNull(configuration, "configuration"));
        return this;
    }

    public LockRuntimeBuilder decorators(List<LockRuntimeDecorator> decorators) {
        this.decorators.clear();
        if (decorators != null) {
            decorators.forEach(decorator -> this.decorators.add(Objects.requireNonNull(decorator, "decorator")));
        }
        return this;
    }

    public LockRuntime build() {
        validateBackendId();
        ProviderMetadata selectedProvider = selectedProvider(validateProviders());
        BackendDescriptor<?> descriptor = selectedProvider.descriptor();
        BackendConfiguration configuration = configurationFor(descriptor);
        BackendClient backendClient = createClient(selectedProvider.provider(), descriptor, configuration);
        LockClient lockClient = new DefaultLockClient(backendClient, descriptor.behavior());
        SynchronousLockExecutor synchronousLockExecutor = new DefaultSynchronousLockExecutor(lockClient);
        LockRuntime runtime = new DefaultLockRuntime(
            new RuntimeInfo(
                descriptor.id(),
                descriptor.displayName(),
                descriptor.behavior(),
                runtimeVersion()
            ),
            lockClient,
            synchronousLockExecutor
        );

        for (LockRuntimeDecorator decorator : decorators) {
            LockRuntime decorated;
            try {
                decorated = decorator.decorate(runtime);
            } catch (RuntimeException | Error failure) {
                closeRuntimeAfterDecoratorFailure(runtime, failure);
                throw failure;
            }
            if (decorated == null) {
                LockConfigurationException failure = new LockConfigurationException(
                    "Lock runtime decorator returned null runtime"
                );
                closeRuntimeAfterDecoratorFailure(runtime, failure);
                throw failure;
            }
            runtime = decorated;
        }
        return runtime;
    }

    private static void closeRuntimeAfterDecoratorFailure(LockRuntime runtime, Throwable failure) {
        try {
            runtime.close();
        } catch (RuntimeException | Error closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private void validateBackendId() {
        if (backendId == null || backendId.isBlank()) {
            throw new LockConfigurationException("A backend id must be configured before building the lock runtime");
        }
    }

    private ProviderMetadata selectedProvider(List<ProviderMetadata> providers) {
        return providers.stream()
            .filter(provider -> backendId.equals(provider.descriptor().id()))
            .findFirst()
            .orElseThrow(() -> new LockConfigurationException("Requested backend not found: " + backendId));
    }

    private List<ProviderMetadata> validateProviders() {
        List<ProviderMetadata> metadata = new ArrayList<>();
        for (BackendProvider<?> provider : backendProviders) {
            if (provider == null) {
                throw new LockConfigurationException("Backend provider must not be null");
            }
            BackendDescriptor<?> descriptor = provider.descriptor();
            if (descriptor == null) {
                throw new LockConfigurationException("Backend provider descriptor must not be null");
            }
            metadata.add(new ProviderMetadata(provider, descriptor));
        }
        validateUniqueBackendIds(metadata);
        return metadata;
    }

    private void validateUniqueBackendIds(List<ProviderMetadata> providers) {
        Map<String, Long> providerCountsById = providers.stream()
            .collect(Collectors.groupingBy(provider -> provider.descriptor().id(), Collectors.counting()));

        providerCountsById.entrySet().stream()
            .filter(entry -> entry.getValue() > 1)
            .findFirst()
            .ifPresent(entry -> {
                throw new LockConfigurationException("Duplicate backend providers registered for id: " + entry.getKey());
            });
    }

    private BackendConfiguration configurationFor(BackendDescriptor<?> descriptor) {
        BackendConfiguration configuration = configurations.get(descriptor.configurationType());
        if (configuration == null) {
            throw new LockConfigurationException(
                "Missing backend configuration for backend "
                    + descriptor.id()
                    + " of type "
                    + descriptor.configurationType().getName()
            );
        }
        return configuration;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BackendClient createClient(
        BackendProvider provider,
        BackendDescriptor<?> descriptor,
        BackendConfiguration configuration
    ) {
        BackendClient client = provider.createClient(configuration);
        if (client == null) {
            throw new LockConfigurationException("Backend provider returned null client: " + descriptor.id());
        }
        return client;
    }

    private static String runtimeVersion() {
        Package runtimePackage = LockRuntimeBuilder.class.getPackage();
        String implementationVersion = runtimePackage == null ? null : runtimePackage.getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank() ? "unknown" : implementationVersion;
    }
}
