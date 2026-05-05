package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.LockRuntime;

@FunctionalInterface
public interface LockRuntimeDecorator {

    LockRuntime decorate(LockRuntime runtime);
}
