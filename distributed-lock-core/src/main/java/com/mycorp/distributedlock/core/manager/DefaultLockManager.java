package com.mycorp.distributedlock.core.manager;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.api.ReadWriteLock;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.SupportedLockModes;
import com.mycorp.distributedlock.core.backend.WaitPolicy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class DefaultLockManager implements LockManager {

    private final LockBackend backend;
    private final SupportedLockModes supportedLockModes;
    private final ConcurrentMap<String, LockCoordinator> coordinators = new ConcurrentHashMap<>();

    public DefaultLockManager(LockBackend backend) {
        this(backend, SupportedLockModes.standard());
    }

    public DefaultLockManager(LockBackend backend, SupportedLockModes supportedLockModes) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.supportedLockModes = Objects.requireNonNull(supportedLockModes, "supportedLockModes");
    }

    @Override
    public MutexLock mutex(String key) {
        String normalizedKey = normalizeKey(key);
        ensureModeSupported(LockMode.MUTEX);
        return new DefaultMutexLock(this, normalizedKey, LockMode.MUTEX);
    }

    @Override
    public ReadWriteLock readWrite(String key) {
        String normalizedKey = normalizeKey(key);
        ensureReadWriteSupported();
        return new DefaultReadWriteLock(this, normalizedKey);
    }

    boolean acquire(String key, LockMode mode, WaitPolicy waitPolicy) throws InterruptedException {
        ensureModeSupported(mode);
        String normalizedKey = normalizeKey(key);
        String coordinatorStateKey = stateKey(normalizedKey, mode);
        LockCoordinator coordinator = coordinators.computeIfAbsent(coordinatorStateKey, ignored ->
            new LockCoordinator(coordinatorStateKey));
        return coordinator.acquire(mode, waitPolicy);
    }

    void release(String key, LockMode mode) {
        ensureModeSupported(mode);
        requiredCoordinator(key, mode).release(mode);
    }

    void close(String key, LockMode mode) {
        if (!hasTrackedState(key, mode)) {
            return;
        }
        release(key, mode);
    }

    boolean isHeldByCurrentThread(String key, LockMode mode) {
        String normalizedKey = normalizeKey(key);
        LockCoordinator coordinator = coordinators.get(stateKey(normalizedKey, mode));
        return coordinator != null && coordinator.isHeldByCurrentThread(mode);
    }

    boolean hasTrackedState(String key, LockMode mode) {
        String normalizedKey = normalizeKey(key);
        LockCoordinator coordinator = coordinators.get(stateKey(normalizedKey, mode));
        return coordinator != null && coordinator.hasTrackedState(mode);
    }

    void ensureReadWriteSupported() {
        ensureModeSupported(LockMode.READ);
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

    private void ensureModeSupported(LockMode mode) {
        switch (mode) {
            case MUTEX -> {
                if (!supportedLockModes.mutexSupported()) {
                    throw new LockConfigurationException("Configured backend does not support mutex locks");
                }
            }
            case READ, WRITE -> {
                if (!supportedLockModes.readWriteSupported()) {
                    throw new LockConfigurationException("Configured backend does not support read/write locks");
                }
            }
        }
    }

    private LockCoordinator requiredCoordinator(String key, LockMode mode) {
        String normalizedKey = normalizeKey(key);
        LockCoordinator coordinator = coordinators.get(stateKey(normalizedKey, mode));
        if (coordinator == null) {
            throw new IllegalMonitorStateException("Current thread does not hold lock " + normalizedKey);
        }
        return coordinator;
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

    private final class LockCoordinator {
        private final String coordinatorStateKey;
        private final ReentrantLock stateLock = new ReentrantLock();
        private final Condition stateChanged = stateLock.newCondition();
        private final LockResource resource;

        private HeldLease mutexLease;
        private HeldLease writeLease;
        private final Map<Thread, HeldLease> readLeases = new HashMap<>();
        private Thread lostMutexOwner;
        private Thread lostWriteOwner;
        private final Map<Thread, Boolean> lostReadOwners = new HashMap<>();
        private int pendingFreshAcquisitions;

        private LockCoordinator(String coordinatorStateKey) {
            this.coordinatorStateKey = coordinatorStateKey;
            this.resource = new LockResource(coordinatorStateKey);
        }

        private boolean acquire(LockMode mode, WaitPolicy waitPolicy) throws InterruptedException {
            Thread current = Thread.currentThread();
            LockCoordinator replacement = null;
            LockOwnershipLostException invalidLeaseFailure = null;

            stateLock.lock();
            try {
                replacement = registeredCoordinator();
                if (replacement == this) {
                    HeldLease existingLease = existingLease(current, mode);
                    if (existingLease != null) {
                        try {
                            existingLease.assertValid();
                        } catch (LockOwnershipLostException exception) {
                            markLostOwnership(current, mode, existingLease);
                            stateChanged.signalAll();
                            invalidLeaseFailure = exception;
                        }
                        if (invalidLeaseFailure == null) {
                            existingLease.increment();
                            return true;
                        }
                    }

                    if (invalidLeaseFailure == null && hasPendingLostOwnership(current, mode)) {
                        invalidLeaseFailure = ownershipLostFailure();
                    }

                    if (invalidLeaseFailure == null) {
                        invalidLeaseFailure = conflictingLostOwnership(current, mode);
                    }

                    if (invalidLeaseFailure == null && mode == LockMode.READ && writeLease != null && writeLease.owner() == current) {
                        throw new IllegalStateException("Cannot acquire read lock while holding write lock");
                    }
                    if (invalidLeaseFailure == null && mode == LockMode.WRITE && readLeases.containsKey(current)) {
                        throw new IllegalStateException("Cannot acquire write lock while holding read lock");
                    }

                    if (invalidLeaseFailure == null) {
                        pendingFreshAcquisitions++;
                    }
                }
            } finally {
                stateLock.unlock();
            }

            if (invalidLeaseFailure != null) {
                retireIfPossible();
                throw invalidLeaseFailure;
            }

            if (replacement != this) {
                return replacement.acquire(mode, waitPolicy);
            }

            BackendLockLease acquiredLease;
            try {
                acquiredLease = backend.acquire(resource, mode, waitPolicy);
            } catch (InterruptedException | RuntimeException exception) {
                abandonFreshAcquisition();
                throw exception;
            }

            return finishFreshAcquisition(mode, current, acquiredLease);
        }

        private void release(LockMode mode) {
            Thread current = Thread.currentThread();
            HeldLease detachedLease;
            LockOwnershipLostException lostOwnershipFailure;

            stateLock.lock();
            try {
                lostOwnershipFailure = consumeLostOwnership(current, mode);
                detachedLease = lostOwnershipFailure == null ? detachForRelease(current, mode) : null;
                stateChanged.signalAll();
            } finally {
                stateLock.unlock();
            }

            if (lostOwnershipFailure != null) {
                retireIfPossible();
                throw lostOwnershipFailure;
            }

            if (detachedLease == null) {
                retireIfPossible();
                return;
            }

            try {
                detachedLease.releaseBackendLease();
            } finally {
                retireIfPossible();
            }
        }

        private boolean isHeldByCurrentThread(LockMode mode) {
            stateLock.lock();
            try {
                HeldLease existingLease = existingLease(Thread.currentThread(), mode);
                return existingLease != null && existingLease.lease.isValidForCurrentExecution();
            } finally {
                stateLock.unlock();
            }
        }

        private boolean hasTrackedState(LockMode mode) {
            stateLock.lock();
            try {
                return hasTrackedState(Thread.currentThread(), mode);
            } finally {
                stateLock.unlock();
            }
        }

        private HeldLease existingLease(Thread owner, LockMode mode) {
            return switch (mode) {
                case MUTEX -> mutexLease != null && mutexLease.owner() == owner ? mutexLease : null;
                case READ -> readLeases.get(owner);
                case WRITE -> writeLease != null && writeLease.owner() == owner ? writeLease : null;
            };
        }

        private LockOwnershipLostException conflictingLostOwnership(Thread owner, LockMode requestedMode) {
            return switch (requestedMode) {
                case MUTEX -> null;
                case READ -> conflictingWriteLostOwnership(owner);
                case WRITE -> conflictingReadLostOwnership(owner);
            };
        }

        private LockOwnershipLostException conflictingWriteLostOwnership(Thread owner) {
            if (writeLease != null && writeLease.owner() == owner) {
                try {
                    writeLease.assertValid();
                } catch (LockOwnershipLostException exception) {
                    markLostOwnership(owner, LockMode.WRITE, writeLease);
                    stateChanged.signalAll();
                    return exception;
                }
            }
            return lostWriteOwner == owner ? ownershipLostFailure() : null;
        }

        private LockOwnershipLostException conflictingReadLostOwnership(Thread owner) {
            HeldLease conflictingRead = readLeases.get(owner);
            if (conflictingRead != null) {
                try {
                    conflictingRead.assertValid();
                } catch (LockOwnershipLostException exception) {
                    markLostOwnership(owner, LockMode.READ, conflictingRead);
                    stateChanged.signalAll();
                    return exception;
                }
            }
            return lostReadOwners.containsKey(owner) ? ownershipLostFailure() : null;
        }

        private void markLostOwnership(Thread owner, LockMode mode, HeldLease expectedLease) {
            switch (mode) {
                case MUTEX -> {
                    if (mutexLease == expectedLease && mutexLease.owner() == owner) {
                        mutexLease = null;
                        lostMutexOwner = owner;
                    }
                }
                case READ -> readLeases.computeIfPresent(owner, (ignored, currentLease) -> {
                    if (currentLease == expectedLease) {
                        lostReadOwners.put(owner, Boolean.TRUE);
                        return null;
                    }
                    return currentLease;
                });
                case WRITE -> {
                    if (writeLease == expectedLease && writeLease.owner() == owner) {
                        writeLease = null;
                        lostWriteOwner = owner;
                    }
                }
            }
        }

        private LockOwnershipLostException consumeLostOwnership(Thread owner, LockMode mode) {
            boolean lostOwnership = switch (mode) {
                case MUTEX -> {
                    boolean matched = lostMutexOwner == owner;
                    if (matched) {
                        lostMutexOwner = null;
                    }
                    yield matched;
                }
                case READ -> lostReadOwners.remove(owner) != null;
                case WRITE -> {
                    boolean matched = lostWriteOwner == owner;
                    if (matched) {
                        lostWriteOwner = null;
                    }
                    yield matched;
                }
            };
            return lostOwnership ? ownershipLostFailure() : null;
        }

        private boolean hasPendingLostOwnership(Thread owner, LockMode mode) {
            return switch (mode) {
                case MUTEX -> lostMutexOwner == owner;
                case READ -> lostReadOwners.containsKey(owner);
                case WRITE -> lostWriteOwner == owner;
            };
        }

        private boolean canInstallFreshLease(Thread owner, LockMode mode) {
            return switch (mode) {
                case MUTEX -> mutexLease == null;
                case READ -> writeLease == null && !readLeases.containsKey(owner);
                case WRITE -> writeLease == null && readLeases.isEmpty();
            };
        }

        private void installFreshLease(Thread owner, BackendLockLease lease) {
            HeldLease heldLease = new HeldLease(owner, lease);
            switch (lease.mode()) {
                case MUTEX -> mutexLease = heldLease;
                case READ -> readLeases.put(owner, heldLease);
                case WRITE -> writeLease = heldLease;
            }
        }

        private HeldLease detachForRelease(Thread owner, LockMode mode) {
            return switch (mode) {
                case MUTEX -> detachExclusiveLease(owner, mutexLease, "mutex lock", lease -> mutexLease = lease);
                case READ -> detachReadLease(owner);
                case WRITE -> detachExclusiveLease(owner, writeLease, "write lock", lease -> writeLease = lease);
            };
        }

        private HeldLease detachReadLease(Thread owner) {
            HeldLease heldLease = readLeases.get(owner);
            if (heldLease == null) {
                throw new IllegalMonitorStateException("Current thread does not hold read lock");
            }
            if (!heldLease.decrementToZero()) {
                return null;
            }
            readLeases.remove(owner);
            return heldLease;
        }

        private HeldLease detachExclusiveLease(
            Thread owner,
            HeldLease heldLease,
            String lockType,
            ExclusiveLeaseSetter setter
        ) {
            if (heldLease == null || heldLease.owner() != owner) {
                throw new IllegalMonitorStateException("Current thread does not hold " + lockType);
            }
            if (!heldLease.decrementToZero()) {
                return null;
            }
            setter.set(null);
            return heldLease;
        }

        private boolean hasTrackedState(Thread owner, LockMode mode) {
            return switch (mode) {
                case MUTEX -> (mutexLease != null && mutexLease.owner() == owner) || lostMutexOwner == owner;
                case READ -> readLeases.containsKey(owner) || lostReadOwners.containsKey(owner);
                case WRITE -> (writeLease != null && writeLease.owner() == owner) || lostWriteOwner == owner;
            };
        }

        private boolean isEmpty() {
            return mutexLease == null
                && writeLease == null
                && readLeases.isEmpty()
                && lostMutexOwner == null
                && lostWriteOwner == null
                && lostReadOwners.isEmpty();
        }

        private LockOwnershipLostException ownershipLostFailure() {
            return new LockOwnershipLostException("Backend ownership lost for key " + resource.key());
        }

        private LockCoordinator registeredCoordinator() {
            LockCoordinator registered = coordinators.putIfAbsent(coordinatorStateKey, this);
            return registered == null ? this : registered;
        }

        private void abandonFreshAcquisition() {
            stateLock.lock();
            try {
                pendingFreshAcquisitions--;
                stateChanged.signalAll();
            } finally {
                stateLock.unlock();
            }
            retireIfPossible();
        }

        private boolean finishFreshAcquisition(LockMode mode, Thread current, BackendLockLease acquiredLease) {
            BackendLockLease leaseToRelease = null;

            stateLock.lock();
            try {
                pendingFreshAcquisitions--;
                if (acquiredLease == null) {
                    stateChanged.signalAll();
                    retireIfPossible();
                    return false;
                }
                if (!canInstallFreshLease(current, mode)) {
                    reconcileConflictingLocalState(current, mode);
                }
                if (canInstallFreshLease(current, mode)) {
                    installFreshLease(current, acquiredLease);
                    stateChanged.signalAll();
                    return true;
                }
                leaseToRelease = acquiredLease;
                stateChanged.signalAll();
            } finally {
                stateLock.unlock();
            }

            try {
                leaseToRelease.release();
            } finally {
                retireIfPossible();
            }
            throw new IllegalStateException("Cannot install backend lease for key " + acquiredLease.key());
        }

        private void reconcileConflictingLocalState(Thread owner, LockMode mode) {
            switch (mode) {
                case MUTEX -> {
                    if (mutexLease != null) {
                        markLostOwnership(mutexLease.owner(), LockMode.MUTEX, mutexLease);
                    }
                }
                case READ -> {
                    if (writeLease != null) {
                        markLostOwnership(writeLease.owner(), LockMode.WRITE, writeLease);
                    }
                }
                case WRITE -> {
                    if (writeLease != null) {
                        markLostOwnership(writeLease.owner(), LockMode.WRITE, writeLease);
                    }
                    if (!readLeases.isEmpty()) {
                        Map<Thread, HeldLease> staleReaders = new HashMap<>(readLeases);
                        staleReaders.forEach((reader, heldLease) ->
                            markLostOwnership(reader, LockMode.READ, heldLease));
                    }
                }
            }
            if (hasPendingLostOwnership(owner, mode)) {
                consumeLostOwnership(owner, mode);
            }
        }

        private void retireIfPossible() {
            stateLock.lock();
            try {
                if (pendingFreshAcquisitions == 0 && isEmpty()) {
                    coordinators.remove(coordinatorStateKey, this);
                }
            } finally {
                stateLock.unlock();
            }
        }
    }

    @FunctionalInterface
    private interface ExclusiveLeaseSetter {
        void set(HeldLease lease);
    }

    private static final class HeldLease {
        private final Thread owner;
        private final BackendLockLease lease;
        private int holdCount = 1;

        private HeldLease(Thread owner, BackendLockLease lease) {
            this.owner = owner;
            this.lease = lease;
        }

        private Thread owner() {
            return owner;
        }

        private void assertValid() {
            if (!lease.isValidForCurrentExecution()) {
                throw new LockOwnershipLostException("Backend ownership lost for key " + lease.key());
            }
        }

        private void increment() {
            holdCount++;
        }

        private boolean decrementToZero() {
            holdCount--;
            return holdCount == 0;
        }

        private void releaseBackendLease() {
            lease.release();
        }
    }
}
