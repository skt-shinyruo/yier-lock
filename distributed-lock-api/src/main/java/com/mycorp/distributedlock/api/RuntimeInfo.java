package com.mycorp.distributedlock.api;

import java.util.Objects;

public record RuntimeInfo(
    String backendId,
    String backendDisplayName,
    BackendBehavior behavior,
    String runtimeVersion
) {
    public RuntimeInfo {
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("backendId must not be blank");
        }
        if (backendDisplayName == null || backendDisplayName.isBlank()) {
            throw new IllegalArgumentException("backendDisplayName must not be blank");
        }
        behavior = Objects.requireNonNull(behavior, "behavior");
        if (runtimeVersion == null || runtimeVersion.isBlank()) {
            throw new IllegalArgumentException("runtimeVersion must not be blank");
        }
    }
}
