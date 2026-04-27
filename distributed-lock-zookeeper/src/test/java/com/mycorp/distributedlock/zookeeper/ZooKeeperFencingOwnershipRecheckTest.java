package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockSessionLostException;
import com.mycorp.distributedlock.core.backend.BackendSession;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZooKeeperFencingOwnershipRecheckTest {

    @Test
    void contenderDeletionBeforeFenceShouldNotReturnLease() throws Exception {
        try (TestingServer server = new TestingServer();
             DeletingBackend backend = new DeletingBackend(new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks"))) {
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
}
