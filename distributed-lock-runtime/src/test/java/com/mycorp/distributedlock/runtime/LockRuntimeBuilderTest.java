package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
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
    void builderShouldConfigureLockManagerWithSelectedBackendCapabilities() throws Exception {
        try (LockRuntime runtime = LockRuntimeBuilder.create()
            .backendModules(List.of(new StubBackendModule("read-write-only", new BackendCapabilities(false, true))))
            .build()) {
            assertThatThrownBy(() -> runtime.lockManager().mutex("orders"))
                .isInstanceOf(LockConfigurationException.class)
                .hasMessageContaining("does not support mutex");

            assertThatCode(() -> runtime.lockManager().readWrite("orders").readLock())
                .doesNotThrowAnyException();
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
                public BackendLockLease acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) {
                    return new BackendLockLease() {
                        @Override
                        public String key() {
                            return resource.key();
                        }

                        @Override
                        public LockMode mode() {
                            return mode;
                        }

                        @Override
                        public boolean isValidForCurrentExecution() {
                            return true;
                        }

                        @Override
                        public void release() {
                        }
                    };
                }
            };
        }
    }
}
