package com.mycorp.distributedlock.api;

public record LockCapabilities(
        boolean mutexSupported,
        boolean readWriteSupported,
        boolean fencingSupported,
        boolean renewableSessionsSupported
) {
}
