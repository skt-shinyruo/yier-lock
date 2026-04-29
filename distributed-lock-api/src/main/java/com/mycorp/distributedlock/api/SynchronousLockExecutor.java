package com.mycorp.distributedlock.api;

@FunctionalInterface
public interface SynchronousLockExecutor {

    /**
     * Executes {@code action} synchronously after acquiring the requested lock.
     * <p>The lease is acquired before the callback is invoked and released before this method returns or throws.
     * If acquisition is interrupted, the {@link InterruptedException} is propagated through this method's
     * {@code throws Exception} contract.</p>
     *
     * @param request lock acquisition request
     * @param action synchronous callback to execute while the lease is held
     * @return callback result
     * @throws Exception when acquisition, callback execution, or lease release fails
     */
    <T> T withLock(LockRequest request, LockedAction<T> action) throws Exception;
}
