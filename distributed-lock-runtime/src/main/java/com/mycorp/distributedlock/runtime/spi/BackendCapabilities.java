package com.mycorp.distributedlock.runtime.spi;

public record BackendCapabilities(
    boolean mutexSupported,
    boolean readWriteSupported,
    boolean fencingSupported,
    boolean renewableSessionsSupported
) {

    public static BackendCapabilities standard() {
        return new BackendCapabilities(true, true, true, true);
    }
}
