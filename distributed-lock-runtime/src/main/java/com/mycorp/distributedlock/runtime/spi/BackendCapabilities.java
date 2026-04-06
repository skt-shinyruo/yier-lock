package com.mycorp.distributedlock.runtime.spi;

import com.mycorp.distributedlock.api.LockCapabilities;

public record BackendCapabilities(
    boolean mutexSupported,
    boolean readWriteSupported,
    boolean fencingSupported,
    boolean renewableSessionsSupported
) {

    public static BackendCapabilities standard() {
        return new BackendCapabilities(true, true, true, true);
    }

    public LockCapabilities asApiCapabilities() {
        return new LockCapabilities(
            mutexSupported,
            readWriteSupported,
            fencingSupported,
            renewableSessionsSupported
        );
    }
}
