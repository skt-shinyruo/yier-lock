package com.mycorp.distributedlock.runtime.spi;

public record BackendCapabilities(
    boolean mutexSupported,
    boolean readWriteSupported
) {

    public static BackendCapabilities standard() {
        return new BackendCapabilities(true, true);
    }
}
