package com.mycorp.distributedlock.core.backend;

import java.util.Objects;

public record LockResource(String key) {

    public LockResource {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("Lock key cannot be blank");
        }
    }
}
