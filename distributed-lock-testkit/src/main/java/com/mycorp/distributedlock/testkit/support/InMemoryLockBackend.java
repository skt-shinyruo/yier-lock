package com.mycorp.distributedlock.testkit.support;

import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class InMemoryLockBackend implements LockBackend {

    private final Map<String, InMemoryLockState> lockStates = new ConcurrentHashMap<>();

    @Override
    public BackendLockLease acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) throws InterruptedException {
        InMemoryLockState state = lockStates.computeIfAbsent(resource.key(), ignored -> new InMemoryLockState());
        boolean acquired = switch (mode) {
            case MUTEX -> acquireMutex(state, waitPolicy);
            case READ -> acquireRead(state, waitPolicy);
            case WRITE -> acquireWrite(state, waitPolicy);
        };

        if (!acquired) {
            return null;
        }

        return new InMemoryLease(resource.key(), mode, state);
    }

    private static boolean acquireMutex(InMemoryLockState state, WaitPolicy waitPolicy) throws InterruptedException {
        if (waitPolicy.unbounded()) {
            state.mutex.lockInterruptibly();
            return true;
        }
        return state.mutex.tryLock(waitPolicy.waitTime().toMillis(), TimeUnit.MILLISECONDS);
    }

    private static boolean acquireRead(InMemoryLockState state, WaitPolicy waitPolicy) throws InterruptedException {
        if (waitPolicy.unbounded()) {
            state.readWrite.readLock().lockInterruptibly();
            return true;
        }
        return state.readWrite.readLock().tryLock(waitPolicy.waitTime().toMillis(), TimeUnit.MILLISECONDS);
    }

    private static boolean acquireWrite(InMemoryLockState state, WaitPolicy waitPolicy) throws InterruptedException {
        if (waitPolicy.unbounded()) {
            state.readWrite.writeLock().lockInterruptibly();
            return true;
        }
        return state.readWrite.writeLock().tryLock(waitPolicy.waitTime().toMillis(), TimeUnit.MILLISECONDS);
    }

    private record InMemoryLease(String key, LockMode mode, InMemoryLockState state) implements BackendLockLease {

        @Override
        public boolean isValidForCurrentExecution() {
            return switch (mode) {
                case MUTEX -> state.mutex.isHeldByCurrentThread();
                case READ -> state.readWrite.getReadHoldCount() > 0;
                case WRITE -> state.readWrite.isWriteLockedByCurrentThread();
            };
        }

        @Override
        public void release() {
            switch (mode) {
                case MUTEX -> state.mutex.unlock();
                case READ -> state.readWrite.readLock().unlock();
                case WRITE -> state.readWrite.writeLock().unlock();
            }
        }
    }

    private static final class InMemoryLockState {
        private final ReentrantLock mutex = new ReentrantLock();
        private final ReentrantReadWriteLock readWrite = new ReentrantReadWriteLock();
    }
}
