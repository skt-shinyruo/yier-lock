package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.api.exception.LockFailureContext;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import io.lettuce.core.ScriptOutputType;
import com.mycorp.distributedlock.spi.BackendLease;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class RedisLease implements BackendLease {

    private final RedisLockBackend backend;
    private final LockKey key;
    private final LockMode mode;
    private final FencingToken fencingToken;
    private final String ownerValue;
    private final RedisBackendSession session;
    private final long leaseMillis;
    private final boolean renewable;
    private final AtomicReference<LeaseState> state = new AtomicReference<>(LeaseState.ACTIVE);
    private final AtomicReference<ScheduledFuture<?>> renewalTask = new AtomicReference<>();

    RedisLease(
        RedisLockBackend backend,
        LockKey key,
        LockMode mode,
        FencingToken fencingToken,
        String ownerValue,
        RedisBackendSession session,
        long leaseMillis,
        boolean renewable
    ) {
        this.backend = backend;
        this.key = key;
        this.mode = mode;
        this.fencingToken = fencingToken;
        this.ownerValue = ownerValue;
        this.session = session;
        this.leaseMillis = leaseMillis;
        this.renewable = renewable;
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
        return state.get();
    }

    @Override
    public boolean isValid() {
        if (state.get() != LeaseState.ACTIVE) {
            return false;
        }
        if (session.state() != SessionState.ACTIVE) {
            return false;
        }
        return ownerValueMatches();
    }

    @Override
    public void release() {
        LeaseState current = state.get();
        if (current == LeaseState.RELEASED) {
            return;
        }
        if (current == LeaseState.LOST) {
            cancelRenewal();
            session.forgetLease(this);
            throw ownershipLostException();
        }

        try {
            RedisLockKeys keys = backend.keys(key.value());
            Long result = switch (mode) {
                case MUTEX -> backend.commands().eval(
                    RedisScripts.VALUE_RELEASE,
                    ScriptOutputType.INTEGER,
                    new String[]{keys.ownerKey(LockMode.MUTEX)},
                    ownerValue
                );
                case READ -> backend.commands().eval(
                    RedisScripts.READ_RELEASE,
                    ScriptOutputType.INTEGER,
                    new String[]{keys.readersKey()},
                    ownerValue
                );
                case WRITE -> backend.commands().eval(
                    RedisScripts.VALUE_RELEASE,
                    ScriptOutputType.INTEGER,
                    new String[]{keys.ownerKey(LockMode.WRITE)},
                    ownerValue
                );
            };
            if (result == null || result == 0L) {
                markLost();
                throw ownershipLostException();
            }
            state.set(LeaseState.RELEASED);
            cancelRenewal();
            session.forgetLease(this);
        } catch (RuntimeException exception) {
            if (exception instanceof LockOwnershipLostException || exception instanceof LockBackendException) {
                throw exception;
            }
            throw new LockBackendException(
                "Failed to release Redis lock for key " + key.value(),
                exception,
                failureContext()
            );
        }
    }

    void startRenewal() {
        long periodMillis = RedisLockBackend.renewalPeriodMillis(leaseMillis);
        long initialDelayMillis = RedisLockBackend.initialRenewalDelayMillis(leaseMillis, periodMillis);
        ScheduledFuture<?> scheduled = backend.renewalExecutor().scheduleAtFixedRate(
            this::renewLease,
            initialDelayMillis,
            periodMillis,
            TimeUnit.MILLISECONDS
        );
        if (!renewalTask.compareAndSet(null, scheduled)) {
            scheduled.cancel(false);
        }
    }

    private void renewLease() {
        if (state.get() != LeaseState.ACTIVE || session.state() != SessionState.ACTIVE) {
            cancelRenewal();
            return;
        }
        try {
            refresh();
        } catch (RuntimeException exception) {
            cancelRenewal();
            session.markSessionLostFromRenewal(exception);
        }
    }

    private boolean refresh() {
        if (state.get() != LeaseState.ACTIVE) {
            return false;
        }
        try {
            RedisLockKeys keys = backend.keys(key.value());
            Long result = switch (mode) {
                case MUTEX -> backend.commands().eval(
                    RedisScripts.VALUE_REFRESH,
                    ScriptOutputType.INTEGER,
                    new String[]{keys.ownerKey(LockMode.MUTEX)},
                    ownerValue,
                    String.valueOf(leaseMillis)
                );
                case READ -> backend.commands().eval(
                    RedisScripts.READ_REFRESH,
                    ScriptOutputType.INTEGER,
                    new String[]{keys.readersKey()},
                    ownerValue,
                    String.valueOf(leaseMillis)
                );
                case WRITE -> backend.commands().eval(
                    RedisScripts.VALUE_REFRESH,
                    ScriptOutputType.INTEGER,
                    new String[]{keys.ownerKey(LockMode.WRITE)},
                    ownerValue,
                    String.valueOf(leaseMillis)
                );
            };
            if (result == null || result == 0L) {
                markLost();
                return false;
            }
            return true;
        } catch (RuntimeException exception) {
            throw new LockBackendException(
                "Failed to renew Redis lock for key " + key.value(),
                exception,
                failureContext()
            );
        }
    }

    private boolean ownerValueMatches() {
        try {
            RedisLockKeys keys = backend.keys(key.value());
            return switch (mode) {
                case MUTEX -> ownerValue.equals(backend.commands().get(keys.ownerKey(LockMode.MUTEX)));
                case READ -> {
                    Long result = backend.commands().eval(
                        RedisScripts.READ_OWNER_MATCH,
                        ScriptOutputType.INTEGER,
                        new String[]{keys.readersKey()},
                        ownerValue
                    );
                    yield result != null && result == 1L;
                }
                case WRITE -> ownerValue.equals(backend.commands().get(keys.ownerKey(LockMode.WRITE)));
            };
        } catch (RuntimeException exception) {
            throw new LockBackendException(
                "Failed to inspect Redis lock for key " + key.value(),
                exception,
                failureContext()
            );
        }
    }

    void markLost() {
        state.compareAndSet(LeaseState.ACTIVE, LeaseState.LOST);
        cancelRenewal();
        session.forgetLease(this);
    }

    private LockOwnershipLostException ownershipLostException() {
        return new LockOwnershipLostException(
            "Redis lock ownership lost for key " + key.value(),
            null,
            failureContext()
        );
    }

    private LockFailureContext failureContext() {
        return new LockFailureContext(key, mode, null, null, "redis", session.sessionId);
    }

    private void cancelRenewal() {
        ScheduledFuture<?> scheduled = renewalTask.getAndSet(null);
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }

    String ownerValue() {
        return ownerValue;
    }

    boolean renewable() {
        return renewable;
    }
}
