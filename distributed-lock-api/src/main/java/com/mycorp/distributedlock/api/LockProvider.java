package com.mycorp.distributedlock.api;

public interface LockProvider {

    /**
     * @return unique provider type identifier (e.g. "redis", "zookeeper")
     */
    String getType();

    /**
     * Higher values take precedence when no explicit provider is selected.
     */
    default int getPriority() {
        return 0;
    }

    DistributedLock createLock(String key);
    
    DistributedReadWriteLock createReadWriteLock(String key);

    /**
     * Allow providers to release resources when the factory shuts down.
     */
    default void close() {
        // no-op by default
    }
}
