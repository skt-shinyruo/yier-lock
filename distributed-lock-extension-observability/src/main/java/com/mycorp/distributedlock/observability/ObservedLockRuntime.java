package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.core.client.DefaultSynchronousLockExecutor;
import com.mycorp.distributedlock.runtime.LockRuntime;

import java.util.Objects;

/**
 * Observability decorator for the standard runtime composition produced by {@code LockRuntimeBuilder}.
 *
 * <p>This decorator intentionally rebuilds the executor around the observed client so that
 * executor-scoped calls emit both acquire and scope observations. It is not a transparent
 * wrapper for arbitrary custom {@link LockRuntime} implementations with bespoke executor
 * semantics.</p>
 */
public final class ObservedLockRuntime implements LockRuntime {
    private final LockRuntime delegate;
    private final LockClient lockClient;
    private final SynchronousLockExecutor synchronousLockExecutor;

    private ObservedLockRuntime(LockRuntime delegate, LockObservationSink sink, String backendId, boolean includeKey) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.lockClient = new ObservedLockClient(delegate.lockClient(), sink, backendId, includeKey);
        this.synchronousLockExecutor = new ObservedLockExecutor(
            new DefaultSynchronousLockExecutor(lockClient),
            sink,
            backendId,
            includeKey
        );
    }

    public static ObservedLockRuntime decorate(
        LockRuntime delegate,
        LockObservationSink sink,
        String backendId,
        boolean includeKey
    ) {
        return new ObservedLockRuntime(delegate, sink, backendId, includeKey);
    }

    @Override
    public LockClient lockClient() {
        return lockClient;
    }

    @Override
    public SynchronousLockExecutor synchronousLockExecutor() {
        return synchronousLockExecutor;
    }

    @Override
    public void close() {
        delegate.close();
    }
}
