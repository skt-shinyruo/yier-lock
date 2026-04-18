package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.testkit.LockClientContract;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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
            try (LockSession session = runtime.lockClient().openSession();
                 LockLease lease = session.acquire(request("zk:fence", LockMode.MUTEX, Duration.ofSeconds(1)))) {
                first = lease.fencingToken().value();
            }
            try (LockSession session = runtime.lockClient().openSession();
                 LockLease lease = session.acquire(request("zk:fence", LockMode.MUTEX, Duration.ofSeconds(1)))) {
                assertThat(lease.fencingToken().value()).isGreaterThan(first);
            }
        }
    }

    @Test
    void zookeeperShouldIssueMonotonicFencingTokensAcrossModes() throws Exception {
        try (LockRuntime runtime = createRuntime();
             LockSession session = runtime.lockClient().openSession()) {
            long first;
            try (LockLease lease = session.acquire(request("zk:fence:mode", LockMode.READ, Duration.ofSeconds(1)))) {
                first = lease.fencingToken().value();
            }
            try (LockLease lease = session.acquire(request("zk:fence:mode", LockMode.WRITE, Duration.ofSeconds(1)))) {
                assertThat(lease.fencingToken().value()).isGreaterThan(first);
            }
        }
    }
}
