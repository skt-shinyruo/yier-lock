package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LockLease;

import java.util.Objects;
import java.util.Optional;

public final class CurrentLockContext {

    private static final ThreadLocal<LockLease> CURRENT_LEASE = new ThreadLocal<>();

    private CurrentLockContext() {
    }

    public static Optional<LockLease> currentLease() {
        return Optional.ofNullable(CURRENT_LEASE.get());
    }

    public static Optional<FencingToken> currentFencingToken() {
        return currentLease().map(LockLease::fencingToken);
    }

    public static LockLease requireCurrentLease() {
        return currentLease()
            .orElseThrow(() -> new IllegalStateException("No lock lease is bound to the current thread"));
    }

    public static FencingToken requireCurrentFencingToken() {
        return currentFencingToken()
            .orElseThrow(() -> new IllegalStateException("No fencing token is bound to the current thread"));
    }

    static Binding bind(LockLease lease) {
        Objects.requireNonNull(lease, "lease");
        LockLease previous = CURRENT_LEASE.get();
        CURRENT_LEASE.set(lease);
        return new Binding(previous);
    }

    static final class Binding implements AutoCloseable {
        private final LockLease previous;
        private boolean closed;

        private Binding(LockLease previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT_LEASE.remove();
                return;
            }
            CURRENT_LEASE.set(previous);
        }
    }
}
