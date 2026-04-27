package com.mycorp.distributedlock.testkit.support;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class BlockingLeaseBackend implements LockBackend {

    private final CountDownLatch acquireAttempted = new CountDownLatch(1);
    private final CountDownLatch releaseObserved = new CountDownLatch(1);
    private final AtomicBoolean firstLeaseHeld = new AtomicBoolean();
    private final AtomicLong fencingCounter = new AtomicLong();

    @Override
    public BackendSession openSession() {
        return new BackendSession() {
            @Override
            public BackendLockLease acquire(LockRequest lockRequest) throws InterruptedException {
                if (firstLeaseHeld.compareAndSet(false, true)) {
                    return new TestLease(
                        lockRequest.key(),
                        lockRequest.mode(),
                        new FencingToken(fencingCounter.incrementAndGet())
                    );
                }
                acquireAttempted.countDown();
                long sleepMillis = switch (lockRequest.waitPolicy().mode()) {
                    case TRY_ONCE -> 0L;
                    case TIMED -> lockRequest.waitPolicy().timeout().toMillis();
                    case INDEFINITE -> 5_000L;
                };
                Thread.sleep(sleepMillis);
                throw new LockAcquisitionTimeoutException("Timed out acquiring test lease for " + lockRequest.key().value());
            }

            @Override
            public SessionState state() {
                return SessionState.ACTIVE;
            }

            @Override
            public void close() {
            }
        };
    }

    public boolean awaitAcquireAttempt() throws InterruptedException {
        return acquireAttempted.await(1, TimeUnit.SECONDS);
    }

    public boolean releaseObservedWithin(Duration duration) throws InterruptedException {
        return releaseObserved.await(duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    private final class TestLease implements BackendLockLease {
        private final LockKey key;
        private final LockMode mode;
        private final FencingToken fencingToken;
        private final AtomicBoolean released = new AtomicBoolean();

        private TestLease(LockKey key, LockMode mode, FencingToken fencingToken) {
            this.key = key;
            this.mode = mode;
            this.fencingToken = fencingToken;
        }

        @Override
        public LockKey key() {
            return key;
        }

        @Override
        public LockMode mode() {
            return mode;
        }

        @Override
        public FencingToken fencingToken() {
            return fencingToken;
        }

        @Override
        public LeaseState state() {
            return released.get() ? LeaseState.RELEASED : LeaseState.ACTIVE;
        }

        @Override
        public boolean isValid() {
            return !released.get();
        }

        @Override
        public void release() {
            if (released.compareAndSet(false, true)) {
                firstLeaseHeld.set(false);
                releaseObserved.countDown();
            }
        }
    }
}
