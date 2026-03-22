package com.mycorp.distributedlock.api;

/**
 * Minimal public entry point for creating lock handles in distributed lock 2.0.
 */
public interface LockManager {

    MutexLock mutex(String key);

    ReadWriteLock readWrite(String key);
}
