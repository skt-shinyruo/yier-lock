package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockKey;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class ZooKeeperPathLayout {

    private final String basePath;

    ZooKeeperPathLayout(String basePath) {
        this.basePath = basePath;
    }

    String queuePath(LockKey key) {
        return queuePath(key.value());
    }

    String queuePath(String key) {
        return joinPath(basePath, "rw/" + encodeKeySegment(key) + "/locks");
    }

    String fenceCounterPath(LockKey key) {
        return fenceCounterPath(key.value());
    }

    String fenceCounterPath(String key) {
        return joinPath(basePath, "fence/" + encodeKeySegment(key));
    }

    private String joinPath(String first, String second) {
        String normalized = first.endsWith("/") && first.length() > 1
            ? first.substring(0, first.length() - 1)
            : first;
        if ("/".equals(normalized)) {
            return "/" + second;
        }
        return normalized + "/" + second;
    }

    private String encodeKeySegment(String key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }
}
