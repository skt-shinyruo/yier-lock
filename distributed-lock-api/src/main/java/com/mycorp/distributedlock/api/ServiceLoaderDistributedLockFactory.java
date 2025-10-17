package com.mycorp.distributedlock.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * Default {@link DistributedLockFactory} implementation that discovers {@link LockProvider}s
 * via {@link ServiceLoader}. Inspired by the plugin architecture used in frameworks like Redisson.
 */
public class ServiceLoaderDistributedLockFactory implements DistributedLockFactory {

    private static final Logger logger = LoggerFactory.getLogger(ServiceLoaderDistributedLockFactory.class);

    private static final String[] PROVIDER_PROPERTY_KEYS = new String[]{
        "distributed.lock.provider",
        "distributed-lock.provider",
        "distributed_lock_provider",
        "com.mycorp.distributedlock.provider",
        "distributed-lock.type",
        "distributed.lock.type",
        "spring.distributed-lock.type"
    };
    private static final String[] PROVIDER_ENV_KEYS = new String[]{
        "DISTRIBUTED_LOCK_PROVIDER",
        "DISTRIBUTED_LOCK_TYPE",
        "SPRING_DISTRIBUTED_LOCK_TYPE"
    };

    private final List<LockProvider> providers;
    private final LockProvider selectedProvider;

    public ServiceLoaderDistributedLockFactory() {
        this(null);
    }

    public ServiceLoaderDistributedLockFactory(String requestedProviderType) {
        List<LockProvider> loaded = new ArrayList<>();
        ServiceLoader<LockProvider> loader = ServiceLoader.load(LockProvider.class);
        loader.iterator().forEachRemaining(loaded::add);

        if (loaded.isEmpty()) {
            throw new IllegalStateException(
                "No LockProvider implementations found on classpath. " +
                "Include a provider module (e.g., distributed-lock-redis or distributed-lock-zookeeper).");
        }

        loaded.sort(Comparator.comparingInt(LockProvider::getPriority).reversed());
        this.providers = Collections.unmodifiableList(loaded);

        String desiredType = requestedProviderType != null ? requestedProviderType : resolveProviderTypeFromSystem();
        this.selectedProvider = selectProvider(desiredType, providers);
        logger.info("Using distributed lock provider: {}", this.selectedProvider.getType());
    }

    private static String resolveProviderTypeFromSystem() {
        for (String key : PROVIDER_PROPERTY_KEYS) {
            String value = System.getProperty(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        for (String key : PROVIDER_ENV_KEYS) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static LockProvider selectProvider(String desiredType, List<LockProvider> candidates) {
        if (desiredType != null) {
            return candidates.stream()
                .filter(provider -> desiredType.equalsIgnoreCase(provider.getType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "Requested distributed lock provider '" + desiredType + "' not found. Available providers: " +
                        availableTypes(candidates)));
        }
        return candidates.get(0);
    }

    private static String availableTypes(List<LockProvider> candidates) {
        return candidates.stream()
            .map(LockProvider::getType)
            .sorted()
            .collect(Collectors.joining(", "));
    }

    public String getActiveProviderType() {
        return selectedProvider.getType();
    }

    @Override
    public DistributedLock getLock(String name) {
        return selectedProvider.createLock(name);
    }

    @Override
    public DistributedReadWriteLock getReadWriteLock(String name) {
        return selectedProvider.createReadWriteLock(name);
    }

    @Override
    public void shutdown() {
        for (LockProvider provider : providers) {
            try {
                provider.close();
            } catch (RuntimeException e) {
                logger.warn("Failed to shutdown lock provider {}", provider.getType(), e);
            }
        }
    }
}
