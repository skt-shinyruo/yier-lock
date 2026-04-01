package com.mycorp.distributedlock.testkit.support;

import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;

import java.util.concurrent.atomic.AtomicBoolean;

public final class OwnershipLossLeaseBackend implements LockBackend {

    private final AtomicBoolean valid = new AtomicBoolean(true);

    @Override
    public BackendLockLease acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) {
        valid.set(true);
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
                return valid.get();
            }

            @Override
            public void release() {
                if (!valid.get()) {
                    throw new LockOwnershipLostException("Synthetic ownership loss for " + resource.key());
                }
                valid.set(false);
            }
        };
    }

    public void invalidateCurrentLease() {
        valid.set(false);
    }
}
