package com.mycorp.distributedlock.springboot.config;

import com.mycorp.distributedlock.runtime.LockRuntime;

@FunctionalInterface
public interface LockRuntimeCustomizer {
    LockRuntime customize(LockRuntime runtime);
}
