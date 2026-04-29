package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.KeeperException;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

final class ZooKeeperQueue {

    private final CuratorFramework curatorFramework;
    private final ZooKeeperPathLayout pathLayout;

    ZooKeeperQueue(CuratorFramework curatorFramework, ZooKeeperPathLayout pathLayout) {
        this.curatorFramework = curatorFramework;
        this.pathLayout = pathLayout;
    }

    List<ZooKeeperQueueNode> nodes(String key) throws Exception {
        try {
            return curatorFramework.getChildren().forPath(pathLayout.queuePath(key)).stream()
                .map(this::queueNode)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(ZooKeeperQueueNode::sequence))
                .toList();
        } catch (KeeperException.NoNodeException exception) {
            return List.of();
        }
    }

    ZooKeeperQueueNode currentNode(List<ZooKeeperQueueNode> nodes, String nodeName) {
        return nodes.stream()
            .filter(node -> node.name().equals(nodeName))
            .findFirst()
            .orElse(null);
    }

    boolean canAcquire(List<ZooKeeperQueueNode> nodes, ZooKeeperQueueNode current) {
        if (isExclusive(current.mode())) {
            return nodes.stream()
                .takeWhile(node -> node.sequence() < current.sequence())
                .findAny()
                .isEmpty();
        }
        return nodes.stream()
            .takeWhile(node -> node.sequence() < current.sequence())
            .noneMatch(node -> isExclusive(node.mode()));
    }

    String watchedPredecessor(String key, List<ZooKeeperQueueNode> nodes, ZooKeeperQueueNode current) {
        if (isExclusive(current.mode())) {
            return nodes.stream()
                .filter(node -> node.sequence() < current.sequence())
                .max(Comparator.comparingLong(ZooKeeperQueueNode::sequence))
                .map(node -> pathLayout.queuePath(key) + "/" + node.name())
                .orElse(null);
        }
        return nodes.stream()
            .filter(node -> node.sequence() < current.sequence() && isExclusive(node.mode()))
            .max(Comparator.comparingLong(ZooKeeperQueueNode::sequence))
            .map(node -> pathLayout.queuePath(key) + "/" + node.name())
            .orElse(null);
    }

    private ZooKeeperQueueNode queueNode(String name) {
        try {
            return ZooKeeperQueueNode.parseIfQueueNode(name);
        } catch (IllegalArgumentException exception) {
            throw new LockBackendException(exception.getMessage(), exception);
        }
    }

    private boolean isExclusive(LockMode mode) {
        return mode == LockMode.MUTEX || mode == LockMode.WRITE;
    }
}
