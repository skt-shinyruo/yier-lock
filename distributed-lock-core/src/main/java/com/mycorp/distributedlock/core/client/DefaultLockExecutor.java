package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockExecutor;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.LockedSupplier;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
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
            T result = action.get();
            rejectAsyncResult(result);
            return result;
        }
    }

    private static void rejectAsyncResult(Object result) {
        if (result == null) {
            return;
        }
        if (result instanceof CompletionStage<?>) {
            throw new LockConfigurationException(
                "LockExecutor only supports synchronous actions, but the action returned CompletionStage"
            );
        }
        if (result instanceof Future<?>) {
            throw new LockConfigurationException(
                "LockExecutor only supports synchronous actions, but the action returned Future"
            );
        }
        if (isReactivePublisher(result)) {
            throw new LockConfigurationException(
                "LockExecutor only supports synchronous actions, but the action returned Publisher"
            );
        }
    }

    private static boolean isReactivePublisher(Object result) {
        try {
            Class<?> publisherType = Class.forName(
                "org.reactivestreams.Publisher",
                false,
                result.getClass().getClassLoader()
            );
            return publisherType.isInstance(result);
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }
}
