package com.mycorp.distributedlock.core.manager;

import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultLockManagerReleaseFailureTest {

    @Test
    void releaseFailureMustSurfaceWithoutLeavingActiveOwnershipBehind() throws Exception {
        ReleaseFailureLeaseBackend backend = new ReleaseFailureLeaseBackend();
        DefaultLockManager manager = new DefaultLockManager(backend);
        MutexLock lock = manager.mutex("orders:88");
        lock.lock();

        assertThatThrownBy(lock::unlock)
            .isInstanceOf(LockBackendException.class);

        lock.lock();
        lock.unlock();
    }

    private static final class ReleaseFailureLeaseBackend implements LockBackend {
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
}
