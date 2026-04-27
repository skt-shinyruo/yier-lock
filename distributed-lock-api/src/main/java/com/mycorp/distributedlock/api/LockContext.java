package com.mycorp.distributedlock.api;

import java.util.Objects;
import java.util.Optional;

public final class LockContext {

    private static final ThreadLocal<LockLease> CURRENT_LEASE = new ThreadLocal<>();

    private LockContext() {
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

    public static Binding bind(LockLease lease) {
        Objects.requireNonNull(lease, "lease");
        LockLease previous = CURRENT_LEASE.get();
        CURRENT_LEASE.set(lease);
        return new Binding(lease, previous);
    }

    public static final class Binding implements AutoCloseable {
        private final LockLease boundLease;
        private final LockLease previous;
        private boolean closed;

        private Binding(LockLease boundLease, LockLease previous) {
            this.boundLease = boundLease;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (CURRENT_LEASE.get() != boundLease) {
                throw new IllegalStateException("Lock context bindings must be closed in LIFO order");
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
