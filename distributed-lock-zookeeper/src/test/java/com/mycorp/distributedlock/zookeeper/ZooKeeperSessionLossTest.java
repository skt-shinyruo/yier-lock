package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZooKeeperSessionLossTest {

    @Test
    void leaseShouldReportOwnershipLossAfterSessionInvalidation() throws Exception {
        AtomicBoolean sessionValid = new AtomicBoolean(true);
        try (TestingServer server = new TestingServer();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(
                 new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks"),
                 sessionValid::get
             )) {
            BackendLockLease lease = backend.acquire(
                new LockResource("zk:lost:1"),
                LockMode.MUTEX,
                WaitPolicy.indefinite()
            );
            sessionValid.set(false);

            assertThat(lease.isValidForCurrentExecution()).isFalse();
            assertThatThrownBy(lease::release)
                .isInstanceOf(LockOwnershipLostException.class);
        }
    }
}
