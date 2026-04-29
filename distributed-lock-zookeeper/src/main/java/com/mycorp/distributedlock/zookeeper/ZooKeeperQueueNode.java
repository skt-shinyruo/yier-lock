package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockMode;

record ZooKeeperQueueNode(String name, LockMode mode, long sequence) {

    static ZooKeeperQueueNode parse(String name) {
        if (name.startsWith("mutex-")) {
            return new ZooKeeperQueueNode(name, LockMode.MUTEX, sequence(name, "mutex-"));
        }
        if (name.startsWith("read-")) {
            return new ZooKeeperQueueNode(name, LockMode.READ, sequence(name, "read-"));
        }
        if (name.startsWith("write-")) {
            return new ZooKeeperQueueNode(name, LockMode.WRITE, sequence(name, "write-"));
        }
        throw new IllegalArgumentException("Malformed ZooKeeper queue node: " + name);
    }

    static ZooKeeperQueueNode parseIfQueueNode(String name) {
        if (!name.startsWith("mutex-") && !name.startsWith("read-") && !name.startsWith("write-")) {
            return null;
        }
        return parse(name);
    }

    private static long sequence(String nodeName, String prefix) {
        String suffix = nodeName.substring(prefix.length());
        if (!suffix.matches("\\d+")) {
            throw new IllegalArgumentException("Malformed ZooKeeper queue node: " + nodeName);
        }
        try {
            return Long.parseLong(suffix);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Malformed ZooKeeper queue node: " + nodeName, exception);
        }
    }
}
