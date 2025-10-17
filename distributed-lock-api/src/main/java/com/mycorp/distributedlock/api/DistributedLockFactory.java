package com.mycorp.distributedlock.api;

/**
 * Contract for creating distributed lock instances.
 * Implementations may delegate to specific backends (Redis, ZooKeeper, etc.).
 */
public interface DistributedLockFactory extends AutoCloseable {

    /**
     * Obtain a distributed lock for the given logical name.
     *
     * @param name logical lock name
     * @return distributed lock instance
     */
    DistributedLock getLock(String name);

    /**
     * Obtain a distributed read/write lock for the given logical name.
     *
     * @param name logical lock name
     * @return distributed read/write lock instance
     */
    DistributedReadWriteLock getReadWriteLock(String name);

    /**
     * Release any resources associated with this factory.
     * Implementations should override when cleanup is required.
     */
    default void shutdown() {
        // no-op by default
    }

    @Override
    default void close() {
        shutdown();
    }
}
