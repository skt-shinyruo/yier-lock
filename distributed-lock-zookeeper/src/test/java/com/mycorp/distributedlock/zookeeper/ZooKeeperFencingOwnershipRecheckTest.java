package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.api.exception.LockSessionLostException;
import com.mycorp.distributedlock.spi.BackendSession;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZooKeeperFencingOwnershipRecheckTest {

    @Test
    void contenderDeletionBeforeFenceShouldNotReturnLease() throws Exception {
        try (ZooKeeperTestSupport support = new ZooKeeperTestSupport();
             DeletingBackend backend = new DeletingBackend(support.configuration())) {
            try (BackendSession session = backend.openSession()) {
                assertThatThrownBy(() -> session.acquire(new LockRequest(
                    new LockKey("zk:fence:deleted"),
                    LockMode.MUTEX,
                    WaitPolicy.tryOnce()
                )))
                    .isInstanceOf(LockSessionLostException.class);
            }
        }
    }

    @Test
    void fenceCounterOverflowShouldFailWithoutPoisoningCounter() throws Exception {
        String key = "zk:fence:overflow";
        try (ZooKeeperTestSupport support = new ZooKeeperTestSupport();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(support.configuration());
             CuratorFramework client = CuratorFrameworkFactory.newClient(support.server().getConnectString(), new ExponentialBackoffRetry(1_000, 3))) {
            client.start();
            assertThat(client.blockUntilConnected(10, TimeUnit.SECONDS)).isTrue();
            String counterPath = support.configuration().basePath() + "/fence/" + encode(key);
            client.create().creatingParentsIfNeeded().forPath(counterPath, longToBytes(Long.MAX_VALUE));

            try (BackendSession session = backend.openSession()) {
                assertThatThrownBy(() -> session.acquire(new LockRequest(
                    new LockKey(key),
                    LockMode.MUTEX,
                    WaitPolicy.tryOnce()
                )))
                    .isInstanceOfSatisfying(LockBackendException.class, exception -> {
                        assertThat(exception).hasMessageContaining("ZooKeeper fencing counter overflow");
                        assertThat(exception.context().backendId()).isEqualTo("zookeeper");
                        assertThat(exception.context().key()).isEqualTo(new LockKey(key));
                        assertThat(exception.context().mode()).isEqualTo(LockMode.MUTEX);
                    });
            }

            assertThat(bytesToLong(client.getData().forPath(counterPath))).isEqualTo(Long.MAX_VALUE);
        }
    }

    @Test
    void sessionLossBeforeFenceShouldNotReturnLease() throws Exception {
        try (ZooKeeperTestSupport support = new ZooKeeperTestSupport();
             LosingBackend backend = new LosingBackend(support.configuration())) {
            BackendSession session = backend.openSession();
            backend.session = session;
            try {
                assertThatThrownBy(() -> session.acquire(new LockRequest(
                    new LockKey("zk:fence:lost"),
                    LockMode.MUTEX,
                    WaitPolicy.tryOnce()
                )))
                    .isInstanceOf(LockSessionLostException.class);
            } finally {
                try {
                    session.close();
                } catch (LockSessionLostException ignored) {
                }
            }
        }
    }

    @Test
    void sessionLossImmediatelyBeforeLeaseRegistrationShouldNotReturnLease() throws Exception {
        try (ZooKeeperTestSupport support = new ZooKeeperTestSupport();
             LosingBeforeRegistrationBackend backend = new LosingBeforeRegistrationBackend(
                 support.configuration()
             )) {
            BackendSession session = backend.openSession();
            backend.session = session;
            try {
                assertThatThrownBy(() -> session.acquire(new LockRequest(
                    new LockKey("zk:fence:lost-before-registration"),
                    LockMode.MUTEX,
                    WaitPolicy.tryOnce()
                )))
                    .isInstanceOf(LockSessionLostException.class);
            } finally {
                try {
                    session.close();
                } catch (LockSessionLostException ignored) {
                }
            }
        }
    }

    private static final class DeletingBackend extends ZooKeeperLockBackend {
        private DeletingBackend(ZooKeeperBackendConfiguration configuration) {
            super(configuration);
        }

        @Override
        void beforeFenceIssued(String contenderPath) {
            try (BackendSession session = openSession()) {
                ((CuratorBackedSession) session).curatorFramework().delete().forPath(contenderPath);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }
    }

    private static final class LosingBackend extends ZooKeeperLockBackend {
        private BackendSession session;

        private LosingBackend(ZooKeeperBackendConfiguration configuration) {
            super(configuration);
        }

        @Override
        void beforeFenceIssued(String contenderPath) {
            try {
                Method markSessionLost = session.getClass().getDeclaredMethod("markSessionLost", RuntimeException.class);
                markSessionLost.setAccessible(true);
                markSessionLost.invoke(session, new LockSessionLostException("ZooKeeper session lost during test"));
            } catch (ReflectiveOperationException exception) {
                throw new RuntimeException(exception);
            }
        }
    }

    private static final class LosingBeforeRegistrationBackend extends ZooKeeperLockBackend {
        private BackendSession session;

        private LosingBeforeRegistrationBackend(ZooKeeperBackendConfiguration configuration) {
            super(configuration);
        }

        @Override
        void beforeLeaseRegistered(String contenderPath) {
            try {
                Method markSessionLost = session.getClass().getDeclaredMethod("markSessionLost", RuntimeException.class);
                markSessionLost.setAccessible(true);
                markSessionLost.invoke(session, new LockSessionLostException("ZooKeeper session lost during registration test"));
            } catch (ReflectiveOperationException exception) {
                throw new RuntimeException(exception);
            }
        }
    }

    private static String encode(String key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] longToBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private static long bytesToLong(byte[] bytes) {
        return ByteBuffer.wrap(bytes).getLong();
    }
}
