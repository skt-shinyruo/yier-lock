package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeasePolicy;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockCapabilities;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.SessionPolicy;
import com.mycorp.distributedlock.api.SessionRequest;
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
    void builderShouldFailWhenMultipleBackendsExistAndNoBackendIsConfigured() {
        LockRuntimeBuilder builder = LockRuntimeBuilder.create()
            .backendModules(List.of(new StubBackendModule("redis"), new StubBackendModule("zookeeper")));

        assertThatThrownBy(builder::build)
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("multiple backends");
    }

    @Test
    void builderShouldExposeLockClientAndExecutorWithSelectedBackendCapabilities() throws Exception {
        try (LockRuntime runtime = LockRuntimeBuilder.create()
            .backendModules(List.of(new StubBackendModule("read-write-only", new BackendCapabilities(false, true, true, true))))
            .build()) {
            try (LockSession session = runtime.lockClient().openSession(new SessionRequest(SessionPolicy.MANUAL_CLOSE))) {
                assertThatThrownBy(() -> session.acquire(sampleRequest(LockMode.MUTEX)))
                    .isInstanceOf(UnsupportedLockCapabilityException.class)
                    .hasMessageContaining("MUTEX");

                try (LockLease lease = session.acquire(sampleRequest(LockMode.READ))) {
                    assertThat(lease.mode()).isEqualTo(LockMode.READ);
                }
            }

            String result = runtime.lockExecutor().withLock(sampleRequest(LockMode.READ), () -> "ok");
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
            WaitPolicy.timed(Duration.ofSeconds(1)),
            LeasePolicy.RELEASE_ON_CLOSE
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
            return new LockBackend() {
                @Override
                public LockCapabilities capabilities() {
                    return capabilities.asApiCapabilities();
                }

                @Override
                public BackendSession openSession(SessionRequest request) {
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
}
