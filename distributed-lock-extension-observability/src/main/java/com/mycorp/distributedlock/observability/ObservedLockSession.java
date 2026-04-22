package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.SessionState;

import java.util.Objects;

public final class ObservedLockSession implements LockSession {
    private final LockSession delegate;
    private final LockObservationSink sink;
    private final String backendId;
    private final boolean includeKey;

    public ObservedLockSession(LockSession delegate, LockObservationSink sink, String backendId, boolean includeKey) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.backendId = backendId;
        this.includeKey = includeKey;
    }

    @Override
    public LockLease acquire(LockRequest request) throws InterruptedException {
        long startedNanos = System.nanoTime();
        try {
            LockLease lease = delegate.acquire(request);
            sink.record(new LockObservationEvent(
                backendId,
                "client",
                "acquire",
                "success",
                request.mode(),
                LockObservationSupport.keyFor(request, includeKey),
                LockObservationSupport.durationSince(startedNanos),
                null
            ));
            return lease;
        } catch (InterruptedException exception) {
            sink.record(new LockObservationEvent(
                backendId,
                "client",
                "acquire",
                "interrupted",
                request.mode(),
                LockObservationSupport.keyFor(request, includeKey),
                LockObservationSupport.durationSince(startedNanos),
                exception
            ));
            throw exception;
        } catch (RuntimeException exception) {
            sink.record(new LockObservationEvent(
                backendId,
                "client",
                "acquire",
                LockObservationSupport.outcomeFor(exception),
                request.mode(),
                LockObservationSupport.keyFor(request, includeKey),
                LockObservationSupport.durationSince(startedNanos),
                exception
            ));
            throw exception;
        }
    }

    @Override
    public SessionState state() {
        return delegate.state();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
