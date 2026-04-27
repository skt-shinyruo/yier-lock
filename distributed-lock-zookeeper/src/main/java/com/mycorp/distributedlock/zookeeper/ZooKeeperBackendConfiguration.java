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
        if (basePath.length() > 1 && basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        org.apache.zookeeper.common.PathUtils.validatePath(basePath);
    }
}
