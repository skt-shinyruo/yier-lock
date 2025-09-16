package com.mycorp.distributedlock.api;

public interface DistributedLockFactory {
    
    DistributedLock getLock(String name);
    
    DistributedReadWriteLock getReadWriteLock(String name);
    
    void shutdown();
}