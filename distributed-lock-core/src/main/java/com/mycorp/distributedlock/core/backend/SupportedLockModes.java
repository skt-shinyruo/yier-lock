package com.mycorp.distributedlock.core.backend;

public record SupportedLockModes(boolean mutexSupported, boolean readWriteSupported) {

    public static SupportedLockModes standard() {
        return new SupportedLockModes(true, true);
    }
}
