package com.mycorp.distributedlock.core.manager;

import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultLockManagerBlockingTest {

    @Test
    void blockedContenderMustNotPreventOwnerRelease() throws Exception {
        BlockingLeaseBackend backend = new BlockingLeaseBackend();
        DefaultLockManager manager = new DefaultLockManager(backend);
        MutexLock owner = manager.mutex("orders:42");
        owner.lock();

        Thread contender = new Thread(() -> {
            try {
                manager.mutex("orders:42").tryLock(Duration.ofSeconds(5));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        contender.start();

        backend.awaitAcquireAttempt();
        owner.unlock();

        assertThat(backend.releaseObservedWithin(Duration.ofSeconds(1))).isTrue();
        contender.interrupt();
        contender.join();
    }

    private static final class BlockingLeaseBackend implements LockBackend {
        private final CountDownLatch acquireAttempted = new CountDownLatch(1);
        private final CountDownLatch releaseObserved = new CountDownLatch(1);
        private final AtomicBoolean firstLeaseHeld = new AtomicBoolean();

        @Override
        public BackendLockLease acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) throws InterruptedException {
            acquireAttempted.countDown();
            if (firstLeaseHeld.compareAndSet(false, true)) {
                return new TestLease(resource.key(), mode, true);
            }
            Thread.sleep(waitPolicy.unbounded() ? 5_000L : waitPolicy.waitTime().toMillis());
            return null;
        }

        private void awaitAcquireAttempt() throws InterruptedException {
            acquireAttempted.await(1, TimeUnit.SECONDS);
        }

        private boolean releaseObservedWithin(Duration duration) throws InterruptedException {
            return releaseObserved.await(duration.toMillis(), TimeUnit.MILLISECONDS);
        }

        private final class TestLease implements BackendLockLease {
            private final String key;
            private final LockMode mode;
            private final boolean valid;

            private TestLease(String key, LockMode mode, boolean valid) {
                this.key = key;
                this.mode = mode;
                this.valid = valid;
            }

            @Override
            public String key() {
                return key;
            }

            @Override
            public LockMode mode() {
                return mode;
            }

            @Override
            public boolean isValidForCurrentExecution() {
                return valid;
            }

            @Override
            public void release() {
                firstLeaseHeld.set(false);
                releaseObserved.countDown();
            }
        }
    }
}
