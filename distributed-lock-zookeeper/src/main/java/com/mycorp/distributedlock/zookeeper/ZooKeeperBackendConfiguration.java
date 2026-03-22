package com.mycorp.distributedlock.zookeeper;

import java.util.Objects;

public record ZooKeeperBackendConfiguration(String connectString, String basePath) {

    public ZooKeeperBackendConfiguration {
        Objects.requireNonNull(connectString, "connectString");
        Objects.requireNonNull(basePath, "basePath");
        if (connectString.isBlank()) {
            throw new IllegalArgumentException("connectString cannot be blank");
        }
        if (basePath.isBlank() || !basePath.startsWith("/")) {
            throw new IllegalArgumentException("basePath must start with '/'");
        }
    }

    public static ZooKeeperBackendConfiguration defaultLocal() {
        return new ZooKeeperBackendConfiguration("127.0.0.1:2181", "/distributed-locks");
    }
}
