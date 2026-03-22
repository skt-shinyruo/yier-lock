package com.mycorp.distributedlock.examples;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;

import java.time.Duration;
import java.util.Map;

public final class ProgrammaticZooKeeperExample {

    private ProgrammaticZooKeeperExample() {
    }

    public static void main(String[] args) throws Exception {
        try (LockRuntime runtime = LockRuntimeBuilder.create()
            .backend("zookeeper")
            .configuration(Map.of(
                "connect-string", "127.0.0.1:2181",
                "base-path", "/distributed-locks"
            ))
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
