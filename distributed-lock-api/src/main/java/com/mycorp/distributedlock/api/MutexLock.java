package com.mycorp.distributedlock.api;

import java.time.Duration;

/**
 * Minimal public mutex contract for distributed lock 2.0.
 * Implementations may surface timeout or ownership loss via runtime exceptions.
 */
public interface MutexLock extends AutoCloseable {

    void lock() throws InterruptedException;

    boolean tryLock(Duration waitTime) throws InterruptedException;

    void unlock();

    boolean isHeldByCurrentThread();

    String key();

    @Override
    default void close() {
        if (isHeldByCurrentThread()) {
            unlock();
        }
    }
}
