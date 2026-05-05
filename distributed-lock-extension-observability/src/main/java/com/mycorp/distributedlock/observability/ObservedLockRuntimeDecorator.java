package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.api.RuntimeInfo;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.runtime.LockRuntimeDecorator;

import java.util.Objects;

public final class ObservedLockRuntimeDecorator implements LockRuntimeDecorator {
    private final LockObservationSink sink;
    private final boolean includeKey;

    public ObservedLockRuntimeDecorator(LockObservationSink sink, boolean includeKey) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.includeKey = includeKey;
    }

    @Override
    public LockRuntime decorate(LockRuntime runtime) {
        LockRuntime delegate = Objects.requireNonNull(runtime, "runtime");
        String backendId = delegate.info().backendId();
        LockClient client = new ObservedLockClient(delegate.lockClient(), sink, backendId, includeKey);
        SynchronousLockExecutor executor = new ObservedLockExecutor(
            delegate.synchronousLockExecutor(),
            sink,
            backendId,
            includeKey
        );
        return new ObservedRuntime(delegate, client, executor);
    }

    private record ObservedRuntime(
        LockRuntime delegate,
        LockClient lockClient,
        SynchronousLockExecutor synchronousLockExecutor
    ) implements LockRuntime {
        @Override
        public RuntimeInfo info() {
            return delegate.info();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
