package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;

import java.util.Objects;

final class SynchronousLockScope {

    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

    private SynchronousLockScope() {
    }

    static boolean contains(LockKey key) {
        Objects.requireNonNull(key, "key");
        Frame frame = CURRENT.get();
        while (frame != null) {
            if (frame.lease().key().equals(key)) {
                return true;
            }
            frame = frame.previous();
        }
        return false;
    }

    static Binding bind(LockLease lease) {
        Objects.requireNonNull(lease, "lease");
        Frame frame = new Frame(lease, CURRENT.get());
        CURRENT.set(frame);
        return new Binding(frame);
    }

    static final class Binding implements AutoCloseable {
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
            if (CURRENT.get() != frame) {
                throw new IllegalStateException("Lock scope bindings must be closed in LIFO order");
            }
            closed = true;
            if (frame.previous() == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(frame.previous());
            }
        }
    }

    private record Frame(LockLease lease, Frame previous) {
    }
}
