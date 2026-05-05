package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.spi.BackendLease;
import com.mycorp.distributedlock.spi.BackendSession;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.test.KillSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ZooKeeperSessionIsolationTest {

    @Test
    void sessionsShouldOwnIndependentCuratorClients() throws Exception {
        try (ZooKeeperTestSupport support = new ZooKeeperTestSupport();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(support.configuration());
             BackendSession first = backend.openSession();
             BackendSession second = backend.openSession()) {
            CuratorFramework firstClient = ((CuratorBackedSession) first).curatorFramework();
            CuratorFramework secondClient = ((CuratorBackedSession) second).curatorFramework();

            assertThat(firstClient).isNotSameAs(secondClient);
        }
    }

    @Test
    void killingOneSessionShouldNotInvalidateSiblingSession() throws Exception {
        try (ZooKeeperTestSupport support = new ZooKeeperTestSupport();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(support.configuration())) {
            BackendSession first = backend.openSession();
            BackendSession second = backend.openSession();
            BackendLease secondLease = second.acquire(new LockRequest(
                new LockKey("zk:isolation:second"),
                LockMode.MUTEX,
                WaitPolicy.timed(Duration.ofSeconds(1))
            ));
            try {
                CuratorFramework firstClient = ((CuratorBackedSession) first).curatorFramework();
                KillSession.kill(firstClient.getZookeeperClient().getZooKeeper(), support.server().getConnectString());

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
