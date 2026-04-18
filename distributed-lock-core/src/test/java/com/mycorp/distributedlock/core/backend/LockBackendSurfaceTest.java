package com.mycorp.distributedlock.core.backend;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class LockBackendSurfaceTest {

    @Test
    void backendShouldOpenSessionsWithoutPublicRequestTypes() throws Exception {
        assertThat(Arrays.stream(LockBackend.class.getDeclaredMethods())
            .map(Method::getName))
            .containsExactly("close", "openSession");
        assertThat(LockBackend.class.getMethod("openSession").getReturnType())
            .isEqualTo(BackendSession.class);
    }
}
