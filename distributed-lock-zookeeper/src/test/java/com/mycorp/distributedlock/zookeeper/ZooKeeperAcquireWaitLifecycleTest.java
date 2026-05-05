package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockSessionLostException;
import com.mycorp.distributedlock.spi.BackendLease;
import com.mycorp.distributedlock.spi.BackendSession;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.test.KillSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ZooKeeperAcquireWaitLifecycleTest {

    @Test
    void indefiniteAcquireShouldWakeWhenWaitingSessionIsLost() throws Exception {
        try (ZooKeeperTestSupport support = new ZooKeeperTestSupport();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(support.configuration());
             BackendSession holder = backend.openSession();
             BackendLease ignored = holder.acquire(request("zk:wait:lost"))) {
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
             BackendLease ignored = holder.acquire(request("zk:wait:closed"));
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
             BackendLease holderLease = holder.acquire(request("zk:wait:timed"));
             BackendSession waiter = backend.openSession()) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<BackendLease> result = executor.submit(() -> waiter.acquire(timedRequest("zk:wait:timed")));
                Thread.sleep(500L);
                holderLease.release();

                try (BackendLease waiterLease = result.get(3, TimeUnit.SECONDS)) {
                    assertThat(waiterLease.isValid()).isTrue();
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void predecessorDeletionShouldWakeWaitingAcquirePromptly() throws Exception {
        try (ZooKeeperTestSupport support = new ZooKeeperTestSupport();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(support.configuration());
             BackendSession holder = backend.openSession();
             BackendLease holderLease = holder.acquire(request("zk:wait:prompt"));
             BackendSession waiter = backend.openSession()) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            AtomicLong releasedNanos = new AtomicLong();
            try {
                Future<Duration> result = executor.submit(() -> {
                    try (BackendLease waiterLease = waiter.acquire(timedRequest("zk:wait:prompt"))) {
                        assertThat(waiterLease.isValid()).isTrue();
                        return Duration.ofNanos(System.nanoTime() - releasedNanos.get());
                    }
                });

                Thread.sleep(150L);
                releasedNanos.set(System.nanoTime());
                holderLease.release();

                Duration elapsedAfterRelease = result.get(2, TimeUnit.SECONDS);
                assertThat(elapsedAfterRelease).isLessThan(Duration.ofMillis(80));
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
