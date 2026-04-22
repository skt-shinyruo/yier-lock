package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockExecutor;
import com.mycorp.distributedlock.core.client.DefaultLockExecutor;
import com.mycorp.distributedlock.runtime.LockRuntime;

import java.util.Objects;

public final class ObservedLockRuntime implements LockRuntime {
    private final LockRuntime delegate;
    private final LockClient lockClient;
    private final LockExecutor lockExecutor;

    private ObservedLockRuntime(LockRuntime delegate, LockObservationSink sink, String backendId, boolean includeKey) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.lockClient = new ObservedLockClient(delegate.lockClient(), sink, backendId, includeKey);
        this.lockExecutor = new ObservedLockExecutor(new DefaultLockExecutor(lockClient), sink, backendId, includeKey);
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
    public LockExecutor lockExecutor() {
        return lockExecutor;
    }

    @Override
    public void close() throws Exception {
        delegate.close();
    }
}
