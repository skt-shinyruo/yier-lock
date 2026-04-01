package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.testkit.LockManagerContract;
import org.apache.curator.test.TestingServer;

class ZooKeeperLockBackendContractTest extends LockManagerContract {

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
}
