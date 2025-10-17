package com.mycorp.distributedlock.core.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class LockKeyUtilsTest {

    @Test
    void generateLockValueIsStablePerThread() {
        String first = LockKeyUtils.generateLockValue();
        String second = LockKeyUtils.generateLockValue();
        assertEquals(first, second, "Lock value should remain stable for the current thread");
    }

    @Test
    void generateLockValueDiffersAcrossThreads() throws ExecutionException, InterruptedException {
        String mainThreadValue = LockKeyUtils.generateLockValue();
        Callable<String> task = LockKeyUtils::generateLockValue;
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> otherThreadValue = executor.submit(task);
            assertNotEquals(mainThreadValue, otherThreadValue.get(), "Each thread must have its own lock value");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void lockKeyHelpersApplyExpectedPrefixes() {
        String baseKey = LockKeyUtils.generateLockKey("orders");
        String readKey = LockKeyUtils.generateReadLockKey("orders");
        String writeKey = LockKeyUtils.generateWriteLockKey("orders");
        String channelKey = LockKeyUtils.generateChannelKey("orders");

        assertTrue(baseKey.startsWith("distributed-lock:"), "Base lock should include expected prefix");
        assertTrue(readKey.endsWith(":read"), "Read lock key should end with :read");
        assertTrue(writeKey.endsWith(":write"), "Write lock key should end with :write");
        assertTrue(channelKey.endsWith(":channel"), "Channel key should end with :channel");
    }
}
