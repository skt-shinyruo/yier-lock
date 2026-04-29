package com.mycorp.distributedlock.api;

@FunctionalInterface
public interface LockedAction<T> {

    /**
     * Executes synchronously while {@code lease} is held.
     * <p>Exceptions thrown by the callback are propagated by the executor after the lease is released.</p>
     *
     * @param lease acquired lease bound to the callback execution
     * @return callback result
     * @throws Exception when callback execution fails
     */
    T execute(LockLease lease) throws Exception;
}
