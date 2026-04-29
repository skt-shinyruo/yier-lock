package com.mycorp.distributedlock.core.backend.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

public final class BackendLeaseTracker<T> {
    private final ConcurrentMap<String, T> leases = new ConcurrentHashMap<>();

    public void register(String leaseId, T lease) {
        Objects.requireNonNull(leaseId, "leaseId");
        Objects.requireNonNull(lease, "lease");
        if (leases.putIfAbsent(leaseId, lease) != null) {
            throw new IllegalStateException("Duplicate backend lease id: " + leaseId);
        }
    }

    public boolean remove(String leaseId) {
        Objects.requireNonNull(leaseId, "leaseId");
        return leases.remove(leaseId) != null;
    }

    public List<T> snapshot() {
        return new ArrayList<>(leases.values());
    }

    public RuntimeException releaseAll(RuntimeException currentFailure, Consumer<T> releaser) {
        Objects.requireNonNull(releaser, "releaser");
        RuntimeException failure = currentFailure;
        for (T lease : snapshot()) {
            try {
                releaser.accept(lease);
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        return failure;
    }
}
