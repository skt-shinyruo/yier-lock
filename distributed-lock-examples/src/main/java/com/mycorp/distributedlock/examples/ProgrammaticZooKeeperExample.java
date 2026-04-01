package com.mycorp.distributedlock.examples;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.MutexLock;
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
            .backendModules(List.of(new ZooKeeperBackendModule(new ZooKeeperBackendConfiguration(
                "127.0.0.1:2181",
                "/distributed-locks"
            ))))
            .build()) {
            LockManager lockManager = runtime.lockManager();
            MutexLock lock = lockManager.mutex("example:zk:inventory-7");
            if (!lock.tryLock(Duration.ofSeconds(2))) {
                throw new IllegalStateException("Could not acquire ZooKeeper lock");
            }

            try (lock) {
                System.out.println("ZooKeeper lock acquired for " + lock.key());
            }
        }
    }
}
