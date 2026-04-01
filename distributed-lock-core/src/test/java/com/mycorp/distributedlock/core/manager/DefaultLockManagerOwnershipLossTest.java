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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultLockManagerOwnershipLossTest {

    @Test
    void sameHandleShouldRecoverAfterOwnershipLossCleanup() throws Exception {
        OwnershipLossLeaseBackend backend = new OwnershipLossLeaseBackend();
        DefaultLockManager manager = new DefaultLockManager(backend);
        MutexLock lock = manager.mutex("orders:77");
        lock.lock();
        backend.invalidateCurrentLease();

        assertThatThrownBy(() -> lock.tryLock(Duration.ZERO))
            .isInstanceOf(LockOwnershipLostException.class);
        assertThatThrownBy(() -> manager.mutex("orders:77").tryLock(Duration.ZERO))
            .isInstanceOf(LockOwnershipLostException.class);
        assertThatThrownBy(lock::unlock)
            .isInstanceOf(LockOwnershipLostException.class);

        assertThat(lock.tryLock(Duration.ZERO)).isTrue();
        lock.unlock();
        assertThat(lock.isHeldByCurrentThread()).isFalse();
    }

    @Test
    void siblingHandlesShouldRecoverWithoutHandlePoison() throws Exception {
        OwnershipLossLeaseBackend backend = new OwnershipLossLeaseBackend();
        DefaultLockManager manager = new DefaultLockManager(backend);
        MutexLock first = manager.mutex("orders:78");
        MutexLock second = manager.mutex("orders:78");

        first.lock();
        backend.invalidateCurrentLease();

        assertThatThrownBy(() -> second.tryLock(Duration.ZERO))
            .isInstanceOf(LockOwnershipLostException.class);
        assertThatThrownBy(first::unlock)
            .isInstanceOf(LockOwnershipLostException.class);

        assertThat(second.tryLock(Duration.ZERO)).isTrue();
        second.unlock();
        assertThat(first.tryLock(Duration.ZERO)).isTrue();
        first.unlock();
    }

    @Test
    void crossThreadRecoveryShouldInstallFreshLeaseOverStaleLocalOwner() throws Exception {
        OwnershipLossLeaseBackend backend = new OwnershipLossLeaseBackend();
        DefaultLockManager manager = new DefaultLockManager(backend);
        MutexLock owner = manager.mutex("orders:79");
        owner.lock();
        backend.invalidateCurrentLease();

        AtomicBoolean contenderAcquired = new AtomicBoolean();
        AtomicBoolean contenderReleased = new AtomicBoolean();
        AtomicReference<Throwable> contenderFailure = new AtomicReference<>();
        Thread contender = new Thread(() -> {
            try {
                MutexLock contenderLock = manager.mutex("orders:79");
                if (contenderLock.tryLock(Duration.ZERO)) {
                    contenderAcquired.set(true);
                    contenderLock.unlock();
                    contenderReleased.set(true);
                }
            } catch (Throwable throwable) {
                contenderFailure.set(throwable);
            }
        });

        contender.start();
        contender.join();

        assertThat(contenderFailure.get()).isNull();
        assertThat(contenderAcquired.get()).isTrue();
        assertThat(contenderReleased.get()).isTrue();
    }

    @Test
    void staleLostWriteHoldShouldSurfaceOwnershipLossBeforeReadTransitionCheck() throws Exception {
        OwnershipLossLeaseBackend backend = new OwnershipLossLeaseBackend();
        DefaultLockManager manager = new DefaultLockManager(backend);
        MutexLock writeLock = manager.readWrite("orders:80").writeLock();
        MutexLock readLock = manager.readWrite("orders:80").readLock();

        writeLock.lock();
        backend.invalidateCurrentLease();

        assertThatThrownBy(() -> readLock.tryLock(Duration.ZERO))
            .isInstanceOf(LockOwnershipLostException.class);
        assertThatThrownBy(writeLock::unlock)
            .isInstanceOf(LockOwnershipLostException.class);

        assertThat(readLock.tryLock(Duration.ZERO)).isTrue();
        readLock.unlock();
    }

    @Test
    void staleLostReadHoldShouldSurfaceOwnershipLossBeforeWriteTransitionCheck() throws Exception {
        OwnershipLossLeaseBackend backend = new OwnershipLossLeaseBackend();
        DefaultLockManager manager = new DefaultLockManager(backend);
        MutexLock readLock = manager.readWrite("orders:81").readLock();
        MutexLock writeLock = manager.readWrite("orders:81").writeLock();

        readLock.lock();
        backend.invalidateCurrentLease();

        assertThatThrownBy(() -> writeLock.tryLock(Duration.ZERO))
            .isInstanceOf(LockOwnershipLostException.class);
        assertThatThrownBy(readLock::unlock)
            .isInstanceOf(LockOwnershipLostException.class);

        assertThat(writeLock.tryLock(Duration.ZERO)).isTrue();
        writeLock.unlock();
    }

    private static final class OwnershipLossLeaseBackend implements LockBackend {
        private final AtomicInteger nextLeaseId = new AtomicInteger();
        private final AtomicInteger currentLeaseId = new AtomicInteger();

        @Override
        public BackendLockLease acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) {
            int leaseId = nextLeaseId.incrementAndGet();
            currentLeaseId.set(leaseId);
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
                    return currentLeaseId.get() == leaseId;
                }

                @Override
                public void release() {
                    if (currentLeaseId.get() != leaseId) {
                        throw new LockOwnershipLostException("Synthetic ownership loss for " + resource.key());
                    }
                    currentLeaseId.compareAndSet(leaseId, 0);
                }
            };
        }

        private void invalidateCurrentLease() {
            currentLeaseId.set(0);
        }
    }
}
