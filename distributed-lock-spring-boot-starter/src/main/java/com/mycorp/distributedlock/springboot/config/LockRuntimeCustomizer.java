package com.mycorp.distributedlock.springboot.config;

import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeDecorator;

@FunctionalInterface
public interface LockRuntimeCustomizer extends LockRuntimeDecorator {

    @Override
    LockRuntime decorate(LockRuntime runtime);
}
