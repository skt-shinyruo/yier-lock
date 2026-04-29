package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.LockMode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

final class RedisLockKeys {
    private final String prefix;

    private RedisLockKeys(String prefix) {
        this.prefix = prefix;
    }

    static RedisLockKeys forKey(String key, RedisKeyStrategy strategy) {
        return switch (strategy) {
            case LEGACY -> new RedisLockKeys("lock:" + key);
            case HASH_TAGGED -> new RedisLockKeys("lock:{" + encodeKey(key) + "}");
        };
    }

    String ownerKey(LockMode mode) {
        return prefix + ":" + normalizeMode(mode) + ":owner";
    }

    String readersKey() {
        return prefix + ":read:owners";
    }

    String pendingWritersKey() {
        return prefix + ":write:pending";
    }

    String fenceKey() {
        return prefix + ":fence";
    }

    private static String normalizeMode(LockMode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    private static String encodeKey(String key) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }
}
