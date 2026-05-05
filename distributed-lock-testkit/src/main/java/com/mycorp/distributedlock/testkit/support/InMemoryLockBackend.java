package com.mycorp.distributedlock.testkit.support;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.spi.BackendClient;
import com.mycorp.distributedlock.spi.BackendLease;
import com.mycorp.distributedlock.spi.BackendSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryLockBackend implements BackendClient {

    private final Map<String, InMemoryLockState> lockStates = new ConcurrentHashMap<>();

    @Override
    public InMemoryBackendSession openSession() {
        return new InMemoryBackendSession(lockStates);
    }

    @Override
    public void close() {
    }

    static BackendLease acquireLease(Map<String, InMemoryLockState> lockStates, LockRequest request)
        throws InterruptedException {
        String keyValue = request.key().value();
        InMemoryLockState state = lockStates.computeIfAbsent(keyValue, ignored -> new InMemoryLockState());
        String ownerId = java.util.UUID.randomUUID().toString();
        boolean acquired = state.acquire(ownerId, request.mode(), request.waitPolicy());
        if (!acquired) {
            throw new LockAcquisitionTimeoutException("Timed out acquiring lock for " + keyValue);
        }
        try {
            return new InMemoryLease(
                request.key(),
                request.mode(),
                new FencingToken(state.nextFence()),
                keyValue,
                ownerId,
                state,
                lockStates,
                new AtomicBoolean(false)
            );
        } catch (RuntimeException exception) {
            state.release(ownerId, request.mode());
            removeIfIdle(lockStates, keyValue, state);
            throw exception;
        }
    }

    private static void removeIfIdle(Map<String, InMemoryLockState> lockStates, String key, InMemoryLockState state) {
        if (state.isIdle()) {
            lockStates.remove(key, state);
        }
    }

    static final class InMemoryLockState {
        private final java.util.Map<String, LockMode> owners = new java.util.HashMap<>();
        private final AtomicLong fencingCounter = new AtomicLong();
        private String exclusiveOwner;

        synchronized boolean acquire(String ownerId, LockMode mode, WaitPolicy waitPolicy) throws InterruptedException {
            return switch (waitPolicy.mode()) {
                case TRY_ONCE -> {
                    if (!canAcquire(mode)) {
                        yield false;
                    }
                    addOwner(ownerId, mode);
                    yield true;
                }
                case TIMED -> acquireTimed(ownerId, mode, waitPolicy.timeout().toNanos());
                case INDEFINITE -> {
                    while (!canAcquire(mode)) {
                        wait();
                    }
                    addOwner(ownerId, mode);
                    yield true;
                }
            };
        }

        synchronized long nextFence() {
            return fencingCounter.incrementAndGet();
        }

        synchronized void release(String ownerId, LockMode mode) {
            LockMode removed = owners.remove(ownerId);
            if (removed == null) {
                return;
            }
            if (removed != mode) {
                throw new IllegalStateException("lock mode mismatch for in-memory owner");
            }
            if (ownerId.equals(exclusiveOwner)) {
                exclusiveOwner = null;
            }
            notifyAll();
        }

        synchronized boolean isIdle() {
            return owners.isEmpty();
        }

        private boolean acquireTimed(String ownerId, LockMode mode, long remainingNanos) throws InterruptedException {
            long deadlineNanos = System.nanoTime() + remainingNanos;
            while (!canAcquire(mode)) {
                if (remainingNanos <= 0L) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
                remainingNanos = deadlineNanos - System.nanoTime();
            }
            addOwner(ownerId, mode);
            return true;
        }

        private boolean canAcquire(LockMode mode) {
            return switch (mode) {
                case MUTEX, WRITE -> owners.isEmpty();
                case READ -> exclusiveOwner == null;
            };
        }

        private void addOwner(String ownerId, LockMode mode) {
            owners.put(ownerId, mode);
            if (mode == LockMode.MUTEX || mode == LockMode.WRITE) {
                exclusiveOwner = ownerId;
            }
        }
    }

    private record InMemoryLease(
        LockKey key,
        LockMode mode,
        FencingToken fencingToken,
        String keyValue,
        String ownerId,
        InMemoryLockState lockState,
        Map<String, InMemoryLockState> lockStates,
        AtomicBoolean released
    ) implements BackendLease {

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
                lockState.release(ownerId, mode);
            }
        }
    }
}
