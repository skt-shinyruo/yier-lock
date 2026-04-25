package com.mycorp.distributedlock.api;

/**
 * Lock modes are evaluated per {@link LockKey}. Modes are not separate lock families:
 * {@link #MUTEX} and {@link #WRITE} are exclusive for the same key, while {@link #READ}
 * may share the same key only with other read leases.
 */
public enum LockMode {
    /**
     * Exclusive lock for the key. Blocks mutex, read, and write acquisitions for the same key.
     */
    MUTEX,

    /**
     * Shared read lock for the key. Can coexist with other readers only.
     */
    READ,

    /**
     * Exclusive write lock for the key. Blocks mutex, read, and write acquisitions for the same key.
     */
    WRITE
}
