package com.mycorp.distributedlock.spi;

public record BackendCapabilities(
    boolean mutexSupported,
    boolean readWriteSupported,
    boolean fencingSupported,
    boolean renewableSessionsSupported,
    boolean fixedLeaseDurationSupported
) {
    public static BackendCapabilities standard() {
        return new BackendCapabilities(true, true, true, true, true);
    }

    public static BackendCapabilities withoutFixedLeaseDuration() {
        return new BackendCapabilities(true, true, true, true, false);
    }

    public static BackendCapabilities mutexOnly() {
        return new BackendCapabilities(true, false, true, true, false);
    }
}
