package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.api.RuntimeInfo;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;

/**
 * Backwards-compatible runtime wrapper entry point. Prefer {@link ObservedLockRuntimeDecorator}
 * when composing a runtime through {@code LockRuntimeBuilder}.
 */
public final class ObservedLockRuntime implements LockRuntime {
    private final LockRuntime delegate;

    private ObservedLockRuntime(LockRuntime delegate) {
        this.delegate = delegate;
    }

    public static ObservedLockRuntime decorate(
        LockRuntime delegate,
        LockObservationSink sink,
        String backendId,
        boolean includeKey
    ) {
        return new ObservedLockRuntime(new ObservedLockRuntimeDecorator(sink, includeKey).decorate(delegate));
    }

    @Override
    public LockClient lockClient() {
        return delegate.lockClient();
    }

    @Override
    public SynchronousLockExecutor synchronousLockExecutor() {
        return delegate.synchronousLockExecutor();
    }

    @Override
    public RuntimeInfo info() {
        return delegate.info();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
