package com.mycorp.distributedlock.api;

import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.api.exception.DistributedLockException;
import com.mycorp.distributedlock.api.exception.LockReentryException;
import com.mycorp.distributedlock.api.exception.LockSessionLostException;
import com.mycorp.distributedlock.api.exception.UnsupportedLockCapabilityException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
        assertThat(LockClient.class.getMethod("openSession").getReturnType()).isEqualTo(LockSession.class);
        assertThat(LockLease.class.getInterfaces()).containsExactly(AutoCloseable.class);
        assertThat(LockLease.class.getMethod("close").getReturnType()).isEqualTo(void.class);
        assertThatThrownBy(() -> Class.forName("com.mycorp.distributedlock.api.LockExecutor"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.mycorp.distributedlock.api.LockedSupplier"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThat(SynchronousLockExecutor.class.getMethod("withLock", LockRequest.class, LockedAction.class).getReturnType())
                .isEqualTo(Object.class);
        assertThat(LockedAction.class.getMethod("execute", LockLease.class).getExceptionTypes())
                .containsExactly(Exception.class);

        assertThat(LockMode.values()).containsExactly(LockMode.MUTEX, LockMode.READ, LockMode.WRITE);
        assertThat(WaitMode.values()).containsExactly(WaitMode.TRY_ONCE, WaitMode.TIMED, WaitMode.INDEFINITE);
        assertThat(LeaseMode.values()).containsExactly(LeaseMode.BACKEND_DEFAULT, LeaseMode.FIXED);
        assertThat(LeaseState.values()).containsExactly(LeaseState.ACTIVE, LeaseState.RELEASED, LeaseState.LOST);
        assertThat(SessionState.values()).containsExactly(SessionState.ACTIVE, SessionState.CLOSED, SessionState.LOST);
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
    void waitPolicyShouldExposeApprovedModesAndRejectContradictoryStates() {
        assertThat(WaitPolicy.tryOnce()).isEqualTo(new WaitPolicy(WaitMode.TRY_ONCE, Duration.ZERO));
        assertThat(WaitPolicy.indefinite()).isEqualTo(new WaitPolicy(WaitMode.INDEFINITE, Duration.ZERO));

        assertThatThrownBy(() -> WaitPolicy.timed(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WaitPolicy(WaitMode.TIMED, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leasePolicyShouldExposeApprovedModesAndRejectContradictoryStates() {
        assertThat(LeasePolicy.backendDefault()).isEqualTo(new LeasePolicy(LeaseMode.BACKEND_DEFAULT, Duration.ZERO));

        assertThatThrownBy(() -> LeasePolicy.fixed(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeasePolicy(LeaseMode.BACKEND_DEFAULT, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestAndValueTypesShouldMatchTheApprovedShape() {
        RecordComponent[] lockRequestComponents = LockRequest.class.getRecordComponents();

        assertThat(Arrays.stream(lockRequestComponents)
                .map(RecordComponent::getName))
                .containsExactly("key", "mode", "waitPolicy", "leasePolicy");
        assertThat(Arrays.stream(lockRequestComponents)
                .map(RecordComponent::getType))
                .containsExactly(LockKey.class, LockMode.class, WaitPolicy.class, LeasePolicy.class);
        assertThat(new LockRequest(new LockKey("orders"), LockMode.MUTEX, WaitPolicy.tryOnce()).leasePolicy())
                .isEqualTo(LeasePolicy.backendDefault());

        assertThatThrownBy(() -> new LockKey(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FencingToken(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new FencingToken(1).value()).isEqualTo(1L);
    }

    @Test
    void removedPolicyAndCapabilityTypesShouldStayGone() {
        assertThatThrownBy(() -> Class.forName("com.mycorp.distributedlock.api.SessionPolicy"))
            .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.mycorp.distributedlock.api.SessionRequest"))
            .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.mycorp.distributedlock.api.LockCapabilities"))
            .isInstanceOf(ClassNotFoundException.class);
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
    void lockContextShouldExposeCurrentLeaseAndFencingTokenAccessors() throws Exception {
        assertThat(LockContext.class.getMethod("currentLease").getReturnType().getSimpleName()).isEqualTo("Optional");
        assertThat(LockContext.class.getMethod("currentFencingToken").getReturnType().getSimpleName()).isEqualTo("Optional");
        assertThat(LockContext.class.getMethod("requireCurrentLease").getReturnType()).isEqualTo(LockLease.class);
        assertThat(LockContext.class.getMethod("requireCurrentFencingToken").getReturnType()).isEqualTo(FencingToken.class);
        assertThat(LockContext.class.getMethod("bind", LockLease.class).getReturnType().getSimpleName()).isEqualTo("Binding");
    }

    @Test
    void lockContextShouldBindAndRestoreLeasesInLifoOrder() {
        LockLease firstLease = new FakeLockLease("first", 1);
        LockLease secondLease = new FakeLockLease("second", 2);

        assertThat(LockContext.currentLease()).isEmpty();
        assertThat(LockContext.currentFencingToken()).isEmpty();
        assertThatThrownBy(LockContext::requireCurrentLease)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(LockContext::requireCurrentFencingToken)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LockContext.bind(null))
                .isInstanceOf(NullPointerException.class);

        try (LockContext.Binding firstBinding = LockContext.bind(firstLease)) {
            assertThat(LockContext.currentLease()).containsSame(firstLease);
            assertThat(LockContext.currentFencingToken()).contains(firstLease.fencingToken());
            assertThat(LockContext.requireCurrentLease()).isSameAs(firstLease);
            assertThat(LockContext.requireCurrentFencingToken()).isEqualTo(firstLease.fencingToken());

            LockContext.Binding secondBinding = LockContext.bind(secondLease);
            assertThat(LockContext.currentLease()).containsSame(secondLease);
            assertThat(LockContext.currentFencingToken()).contains(secondLease.fencingToken());
            secondBinding.close();
            secondBinding.close();

            assertThat(LockContext.currentLease()).containsSame(firstLease);
            assertThat(LockContext.currentFencingToken()).contains(firstLease.fencingToken());
        }

        assertThat(LockContext.currentLease()).isEmpty();
        assertThat(LockContext.currentFencingToken()).isEmpty();
    }

    @Test
    void lockContextBindingShouldRejectOutOfOrderCloseWithoutCorruptingContext() {
        LockLease firstLease = new FakeLockLease("first", 1);
        LockLease secondLease = new FakeLockLease("second", 2);
        LockContext.Binding firstBinding = LockContext.bind(firstLease);
        LockContext.Binding secondBinding = LockContext.bind(secondLease);

        assertThatThrownBy(firstBinding::close)
                .isInstanceOf(IllegalStateException.class);
        assertThat(LockContext.currentLease()).containsSame(secondLease);
        assertThat(LockContext.currentFencingToken()).contains(secondLease.fencingToken());

        secondBinding.close();
        firstBinding.close();
        assertThat(LockContext.currentLease()).isEmpty();
    }

    @Test
    void lockContextBindingShouldExposeOnlyCloseAsPublicOperation() {
        assertThat(Modifier.isPublic(LockContext.Binding.class.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(LockContext.Binding.class.getModifiers())).isTrue();
        assertThat(AutoCloseable.class).isAssignableFrom(LockContext.Binding.class);
        assertThat(Arrays.stream(LockContext.Binding.class.getDeclaredConstructors())
                .map(Constructor::getModifiers)
                .noneMatch(Modifier::isPublic))
                .isTrue();
        assertThat(Arrays.stream(LockContext.Binding.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName))
                .containsExactly("close");
    }

    @Test
    void apiShouldExposeTheSupportedExceptionTypes() {
        assertThat(LockOwnershipLostException.class.getSuperclass()).isEqualTo(DistributedLockException.class);
        assertThat(LockAcquisitionTimeoutException.class.getSuperclass()).isEqualTo(DistributedLockException.class);
        assertThat(LockBackendException.class.getSuperclass()).isEqualTo(DistributedLockException.class);
        assertThat(LockConfigurationException.class.getSuperclass()).isEqualTo(DistributedLockException.class);
        assertThat(LockSessionLostException.class.getSuperclass()).isEqualTo(DistributedLockException.class);
        assertThat(UnsupportedLockCapabilityException.class.getSuperclass()).isEqualTo(DistributedLockException.class);
        assertThat(LockReentryException.class.getSuperclass()).isEqualTo(DistributedLockException.class);
    }

    private static final class FakeLockLease implements LockLease {
        private final LockKey key;
        private final FencingToken fencingToken;

        private FakeLockLease(String key, long fencingTokenValue) {
            this.key = new LockKey(key);
            this.fencingToken = new FencingToken(fencingTokenValue);
        }

        @Override
        public LockKey key() {
            return key;
        }

        @Override
        public LockMode mode() {
            return LockMode.MUTEX;
        }

        @Override
        public FencingToken fencingToken() {
            return fencingToken;
        }

        @Override
        public LeaseState state() {
            return LeaseState.ACTIVE;
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public void release() {
        }
    }
}
