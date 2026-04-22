package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockSession;

import java.util.Objects;

public final class ObservedLockClient implements LockClient {
    private final LockClient delegate;
    private final LockObservationSink sink;
    private final String backendId;
    private final boolean includeKey;

    public ObservedLockClient(LockClient delegate, LockObservationSink sink, String backendId, boolean includeKey) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.backendId = backendId;
        this.includeKey = includeKey;
    }

    @Override
    public LockSession openSession() {
        return new ObservedLockSession(delegate.openSession(), sink, backendId, includeKey);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
