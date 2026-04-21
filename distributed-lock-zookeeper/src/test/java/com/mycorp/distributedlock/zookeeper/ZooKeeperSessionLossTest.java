package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.api.exception.LockSessionLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.test.KillSession;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZooKeeperSessionLossTest {

    @Test
    void lostSessionShouldRemainLostAndRejectNewAcquires() throws Exception {
        try (TestingServer server = new TestingServer();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(
                 new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks")
             )) {
            BackendSession session = backend.openSession();
            try {
                CuratorFramework curatorFramework = ((CuratorBackedSession) session).curatorFramework();
                KillSession.kill(curatorFramework.getZookeeperClient().getZooKeeper(), server.getConnectString());

                waitUntilState(session, SessionState.LOST);
                assertThat(session.state()).isEqualTo(SessionState.LOST);
                Thread.sleep(250L);
                assertThat(session.state()).isEqualTo(SessionState.LOST);
                assertThatThrownBy(() -> session.acquire(new LockRequest(
                    new LockKey("zk:lost:acquire"),
                    LockMode.MUTEX,
                    WaitPolicy.indefinite()
                ))).isInstanceOf(LockSessionLostException.class);
            } finally {
                try {
                    session.close();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    @Test
    void lostLeaseShouldExposeLostStateAndRejectRelease() throws Exception {
        try (TestingServer server = new TestingServer();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(
                 new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks")
             )) {
            BackendSession session = backend.openSession();
            BackendLockLease lease = session.acquire(new LockRequest(
                new LockKey("zk:lost:lease"),
                LockMode.MUTEX,
                WaitPolicy.indefinite()
            ));
            try {
                CuratorFramework curatorFramework = ((CuratorBackedSession) session).curatorFramework();
                KillSession.kill(curatorFramework.getZookeeperClient().getZooKeeper(), server.getConnectString());

                waitUntilState(session, SessionState.LOST);
                assertThat(lease.isValid()).isFalse();
                assertThat(lease.state()).isEqualTo(LeaseState.LOST);
                assertThatThrownBy(lease::release)
                    .isInstanceOf(LockOwnershipLostException.class)
                    .hasMessageContaining("zk:lost:lease");
            } finally {
                try {
                    lease.close();
                } catch (RuntimeException ignored) {
                }
                try {
                    session.close();
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
