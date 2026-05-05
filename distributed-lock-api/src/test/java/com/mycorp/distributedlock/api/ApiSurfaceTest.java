package com.mycorp.distributedlock.api;

import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.api.exception.LockFailureContext;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.api.exception.DistributedLockException;
import com.mycorp.distributedlock.api.exception.LockReentryException;
import com.mycorp.distributedlock.api.exception.LockSessionLostException;
import com.mycorp.distributedlock.api.exception.UnsupportedLockCapabilityException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    void lockRuntimeShouldExposeOnlyApiTypes() throws Exception {
        Method info = LockRuntime.class.getMethod("info");

        assertThat(LockRuntime.class.getInterfaces()).containsExactly(AutoCloseable.class);
        assertThat(Arrays.stream(LockRuntime.class.getDeclaredMethods())
                .map(method -> method.getName() + ":" + method.getReturnType().getSimpleName() + ":"
                        + Arrays.stream(method.getParameterTypes())
                                .map(Class::getSimpleName)
                                .collect(Collectors.joining(","))
                        + ":"
                        + Arrays.stream(method.getExceptionTypes())
                                .map(Class::getSimpleName)
                                .collect(Collectors.joining(",")))
                .sorted()
                .collect(Collectors.toList()))
                .containsExactly(
                        "close:void::",
                        "info:RuntimeInfo::",
                        "lockClient:LockClient::",
                        "synchronousLockExecutor:SynchronousLockExecutor::");
        assertThat(info.getReturnType()).isEqualTo(RuntimeInfo.class);
        assertThat(Arrays.stream(LockRuntime.class.getDeclaredMethods())
                .flatMap(ApiSurfaceTest::runtimeMethodTypeNames))
                .noneMatch(name -> name.contains("com.mycorp.distributedlock.spi."));
    }

    @Test
    void backendBehaviorShouldDescribeRedisAndZooKeeperVisibleSemantics() {
        BackendBehavior redis = BackendBehavior.builder()
                .lockModes(Set.of(LockMode.MUTEX, LockMode.READ, LockMode.WRITE))
                .fencing(FencingSemantics.MONOTONIC_PER_KEY)
                .leaseSemantics(Set.of(LeaseSemantics.RENEWABLE_WATCHDOG, LeaseSemantics.FIXED_TTL))
                .session(SessionSemantics.CLIENT_LOCAL_TTL)
                .wait(WaitSemantics.POLLING)
                .fairness(FairnessSemantics.EXCLUSIVE_PREFERRED)
                .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
                .costModel(BackendCostModel.CHEAP_SESSION)
                .build();

        BackendBehavior zookeeper = BackendBehavior.builder()
                .lockModes(Set.of(LockMode.MUTEX, LockMode.READ, LockMode.WRITE))
                .fencing(FencingSemantics.MONOTONIC_PER_KEY)
                .leaseSemantics(Set.of(LeaseSemantics.SESSION_BOUND))
                .session(SessionSemantics.BACKEND_EPHEMERAL_SESSION)
                .wait(WaitSemantics.WATCHED_QUEUE)
                .fairness(FairnessSemantics.FIFO_QUEUE)
                .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
                .costModel(BackendCostModel.NETWORK_CLIENT_PER_SESSION)
                .build();

        assertThat(redis.supportsLockMode(LockMode.READ)).isTrue();
        assertThat(redis.supportsLeaseSemantics(LeaseSemantics.FIXED_TTL)).isTrue();
        assertThat(zookeeper.supportsLeaseSemantics(LeaseSemantics.FIXED_TTL)).isFalse();
    }

    @Test
    void runtimeMetadataRecordsShouldExposeTheApprovedShape() throws Exception {
        assertThat(Arrays.stream(BackendBehavior.class.getRecordComponents())
                .map(component -> component.getName() + ":" + component.getType().getSimpleName()))
                .containsExactly(
                        "lockModes:Set",
                        "fencing:FencingSemantics",
                        "leaseSemantics:Set",
                        "session:SessionSemantics",
                        "waitSemantics:WaitSemantics",
                        "fairness:FairnessSemantics",
                        "ownershipLoss:OwnershipLossSemantics",
                        "costModel:BackendCostModel");
        assertThat(Arrays.stream(RuntimeInfo.class.getRecordComponents())
                .map(component -> component.getName() + ":" + component.getType().getSimpleName()))
                .containsExactly(
                        "backendId:String",
                        "backendDisplayName:String",
                        "behavior:BackendBehavior",
                        "runtimeVersion:String");
        assertThat(Arrays.stream(BackendBehavior.Builder.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(method -> method.getName() + ":" + method.getReturnType().getSimpleName() + ":"
                        + Arrays.stream(method.getParameterTypes())
                                .map(Class::getSimpleName)
                                .collect(Collectors.joining(",")))
                .sorted()
                .collect(Collectors.toList()))
                .containsExactly(
                        "build:BackendBehavior:",
                        "costModel:Builder:BackendCostModel",
                        "fairness:Builder:FairnessSemantics",
                        "fencing:Builder:FencingSemantics",
                        "leaseSemantics:Builder:Set",
                        "lockModes:Builder:Set",
                        "ownershipLoss:Builder:OwnershipLossSemantics",
                        "session:Builder:SessionSemantics",
                        "wait:Builder:WaitSemantics",
                        "waitSemantics:Builder:WaitSemantics");

        BackendBehavior behavior = standardBehaviorBuilder()
                .waitSemantics(WaitSemantics.WATCHED_QUEUE)
                .build();

        assertThat(behavior.waitSemantics()).isEqualTo(WaitSemantics.WATCHED_QUEUE);
    }

    @Test
    void backendBehaviorShouldDefensivelyCopyAndExposeImmutableEnumSets() {
        EnumSet<LockMode> lockModes = EnumSet.of(LockMode.MUTEX);
        EnumSet<LeaseSemantics> leaseSemantics = EnumSet.of(LeaseSemantics.FIXED_TTL);

        BackendBehavior behavior = standardBehaviorBuilder()
                .lockModes(lockModes)
                .leaseSemantics(leaseSemantics)
                .build();

        lockModes.add(LockMode.READ);
        leaseSemantics.add(LeaseSemantics.SESSION_BOUND);

        assertThat(behavior.lockModes()).containsExactly(LockMode.MUTEX);
        assertThat(behavior.leaseSemantics()).containsExactly(LeaseSemantics.FIXED_TTL);
        assertThatThrownBy(() -> behavior.lockModes().add(LockMode.WRITE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> behavior.leaseSemantics().add(LeaseSemantics.RENEWABLE_WATCHDOG))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void backendBehaviorShouldRejectNullAndEmptyEnumSets() {
        assertThatThrownBy(() -> standardBehaviorBuilder().lockModes(null).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> standardBehaviorBuilder().lockModes(Set.of()).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> standardBehaviorBuilder().leaseSemantics(null).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> standardBehaviorBuilder().leaseSemantics(Set.of()).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void backendBehaviorShouldRejectNullScalarSemantics() {
        BackendBehavior behavior = standardBehaviorBuilder().build();

        assertThatThrownBy(() -> standardBehaviorBuilder().fencing(null).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> standardBehaviorBuilder().session(null).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> standardBehaviorBuilder().wait(null).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> standardBehaviorBuilder().fairness(null).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> standardBehaviorBuilder().ownershipLoss(null).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> standardBehaviorBuilder().costModel(null).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> behavior.supportsLockMode(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> behavior.supportsLeaseSemantics(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void runtimeInfoShouldRejectBlankRequiredStringsAndNullBehavior() {
        BackendBehavior behavior = standardBehaviorBuilder().build();

        assertThatThrownBy(() -> new RuntimeInfo(null, "Redis", behavior, "1.0.0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeInfo(" ", "Redis", behavior, "1.0.0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeInfo("redis", null, behavior, "1.0.0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeInfo("redis", " ", behavior, "1.0.0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeInfo("redis", "Redis", behavior, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeInfo("redis", "Redis", behavior, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeInfo("redis", "Redis", null, "1.0.0"))
                .isInstanceOf(NullPointerException.class);
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
    void lockRequestShouldExposeErgonomicFactoriesAndCopyMethods() {
        LockRequest mutex = LockRequest.mutex("orders:42", WaitPolicy.timed(Duration.ofSeconds(2)));
        assertThat(mutex).isEqualTo(new LockRequest(
                new LockKey("orders:42"),
                LockMode.MUTEX,
                WaitPolicy.timed(Duration.ofSeconds(2)),
                LeasePolicy.backendDefault()
        ));

        LockRequest read = LockRequest.read("orders:42", WaitPolicy.tryOnce());
        assertThat(read.mode()).isEqualTo(LockMode.READ);

        LockRequest write = LockRequest.write("orders:42", WaitPolicy.indefinite());
        assertThat(write.mode()).isEqualTo(LockMode.WRITE);

        LockRequest fixedLease = mutex.withLeasePolicy(LeasePolicy.fixed(Duration.ofSeconds(5)));
        assertThat(fixedLease.leasePolicy()).isEqualTo(LeasePolicy.fixed(Duration.ofSeconds(5)));
        assertThat(fixedLease.key()).isEqualTo(mutex.key());
        assertThat(fixedLease.mode()).isEqualTo(mutex.mode());
        assertThat(fixedLease.waitPolicy()).isEqualTo(mutex.waitPolicy());

        LockRequest tryOnce = mutex.withWaitPolicy(WaitPolicy.tryOnce());
        assertThat(tryOnce.waitPolicy()).isEqualTo(WaitPolicy.tryOnce());
        assertThat(tryOnce.leasePolicy()).isEqualTo(mutex.leasePolicy());
    }

    @Test
    void distributedLockExceptionsShouldExposeStructuredContext() {
        LockFailureContext context = new LockFailureContext(
                new LockKey("orders:42"),
                LockMode.MUTEX,
                WaitPolicy.timed(Duration.ofSeconds(2)),
                LeasePolicy.backendDefault(),
                "redis",
                "session-1"
        );

        LockAcquisitionTimeoutException contextual = new LockAcquisitionTimeoutException("timed out", null, context);
        assertThat(contextual.context()).isEqualTo(context);

        LockAcquisitionTimeoutException legacy = new LockAcquisitionTimeoutException("timed out");
        assertThat(legacy.context()).isEqualTo(LockFailureContext.empty());
        assertThat(legacy.context()).isNotNull();

        LockAcquisitionTimeoutException lateCause = new LockAcquisitionTimeoutException("timed out");
        IllegalStateException late = new IllegalStateException("late cause");
        lateCause.initCause(late);
        assertThat(lateCause.getCause()).isSameAs(late);

        IllegalStateException cause = new IllegalStateException("cause");
        LockAcquisitionTimeoutException withCause = new LockAcquisitionTimeoutException("timed out", cause, context);
        assertThat(withCause.getCause()).isSameAs(cause);
        assertThat(withCause.context()).isEqualTo(context);
        assertThat(new LockAcquisitionTimeoutException("timed out", null, null).context())
                .isEqualTo(LockFailureContext.empty());
    }

    @Test
    void distributedLockExceptionSerializationShouldNotRequireSerializableContextGraph() throws Exception {
        LockAcquisitionTimeoutException exception = new LockAcquisitionTimeoutException(
                "timed out",
                null,
                new LockFailureContext(
                        new LockKey("orders:42"),
                        LockMode.MUTEX,
                        WaitPolicy.tryOnce(),
                        LeasePolicy.backendDefault(),
                        "redis",
                        "session-1"
                )
        );

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(exception);
        }

        LockAcquisitionTimeoutException restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (LockAcquisitionTimeoutException) input.readObject();
        }

        assertThat(restored.getMessage()).isEqualTo("timed out");
        assertThat(restored.context()).isEqualTo(LockFailureContext.empty());
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
    void lockContextShouldNotBePartOfThePublicApi() {
        assertThatThrownBy(() -> Class.forName("com.mycorp.distributedlock.api.LockContext"))
            .isInstanceOf(ClassNotFoundException.class);
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

    private static Stream<String> runtimeMethodTypeNames(Method method) {
        Stream<String> rawTypeNames = Stream.concat(
                Stream.of(method.getReturnType().getName()),
                Stream.concat(
                        Arrays.stream(method.getParameterTypes()).map(Class::getName),
                        Arrays.stream(method.getExceptionTypes()).map(Class::getName)));
        Stream<String> genericTypeNames = Stream.concat(
                Stream.of(method.getGenericReturnType()),
                Stream.concat(
                        Arrays.stream(method.getGenericParameterTypes()),
                        Arrays.stream(method.getGenericExceptionTypes())))
                .map(Type::getTypeName);
        Stream<String> typeParameterBounds = Arrays.stream(method.getTypeParameters())
                .flatMap(typeParameter -> Arrays.stream(typeParameter.getBounds()))
                .map(Type::getTypeName);

        return Stream.of(rawTypeNames, genericTypeNames, typeParameterBounds)
                .flatMap(stream -> stream);
    }

    private static BackendBehavior.Builder standardBehaviorBuilder() {
        return BackendBehavior.builder()
                .lockModes(Set.of(LockMode.MUTEX, LockMode.READ, LockMode.WRITE))
                .fencing(FencingSemantics.MONOTONIC_PER_KEY)
                .leaseSemantics(Set.of(LeaseSemantics.RENEWABLE_WATCHDOG, LeaseSemantics.FIXED_TTL))
                .session(SessionSemantics.CLIENT_LOCAL_TTL)
                .wait(WaitSemantics.POLLING)
                .fairness(FairnessSemantics.EXCLUSIVE_PREFERRED)
                .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
                .costModel(BackendCostModel.CHEAP_SESSION);
    }

}
