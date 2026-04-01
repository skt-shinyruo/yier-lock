package com.mycorp.distributedlock.core.backend;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LockBackendSurfaceTest {

    @Test
    void backendAcquireShouldReturnBackendLockLease() throws Exception {
        assertThat(LockBackend.class
            .getMethod("acquire", LockResource.class, LockMode.class, WaitPolicy.class)
            .getReturnType())
            .isEqualTo(BackendLockLease.class);
    }
}
