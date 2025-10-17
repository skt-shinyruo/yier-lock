package com.mycorp.distributedlock.redis.script;

/**
 * Optimized Lua scripts for Redis distributed locks.
 * Inspired by Redisson's script optimizations and best practices.
 * 
 * All scripts are atomic and handle edge cases like:
 * - Reentrancy with proper counter management
 * - Expiration time tracking for fencing tokens
 * - TTL refresh without race conditions
 * - Clean unlock with pub/sub notifications
 */
public final class RedisLockScripts {

    private RedisLockScripts() {
        // Utility class
    }

    /**
     * Enhanced lock acquisition script with fencing token support.
     * 
     * KEYS[1] = lock key
     * ARGV[1] = lock value (owner identifier)
     * ARGV[2] = lease time in milliseconds
     * ARGV[3] = current timestamp in milliseconds
     * 
     * Returns:
     * - 1 if lock acquired
     * - 0 if lock held by another owner
     * - Fencing token (negative) if reentrant acquisition
     */
    public static final String LOCK_SCRIPT = 
        "local lockKey = KEYS[1]\n" +
        "local lockValue = ARGV[1]\n" +
        "local leaseTime = tonumber(ARGV[2])\n" +
        "local currentTime = tonumber(ARGV[3])\n" +
        "\n" +
        "-- Check if lock exists\n" +
        "local owner = redis.call('HGET', lockKey, 'owner')\n" +
        "\n" +
        "if owner == false then\n" +
        "    -- Lock doesn't exist, acquire it\n" +
        "    local token = redis.call('INCR', lockKey .. ':token')\n" +
        "    redis.call('HMSET', lockKey,\n" +
        "        'owner', lockValue,\n" +
        "        'count', 1,\n" +
        "        'acquired', currentTime,\n" +
        "        'token', token)\n" +
        "    redis.call('PEXPIRE', lockKey, leaseTime)\n" +
        "    redis.call('PEXPIRE', lockKey .. ':token', leaseTime * 2)\n" +
        "    return token\n" +
        "end\n" +
        "\n" +
        "-- Check for reentrancy\n" +
        "if owner == lockValue then\n" +
        "    local count = redis.call('HINCRBY', lockKey, 'count', 1)\n" +
        "    redis.call('PEXPIRE', lockKey, leaseTime)\n" +
        "    local token = redis.call('HGET', lockKey, 'token')\n" +
        "    return -tonumber(token)\n" +  // Negative to indicate reentrant
        "end\n" +
        "\n" +
        "-- Lock held by another owner\n" +
        "return 0";

    /**
     * Unlock script with proper reentrancy and notification support.
     * 
     * KEYS[1] = lock key
     * KEYS[2] = channel key for pub/sub
     * ARGV[1] = lock value (owner identifier)
     * ARGV[2] = lease time (for partial unlock scenarios)
     * ARGV[3] = unlock message
     * 
     * Returns:
     * - Positive number: remaining reentrant count
     * - 0: lock fully released
     * - -1: not owner / invalid unlock
     */
    public static final String UNLOCK_SCRIPT =
        "local lockKey = KEYS[1]\n" +
        "local channelKey = KEYS[2]\n" +
        "local lockValue = ARGV[1]\n" +
        "local leaseTime = tonumber(ARGV[2])\n" +
        "local unlockMsg = ARGV[3]\n" +
        "\n" +
        "-- Verify ownership\n" +
        "local owner = redis.call('HGET', lockKey, 'owner')\n" +
        "if owner ~= lockValue then\n" +
        "    return -1\n" +
        "end\n" +
        "\n" +
        "-- Decrement reentrant counter\n" +
        "local count = redis.call('HINCRBY', lockKey, 'count', -1)\n" +
        "\n" +
        "if count > 0 then\n" +
        "    -- Still have reentrant holds, refresh TTL\n" +
        "    redis.call('PEXPIRE', lockKey, leaseTime)\n" +
        "    return count\n" +
        "else\n" +
        "    -- Fully unlocked, clean up and notify\n" +
        "    redis.call('DEL', lockKey)\n" +
        "    redis.call('PUBLISH', channelKey, unlockMsg)\n" +
        "    return 0\n" +
        "end";

    /**
     * Watchdog renewal script with ownership validation.
     * 
     * KEYS[1] = lock key
     * ARGV[1] = lock value (owner identifier)
     * ARGV[2] = new lease time in milliseconds
     * ARGV[3] = current timestamp
     * 
     * Returns:
     * - 1 if renewed successfully
     * - 0 if not owner or lock doesn't exist
     */
    public static final String RENEW_SCRIPT =
        "local lockKey = KEYS[1]\n" +
        "local lockValue = ARGV[1]\n" +
        "local leaseTime = tonumber(ARGV[2])\n" +
        "local currentTime = tonumber(ARGV[3])\n" +
        "\n" +
        "local owner = redis.call('HGET', lockKey, 'owner')\n" +
        "if owner == lockValue then\n" +
        "    redis.call('PEXPIRE', lockKey, leaseTime)\n" +
        "    redis.call('HSET', lockKey, 'renewed', currentTime)\n" +
        "    return 1\n" +
        "end\n" +
        "return 0";

