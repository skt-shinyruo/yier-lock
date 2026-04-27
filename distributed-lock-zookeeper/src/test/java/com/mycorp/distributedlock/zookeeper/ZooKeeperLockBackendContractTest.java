package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LeasePolicy;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.api.exception.UnsupportedLockCapabilityException;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.testkit.LockClientContract;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.KeeperException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZooKeeperLockBackendContractTest extends LockClientContract {

    private static final String BASE_PATH = "/distributed-locks";

    private TestingServer server;

    @Override
    protected LockRuntime createRuntime() throws Exception {
        server = new TestingServer();
        return LockRuntimeBuilder.create()
            .backend("zookeeper")
            .backendModules(java.util.List.of(new ZooKeeperBackendModule(
                new ZooKeeperBackendConfiguration(server.getConnectString(), BASE_PATH)
            )))
            .build();
    }

    @AfterEach
    void closeServer() throws Exception {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    @Test
    void zookeeperShouldIssueMonotonicFencingTokens() throws Exception {
        try (LockRuntime runtime = createRuntime()) {
            long first;
            try (LockSession session = runtime.lockClient().openSession();
                 LockLease lease = session.acquire(request("zk:fence", LockMode.MUTEX, Duration.ofSeconds(1)))) {
                first = lease.fencingToken().value();
            }
            try (LockSession session = runtime.lockClient().openSession();
                 LockLease lease = session.acquire(request("zk:fence", LockMode.MUTEX, Duration.ofSeconds(1)))) {
                assertThat(lease.fencingToken().value()).isGreaterThan(first);
            }
        }
    }

    @Test
    void zookeeperShouldIssueMonotonicFencingTokensAcrossModes() throws Exception {
        try (LockRuntime runtime = createRuntime();
             LockSession session = runtime.lockClient().openSession()) {
            long first;
            try (LockLease lease = session.acquire(request("zk:fence:mode", LockMode.READ, Duration.ofSeconds(1)))) {
                first = lease.fencingToken().value();
            }
            try (LockLease lease = session.acquire(request("zk:fence:mode", LockMode.WRITE, Duration.ofSeconds(1)))) {
                assertThat(lease.fencingToken().value()).isGreaterThan(first);
            }
        }
    }

    @Test
    void fixedLeaseShouldBeRejectedByCoreCapabilitiesWithoutCreatingContenderNode() throws Exception {
        try (LockRuntime runtime = createRuntime()) {
            LockRequest fixedLease = new LockRequest(
                new LockKey("zk:fixed:reject"),
                LockMode.MUTEX,
                WaitPolicy.tryOnce(),
                LeasePolicy.fixed(Duration.ofSeconds(5))
            );

            try (LockSession session = runtime.lockClient().openSession()) {
                assertThatThrownBy(() -> session.acquire(fixedLease))
                    .isInstanceOf(UnsupportedLockCapabilityException.class)
                    .hasMessageContaining("fixed lease");
            }

            assertThat(childrenUnderQueueRoot("zk:fixed:reject")).isEmpty();
        }
    }

    @Test
    void tryOnceTimeoutShouldRemoveContenderNodeWhileHolderOwnsKey() throws Exception {
        try (LockRuntime runtime = createRuntime();
             LockSession holder = runtime.lockClient().openSession();
             LockLease ignored = holder.acquire(request("zk:try-once:cleanup", LockMode.MUTEX, WaitPolicy.indefinite()));
             LockSession contender = runtime.lockClient().openSession()) {
            assertThatThrownBy(() -> contender.acquire(request(
                "zk:try-once:cleanup",
                LockMode.MUTEX,
                WaitPolicy.tryOnce()
            )))
                .isInstanceOf(LockAcquisitionTimeoutException.class);

            assertThat(childrenUnderQueueRoot("zk:try-once:cleanup")).hasSize(1);
        }
    }

    private List<String> childrenUnderQueueRoot(String key) throws Exception {
        try (CuratorFramework client = CuratorFrameworkFactory.newClient(
            server.getConnectString(),
            new ExponentialBackoffRetry(1_000, 3)
        )) {
            client.start();
            assertThat(client.blockUntilConnected(10, TimeUnit.SECONDS)).isTrue();
            try {
                return client.getChildren().forPath(queueRootPath(key));
            } catch (KeeperException.NoNodeException exception) {
                return List.of();
            }
        }
    }

    private static String queueRootPath(String key) {
        return BASE_PATH + "/rw/" + encodeKeySegment(key) + "/locks";
    }

    private static String encodeKeySegment(String key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }
}
