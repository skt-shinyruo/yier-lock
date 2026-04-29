package com.mycorp.distributedlock.examples;

import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.zookeeper.ZooKeeperBackendConfiguration;
import com.mycorp.distributedlock.zookeeper.ZooKeeperBackendModule;

import java.time.Duration;
import java.util.List;

public final class ProgrammaticZooKeeperExample {

    private ProgrammaticZooKeeperExample() {
    }

    public static void main(String[] args) throws Exception {
        try (LockRuntime runtime = LockRuntimeBuilder.create()
            .backend("zookeeper")
            .backendModules(List.of(new ZooKeeperBackendModule(new ZooKeeperBackendConfiguration(
                "127.0.0.1:2181",
                "/distributed-locks"
            ))))
            .build();
             LockSession session = runtime.lockClient().openSession();
             LockLease lease = session.acquire(sampleRequest("example:zk:inventory-7"))) {
            System.out.println(
                "ZooKeeper lease acquired for " + lease.key().value()
                    + " with fencing token " + lease.fencingToken().value()
            );
        }
    }

    private static LockRequest sampleRequest(String key) {
        return LockRequest.mutex(key, WaitPolicy.timed(Duration.ofSeconds(2)));
    }
}
