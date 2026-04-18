package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZooKeeperLockBackendBehaviorTest {

    @Test
    void distinctKeysMustNotCollideAfterPathEncoding() throws Exception {
        try (TestingServer server = new TestingServer();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(
                 new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks")
             )) {
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
    void constructorShouldFailWhenInitialZooKeeperConnectionCannotBeEstablished() throws Exception {
        int unavailablePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unavailablePort = socket.getLocalPort();
        }

        assertThatThrownBy(() -> new ZooKeeperLockBackend(
            new ZooKeeperBackendConfiguration("127.0.0.1:" + unavailablePort, "/distributed-locks")
        ))
            .isInstanceOf(LockBackendException.class)
            .hasMessageContaining("connect");
    }

    private static LockRequest request(String key, Duration waitTime) {
        return new LockRequest(
            new LockKey(key),
            LockMode.MUTEX,
            WaitPolicy.timed(waitTime)
        );
    }
}
