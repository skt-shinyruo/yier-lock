package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.LockRuntime;

@FunctionalInterface
public interface LockRuntimeDecorator {

    /**
     * Decorates a non-null runtime and returns a non-null runtime. Wrappers should preserve the delegate
     * close lifecycle unless they fully own the returned runtime lifecycle.
     */
    LockRuntime decorate(LockRuntime runtime);
}
