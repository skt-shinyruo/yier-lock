package com.mycorp.distributedlock.core.manager;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.api.ReadWriteLock;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class DefaultLockManager implements LockManager {

    private final LockBackend backend;
    private final ConcurrentMap<String, LockState> states = new ConcurrentHashMap<>();

    public DefaultLockManager(LockBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public MutexLock mutex(String key) {
        return new DefaultMutexLock(this, normalizeKey(key), LockMode.MUTEX);
    }

    @Override
    public ReadWriteLock readWrite(String key) {
        return new DefaultReadWriteLock(this, normalizeKey(key));
    }

    boolean acquire(String key, LockMode mode, WaitPolicy waitPolicy) throws InterruptedException {
        String normalizedKey = normalizeKey(key);
        String stateKey = stateKey(normalizedKey, mode);
        LockState state = states.computeIfAbsent(stateKey, ignored -> new LockState(backend, normalizedKey, stateKey));
        return state.acquire(mode, waitPolicy);
    }

    void release(String key, LockMode mode) {
        String normalizedKey = normalizeKey(key);
        String stateKey = stateKey(normalizedKey, mode);
        LockState state = states.get(stateKey);
        if (state == null) {
            throw new IllegalMonitorStateException("Current thread does not hold lock " + normalizedKey);
        }

        if (state.release(mode)) {
            states.remove(stateKey, state);
        }
    }

    boolean isHeldByCurrentThread(String key, LockMode mode) {
        String normalizedKey = normalizeKey(key);
        LockState state = states.get(stateKey(normalizedKey, mode));
        return state != null && state.isHeldByCurrentThread(mode);
    }

    static WaitPolicy indefiniteWait() {
        return WaitPolicy.indefinite();
    }

    static WaitPolicy timedWait(Duration waitTime) {
        Objects.requireNonNull(waitTime, "waitTime");
        if (waitTime.isNegative()) {
            throw new IllegalArgumentException("waitTime cannot be negative");
        }
        return WaitPolicy.timed(waitTime);
    }

    private static String normalizeKey(String key) {
        Objects.requireNonNull(key, "key");
        String normalized = key.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Lock key cannot be blank");
        }
        return normalized;
    }

    private static String stateKey(String key, LockMode mode) {
        return mode == LockMode.MUTEX ? "mutex:" + key : "rw:" + key;
    }

    private static final class LockState {
        private final LockBackend backend;
        private final LockResource resource;

        private Thread mutexOwner;
        private int mutexHoldCount;
        private BackendLockLease mutexLease;

        private Thread writeOwner;
        private int writeHoldCount;
        private BackendLockLease writeLease;

        private final Map<Thread, ReadHold> readHolds = new HashMap<>();

        private LockState(LockBackend backend, String key, String backendKey) {
            this.backend = backend;
            this.resource = new LockResource(backendKey);
        }

        private synchronized boolean acquire(LockMode mode, WaitPolicy waitPolicy) throws InterruptedException {
            Thread current = Thread.currentThread();
            return switch (mode) {
                case MUTEX -> acquireMutex(current, waitPolicy);
                case READ -> acquireRead(current, waitPolicy);
                case WRITE -> acquireWrite(current, waitPolicy);
            };
        }

        private synchronized boolean release(LockMode mode) {
            Thread current = Thread.currentThread();
            switch (mode) {
                case MUTEX -> releaseMutex(current);
                case READ -> releaseRead(current);
                case WRITE -> releaseWrite(current);
            }
            return isEmpty();
        }

        private synchronized boolean isHeldByCurrentThread(LockMode mode) {
            Thread current = Thread.currentThread();
            return switch (mode) {
                case MUTEX -> current == mutexOwner && mutexLease != null && mutexLease.isValidForCurrentExecution();
                case READ -> {
                    ReadHold hold = readHolds.get(current);
                    yield hold != null && hold.lease != null && hold.lease.isValidForCurrentExecution();
                }
                case WRITE -> current == writeOwner && writeLease != null && writeLease.isValidForCurrentExecution();
            };
        }

        private boolean acquireMutex(Thread current, WaitPolicy waitPolicy) throws InterruptedException {
            if (current == mutexOwner) {
                mutexHoldCount++;
                return true;
            }
            BackendLockLease acquiredLease = backend.acquire(resource, LockMode.MUTEX, waitPolicy);
            if (acquiredLease == null) {
                return false;
            }
            mutexLease = acquiredLease;
            mutexOwner = current;
            mutexHoldCount = 1;
            return true;
        }

        private boolean acquireRead(Thread current, WaitPolicy waitPolicy) throws InterruptedException {
            if (current == writeOwner) {
                throw new IllegalStateException("Cannot acquire read lock while holding write lock");
            }
            ReadHold hold = readHolds.get(current);
            if (hold != null) {
                hold.count++;
                return true;
            }

            BackendLockLease lease = backend.acquire(resource, LockMode.READ, waitPolicy);
            if (lease == null) {
                return false;
            }
            readHolds.put(current, new ReadHold(lease));
            return true;
        }

        private boolean acquireWrite(Thread current, WaitPolicy waitPolicy) throws InterruptedException {
            if (readHolds.containsKey(current)) {
                throw new IllegalStateException("Cannot acquire write lock while holding read lock");
            }
            if (current == writeOwner) {
                writeHoldCount++;
                return true;
            }

            BackendLockLease acquiredLease = backend.acquire(resource, LockMode.WRITE, waitPolicy);
            if (acquiredLease == null) {
                return false;
            }
            writeLease = acquiredLease;
            writeOwner = current;
            writeHoldCount = 1;
            return true;
        }

        private void releaseMutex(Thread current) {
            if (current != mutexOwner || mutexLease == null) {
                throw new IllegalMonitorStateException("Current thread does not hold mutex lock");
            }
            mutexHoldCount--;
            if (mutexHoldCount == 0) {
                BackendLockLease lease = mutexLease;
                mutexLease = null;
                mutexOwner = null;
                lease.release();
            }
        }

        private void releaseRead(Thread current) {
            ReadHold hold = readHolds.get(current);
            if (hold == null || hold.lease == null) {
                throw new IllegalMonitorStateException("Current thread does not hold read lock");
            }
            hold.count--;
            if (hold.count == 0) {
                readHolds.remove(current);
                hold.lease.release();
            }
        }

        private void releaseWrite(Thread current) {
            if (current != writeOwner || writeLease == null) {
                throw new IllegalMonitorStateException("Current thread does not hold write lock");
            }
            writeHoldCount--;
            if (writeHoldCount == 0) {
                BackendLockLease lease = writeLease;
                writeLease = null;
                writeOwner = null;
                lease.release();
            }
        }

        private boolean isEmpty() {
            return mutexLease == null && writeLease == null && readHolds.isEmpty();
        }

        private static final class ReadHold {
            private final BackendLockLease lease;
            private int count;

            private ReadHold(BackendLockLease lease) {
                this.lease = lease;
                this.count = 1;
            }
        }
    }
}
