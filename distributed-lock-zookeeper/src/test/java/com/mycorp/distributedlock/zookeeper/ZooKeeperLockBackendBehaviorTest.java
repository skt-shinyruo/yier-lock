package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZooKeeperLockBackendBehaviorTest {

    @Test
    void distinctKeysMustNotCollideAfterPathEncoding() throws Exception {
        try (ZooKeeperTestSupport support = new ZooKeeperTestSupport();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(support.configuration())) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                try (BackendSession firstSession = backend.openSession();
                     BackendLockLease ignored = firstSession.acquire(request("orders:1", Duration.ofSeconds(1)))) {
                    assertThat(executor.submit(() -> {
                        try (BackendSession secondSession = backend.openSession();
                             BackendLockLease lease = secondSession.acquire(request("orders_1", Duration.ofMillis(100)))) {
                            return lease != null;
                        }
                    }).get()).isTrue();
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void closingSessionShouldReleaseActiveLeases() throws Exception {
        try (ZooKeeperTestSupport support = new ZooKeeperTestSupport();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(support.configuration())) {
            BackendSession ownerSession = backend.openSession();
            BackendLockLease ownerLease = ownerSession.acquire(request("zk:session-close:release", Duration.ofSeconds(1)));

            assertThatCode(ownerSession::close).doesNotThrowAnyException();

            assertThat(ownerSession.state()).isEqualTo(SessionState.CLOSED);
            assertThat(ownerLease.state()).isEqualTo(LeaseState.RELEASED);
            try (BackendSession nextSession = backend.openSession();
                 BackendLockLease nextLease = nextSession.acquire(request(
                     "zk:session-close:release",
                     Duration.ofSeconds(1)
                 ))) {
                assertThat(nextLease).isNotNull();
            }
        }
    }

    @Test
    void openSessionShouldReportConnectionFailureFromClientFactory() {
        ZooKeeperClientFactory clientFactory = mock(ZooKeeperClientFactory.class);
        LockBackendException failure = new LockBackendException("Failed to connect to ZooKeeper");
        when(clientFactory.connect()).thenThrow(failure);
        ZooKeeperLockBackend backend = new ZooKeeperLockBackend(
            new ZooKeeperBackendConfiguration("127.0.0.1:1", "/distributed-locks"),
            clientFactory
        );

        assertThatThrownBy(backend::openSession)
            .isSameAs(failure);

        verify(clientFactory).connect();
    }

    private static LockRequest request(String key, Duration waitTime) {
        return new LockRequest(
            new LockKey(key),
            LockMode.MUTEX,
            WaitPolicy.timed(waitTime)
        );
    }
}
