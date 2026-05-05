package com.mycorp.distributedlock.context;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LockLease;

import java.util.Objects;
import java.util.Optional;

public final class LockScopeContext {

    private static final ThreadLocal<LockLease> CURRENT = new ThreadLocal<>();

    private LockScopeContext() {
    }

    public static Optional<LockLease> currentLease() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static FencingToken requireCurrentFencingToken() {
        return currentLease()
            .map(LockLease::fencingToken)
            .orElseThrow(() -> new IllegalStateException("No lock lease is bound to the current thread"));
    }

    static Binding bind(LockLease lease) {
        Objects.requireNonNull(lease, "lease");
        LockLease previous = CURRENT.get();
        CURRENT.set(lease);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
                return;
            }
            CURRENT.set(previous);
        };
    }

    @FunctionalInterface
    interface Binding extends AutoCloseable {
        @Override
        void close();
    }
}
