package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.LockedAction;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.api.exception.LockFailureContext;
import com.mycorp.distributedlock.api.exception.LockReentryException;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;

public final class DefaultSynchronousLockExecutor implements SynchronousLockExecutor {

    private final LockClient client;

    public DefaultSynchronousLockExecutor(LockClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public <T> T withLock(LockRequest request, LockedAction<T> action) throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(action, "action");
        rejectReentry(request);
        try (LockSession session = client.openSession();
             LockLease lease = session.acquire(request);
             SynchronousLockScope.Binding ignored = SynchronousLockScope.bind(lease)) {
            T result = action.execute(lease);
            rejectAsyncResult(result);
            return result;
        }
    }

    private static void rejectReentry(LockRequest request) {
        if (SynchronousLockScope.contains(request.key())) {
            throw new LockReentryException(
                "Lock key is already held in the current synchronous scope: " + request.key().value(),
                null,
                LockFailureContext.fromRequest(request, null, null)
            );
        }
    }

    private static void rejectAsyncResult(Object result) {
        if (result == null) {
            return;
        }
        if (result instanceof CompletionStage<?>) {
            throw new LockConfigurationException(
                "SynchronousLockExecutor only supports synchronous actions, but the action returned CompletionStage"
            );
        }
        if (result instanceof Future<?>) {
            throw new LockConfigurationException(
                "SynchronousLockExecutor only supports synchronous actions, but the action returned Future"
            );
        }
        if (isReactivePublisher(result)) {
            throw new LockConfigurationException(
                "SynchronousLockExecutor only supports synchronous actions, but the action returned Publisher"
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
