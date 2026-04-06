package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.core.manager.DefaultLockManager;
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
            DefaultLockManager manager = new DefaultLockManager(backend);
            try {
                manager.mutex("orders:1").lock();
                try {
                    assertThat(executor.submit(() -> manager.mutex("orders_1").tryLock(Duration.ofMillis(100))).get())
                        .isTrue();
                } finally {
                    manager.mutex("orders:1").unlock();
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
}
