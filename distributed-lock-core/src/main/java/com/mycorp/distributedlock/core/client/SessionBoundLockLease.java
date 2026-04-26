package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class SessionBoundLockLease implements LockLease {

    private enum LifecycleState {
        ACTIVE,
        RELEASING,
        TERMINAL
    }

    private final BackendLockLease delegate;
    private final Consumer<SessionBoundLockLease> unregister;
    private final AtomicReference<LifecycleState> lifecycle = new AtomicReference<>(LifecycleState.ACTIVE);

    SessionBoundLockLease(BackendLockLease delegate, Consumer<SessionBoundLockLease> unregister) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.unregister = Objects.requireNonNull(unregister, "unregister");
    }

    @Override
    public LockKey key() {
        return delegate.key();
    }

    @Override
    public LockMode mode() {
        return delegate.mode();
    }

    @Override
    public FencingToken fencingToken() {
        return delegate.fencingToken();
    }

    @Override
    public LeaseState state() {
        return delegate.state();
    }

    @Override
    public boolean isValid() {
        return delegate.isValid();
    }

    @Override
    public void release() {
        if (!beginRelease()) {
            return;
        }

        boolean terminal = false;
        try {
            delegate.release();
            terminal = true;
        } catch (LockOwnershipLostException exception) {
            terminal = true;
            throw exception;
        } catch (RuntimeException exception) {
            terminal = delegateIsNoLongerActive();
            throw exception;
        } finally {
            finishRelease(terminal);
        }
    }

    private boolean beginRelease() {
        while (true) {
            LifecycleState current = lifecycle.get();
            if (current == LifecycleState.TERMINAL) {
                return false;
            }
            if (current == LifecycleState.RELEASING) {
                Thread.onSpinWait();
                continue;
            }
            if (lifecycle.compareAndSet(LifecycleState.ACTIVE, LifecycleState.RELEASING)) {
                return true;
            }
        }
    }

    private boolean delegateIsNoLongerActive() {
        try {
            return delegate.state() != LeaseState.ACTIVE;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void finishRelease(boolean terminal) {
        if (terminal) {
            lifecycle.set(LifecycleState.TERMINAL);
            unregister.accept(this);
            return;
        }
        lifecycle.compareAndSet(LifecycleState.RELEASING, LifecycleState.ACTIVE);
    }
}
