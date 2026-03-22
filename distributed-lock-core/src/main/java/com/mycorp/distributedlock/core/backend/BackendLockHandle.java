package com.mycorp.distributedlock.core.backend;

@FunctionalInterface
public interface BackendLockHandle {

    String key();

    default LockMode mode() {
        return LockMode.MUTEX;
    }
}
