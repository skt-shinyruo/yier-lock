package com.mycorp.distributedlock.core.manager;

import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.SupportedLockModes;
import com.mycorp.distributedlock.core.backend.WaitPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultLockManagerCapabilitiesTest {

    @Test
    void readWriteShouldFailFastWhenBackendDoesNotSupportIt() {
        DefaultLockManager manager = new DefaultLockManager(new NoOpBackend(), new SupportedLockModes(true, false));

        assertThatThrownBy(() -> manager.readWrite("catalog:1"))
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("read/write");
    }

    private static final class NoOpBackend implements LockBackend {
        @Override
        public BackendLockLease acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) {
            return null;
        }
    }
}
