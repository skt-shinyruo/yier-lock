package com.mycorp.distributedlock.core.client;

public record SupportedLockModes(
    boolean mutexSupported,
    boolean readWriteSupported,
    boolean fixedLeaseDurationSupported
) {
}
