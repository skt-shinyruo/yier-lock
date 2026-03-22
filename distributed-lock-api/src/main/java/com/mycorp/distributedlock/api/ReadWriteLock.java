package com.mycorp.distributedlock.api;

/**
 * Minimal public read/write lock contract for distributed lock 2.0.
 */
public interface ReadWriteLock {

    MutexLock readLock();

    MutexLock writeLock();
}
