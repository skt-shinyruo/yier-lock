package com.mycorp.distributedlock.core.manager;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.api.ReadWriteLock;
import com.mycorp.distributedlock.core.backend.BackendLockHandle;
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
        private BackendLockHandle mutexHandle;

        private Thread writeOwner;
        private int writeHoldCount;
        private BackendLockHandle writeHandle;

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
                case MUTEX -> current == mutexOwner && mutexHandle != null && backend.isHeldByCurrentExecution(mutexHandle);
                case READ -> {
                    ReadHold hold = readHolds.get(current);
                    yield hold != null && hold.handle != null && backend.isHeldByCurrentExecution(hold.handle);
                }
                case WRITE -> current == writeOwner && writeHandle != null && backend.isHeldByCurrentExecution(writeHandle);
            };
        }

        private boolean acquireMutex(Thread current, WaitPolicy waitPolicy) throws InterruptedException {
            if (current == mutexOwner) {
                mutexHoldCount++;
                return true;
            }
            BackendLockHandle acquiredHandle = backend.acquire(resource, LockMode.MUTEX, waitPolicy);
            if (acquiredHandle == null) {
                return false;
            }
            mutexHandle = acquiredHandle;
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

            BackendLockHandle handle = backend.acquire(resource, LockMode.READ, waitPolicy);
            if (handle == null) {
                return false;
            }
            readHolds.put(current, new ReadHold(handle));
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

            BackendLockHandle acquiredHandle = backend.acquire(resource, LockMode.WRITE, waitPolicy);
            if (acquiredHandle == null) {
                return false;
            }
            writeHandle = acquiredHandle;
            writeOwner = current;
            writeHoldCount = 1;
            return true;
        }

        private void releaseMutex(Thread current) {
            if (current != mutexOwner || mutexHandle == null) {
                throw new IllegalMonitorStateException("Current thread does not hold mutex lock");
            }
            mutexHoldCount--;
            if (mutexHoldCount == 0) {
                BackendLockHandle handle = mutexHandle;
                mutexHandle = null;
                mutexOwner = null;
                backend.release(handle);
            }
        }

        private void releaseRead(Thread current) {
            ReadHold hold = readHolds.get(current);
            if (hold == null || hold.handle == null) {
                throw new IllegalMonitorStateException("Current thread does not hold read lock");
            }
            hold.count--;
            if (hold.count == 0) {
                readHolds.remove(current);
                backend.release(hold.handle);
            }
        }

        private void releaseWrite(Thread current) {
            if (current != writeOwner || writeHandle == null) {
                throw new IllegalMonitorStateException("Current thread does not hold write lock");
            }
            writeHoldCount--;
            if (writeHoldCount == 0) {
                BackendLockHandle handle = writeHandle;
                writeHandle = null;
                writeOwner = null;
                backend.release(handle);
            }
        }

        private boolean isEmpty() {
            return mutexHandle == null && writeHandle == null && readHolds.isEmpty();
        }

        private static final class ReadHold {
            private final BackendLockHandle handle;
            private int count;

            private ReadHold(BackendLockHandle handle) {
                this.handle = handle;
                this.count = 1;
            }
        }
    }
}
