package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.api.exception.UnsupportedLockCapabilityException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LockRuntimeBuilderTest {

    @Test
    void builderShouldFailWhenNoBackendIsConfiguredEvenIfOneModuleExists() {
        LockRuntimeBuilder builder = LockRuntimeBuilder.create()
            .backendModules(List.of(new StubBackendModule("redis")));

        assertThatThrownBy(builder::build)
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("backend id must be configured");
    }

    @Test
    void builderShouldFailWhenBackendIsBlank() {
        LockRuntimeBuilder builder = LockRuntimeBuilder.create()
            .backend("   ")
            .backendModules(List.of(new StubBackendModule("redis")));

        assertThatThrownBy(builder::build)
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("backend id must be configured");
    }

    @Test
    void builderShouldRejectBackendWithoutMutexSupport() {
        LockRuntimeBuilder builder = LockRuntimeBuilder.create()
            .backend("redis")
            .backendModules(List.of(new StubBackendModule(
                "redis",
                new BackendCapabilities(false, true, true, true, true)
            )));

        assertThatThrownBy(builder::build)
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("redis")
            .hasMessageContaining("mutexSupported");
    }

    @Test
    void builderShouldRejectBackendWithoutFencingSupport() {
        LockRuntimeBuilder builder = LockRuntimeBuilder.create()
            .backend("redis")
            .backendModules(List.of(new StubBackendModule(
                "redis",
                new BackendCapabilities(true, true, false, true, true)
            )));

        assertThatThrownBy(builder::build)
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("redis")
            .hasMessageContaining("fencingSupported");
    }

    @Test
    void builderShouldRejectBackendWithoutRenewableSessionsSupport() {
        LockRuntimeBuilder builder = LockRuntimeBuilder.create()
            .backend("redis")
            .backendModules(List.of(new StubBackendModule(
                "redis",
                new BackendCapabilities(true, true, true, false, true)
            )));

        assertThatThrownBy(builder::build)
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("redis")
            .hasMessageContaining("renewableSessionsSupported");
    }

    @Test
    void builderShouldRejectInvalidCapabilitiesBeforeCreatingBackend() {
        TrackingBackendModule module = new TrackingBackendModule(
            "redis",
            new BackendCapabilities(false, true, true, true, true)
        );
        LockRuntimeBuilder builder = LockRuntimeBuilder.create()
            .backend("redis")
            .backendModules(List.of(module));

        assertThatThrownBy(builder::build)
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("redis")
            .hasMessageContaining("mutexSupported");
        assertThat(module.createBackendAttempted()).isFalse();
    }

    @Test
    void builderShouldRejectNullCapabilitiesBeforeCreatingBackend() {
        TrackingBackendModule module = new TrackingBackendModule("redis", null);
        LockRuntimeBuilder builder = LockRuntimeBuilder.create()
            .backend("redis")
            .backendModules(List.of(module));

        assertThatThrownBy(builder::build)
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("redis")
            .hasMessageContaining("capabilities must not be null");
        assertThat(module.createBackendAttempted()).isFalse();
    }

    @Test
    void builderShouldExposeLockClientAndExecutorWithExplicitMutexOnlyBackend() throws Exception {
        try (LockRuntime runtime = LockRuntimeBuilder.create()
            .backend("mutex-only")
            .backendModules(List.of(new StubBackendModule(
                "mutex-only",
                new BackendCapabilities(true, false, true, true, true)
            )))
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
    void builderShouldAllowBackendWithoutFixedLeaseDurationSupportAtStartup() throws Exception {
        try (LockRuntime runtime = LockRuntimeBuilder.create()
            .backend("zookeeper")
            .backendModules(List.of(new StubBackendModule(
                "zookeeper",
                new BackendCapabilities(true, true, true, true, false)
            )))
            .build()) {
            assertThat(runtime.synchronousLockExecutor()).isNotNull();
        }
    }

    @Test
    void builderShouldReuseValidatedCapabilitiesAfterBackendCreation() throws Exception {
        try (LockRuntime runtime = LockRuntimeBuilder.create()
            .backend("volatile")
            .backendModules(List.of(new VolatileCapabilitiesBackendModule(
                new BackendCapabilities(true, false, true, true, true)
            )))
            .build()) {
            try (LockSession session = runtime.lockClient().openSession()) {
                try (LockLease lease = session.acquire(sampleRequest(LockMode.MUTEX))) {
                    assertThat(lease.mode()).isEqualTo(LockMode.MUTEX);
                }

                assertThatThrownBy(() -> session.acquire(sampleRequest(LockMode.READ)))
                    .isInstanceOf(UnsupportedLockCapabilityException.class)
                    .hasMessageContaining("READ");
            }

            assertThat(runtime.synchronousLockExecutor()).isNotNull();
        }
    }

    @Test
    void builderShouldUseExplicitlySelectedBackendWhenMultipleUniqueModulesExist() throws Exception {
        try (LockRuntime runtime = LockRuntimeBuilder.create()
            .backend("zookeeper")
            .backendModules(List.of(
                new StubBackendModule("redis", new BackendCapabilities(true, false, true, true, true)),
                new StubBackendModule("zookeeper", BackendCapabilities.standard())
            ))
            .build()) {
            try (LockSession session = runtime.lockClient().openSession()) {
                try (LockLease lease = session.acquire(sampleRequest(LockMode.READ))) {
                    assertThat(lease.mode()).isEqualTo(LockMode.READ);
                }
            }

            String result = runtime.synchronousLockExecutor().withLock(sampleRequest(LockMode.WRITE), lease -> "ok");
            assertThat(result).isEqualTo("ok");
        }
    }

    @Test
    void builderShouldRejectDuplicateBackendIdsExplicitly() {
        LockRuntimeBuilder builder = LockRuntimeBuilder.create()
            .backend("redis")
            .backendModules(List.of(new StubBackendModule("redis"), new StubBackendModule("redis")));

        assertThatThrownBy(builder::build)
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("Duplicate backend modules")
            .hasMessageContaining("redis");
    }

    private static LockRequest sampleRequest(LockMode mode) {
        return new LockRequest(
            new LockKey("orders"),
            mode,
            WaitPolicy.timed(Duration.ofSeconds(1))
        );
    }

    private static final class StubBackendModule implements BackendModule {
        private final String id;
        private final BackendCapabilities capabilities;

        private StubBackendModule(String id) {
            this(id, BackendCapabilities.standard());
        }

        private StubBackendModule(String id, BackendCapabilities capabilities) {
            this.id = id;
            this.capabilities = capabilities;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public BackendCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public LockBackend createBackend() {
            return stubBackend();
        }
    }

    private static final class TrackingBackendModule implements BackendModule {
        private final String id;
        private final BackendCapabilities capabilities;
        private boolean createBackendAttempted;

        private TrackingBackendModule(String id, BackendCapabilities capabilities) {
            this.id = id;
            this.capabilities = capabilities;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public BackendCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public LockBackend createBackend() {
            createBackendAttempted = true;
            return stubBackend();
        }

        private boolean createBackendAttempted() {
            return createBackendAttempted;
        }
    }

    private static final class VolatileCapabilitiesBackendModule implements BackendModule {
        private final BackendCapabilities firstCapabilities;
        private boolean capabilitiesReturned;

        private VolatileCapabilitiesBackendModule(BackendCapabilities firstCapabilities) {
            this.firstCapabilities = firstCapabilities;
        }

        @Override
        public String id() {
            return "volatile";
        }

        @Override
        public BackendCapabilities capabilities() {
            if (capabilitiesReturned) {
                return null;
            }
            capabilitiesReturned = true;
            return firstCapabilities;
        }

        @Override
        public LockBackend createBackend() {
            return stubBackend();
        }
    }

    private record StubLease(LockKey key, LockMode mode, FencingToken fencingToken) implements BackendLockLease {

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

    private static LockBackend stubBackend() {
        return new LockBackend() {
            @Override
            public BackendSession openSession() {
                return new BackendSession() {
                    @Override
                    public BackendLockLease acquire(LockRequest lockRequest) {
                        return new StubLease(lockRequest.key(), lockRequest.mode(), new FencingToken(1L));
                    }

                    @Override
                    public SessionState state() {
                        return SessionState.ACTIVE;
                    }

                    @Override
                    public void close() {
                    }
                };
            }

            @Override
            public void close() {
            }
        };
    }
}
