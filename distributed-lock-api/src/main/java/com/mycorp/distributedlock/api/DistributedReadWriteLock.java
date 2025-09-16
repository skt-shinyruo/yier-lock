package com.mycorp.distributedlock.api;

public interface DistributedReadWriteLock {
    
    DistributedLock readLock();
    
    DistributedLock writeLock();
    
    String getName();
}