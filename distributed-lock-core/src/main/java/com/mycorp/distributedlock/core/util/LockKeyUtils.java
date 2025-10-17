package com.mycorp.distributedlock.core.util;

import java.util.UUID;

public final class LockKeyUtils {
    
    private static final String LOCK_PREFIX = "distributed-lock:";
    private static final String READ_LOCK_SUFFIX = ":read";
    private static final String WRITE_LOCK_SUFFIX = ":write";
    private static final String CHANNEL_SUFFIX = ":channel";
    private static final String CLIENT_ID = UUID.randomUUID().toString();
    
    private LockKeyUtils() {
    }
    
    public static String generateLockKey(String lockName) {
        return LOCK_PREFIX + lockName;
    }
    
    public static String generateReadLockKey(String lockName) {
        return LOCK_PREFIX + lockName + READ_LOCK_SUFFIX;
    }
    
    public static String generateWriteLockKey(String lockName) {
        return LOCK_PREFIX + lockName + WRITE_LOCK_SUFFIX;
    }
    
    public static String generateChannelKey(String lockName) {
        return LOCK_PREFIX + lockName + CHANNEL_SUFFIX;
    }
    
    public static String generateLockValue() {
        return CLIENT_ID + ":" + Thread.currentThread().getId();
    }
    
    public static String getCurrentThreadLockValue() {
        return generateLockValue();
    }
    
    public static boolean isCurrentThreadValue(String value) {
        if (value == null) {
            return false;
        }
        String[] parts = value.split(":");
        if (parts.length != 2) {
            return false;
        }
        try {
            long threadId = Long.parseLong(parts[1]);
            return threadId == Thread.currentThread().getId();
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
