package com.mycorp.distributedlock.core.manager;

import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultLockManagerOwnershipLossTest {

    @Test
    void staleLocalReentryMustFailAfterBackendOwnershipLoss() throws Exception {
        OwnershipLossLeaseBackend backend = new OwnershipLossLeaseBackend();
        DefaultLockManager manager = new DefaultLockManager(backend);
        MutexLock lock = manager.mutex("orders:77");
        lock.lock();
        backend.invalidateCurrentLease();

        assertThatThrownBy(() -> manager.mutex("orders:77").tryLock(Duration.ZERO))
            .isInstanceOf(LockOwnershipLostException.class);
        assertThatThrownBy(lock::unlock)
            .isInstanceOf(IllegalMonitorStateException.class);

        MutexLock fresh = manager.mutex("orders:77");
        assertThat(fresh.tryLock(Duration.ZERO)).isTrue();
        fresh.unlock();
    }

    private static final class OwnershipLossLeaseBackend implements LockBackend {
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

        private void invalidateCurrentLease() {
            valid.set(false);
        }
    }
}
