package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.spi.BackendLease;

import java.util.Objects;
import java.util.function.Consumer;

final class SessionBoundLockLease implements LockLease {

    private enum LifecycleState {
        ACTIVE,
        RELEASING,
        TERMINAL
    }

    private final BackendLease delegate;
    private final Consumer<SessionBoundLockLease> unregister;
    private LifecycleState lifecycle = LifecycleState.ACTIVE;

    SessionBoundLockLease(BackendLease delegate, Consumer<SessionBoundLockLease> unregister) {
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
        synchronized (this) {
            while (lifecycle == LifecycleState.RELEASING) {
                try {
                    wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new LockBackendException("Interrupted while waiting for lock release", exception);
                }
            }
            if (lifecycle == LifecycleState.TERMINAL) {
                return false;
            }
            lifecycle = LifecycleState.RELEASING;
            return true;
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
        synchronized (this) {
            if (terminal) {
                lifecycle = LifecycleState.TERMINAL;
            } else {
                lifecycle = LifecycleState.ACTIVE;
            }
            notifyAll();
        }
        if (terminal) {
            unregister.accept(this);
        }
    }
}
