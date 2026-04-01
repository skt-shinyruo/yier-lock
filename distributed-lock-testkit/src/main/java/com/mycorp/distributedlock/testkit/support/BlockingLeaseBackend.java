package com.mycorp.distributedlock.testkit.support;

import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BlockingLeaseBackend implements LockBackend {

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

    public void awaitAcquireAttempt() throws InterruptedException {
        acquireAttempted.await(1, TimeUnit.SECONDS);
    }

    public boolean releaseObservedWithin(Duration duration) throws InterruptedException {
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
