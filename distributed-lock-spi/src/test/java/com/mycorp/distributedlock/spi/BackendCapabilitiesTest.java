package com.mycorp.distributedlock.spi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackendCapabilitiesTest {

    @Test
    void namedFactoriesShouldDescribeSupportedFeatureSets() {
        assertThat(BackendCapabilities.standard())
            .isEqualTo(new BackendCapabilities(true, true, true, true, true));
        assertThat(BackendCapabilities.withoutFixedLeaseDuration())
            .isEqualTo(new BackendCapabilities(true, true, true, true, false));
        assertThat(BackendCapabilities.mutexOnly())
            .isEqualTo(new BackendCapabilities(true, false, true, true, false));
    }
}
