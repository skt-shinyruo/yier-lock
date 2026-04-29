package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockSessionLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.test.KillSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ZooKeeperAcquireWaitLifecycleTest {

    @Test
    void indefiniteAcquireShouldWakeWhenWaitingSessionIsLost() throws Exception {
        try (ZooKeeperTestSupport support = new ZooKeeperTestSupport();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(support.configuration());
             BackendSession holder = backend.openSession();
             BackendLockLease ignored = holder.acquire(request("zk:wait:lost"))) {
            BackendSession waiter = backend.openSession();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Throwable> result = executor.submit(() -> acquireAndReturnFailure(waiter, "zk:wait:lost"));
                Thread.sleep(250L);
                CuratorFramework curator = ((CuratorBackedSession) waiter).curatorFramework();
                KillSession.kill(curator.getZookeeperClient().getZooKeeper(), support.server().getConnectString());

                Throwable failure = result.get(3, TimeUnit.SECONDS);
                assertThat(failure).isInstanceOf(LockSessionLostException.class);
            } finally {
                executor.shutdownNow();
                closeQuietly(waiter);
            }
        }
    }

    @Test
    void indefiniteAcquireShouldWakeWhenWaitingSessionIsClosed() throws Exception {
        try (ZooKeeperTestSupport support = new ZooKeeperTestSupport();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(support.configuration());
             BackendSession holder = backend.openSession();
             BackendLockLease ignored = holder.acquire(request("zk:wait:closed"));
             BackendSession waiter = backend.openSession()) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Throwable> result = executor.submit(() -> acquireAndReturnFailure(waiter, "zk:wait:closed"));
                Thread.sleep(250L);
                waiter.close();

                Throwable failure = result.get(3, TimeUnit.SECONDS);
                assertThat(failure).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already closed");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void timedAcquireShouldContinueWaitingAcrossBoundedSlices() throws Exception {
        try (ZooKeeperTestSupport support = new ZooKeeperTestSupport();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(support.configuration());
             BackendSession holder = backend.openSession();
             BackendLockLease holderLease = holder.acquire(request("zk:wait:timed"));
             BackendSession waiter = backend.openSession()) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<BackendLockLease> result = executor.submit(() -> waiter.acquire(timedRequest("zk:wait:timed")));
                Thread.sleep(500L);
                holderLease.release();

                try (BackendLockLease waiterLease = result.get(3, TimeUnit.SECONDS)) {
                    assertThat(waiterLease.isValid()).isTrue();
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static Throwable acquireAndReturnFailure(BackendSession session, String key) {
        try {
            session.acquire(request(key));
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static LockRequest request(String key) {
        return new LockRequest(new LockKey(key), LockMode.MUTEX, WaitPolicy.indefinite());
    }

    private static LockRequest timedRequest(String key) {
        return new LockRequest(new LockKey(key), LockMode.MUTEX, WaitPolicy.timed(Duration.ofSeconds(2)));
    }

    private static void closeQuietly(BackendSession session) {
        try {
            session.close();
        } catch (RuntimeException ignored) {
        } catch (Exception ignored) {
        }
    }
}
