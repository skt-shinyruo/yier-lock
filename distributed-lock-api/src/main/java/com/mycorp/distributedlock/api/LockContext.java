package com.mycorp.distributedlock.api;

import java.util.Objects;
import java.util.Optional;

public final class LockContext {

    private static final ThreadLocal<Frame> CURRENT_FRAME = new ThreadLocal<>();

    private LockContext() {
    }

    public static Optional<LockLease> currentLease() {
        return Optional.ofNullable(CURRENT_FRAME.get())
                .map(Frame::lease);
    }

    public static Optional<FencingToken> currentFencingToken() {
        return currentLease().map(LockLease::fencingToken);
    }

    public static boolean containsLease(LockKey key) {
        Objects.requireNonNull(key, "key");
        Frame frame = CURRENT_FRAME.get();
        while (frame != null) {
            if (frame.lease().key().equals(key)) {
                return true;
            }
            frame = frame.previous();
        }
        return false;
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
        Frame frame = new Frame(lease, CURRENT_FRAME.get());
        CURRENT_FRAME.set(frame);
        return new Binding(frame);
    }

    public static final class Binding implements AutoCloseable {
        private final Frame frame;
        private boolean closed;

        private Binding(Frame frame) {
            this.frame = frame;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (CURRENT_FRAME.get() != frame) {
                throw new IllegalStateException("Lock context bindings must be closed in LIFO order");
            }
            closed = true;
            if (frame.previous() == null) {
                CURRENT_FRAME.remove();
                return;
            }
            CURRENT_FRAME.set(frame.previous());
        }
    }

    private record Frame(LockLease lease, Frame previous) {
    }
}
