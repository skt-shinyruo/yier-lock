package com.mycorp.distributedlock.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public interface DistributedLock extends AutoCloseable {
    
    void lock(long leaseTime, TimeUnit unit) throws InterruptedException;
    
    void lock() throws InterruptedException;
    
    boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException;
    
    boolean tryLock() throws InterruptedException;
    
    void unlock();
    
    CompletableFuture<Void> lockAsync(long leaseTime, TimeUnit unit);
    
    CompletableFuture<Void> lockAsync();
    
    CompletableFuture<Boolean> tryLockAsync(long waitTime, long leaseTime, TimeUnit unit);
    
    CompletableFuture<Boolean> tryLockAsync();
    
    CompletableFuture<Void> unlockAsync();
    
    boolean isLocked();
    
    boolean isHeldByCurrentThread();
    
    String getName();
    
    @Override
    default void close() {
        if (isHeldByCurrentThread()) {
            unlock();
        }
    }
}