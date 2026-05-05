package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.spi.BackendSession;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
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
        try (ZooKeeperTestSupport support = new ZooKeeperTestSupport();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(support.configuration());
             CuratorFramework client = CuratorFrameworkFactory.newClient(support.server().getConnectString(), new ExponentialBackoffRetry(1_000, 3))) {
            client.start();
            assertThat(client.blockUntilConnected(10, TimeUnit.SECONDS)).isTrue();
            String queueRoot = support.configuration().basePath() + "/rw/" + encode("zk:bad-node") + "/locks";
            client.create().creatingParentsIfNeeded().forPath(queueRoot + "/read-bad");

            try (BackendSession session = backend.openSession()) {
                assertThatThrownBy(() -> session.acquire(new LockRequest(
                    new LockKey("zk:bad-node"),
                    LockMode.READ,
                    WaitPolicy.tryOnce()
                )))
                    .isInstanceOfSatisfying(LockBackendException.class, exception -> {
                        assertThat(exception).hasMessageContaining("read-bad");
                        assertThat(exception.context().backendId()).isEqualTo("zookeeper");
                        assertThat(exception.context().key()).isEqualTo(new LockKey("zk:bad-node"));
                        assertThat(exception.context().mode()).isEqualTo(LockMode.READ);
                    });
            }
        }
    }

    @Test
    void overflowingQueueNodeSequenceShouldFailWithBackendException() throws Exception {
        try (ZooKeeperTestSupport support = new ZooKeeperTestSupport();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(support.configuration());
             CuratorFramework client = CuratorFrameworkFactory.newClient(support.server().getConnectString(), new ExponentialBackoffRetry(1_000, 3))) {
            client.start();
            assertThat(client.blockUntilConnected(10, TimeUnit.SECONDS)).isTrue();
            String childName = "read-999999999999999999999999999999999999";
            String queueRoot = support.configuration().basePath() + "/rw/" + encode("zk:overflow-node") + "/locks";
            client.create().creatingParentsIfNeeded().forPath(queueRoot + "/" + childName);

            try (BackendSession session = backend.openSession()) {
                assertThatThrownBy(() -> session.acquire(new LockRequest(
                    new LockKey("zk:overflow-node"),
                    LockMode.READ,
                    WaitPolicy.tryOnce()
                )))
                    .isInstanceOfSatisfying(LockBackendException.class, exception -> {
                        assertThat(exception).hasMessageContaining(childName);
                        assertThat(exception.context().backendId()).isEqualTo("zookeeper");
                        assertThat(exception.context().key()).isEqualTo(new LockKey("zk:overflow-node"));
                        assertThat(exception.context().mode()).isEqualTo(LockMode.READ);
                    });
            }
        }
    }

    @Test
    void negativeQueueNodeSequenceShouldFailWithBackendException() throws Exception {
        try (ZooKeeperTestSupport support = new ZooKeeperTestSupport();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(support.configuration());
             CuratorFramework client = CuratorFrameworkFactory.newClient(support.server().getConnectString(), new ExponentialBackoffRetry(1_000, 3))) {
            client.start();
            assertThat(client.blockUntilConnected(10, TimeUnit.SECONDS)).isTrue();
            String childName = "read--2147483648";
            String queueRoot = support.configuration().basePath() + "/rw/" + encode("zk:negative-node") + "/locks";
            client.create().creatingParentsIfNeeded().forPath(queueRoot + "/" + childName);

            try (BackendSession session = backend.openSession()) {
                assertThatThrownBy(() -> session.acquire(new LockRequest(
                    new LockKey("zk:negative-node"),
                    LockMode.READ,
                    WaitPolicy.tryOnce()
                )))
                    .isInstanceOf(LockBackendException.class)
                    .hasMessageContaining(childName);
            }
        }
    }

    private static String encode(String key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }
}
