package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendContext;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    private static final class StubBackendModule implements BackendModule {
        private final String id;

        private StubBackendModule(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public BackendCapabilities capabilities() {
            return BackendCapabilities.standard();
        }

        @Override
        public LockBackend createBackend(BackendContext context) {
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
