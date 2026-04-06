package com.mycorp.distributedlock.api;

public record LockKey(String value) {

    public LockKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Lock key must not be blank");
        }
    }
}
