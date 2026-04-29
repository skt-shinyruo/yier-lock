package com.mycorp.distributedlock.testsuite;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.runtime.LockRuntime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegressionMatrixSmokeTest {

    @Test
    void suiteShouldResolveCoreRuntimeApiTypes() {
        assertThat(LockRuntime.class).isNotNull();
        assertThat(LockClient.class).isNotNull();
        assertThat(SynchronousLockExecutor.class).isNotNull();
    }
}
