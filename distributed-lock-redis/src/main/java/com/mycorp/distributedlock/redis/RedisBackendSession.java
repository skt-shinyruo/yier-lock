package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitMode;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.api.exception.LockFailureContext;
import com.mycorp.distributedlock.api.exception.LockSessionLostException;
import io.lettuce.core.ScriptOutputType;
import com.mycorp.distributedlock.spi.BackendLease;
import com.mycorp.distributedlock.spi.BackendSession;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class RedisBackendSession implements BackendSession {

    private final RedisLockBackend backend;
    final String sessionId;
    private final ConcurrentMap<String, RedisLease> activeLeases = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private final AtomicReference<RuntimeException> lossCause = new AtomicReference<>();
    private final ScheduledFuture<?> renewalTask;

    RedisBackendSession(RedisLockBackend backend, String sessionId) {
        this.backend = backend;
        this.sessionId = sessionId;
        touchSessionKey();
        this.renewalTask = scheduleRenewal();
    }

    @Override
    public BackendLease acquire(LockRequest request) throws InterruptedException {
        ensureActive(request);

        String exclusiveIntent = request.mode() == com.mycorp.distributedlock.api.LockMode.WRITE
            || request.mode() == com.mycorp.distributedlock.api.LockMode.MUTEX
            ? RedisLockBackend.writerIntentValue(sessionId, RedisLockBackend.nextSessionId())
            : null;
        boolean acquired = false;
        boolean cleanupAttempted = false;

        try {
            long deadlineNanos = deadlineNanos(request);

            do {
                ensureActive(request);
                RedisLease lease = tryAcquire(request, exclusiveIntent);
                if (lease != null) {
                    acquired = true;
                    activeLeases.put(lease.ownerValue(), lease);
                    if (lease.renewable()) {
                        lease.startRenewal();
                    }
                    return lease;
                }
                if (request.waitPolicy().mode() == WaitMode.TRY_ONCE || System.nanoTime() >= deadlineNanos) {
                    throw new LockAcquisitionTimeoutException(
                        "Timed out acquiring Redis lock for key " + request.key().value(),
                        null,
                        LockFailureContext.fromRequest(request, "redis", sessionId)
                    );
                }
                Thread.sleep(sleepMillis(request, deadlineNanos));
            } while (true);
        } catch (InterruptedException exception) {
            cleanupPendingWriterIntent(request, exclusiveIntent, exception);
            cleanupAttempted = true;
            Thread.currentThread().interrupt();
            throw exception;
        } catch (RuntimeException exception) {
            cleanupPendingWriterIntent(request, exclusiveIntent, exception);
            cleanupAttempted = true;
            throw exception;
        } finally {
            if (!acquired && !cleanupAttempted && exclusiveIntent != null) {
                clearPendingWriterIntentBestEffort(request.key().value(), exclusiveIntent);
            }
        }
    }

    @Override
    public SessionState state() {
        if (closed.get()) {
            return SessionState.CLOSED;
        }
        return valid.get() ? SessionState.ACTIVE : SessionState.LOST;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        renewalTask.cancel(false);
        RuntimeException terminalLoss = !valid.get() ? lossCause() : null;
        RuntimeException failure = null;
        for (RedisLease lease : new ArrayList<>(activeLeases.values())) {
            try {
                lease.release();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }

        try {
            backend.commands().del(RedisLockBackend.sessionKey(sessionId));
        } catch (RuntimeException exception) {
            if (terminalLoss != null) {
                terminalLoss.addSuppressed(exception);
            } else if (failure == null) {
                failure = new LockBackendException("Failed to close Redis session " + sessionId, exception);
            } else {
                failure.addSuppressed(exception);
            }
        }

        if (failure != null) {
            throw failure;
        }
        if (terminalLoss != null) {
            throw terminalLoss;
        }
    }

    private RedisLease tryAcquire(LockRequest request, String exclusiveIntent) {
        long leaseMillis = effectiveLeaseMillis(request);
        long fence = switch (request.mode()) {
            case MUTEX -> tryAcquireMutex(request, leaseMillis, Objects.requireNonNull(exclusiveIntent, "exclusiveIntent"));
            case READ -> tryAcquireRead(request, leaseMillis);
            case WRITE -> tryAcquireWrite(request, leaseMillis, Objects.requireNonNull(exclusiveIntent, "exclusiveIntent"));
        };
        if (fence <= 0) {
            return null;
        }

        return new RedisLease(
            backend,
            request.key(),
            request.mode(),
            new FencingToken(fence),
            RedisLockBackend.ownerValue(sessionId, fence),
            this,
            leaseMillis,
            renewable(request)
        );
    }

    private long tryAcquireMutex(LockRequest request, long leaseMillis, String exclusiveIntent) {
        try {
            String key = request.key().value();
            RedisLockKeys keys = backend.keys(key);
            Long result = backend.commands().eval(
                RedisScripts.MUTEX_ACQUIRE,
                ScriptOutputType.INTEGER,
                new String[]{
                    keys.ownerKey(com.mycorp.distributedlock.api.LockMode.MUTEX),
                    keys.ownerKey(com.mycorp.distributedlock.api.LockMode.WRITE),
                    keys.readersKey(),
                    keys.fenceKey(),
                    keys.pendingWritersKey()
                },
                sessionId,
                String.valueOf(leaseMillis),
                exclusiveIntent,
                String.valueOf(pendingWriterIntentMillis())
            );
            return result == null ? 0L : result;
        } catch (RuntimeException exception) {
            throw new LockBackendException(
                "Failed to acquire Redis mutex for key " + request.key().value(),
                exception,
                LockFailureContext.fromRequest(request, "redis", sessionId)
            );
        }
    }

    private long tryAcquireRead(LockRequest request, long leaseMillis) {
        try {
            String key = request.key().value();
            RedisLockKeys keys = backend.keys(key);
            Long result = backend.commands().eval(
                RedisScripts.READ_ACQUIRE,
                ScriptOutputType.INTEGER,
                new String[]{
                    keys.ownerKey(com.mycorp.distributedlock.api.LockMode.MUTEX),
                    keys.ownerKey(com.mycorp.distributedlock.api.LockMode.WRITE),
                    keys.readersKey(),
                    keys.fenceKey(),
                    keys.pendingWritersKey()
                },
                sessionId,
                String.valueOf(leaseMillis)
            );
            return result == null ? 0L : result;
        } catch (RuntimeException exception) {
            throw new LockBackendException(
                "Failed to acquire Redis read lock for key " + request.key().value(),
                exception,
                LockFailureContext.fromRequest(request, "redis", sessionId)
            );
        }
    }

    private long tryAcquireWrite(LockRequest request, long leaseMillis, String writerIntent) {
        try {
            String key = request.key().value();
            RedisLockKeys keys = backend.keys(key);
            Long result = backend.commands().eval(
                RedisScripts.WRITE_ACQUIRE,
                ScriptOutputType.INTEGER,
                new String[]{
                    keys.ownerKey(com.mycorp.distributedlock.api.LockMode.MUTEX),
                    keys.ownerKey(com.mycorp.distributedlock.api.LockMode.WRITE),
                    keys.readersKey(),
                    keys.fenceKey(),
                    keys.pendingWritersKey()
                },
                sessionId,
                String.valueOf(leaseMillis),
                writerIntent,
                String.valueOf(pendingWriterIntentMillis())
            );
            return result == null ? 0L : result;
        } catch (RuntimeException exception) {
            throw new LockBackendException(
                "Failed to acquire Redis write lock for key " + request.key().value(),
                exception,
                LockFailureContext.fromRequest(request, "redis", sessionId)
            );
        }
    }

    private void cleanupPendingWriterIntent(LockRequest request, String exclusiveIntent, Throwable failure) {
        if (exclusiveIntent == null) {
            return;
        }
        try {
            clearPendingWriterIntent(request.key().value(), exclusiveIntent);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void clearPendingWriterIntentBestEffort(String key, String writerIntent) {
        try {
            clearPendingWriterIntent(key, writerIntent);
        } catch (RuntimeException ignored) {
        }
    }

    private void clearPendingWriterIntent(String key, String writerIntent) {
        try {
            RedisLockKeys keys = backend.keys(key);
            backend.commands().eval(
                RedisScripts.PENDING_WRITER_CLEANUP,
                ScriptOutputType.INTEGER,
                new String[]{keys.pendingWritersKey()},
                writerIntent
            );
        } catch (RuntimeException exception) {
            throw new LockBackendException("Failed to clear Redis pending writer intent for key " + key, exception);
        }
    }

    private ScheduledFuture<?> scheduleRenewal() {
        long sessionLeaseMillis = TimeUnit.SECONDS.toMillis(backend.configuration().leaseSeconds());
        long periodMillis = RedisLockBackend.renewalPeriodMillis(sessionLeaseMillis);
        long initialDelayMillis = RedisLockBackend.initialRenewalDelayMillis(sessionLeaseMillis, periodMillis);
        return backend.renewalExecutor().scheduleAtFixedRate(this::renewSession, initialDelayMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    private void renewSession() {
        if (closed.get()) {
            return;
        }
        try {
            if (!renewSessionKey()) {
                markSessionLost(new LockSessionLostException("Redis session lost: " + sessionId));
            }
        } catch (RuntimeException exception) {
            markSessionLost(new LockSessionLostException("Redis session lost: " + sessionId, exception));
        }
    }

    void markSessionLostFromRenewal(RuntimeException cause) {
        markSessionLost(new LockSessionLostException("Redis session lost: " + sessionId, cause));
    }

    private boolean renewSessionKey() {
        try {
            return Boolean.TRUE.equals(backend.commands().expire(RedisLockBackend.sessionKey(sessionId), backend.configuration().leaseSeconds()));
        } catch (RuntimeException exception) {
            throw new LockBackendException("Failed to renew Redis session " + sessionId, exception);
        }
    }

    private void touchSessionKey() {
        try {
            backend.commands().setex(RedisLockBackend.sessionKey(sessionId), backend.configuration().leaseSeconds(), "1");
        } catch (RuntimeException exception) {
            throw new LockBackendException("Failed to create Redis session " + sessionId, exception);
        }
    }

    void forgetLease(RedisLease lease) {
        activeLeases.remove(lease.ownerValue());
    }

    private void markSessionLost(RuntimeException cause) {
        lossCause.compareAndSet(null, cause);
        if (!valid.compareAndSet(true, false)) {
            return;
        }
        for (RedisLease lease : new ArrayList<>(activeLeases.values())) {
            lease.markLost();
        }
        renewalTask.cancel(false);
    }

    private RuntimeException lossCause() {
        RuntimeException cause = lossCause.get();
        return cause != null ? cause : new LockSessionLostException("Redis session lost: " + sessionId);
    }

    private void ensureActive(LockRequest request) {
        if (closed.get()) {
            throw new IllegalStateException("Redis session is already closed");
        }
        if (!valid.get()) {
            throw new LockSessionLostException(
                "Redis session lost: " + sessionId,
                null,
                LockFailureContext.fromRequest(request, "redis", sessionId)
            );
        }
    }

    private long deadlineNanos(LockRequest request) {
        return switch (request.waitPolicy().mode()) {
            case TRY_ONCE -> System.nanoTime();
            case TIMED -> System.nanoTime() + request.waitPolicy().timeout().toNanos();
            case INDEFINITE -> Long.MAX_VALUE;
        };
    }

    private long sleepMillis(LockRequest request, long deadlineNanos) {
        if (request.waitPolicy().mode() == WaitMode.INDEFINITE) {
            return 25L;
        }
        return Math.min(25L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())));
    }

    private long effectiveLeaseMillis(LockRequest request) {
        if (request.leasePolicy().mode() == LeaseMode.BACKEND_DEFAULT) {
            return TimeUnit.SECONDS.toMillis(backend.configuration().leaseSeconds());
        }
        return Math.max(1L, request.leasePolicy().duration().toMillis());
    }

    private boolean renewable(LockRequest request) {
        return request.leasePolicy().mode() == LeaseMode.BACKEND_DEFAULT
            || backend.configuration().fixedLeaseRenewalEnabled();
    }

    private long pendingWriterIntentMillis() {
        return TimeUnit.SECONDS.toMillis(backend.configuration().leaseSeconds());
    }
}
