package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.testkit.LockClientContract;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ZooKeeperLockBackendContractTest extends LockClientContract {

    private TestingServer server;

    @Override
    protected LockRuntime createRuntime() throws Exception {
        server = new TestingServer();
        return LockRuntimeBuilder.create()
            .backend("zookeeper")
            .backendModules(java.util.List.of(new ZooKeeperBackendModule(
                new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks")
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
            try (LockSession session = runtime.lockClient().openSession(defaultSession());
                 LockLease lease = session.acquire(sampleRequest("zk:fence"))) {
                first = lease.fencingToken().value();
            }
            try (LockSession session = runtime.lockClient().openSession(defaultSession());
                 LockLease lease = session.acquire(sampleRequest("zk:fence"))) {
                assertThat(lease.fencingToken().value()).isGreaterThan(first);
            }
        }
    }
}
