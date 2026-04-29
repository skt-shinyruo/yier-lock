package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.api.exception.LockFailureContext;
import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

import java.nio.ByteBuffer;

final class ZooKeeperFencingCounter {

    private final CuratorFramework curatorFramework;
    private final ZooKeeperPathLayout pathLayout;

    ZooKeeperFencingCounter(CuratorFramework curatorFramework, ZooKeeperPathLayout pathLayout) {
        this.curatorFramework = curatorFramework;
        this.pathLayout = pathLayout;
    }

    long nextFence(LockRequest request, String sessionId, Runnable ensureActive) throws Exception {
        String key = request.key().value();
        String counterPath = pathLayout.fenceCounterPath(key);
        ensurePersistentCounter(counterPath);
        while (true) {
            Stat stat = new Stat();
            byte[] current = curatorFramework.getData().storingStatIn(stat).forPath(counterPath);
            long currentFence = bytesToLong(current);
            if (currentFence == Long.MAX_VALUE) {
                throw new LockBackendException(
                    "ZooKeeper fencing counter overflow for key " + key,
                    null,
                    LockFailureContext.fromRequest(request, "zookeeper", sessionId)
                );
            }
            long next = currentFence + 1L;
            try {
                curatorFramework.setData()
                    .withVersion(stat.getVersion())
                    .forPath(counterPath, longToBytes(next));
                return next;
            } catch (KeeperException.BadVersionException ignored) {
                ensureActive.run();
                Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(1L, 6L));
            }
        }
    }

    private void ensurePersistentCounter(String counterPath) throws Exception {
        if (curatorFramework.checkExists().forPath(counterPath) != null) {
            return;
        }
        try {
            curatorFramework.create()
                .creatingParentsIfNeeded()
                .withMode(CreateMode.PERSISTENT)
                .forPath(counterPath, longToBytes(0L));
        } catch (KeeperException.NodeExistsException ignored) {
        }
    }

    private static byte[] longToBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private static long bytesToLong(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return 0L;
        }
        return ByteBuffer.wrap(bytes).getLong();
    }
}
