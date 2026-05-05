package com.mycorp.distributedlock.spi;

import com.mycorp.distributedlock.api.BackendBehavior;

import java.util.Objects;

public record BackendDescriptor<C extends BackendConfiguration>(
    String id,
    String displayName,
    Class<C> configurationType,
    BackendBehavior behavior
) {
    public BackendDescriptor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Backend descriptor id must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Backend descriptor displayName must not be blank");
        }
        configurationType = Objects.requireNonNull(configurationType, "configurationType");
        behavior = Objects.requireNonNull(behavior, "behavior");
    }
}
