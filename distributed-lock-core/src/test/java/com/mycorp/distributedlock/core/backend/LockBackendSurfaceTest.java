package com.mycorp.distributedlock.core.backend;

import com.mycorp.distributedlock.api.LockCapabilities;
import com.mycorp.distributedlock.api.SessionRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LockBackendSurfaceTest {

    @Test
    void backendShouldExposeCapabilitiesAndOpenSessions() throws Exception {
        assertThat(LockBackend.class.getMethod("capabilities").getReturnType())
            .isEqualTo(LockCapabilities.class);
        assertThat(LockBackend.class
            .getMethod("openSession", SessionRequest.class)
            .getReturnType())
            .isEqualTo(BackendSession.class);
    }
}
