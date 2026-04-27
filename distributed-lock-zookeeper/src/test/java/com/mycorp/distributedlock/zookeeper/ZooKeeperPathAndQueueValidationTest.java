package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.core.backend.BackendSession;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZooKeeperPathAndQueueValidationTest {

    @Test
    void basePathShouldRejectInvalidZookeeperPaths() {
        assertThatThrownBy(() -> new ZooKeeperBackendConfiguration("127.0.0.1:2181", "locks"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ZooKeeperBackendConfiguration("127.0.0.1:2181", "/locks//bad"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trailingSlashShouldBeNormalized() {
        ZooKeeperBackendConfiguration configuration = new ZooKeeperBackendConfiguration("127.0.0.1:2181", "/locks/");
        assertThat(configuration.basePath()).isEqualTo("/locks");
    }

    @Test
    void malformedQueueNodeShouldFailWithBackendException() throws Exception {
        try (TestingServer server = new TestingServer();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks"));
             CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), new ExponentialBackoffRetry(1_000, 3))) {
            client.start();
            assertThat(client.blockUntilConnected(10, TimeUnit.SECONDS)).isTrue();
            String queueRoot = "/distributed-locks/rw/" + encode("zk:bad-node") + "/locks";
            client.create().creatingParentsIfNeeded().forPath(queueRoot + "/read-bad");

            try (BackendSession session = backend.openSession()) {
                assertThatThrownBy(() -> session.acquire(new LockRequest(
                    new LockKey("zk:bad-node"),
                    LockMode.READ,
                    WaitPolicy.tryOnce()
                )))
                    .isInstanceOf(LockBackendException.class)
                    .hasMessageContaining("read-bad");
            }
        }
    }

    private static String encode(String key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }
}
