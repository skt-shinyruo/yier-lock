package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LeasePolicy;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionPolicy;
import com.mycorp.distributedlock.api.SessionRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZooKeeperSessionLossTest {

    @Test
    void zookeeperLeaseShouldBecomeInvalidAfterSessionLoss() throws Exception {
        AtomicBoolean sessionValid = new AtomicBoolean(true);
        try (TestingServer server = new TestingServer();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(
                 new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks"),
                 sessionValid::get
             );
             BackendSession session = backend.openSession(new SessionRequest(SessionPolicy.MANUAL_CLOSE));
             BackendLockLease lease = session.acquire(new LockRequest(
                 new LockKey("zk:lost:1"),
                 LockMode.MUTEX,
                 WaitPolicy.indefinite(),
                 LeasePolicy.RELEASE_ON_CLOSE
             ))) {
            sessionValid.set(false);

            assertThat(lease.isValid()).isFalse();
            assertThatThrownBy(lease::release)
                .isInstanceOf(LockOwnershipLostException.class);
        }
    }
}
