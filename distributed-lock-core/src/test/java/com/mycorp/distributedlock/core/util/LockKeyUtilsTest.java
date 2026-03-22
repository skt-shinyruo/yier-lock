package com.mycorp.distributedlock.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockKeyUtilsTest {

    @Test
    void shouldGeneratePrefixedKeys() {
        String lockKey = LockKeyUtils.generateLockKey("order-lock");
        String readKey = LockKeyUtils.generateReadLockKey("order-lock");
        String writeKey = LockKeyUtils.generateWriteLockKey("order-lock");
        String channelKey = LockKeyUtils.generateChannelKey("order-lock");

        assertTrue(lockKey.startsWith("distributed-lock:"));
        assertTrue(readKey.endsWith(":read"));
        assertTrue(writeKey.endsWith(":write"));
        assertTrue(channelKey.endsWith(":channel"));
    }

    @Test
    void shouldGenerateThreadScopedValues() {
        String value = LockKeyUtils.generateLockValue();

        assertNotNull(value);
        assertTrue(LockKeyUtils.isCurrentThreadValue(value));
        assertFalse(LockKeyUtils.isCurrentThreadValue("other-client:999"));
    }
}
