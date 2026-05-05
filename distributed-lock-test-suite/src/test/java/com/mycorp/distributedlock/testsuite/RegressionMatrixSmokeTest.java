package com.mycorp.distributedlock.testsuite;

import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.testkit.BackendConformanceFixture;
import com.mycorp.distributedlock.testkit.InMemoryConformanceFixture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RegressionMatrixSmokeTest {

    @Test
    void suiteShouldRunAtLeastOneRealFixture() throws Exception {
        List<BackendConformanceFixture<?>> fixtures = List.of(new InMemoryConformanceFixture());

        assertThat(fixtures).isNotEmpty();
        for (BackendConformanceFixture<?> fixture : fixtures) {
            try (fixture; LockRuntime runtime = fixture.runtimeBuilder().build()) {
                assertThat(runtime.info().backendId()).isNotBlank();
                assertThat(runtime.lockClient()).isNotNull();
                assertThat(runtime.synchronousLockExecutor()).isNotNull();
                assertThat(runtime.info().behavior()).isEqualTo(fixture.behavior());
            }
        }
    }
}
