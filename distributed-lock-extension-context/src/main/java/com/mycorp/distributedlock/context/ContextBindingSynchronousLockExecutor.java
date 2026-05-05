package com.mycorp.distributedlock.context;

import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockedAction;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;

import java.util.Objects;

public final class ContextBindingSynchronousLockExecutor implements SynchronousLockExecutor {
    private final SynchronousLockExecutor delegate;

    public ContextBindingSynchronousLockExecutor(SynchronousLockExecutor delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public <T> T withLock(LockRequest request, LockedAction<T> action) throws Exception {
        return delegate.withLock(request, lease -> {
            try (LockScopeContext.Binding ignored = LockScopeContext.bind(lease)) {
                return action.execute(lease);
            }
        });
    }
}
