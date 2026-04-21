package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.test.KillSession;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ZooKeeperSessionIsolationTest {

    @Test
    void sessionsShouldOwnIndependentCuratorClients() throws Exception {
        try (TestingServer server = new TestingServer();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(
                 new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks")
             );
             BackendSession first = backend.openSession();
             BackendSession second = backend.openSession()) {
            CuratorFramework firstClient = ((CuratorBackedSession) first).curatorFramework();
            CuratorFramework secondClient = ((CuratorBackedSession) second).curatorFramework();

            assertThat(firstClient).isNotSameAs(secondClient);
        }
    }

    @Test
    void killingOneSessionShouldNotInvalidateSiblingSession() throws Exception {
        try (TestingServer server = new TestingServer();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(
                 new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks")
             )) {
            BackendSession first = backend.openSession();
            BackendSession second = backend.openSession();
            BackendLockLease secondLease = second.acquire(new LockRequest(
                new LockKey("zk:isolation:second"),
                LockMode.MUTEX,
                WaitPolicy.timed(Duration.ofSeconds(1))
            ));
            try {
                CuratorFramework firstClient = ((CuratorBackedSession) first).curatorFramework();
                KillSession.kill(firstClient.getZookeeperClient().getZooKeeper(), server.getConnectString());

                waitUntilState(first, SessionState.LOST);
                assertThat(second.state()).isEqualTo(SessionState.ACTIVE);
                assertThat(secondLease.isValid()).isTrue();
            } finally {
                try {
                    secondLease.close();
                } catch (RuntimeException ignored) {
                }
                try {
                    second.close();
                } catch (RuntimeException ignored) {
                }
                try {
                    first.close();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private static void waitUntilState(BackendSession session, SessionState expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (session.state() == expected) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("session did not reach state " + expected);
    }
}
