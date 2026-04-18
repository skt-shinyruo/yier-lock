package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockExecutor;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.LockedSupplier;

import java.util.Objects;

public final class DefaultLockExecutor implements LockExecutor {

    private final LockClient client;

    public DefaultLockExecutor(LockClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public <T> T withLock(LockRequest request, LockedSupplier<T> action) throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(action, "action");
        try (LockSession session = client.openSession();
             LockLease lease = session.acquire(request);
             CurrentLockContext.Binding ignored = CurrentLockContext.bind(lease)) {
            return action.get();
        }
    }
}