    /**
     * Check lock status with detailed information.
     * 
     * KEYS[1] = lock key
     * ARGV[1] = lock value (owner identifier)
     * 
     * Returns: array with [is_locked, is_owner, reentrant_count, remaining_ttl_ms, token]
     */
    public static final String STATUS_SCRIPT =
        "local lockKey = KEYS[1]\n" +
        "local lockValue = ARGV[1]\n" +
        "\n" +
        "local owner = redis.call('HGET', lockKey, 'owner')\n" +
        "if owner == false then\n" +
        "    return {0, 0, 0, -1, 0}\n" +
        "end\n" +
        "\n" +
        "local isOwner = (owner == lockValue) and 1 or 0\n" +
        "local count = redis.call('HGET', lockKey, 'count') or 0\n" +
        "local ttl = redis.call('PTTL', lockKey)\n" +
        "local token = redis.call('HGET', lockKey, 'token') or 0\n" +
        "\n" +
        "return {1, isOwner, tonumber(count), tonumber(ttl), tonumber(token)}";

    /**
     * Force unlock script (admin operation, use with caution).
     * 
     * KEYS[1] = lock key
     * KEYS[2] = channel key
     * ARGV[1] = unlock message
     * 
     * Returns: 1 if deleted, 0 if didn't exist
     */
    public static final String FORCE_UNLOCK_SCRIPT =
        "local lockKey = KEYS[1]\n" +
        "local channelKey = KEYS[2]\n" +
        "local unlockMsg = ARGV[1]\n" +
        "\n" +
        "local deleted = redis.call('DEL', lockKey)\n" +
        "if deleted > 0 then\n" +
        "    redis.call('PUBLISH', channelKey, unlockMsg)\n" +
        "    return 1\n" +
        "end\n" +
        "return 0";

    /**
     * Extend lock lease time (for long-running operations).
     * 
     * KEYS[1] = lock key
     * ARGV[1] = lock value (owner identifier)
     * ARGV[2] = additional time in milliseconds
     * 
     * Returns:
     * - New TTL if successful
     * - -1 if not owner
     * - -2 if lock doesn't exist
     */
    public static final String EXTEND_SCRIPT =
        "local lockKey = KEYS[1]\n" +
        "local lockValue = ARGV[1]\n" +
        "local additionalTime = tonumber(ARGV[2])\n" +
        "\n" +
        "local owner = redis.call('HGET', lockKey, 'owner')\n" +
        "if owner == false then\n" +
        "    return -2\n" +
        "end\n" +
        "\n" +
        "if owner ~= lockValue then\n" +
        "    return -1\n" +
        "end\n" +
        "\n" +
        "local currentTtl = redis.call('PTTL', lockKey)\n" +
        "if currentTtl > 0 then\n" +
        "    local newTtl = currentTtl + additionalTime\n" +
        "    redis.call('PEXPIRE', lockKey, newTtl)\n" +
        "    return newTtl\n" +
        "end\n" +
        "\n" +
        "-- Shouldn't happen, but handle gracefully\n" +
        "redis.call('PEXPIRE', lockKey, additionalTime)\n" +
        "return additionalTime";

    /**
     * Fair lock queue entry script.
     * Used by fair lock implementation to maintain FIFO ordering.
     * 
     * KEYS[1] = queue key (sorted set)
     * KEYS[2] = timeout key
     * ARGV[1] = thread identifier
     * ARGV[2] = timeout timestamp
     * ARGV[3] = current timestamp
     * ARGV[4] = wait time in milliseconds
     * 
     * Returns: position in queue (0-based)
     */
    public static final String FAIR_QUEUE_SCRIPT =
        "local queueKey = KEYS[1]\n" +
        "local timeoutKey = KEYS[2]\n" +
        "local threadId = ARGV[1]\n" +
        "local timeoutTime = tonumber(ARGV[2])\n" +
        "local currentTime = tonumber(ARGV[3])\n" +
        "local waitTime = tonumber(ARGV[4])\n" +
        "\n" +
        "-- Clean up expired entries\n" +
        "redis.call('ZREMRANGEBYSCORE', queueKey, '-inf', currentTime - waitTime)\n" +
        "\n" +
        "-- Add to queue if not already present\n" +
        "local score = redis.call('ZSCORE', queueKey, threadId)\n" +
        "if score == false then\n" +
        "    redis.call('ZADD', queueKey, currentTime, threadId)\n" +
        "    redis.call('PEXPIRE', queueKey, waitTime * 2)\n" +
        "end\n" +
        "\n" +
        "-- Set timeout for this thread\n" +
        "redis.call('HSET', timeoutKey, threadId, timeoutTime)\n" +
        "redis.call('PEXPIRE', timeoutKey, waitTime * 2)\n" +
        "\n" +
        "-- Return position in queue\n" +
        "return redis.call('ZRANK', queueKey, threadId)";
}
