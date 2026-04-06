package com.mycorp.distributedlock.api;

public record FencingToken(long value) {

    public FencingToken {
        if (value <= 0) {
            throw new IllegalArgumentException("Fencing token must be positive");
        }
    }
}
