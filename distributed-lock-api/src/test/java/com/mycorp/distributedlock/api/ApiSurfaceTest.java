package com.mycorp.distributedlock.api;

import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.api.exception.LockSessionLostException;
import com.mycorp.distributedlock.api.exception.UnsupportedLockCapabilityException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiSurfaceTest {

    @Test
    void apiShouldExposeTheApprovedLeaseSessionFencingTypes() throws Exception {
        assertThat(LockClient.class.getInterfaces()).containsExactly(AutoCloseable.class);
        assertThat(LockSession.class.getMethod("acquire", LockRequest.class).getExceptionTypes())
                .containsExactly(InterruptedException.class);
        assertThat(LockSession.class.getMethod("state").getReturnType()).isEqualTo(SessionState.class);
        assertThat(LockClient.class.getMethod("openSession", SessionRequest.class).getReturnType()).isEqualTo(LockSession.class);
        assertThat(LockLease.class.getInterfaces()).containsExactly(AutoCloseable.class);
        assertThat(LockLease.class.getMethod("close").getReturnType()).isEqualTo(void.class);
        assertThat(LockExecutor.class.getMethod("withLock", LockRequest.class, LockedSupplier.class).getReturnType())
                .isEqualTo(Object.class);

        assertThat(LockMode.values()).containsExactly(LockMode.MUTEX, LockMode.READ, LockMode.WRITE);
        assertThat(LeaseState.values()).containsExactly(LeaseState.ACTIVE, LeaseState.RELEASED, LeaseState.LOST);
        assertThat(SessionState.values()).containsExactly(SessionState.ACTIVE, SessionState.CLOSED, SessionState.LOST);
        assertThat(LockCapabilities.class).isNotNull();
        assertThat(LeasePolicy.class).isNotNull();
        assertThat(SessionPolicy.class).isNotNull();
    }

    @Test
    void lockClientShouldExposeOnlyTheApprovedOperations() {
        assertThat(Arrays.stream(LockClient.class.getDeclaredMethods())
                .map(method -> method.getName() + ":" + method.getReturnType().getSimpleName())
                .sorted()
                .collect(Collectors.toList()))
                .containsExactly(
                        "close:void",
                        "openSession:LockSession");
    }

    @Test
    void waitPolicyShouldBeOwnedByTheApiAndUseTimedOrIndefiniteValueObjects() {
        WaitPolicy timed = WaitPolicy.timed(Duration.ofSeconds(5));
        WaitPolicy indefinite = WaitPolicy.indefinite();

        assertThat(timed.waitTime()).isEqualTo(Duration.ofSeconds(5));
        assertThat(timed.unbounded()).isFalse();
        assertThat(indefinite.waitTime()).isEqualTo(Duration.ZERO);
        assertThat(indefinite.unbounded()).isTrue();
    }

    @Test
    void waitPolicyShouldRejectContradictoryPublicRecordStates() {
        assertThatThrownBy(() -> new WaitPolicy(Duration.ofSeconds(5), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WaitPolicy(null, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WaitPolicy(Duration.ofSeconds(-1), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestAndValueTypesShouldMatchTheApprovedShape() {
        RecordComponent[] sessionRequestComponents = SessionRequest.class.getRecordComponents();
        RecordComponent[] lockRequestComponents = LockRequest.class.getRecordComponents();
        RecordComponent[] lockCapabilitiesComponents = LockCapabilities.class.getRecordComponents();

        assertThat(Arrays.stream(sessionRequestComponents)
                .map(RecordComponent::getName))
                .containsExactly("sessionPolicy");
        assertThat(Arrays.stream(sessionRequestComponents)
                .map(RecordComponent::getType))
                .containsExactly(SessionPolicy.class);

        assertThat(Arrays.stream(lockRequestComponents)
                .map(RecordComponent::getName))
                .containsExactly("key", "mode", "waitPolicy", "leasePolicy");
        assertThat(Arrays.stream(lockRequestComponents)
                .map(RecordComponent::getType))
                .containsExactly(LockKey.class, LockMode.class, WaitPolicy.class, LeasePolicy.class);

        assertThat(Arrays.stream(lockCapabilitiesComponents)
                .map(RecordComponent::getName))
                .containsExactly(
                        "mutexSupported",
                        "readWriteSupported",
                        "fencingSupported",
                        "renewableSessionsSupported");
        assertThat(Arrays.stream(lockCapabilitiesComponents)
                .map(RecordComponent::getType))
                .containsExactly(boolean.class, boolean.class, boolean.class, boolean.class);

        assertThatThrownBy(() -> new LockKey(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FencingToken(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new FencingToken(1).value()).isEqualTo(1L);
    }

    @Test
    void lockLeaseShouldExposeTheApprovedOperations() {
        assertThat(Arrays.stream(LockLease.class.getDeclaredMethods())
                .map(method -> method.getName() + ":" + method.getReturnType().getSimpleName())
                .sorted()
                .collect(Collectors.toList()))
                .containsExactly(
                        "close:void",
                        "fencingToken:FencingToken",
                        "isValid:boolean",
                        "key:LockKey",
                        "mode:LockMode",
                        "release:void",
                        "state:LeaseState");
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
