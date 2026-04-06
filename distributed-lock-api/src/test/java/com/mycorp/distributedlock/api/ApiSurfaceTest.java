package com.mycorp.distributedlock.api;

import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.api.exception.LockSessionLostException;
import com.mycorp.distributedlock.api.exception.UnsupportedLockCapabilityException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiSurfaceTest {

    @Test
    void apiShouldExposeTheApprovedLeaseSessionFencingTypes() throws Exception {
        assertThat(LockClient.class.getInterfaces()).isEmpty();
        assertThat(LockSession.class.getMethod("acquire", LockRequest.class).getExceptionTypes())
                .containsExactly(InterruptedException.class);
        assertThat(LockSession.class.getMethod("state").getReturnType()).isEqualTo(SessionState.class);
        assertThat(LockClient.class.getMethod("openSession", SessionRequest.class).getReturnType()).isEqualTo(LockSession.class);
        assertThat(LockLease.class.getInterfaces()).containsExactly(AutoCloseable.class);
        assertThat(LockLease.class.getMethod("close").getReturnType()).isEqualTo(void.class);
        assertThat(LockExecutor.class.getMethod("withLock", LockRequest.class, LockedSupplier.class).getReturnType())
                .isEqualTo(Object.class);

        assertThat(LockMode.values()).containsExactly(LockMode.EXCLUSIVE, LockMode.SHARED);
        assertThat(LeaseState.values()).containsExactly(LeaseState.ACTIVE, LeaseState.RELEASED, LeaseState.LOST);
        assertThat(SessionState.values()).containsExactly(SessionState.ACTIVE, SessionState.CLOSED, SessionState.LOST);
        assertThat(LockCapabilities.class).isNotNull();
        assertThat(LeasePolicy.class).isNotNull();
        assertThat(SessionPolicy.class).isNotNull();
    }

    @Test
    void waitPolicyShouldBeOwnedByTheApiAndDurationBased() {
        WaitPolicy timed = WaitPolicy.timed(Duration.ofSeconds(5));
        WaitPolicy indefinite = WaitPolicy.indefinite();

        assertThat(timed.duration()).isEqualTo(Duration.ofSeconds(5));
        assertThat(indefinite.duration()).isNull();
    }

    @Test
    void valueTypesShouldValidateTheirInputs() {
        assertThatThrownBy(() -> new LockKey(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FencingToken(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new FencingToken(1).value()).isEqualTo(1L);
    }

    @Test
    void apiShouldExposeTheSupportedExceptionTypes() {
        assertThat(LockOwnershipLostException.class.getSuperclass()).isEqualTo(RuntimeException.class);
        assertThat(LockAcquisitionTimeoutException.class.getSuperclass()).isEqualTo(RuntimeException.class);
        assertThat(LockBackendException.class.getSuperclass()).isEqualTo(RuntimeException.class);
        assertThat(LockConfigurationException.class.getSuperclass()).isEqualTo(RuntimeException.class);
        assertThat(LockSessionLostException.class.getSuperclass()).isEqualTo(RuntimeException.class);
        assertThat(UnsupportedLockCapabilityException.class.getSuperclass()).isEqualTo(RuntimeException.class);
    }
}
