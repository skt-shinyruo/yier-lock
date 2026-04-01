package com.mycorp.distributedlock.testkit.support;

import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ReleaseFailureLeaseBackend implements LockBackend {

    private final AtomicBoolean firstRelease = new AtomicBoolean(true);

    @Override
    public BackendLockLease acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) {
        return new BackendLockLease() {
            @Override
            public String key() {
                return resource.key();
            }

            @Override
            public LockMode mode() {
                return mode;
            }

            @Override
            public boolean isValidForCurrentExecution() {
                return true;
            }

            @Override
            public void release() {
                if (firstRelease.compareAndSet(true, false)) {
                    throw new LockBackendException("Synthetic release failure");
                }
            }
        };
    }
}
