package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockedAction;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;

import java.util.Objects;

public final class ObservedLockExecutor implements SynchronousLockExecutor {
    private final SynchronousLockExecutor delegate;
    private final LockObservationSink sink;
    private final String backendId;
    private final boolean includeKey;

    public ObservedLockExecutor(SynchronousLockExecutor delegate, LockObservationSink sink, String backendId, boolean includeKey) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.backendId = backendId;
        this.includeKey = includeKey;
    }

    @Override
    public <T> T withLock(LockRequest request, LockedAction<T> action) throws Exception {
        long startedNanos = System.nanoTime();
        try {
            T result = delegate.withLock(request, action);
            LockObservationSupport.publishSafely(sink, new LockObservationEvent(
                backendId,
                "executor",
                "scope",
                "success",
                request.mode(),
                LockObservationSupport.keyFor(request, includeKey),
                LockObservationSupport.durationSince(startedNanos),
                null
            ));
            return result;
        } catch (Throwable throwable) {
            LockObservationSupport.publishSafely(sink, new LockObservationEvent(
                backendId,
                "executor",
                "scope",
                LockObservationSupport.scopeOutcomeFor(throwable),
                request.mode(),
                LockObservationSupport.keyFor(request, includeKey),
                LockObservationSupport.durationSince(startedNanos),
                throwable
            ));
            throw throwable;
        }
    }
}
