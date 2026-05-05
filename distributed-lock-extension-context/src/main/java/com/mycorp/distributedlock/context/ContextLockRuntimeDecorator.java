package com.mycorp.distributedlock.context;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.api.RuntimeInfo;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.runtime.LockRuntimeDecorator;

import java.util.Objects;

public final class ContextLockRuntimeDecorator implements LockRuntimeDecorator {
    @Override
    public LockRuntime decorate(LockRuntime runtime) {
        LockRuntime delegate = Objects.requireNonNull(runtime, "runtime");
        SynchronousLockExecutor executor = new ContextBindingSynchronousLockExecutor(delegate.synchronousLockExecutor());
        return new LockRuntime() {
            @Override
            public LockClient lockClient() {
                return delegate.lockClient();
            }

            @Override
            public SynchronousLockExecutor synchronousLockExecutor() {
                return executor;
            }

            @Override
            public RuntimeInfo info() {
                return delegate.info();
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
    }
}
