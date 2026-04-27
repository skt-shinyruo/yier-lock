package com.mycorp.distributedlock.testkit.support;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockBackend;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class InMemoryLockBackend implements LockBackend {

    private final Map<String, InMemoryLockState> lockStates = new ConcurrentHashMap<>();

    @Override
    public InMemoryBackendSession openSession() {
        return new InMemoryBackendSession(lockStates);
    }

    static BackendLockLease acquireLease(Map<String, InMemoryLockState> lockStates, LockRequest request)
        throws InterruptedException {
        InMemoryLockState state = lockStates.computeIfAbsent(request.key().value(), ignored -> new InMemoryLockState());
        boolean acquired = switch (request.mode()) {
            case MUTEX -> acquireMutex(state, request.waitPolicy());
            case READ -> acquireRead(state, request.waitPolicy());
            case WRITE -> acquireWrite(state, request.waitPolicy());
        };

        if (!acquired) {
            throw new LockAcquisitionTimeoutException("Timed out acquiring lock for " + request.key().value());
        }

        return new InMemoryLease(
            request.key(),
            request.mode(),
            new FencingToken(state.fencingCounter.incrementAndGet()),
            state,
            new AtomicBoolean(false)
        );
    }

    static void unlockState(InMemoryLockState state, LockMode mode) {
        switch (mode) {
            case MUTEX -> state.readWrite.writeLock().unlock();
            case READ -> state.readWrite.readLock().unlock();
            case WRITE -> state.readWrite.writeLock().unlock();
        }
    }

    private static boolean acquireMutex(InMemoryLockState state, WaitPolicy waitPolicy) throws InterruptedException {
        return tryLock(state.readWrite.writeLock(), waitPolicy);
    }

    private static boolean acquireRead(InMemoryLockState state, WaitPolicy waitPolicy) throws InterruptedException {
        return tryLock(state.readWrite.readLock(), waitPolicy);
    }

    private static boolean acquireWrite(InMemoryLockState state, WaitPolicy waitPolicy) throws InterruptedException {
        return tryLock(state.readWrite.writeLock(), waitPolicy);
    }

    private static boolean tryLock(Lock lock, WaitPolicy waitPolicy) throws InterruptedException {
        return switch (waitPolicy.mode()) {
            case TRY_ONCE -> lock.tryLock();
            case TIMED -> lock.tryLock(waitPolicy.timeout().toMillis(), TimeUnit.MILLISECONDS);
            case INDEFINITE -> {
                lock.lockInterruptibly();
                yield true;
            }
        };
    }

    static final class InMemoryLockState {
        final ReentrantReadWriteLock readWrite = new ReentrantReadWriteLock();
        final AtomicLong fencingCounter = new AtomicLong();
    }

    private record InMemoryLease(
        LockKey key,
        LockMode mode,
        FencingToken fencingToken,
        InMemoryLockState lockState,
        AtomicBoolean released
    ) implements BackendLockLease {

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
                unlockState(lockState, mode);
            }
        }
    }
}
