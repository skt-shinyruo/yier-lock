package com.mycorp.distributedlock.zookeeper.operation;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import org.apache.curator.framework.CuratorFramework;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

class ZooKeeperBatchLockOperationsTest {

    @Test
    void shouldUseProvidedCuratorFrameworkInsteadOfFallingBackToLocalLock() {
        CuratorFramework curatorFramework = mock(CuratorFramework.class);
        ZooKeeperBatchLockOperations operations = new ZooKeeperBatchLockOperations(
                curatorFramework,
                null,
                null,
                new LockConfiguration(),
                null
        );

        DistributedLock lock = operations.getLock("batch-zk-lock");

        assertInstanceOf(com.mycorp.distributedlock.zookeeper.ZooKeeperDistributedLock.class, lock);
    }
}
