package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockExecutor;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.LockedSupplier;
import com.mycorp.distributedlock.api.SessionRequest;

import java.util.Objects;

public final class DefaultLockExecutor implements LockExecutor {

    private final LockClient client;
    private final SessionRequest sessionRequest;

    public DefaultLockExecutor(LockClient client, SessionRequest sessionRequest) {
        this.client = Objects.requireNonNull(client, "client");
        this.sessionRequest = Objects.requireNonNull(sessionRequest, "sessionRequest");
    }

    @Override
    public <T> T withLock(LockRequest request, LockedSupplier<T> action) throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(action, "action");
        try (LockSession session = client.openSession(sessionRequest);
             LockLease lease = session.acquire(request)) {
            return action.get();
        }
    }
}
