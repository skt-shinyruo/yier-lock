package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.api.BackendCostModel;
import com.mycorp.distributedlock.api.FairnessSemantics;
import com.mycorp.distributedlock.api.FencingSemantics;
import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseSemantics;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.OwnershipLossSemantics;
import com.mycorp.distributedlock.api.RuntimeInfo;
import com.mycorp.distributedlock.api.SessionSemantics;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.WaitSemantics;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.api.exception.UnsupportedLockCapabilityException;
import com.mycorp.distributedlock.spi.BackendClient;
import com.mycorp.distributedlock.spi.BackendConfiguration;
import com.mycorp.distributedlock.spi.BackendDescriptor;
import com.mycorp.distributedlock.spi.BackendLease;
import com.mycorp.distributedlock.spi.BackendProvider;
import com.mycorp.distributedlock.spi.BackendSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LockRuntimeBuilderTest {

    @Test
    void runtimeShouldExposeNarrowCloseOperationFromApiContract() throws Exception {
        assertThat(LockRuntime.class.getMethod("close").getExceptionTypes()).isEmpty();
    }

    @Test
    void builderShouldAssembleRuntimeFromSelectedProviderAndConfiguration() throws Exception {
        TestConfiguration configuration = new TestConfiguration("primary");
        TrackingProvider provider = new TrackingProvider(
            "redis",
            "Redis",
            TestConfiguration.class,
            standardBehavior()
        );

        try (LockRuntime runtime = LockRuntimeBuilder.create()
            .backend("redis")
            .backendProvider(provider)
            .backendConfiguration(configuration)
            .build()) {
            RuntimeInfo info = runtime.info();
            assertThat(info.backendId()).isEqualTo("redis");
            assertThat(info.backendDisplayName()).isEqualTo("Redis");
            assertThat(info.behavior()).isEqualTo(standardBehavior());
            assertThat(info.runtimeVersion()).isNotBlank();
            assertThat(provider.createdConfigurations()).containsExactly(configuration);

            try (LockSession session = runtime.lockClient().openSession();
                 LockLease lease = session.acquire(sampleRequest(LockMode.READ))) {
                assertThat(lease.mode()).isEqualTo(LockMode.READ);
                assertThat(lease.fencingToken()).isEqualTo(new FencingToken(1L));
            }

            String result = runtime.synchronousLockExecutor().withLock(sampleRequest(LockMode.WRITE), lease -> "ok");
            assertThat(result).isEqualTo("ok");
        }

        assertThat(provider.createdClient().closeCount().get()).isEqualTo(1);
    }

    @Test
    void builderShouldAcceptConfigurationRegisteredWithExplicitType() {
        SpecialTestConfiguration configuration = new SpecialTestConfiguration("special");
        TrackingProvider provider = new TrackingProvider(
            "redis",
            "Redis",
            TestConfiguration.class,
            standardBehavior()
        );

        try (LockRuntime runtime = LockRuntimeBuilder.create()
            .backend("redis")
            .backendProvider(provider)
            .backendConfiguration(TestConfiguration.class, configuration)
            .build()) {
            assertThat(runtime.info().backendId()).isEqualTo("redis");
        }

        assertThat(provider.createdConfigurations()).containsExactly(configuration);
    }

    @Test
    void builderShouldUseExplicitlySelectedProviderWhenMultipleUniqueProvidersExist() throws Exception {
        TrackingProvider redis = new TrackingProvider(
            "redis",
            "Redis",
            TestConfiguration.class,
            mutexOnlyBehavior()
        );
        TrackingProvider zookeeper = new TrackingProvider(
            "zookeeper",
            "ZooKeeper",
            TestConfiguration.class,
            standardBehavior()
        );

        try (LockRuntime runtime = LockRuntimeBuilder.create()
            .backend("zookeeper")
            .backendProviders(List.of(redis, zookeeper))
            .backendConfiguration(new TestConfiguration("selected"))
            .build()) {
            assertThat(runtime.info().backendId()).isEqualTo("zookeeper");
            assertThat(runtime.info().backendDisplayName()).isEqualTo("ZooKeeper");

            try (LockSession session = runtime.lockClient().openSession();
                 LockLease lease = session.acquire(sampleRequest(LockMode.READ))) {
                assertThat(lease.mode()).isEqualTo(LockMode.READ);
            }
        }

        assertThat(redis.createdConfigurations()).isEmpty();
        assertThat(zookeeper.createdConfigurations()).hasSize(1);
    }

    @Test
    void builderShouldApplyDecoratorsInSuppliedOrder() {
        List<String> order = new ArrayList<>();
        LockRuntimeDecorator first = runtime -> new RecordingRuntime(runtime, "first", order);
        LockRuntimeDecorator second = runtime -> new RecordingRuntime(runtime, "second", order);

        try (LockRuntime runtime = LockRuntimeBuilder.create()
            .backend("redis")
            .backendProvider(new TrackingProvider("redis", "Redis", TestConfiguration.class, standardBehavior()))
            .backendConfiguration(new TestConfiguration("decorated"))
            .decorators(List.of(first, second))
            .build()) {
            assertThat(runtime.info().backendId()).isEqualTo("redis");
            assertThat(order).containsExactly("first:decorate", "second:decorate", "second:info", "first:info");
        }
    }

    @Test
    void builderShouldFailWhenNoBackendIsConfiguredEvenIfOneProviderExists() {
        LockRuntimeBuilder builder = LockRuntimeBuilder.create()
            .backendProvider(new TrackingProvider("redis", "Redis", TestConfiguration.class, standardBehavior()))
            .backendConfiguration(new TestConfiguration("redis"));

        assertThatThrownBy(builder::build)
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("backend id must be configured");
    }

    @Test
    void builderShouldFailWhenBackendIsBlank() {
        LockRuntimeBuilder builder = LockRuntimeBuilder.create()
            .backend("   ")
            .backendProvider(new TrackingProvider("redis", "Redis", TestConfiguration.class, standardBehavior()))
            .backendConfiguration(new TestConfiguration("redis"));

        assertThatThrownBy(builder::build)
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("backend id must be configured");
    }

    @Test
    void builderShouldRejectNullProviderEntries() {
        LockRuntimeBuilder builder = LockRuntimeBuilder.create()
            .backend("redis")
            .backendProviders(Arrays.asList(
                new TrackingProvider("redis", "Redis", TestConfiguration.class, standardBehavior()),
                null
            ))
            .backendConfiguration(new TestConfiguration("redis"));

        assertThatThrownBy(builder::build)
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("Backend provider must not be null");
    }

    @Test
    void builderShouldRejectDuplicateProviderIds() {
        LockRuntimeBuilder builder = LockRuntimeBuilder.create()
            .backend("redis")
            .backendProviders(List.of(
                new TrackingProvider("redis", "Redis", TestConfiguration.class, standardBehavior()),
                new TrackingProvider("redis", "Redis duplicate", TestConfiguration.class, standardBehavior())
            ))
            .backendConfiguration(new TestConfiguration("redis"));

        assertThatThrownBy(builder::build)
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("Duplicate backend providers")
            .hasMessageContaining("redis");
    }

    @Test
    void builderShouldRejectMissingSelectedProvider() {
        LockRuntimeBuilder builder = LockRuntimeBuilder.create()
            .backend("zookeeper")
            .backendProvider(new TrackingProvider("redis", "Redis", TestConfiguration.class, standardBehavior()))
            .backendConfiguration(new TestConfiguration("redis"));

        assertThatThrownBy(builder::build)
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("Requested backend not found")
            .hasMessageContaining("zookeeper");
    }

    @Test
    void builderShouldRejectNullDescriptor() {
        LockRuntimeBuilder builder = LockRuntimeBuilder.create()
            .backend("redis")
            .backendProvider(new BackendProvider<TestConfiguration>() {
                @Override
                public BackendDescriptor<TestConfiguration> descriptor() {
                    return null;
                }

                @Override
                public BackendClient createBackendClient(TestConfiguration configuration) {
                    return new TrackingBackendClient();
                }
            })
            .backendConfiguration(new TestConfiguration("redis"));

        assertThatThrownBy(builder::build)
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("Backend provider descriptor must not be null");
    }

    @Test
    void builderShouldRejectMissingConfigurationForSelectedDescriptor() {
        TrackingProvider provider = new TrackingProvider(
            "redis",
            "Redis",
            TestConfiguration.class,
            standardBehavior()
        );
        LockRuntimeBuilder builder = LockRuntimeBuilder.create()
            .backend("redis")
            .backendProvider(provider);

        assertThatThrownBy(builder::build)
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("Missing backend configuration")
            .hasMessageContaining("redis")
            .hasMessageContaining(TestConfiguration.class.getName());
        assertThat(provider.createdConfigurations()).isEmpty();
    }

    @Test
    void builderShouldRejectNullClientFromSelectedProvider() {
        TrackingProvider provider = new TrackingProvider(
            "redis",
            "Redis",
            TestConfiguration.class,
            standardBehavior()
        ) {
            @Override
            public BackendClient createBackendClient(TestConfiguration configuration) {
                super.createBackendClient(configuration);
                return null;
            }
        };
        LockRuntimeBuilder builder = LockRuntimeBuilder.create()
            .backend("redis")
            .backendProvider(provider)
            .backendConfiguration(new TestConfiguration("redis"));

        assertThatThrownBy(builder::build)
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("Backend provider returned null client")
            .hasMessageContaining("redis");
    }

    @Test
    void builderShouldExposeLockClientAndExecutorWithExplicitMutexOnlyProvider() throws Exception {
        try (LockRuntime runtime = LockRuntimeBuilder.create()
            .backend("mutex-only")
            .backendProvider(new TrackingProvider(
                "mutex-only",
                "Mutex Only",
                TestConfiguration.class,
                mutexOnlyBehavior()
            ))
            .backendConfiguration(new TestConfiguration("mutex-only"))
            .build()) {
            try (LockSession session = runtime.lockClient().openSession()) {
                try (LockLease lease = session.acquire(sampleRequest(LockMode.MUTEX))) {
                    assertThat(lease.mode()).isEqualTo(LockMode.MUTEX);
                }

                assertThatThrownBy(() -> session.acquire(sampleRequest(LockMode.READ)))
                    .isInstanceOf(UnsupportedLockCapabilityException.class)
                    .hasMessageContaining("READ");
            }

            String result = runtime.synchronousLockExecutor().withLock(sampleRequest(LockMode.MUTEX), lease -> "ok");
            assertThat(result).isEqualTo("ok");
        }
    }

    @Test
    void builderShouldAllowBackendWithoutFixedLeaseDurationSupportAtStartup() {
        try (LockRuntime runtime = LockRuntimeBuilder.create()
            .backend("zookeeper")
            .backendProvider(new TrackingProvider(
                "zookeeper",
                "ZooKeeper",
                TestConfiguration.class,
                withoutFixedLeaseBehavior()
            ))
            .backendConfiguration(new TestConfiguration("zookeeper"))
            .build()) {
            assertThat(runtime.synchronousLockExecutor()).isNotNull();
        }
    }

    private static LockRequest sampleRequest(LockMode mode) {
        return new LockRequest(
            new LockKey("orders"),
            mode,
            WaitPolicy.timed(Duration.ofSeconds(1))
        );
    }

    private static BackendBehavior standardBehavior() {
        return BackendBehavior.builder()
            .lockModes(Set.of(LockMode.MUTEX, LockMode.READ, LockMode.WRITE))
            .fencing(FencingSemantics.MONOTONIC_PER_KEY)
            .leaseSemantics(Set.of(LeaseSemantics.RENEWABLE_WATCHDOG, LeaseSemantics.FIXED_TTL))
            .session(SessionSemantics.CLIENT_LOCAL_TTL)
            .wait(WaitSemantics.POLLING)
            .fairness(FairnessSemantics.EXCLUSIVE_PREFERRED)
            .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
            .costModel(BackendCostModel.CHEAP_SESSION)
            .build();
    }

    private static BackendBehavior mutexOnlyBehavior() {
        return BackendBehavior.builder()
            .lockModes(Set.of(LockMode.MUTEX))
            .fencing(FencingSemantics.MONOTONIC_PER_KEY)
            .leaseSemantics(Set.of(LeaseSemantics.RENEWABLE_WATCHDOG))
            .session(SessionSemantics.CLIENT_LOCAL_TTL)
            .wait(WaitSemantics.POLLING)
            .fairness(FairnessSemantics.NONE)
            .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
            .costModel(BackendCostModel.CHEAP_SESSION)
            .build();
    }

    private static BackendBehavior withoutFixedLeaseBehavior() {
        return BackendBehavior.builder()
            .lockModes(Set.of(LockMode.MUTEX, LockMode.READ, LockMode.WRITE))
            .fencing(FencingSemantics.MONOTONIC_PER_KEY)
            .leaseSemantics(Set.of(LeaseSemantics.RENEWABLE_WATCHDOG))
            .session(SessionSemantics.BACKEND_EPHEMERAL_SESSION)
            .wait(WaitSemantics.WATCHED_QUEUE)
            .fairness(FairnessSemantics.FIFO_QUEUE)
            .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
            .costModel(BackendCostModel.POOLED_NETWORK_CLIENT)
            .build();
    }

    private static class TestConfiguration implements BackendConfiguration {
        private final String name;

        private TestConfiguration(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final class SpecialTestConfiguration extends TestConfiguration {
        private SpecialTestConfiguration(String name) {
            super(name);
        }
    }

    private static class TrackingProvider implements BackendProvider<TestConfiguration> {
        private final BackendDescriptor<TestConfiguration> descriptor;
        private final List<TestConfiguration> createdConfigurations = new ArrayList<>();
        private TrackingBackendClient createdClient;

        private TrackingProvider(
            String id,
            String displayName,
            Class<TestConfiguration> configurationType,
            BackendBehavior behavior
        ) {
            this.descriptor = new BackendDescriptor<>(id, displayName, configurationType, behavior);
        }

        @Override
        public BackendDescriptor<TestConfiguration> descriptor() {
            return descriptor;
        }

        @Override
        public BackendClient createBackendClient(TestConfiguration configuration) {
            createdConfigurations.add(configuration);
            createdClient = new TrackingBackendClient();
            return createdClient;
        }

        private List<TestConfiguration> createdConfigurations() {
            return createdConfigurations;
        }

        private TrackingBackendClient createdClient() {
            return createdClient;
        }
    }

    private static final class TrackingBackendClient implements BackendClient {
        private final AtomicInteger acquireCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public BackendSession openSession() {
            return new TrackingBackendSession(acquireCount);
        }

        private AtomicInteger closeCount() {
            return closeCount;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    private static final class TrackingBackendSession implements BackendSession {
        private final AtomicInteger acquireCount;

        private TrackingBackendSession(AtomicInteger acquireCount) {
            this.acquireCount = acquireCount;
        }

        @Override
        public BackendLease acquire(LockRequest lockRequest) {
            acquireCount.incrementAndGet();
            return new StubLease(lockRequest.key(), lockRequest.mode(), new FencingToken(1L));
        }

        @Override
        public SessionState state() {
            return SessionState.ACTIVE;
        }

        @Override
        public void close() {
        }
    }

    private record StubLease(LockKey key, LockMode mode, FencingToken fencingToken) implements BackendLease {

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

    private static final class RecordingRuntime implements LockRuntime {
        private final LockRuntime delegate;
        private final String label;
        private final List<String> order;

        private RecordingRuntime(LockRuntime delegate, String label, List<String> order) {
            this.delegate = delegate;
            this.label = label;
            this.order = order;
            order.add(label + ":decorate");
        }

        @Override
        public com.mycorp.distributedlock.api.LockClient lockClient() {
            order.add(label + ":lockClient");
            return delegate.lockClient();
        }

        @Override
        public SynchronousLockExecutor synchronousLockExecutor() {
            order.add(label + ":synchronousLockExecutor");
            return delegate.synchronousLockExecutor();
        }

        @Override
        public RuntimeInfo info() {
            order.add(label + ":info");
            return delegate.info();
        }

        @Override
        public void close() {
            order.add(label + ":close");
            delegate.close();
        }
    }
}
