package com.mycorp.distributedlock.redis.springboot.integration;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.api.RuntimeInfo;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;

final class TestRuntimes {

    private TestRuntimes() {
    }

    static LockRuntime stub(String backendId) {
        return new LockRuntime() {
            @Override
            public LockClient lockClient() {
                return null;
            }

            @Override
            public SynchronousLockExecutor synchronousLockExecutor() {
                return null;
            }

            @Override
            public RuntimeInfo info() {
                return new RuntimeInfo(backendId, backendId, new TestBackendProviderSupport(backendId).descriptor().behavior(), "test");
            }

            @Override
            public void close() {
            }
        };
    }
}
