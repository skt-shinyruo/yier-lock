package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObservedLockSessionTest {

    @Test
    void acquireShouldRecordSuccessWithoutPublishingTheRawKeyByDefault() throws Exception {
        LockRequest request = request("orders:42", LockMode.MUTEX);
        LockLease lease = mock(LockLease.class);
        com.mycorp.distributedlock.api.LockSession delegate = mock(com.mycorp.distributedlock.api.LockSession.class);
        when(delegate.acquire(request)).thenReturn(lease);

        List<LockObservationEvent> events = new ArrayList<>();
        ObservedLockSession session = new ObservedLockSession(delegate, events::add, "redis", false);

        assertThat(session.acquire(request)).isSameAs(lease);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.surface()).isEqualTo("client");
            assertThat(event.operation()).isEqualTo("acquire");
            assertThat(event.outcome()).isEqualTo("success");
            assertThat(event.backendId()).isEqualTo("redis");
            assertThat(event.mode()).isEqualTo(LockMode.MUTEX);
            assertThat(event.key()).isNull();
        });
    }

    @Test
    void acquireShouldRecordTimeoutOutcome() throws Exception {
        LockRequest request = request("inventory:7", LockMode.WRITE);
        com.mycorp.distributedlock.api.LockSession delegate = mock(com.mycorp.distributedlock.api.LockSession.class);
        when(delegate.acquire(request)).thenThrow(new LockAcquisitionTimeoutException("Timed out"));

        List<LockObservationEvent> events = new ArrayList<>();
        ObservedLockSession session = new ObservedLockSession(delegate, events::add, "zookeeper", true);

        assertThatThrownBy(() -> session.acquire(request))
            .isInstanceOf(LockAcquisitionTimeoutException.class);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo("timeout");
            assertThat(event.key()).isEqualTo("inventory:7");
            assertThat(event.mode()).isEqualTo(LockMode.WRITE);
        });
    }

    @Test
    void acquireShouldRecordBackendFailureOutcome() throws Exception {
        LockRequest request = request("inventory:9", LockMode.MUTEX);
        com.mycorp.distributedlock.api.LockSession delegate = mock(com.mycorp.distributedlock.api.LockSession.class);
        when(delegate.acquire(request)).thenThrow(new LockBackendException("backend broke"));

        List<LockObservationEvent> events = new ArrayList<>();
        ObservedLockSession session = new ObservedLockSession(delegate, events::add, "redis", true);

        assertThatThrownBy(() -> session.acquire(request))
            .isInstanceOf(LockBackendException.class);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo("backend-failure");
            assertThat(event.key()).isEqualTo("inventory:9");
            assertThat(event.mode()).isEqualTo(LockMode.MUTEX);
        });
    }

    private static LockRequest request(String key, LockMode mode) {
        return new LockRequest(new LockKey(key), mode, WaitPolicy.timed(Duration.ofSeconds(1)));
    }
}
