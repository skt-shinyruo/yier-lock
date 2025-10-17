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

    /**
     * Get the fencing token for the current lock holder.
     * Returns FencingToken.NONE if lock is not held by current thread.
     * 
     * Fencing tokens provide protection against issues with lock expiry
     * and delayed operations, as described by Martin Kleppmann.
     * 
     * @return fencing token if lock is held, FencingToken.NONE otherwise
     */
    default FencingToken getFencingToken() {
        return FencingToken.NONE;
    }

    String getName();

    @Override
    default void close() {
        if (isHeldByCurrentThread()) {
            unlock();
        }
    }
}