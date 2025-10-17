package com.mycorp.distributedlock.api;

/**
 * Fencing token for distributed locks.
 * 
 * Provides monotonically increasing tokens to prevent issues from delayed
 * operations after lock expiry, as described in Martin Kleppmann's analysis
 * and implemented in systems like Apache BookKeeper and ZooKeeper.
 * 
 * The token should be:
 * - Monotonically increasing (each new lock acquisition gets a higher token)
 * - Passed to resources being protected
 * - Validated by resources to reject operations with lower tokens
 * 
 * Example usage:
 * <pre>
 * DistributedLock lock = factory.getLock("resource");
 * lock.lock(30, TimeUnit.SECONDS);
 * try {
 *     FencingToken token = lock.getFencingToken();
 *     // Pass token to protected resource
 *     database.write(data, token);
 * } finally {
 *     lock.unlock();
 * }
 * </pre>
 */
public class FencingToken implements Comparable<FencingToken> {

    private final long value;
    private final String lockName;
    private final long timestamp;

    /**
     * Create a new fencing token.
     *
     * @param value Monotonically increasing token value
     * @param lockName Name of the lock this token belongs to
     */
    public FencingToken(long value, String lockName) {
        this(value, lockName, System.currentTimeMillis());
    }

    /**
     * Create a new fencing token with explicit timestamp.
     *
     * @param value Monotonically increasing token value
     * @param lockName Name of the lock this token belongs to
     * @param timestamp Timestamp when token was issued
     */
    public FencingToken(long value, String lockName, long timestamp) {
        if (value <= 0) {
            throw new IllegalArgumentException("Token value must be positive");
        }
        if (lockName == null || lockName.isEmpty()) {
            throw new IllegalArgumentException("Lock name cannot be null or empty");
        }
        this.value = value;
        this.lockName = lockName;
        this.timestamp = timestamp;
    }

    /**
     * Get the token value.
     * This value is monotonically increasing per lock.
     */
    public long getValue() {
        return value;
    }

    /**
     * Get the lock name this token belongs to.
     */
    public String getLockName() {
        return lockName;
    }

    /**
     * Get the timestamp when this token was issued.
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Check if this token is valid (newer than) another token.
     * Used by resources to reject operations with stale tokens.
     */
    public boolean isNewerThan(FencingToken other) {
        if (other == null) {
            return true;
        }
        if (!this.lockName.equals(other.lockName)) {
            throw new IllegalArgumentException(
                "Cannot compare tokens from different locks: " + this.lockName + " vs " + other.lockName
            );
        }
        return this.value > other.value;
    }

    @Override
    public int compareTo(FencingToken other) {
        if (!this.lockName.equals(other.lockName)) {
            throw new IllegalArgumentException(
                "Cannot compare tokens from different locks: " + this.lockName + " vs " + other.lockName
            );
        }
        return Long.compare(this.value, other.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FencingToken that = (FencingToken) o;
        return value == that.value && lockName.equals(that.lockName);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(value);
        result = 31 * result + lockName.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return String.format("FencingToken{lock='%s', value=%d, timestamp=%d}",
            lockName, value, timestamp);
    }

    /**
     * Special token indicating no valid token (lock not held).
     * Uses a sentinel subclass to override behavior.
     */
    public static final FencingToken NONE = new FencingToken(1, "__none__", 0L) {
        @Override
        public boolean isNewerThan(FencingToken other) {
            return false;
        }

        @Override
        public String toString() {
            return "FencingToken{NONE}";
        }
        
        @Override
        public boolean isValid() {
            return false;
        }
    };

    /**
     * Check if this is a valid token (not NONE).
     */
    public boolean isValid() {
        return this != NONE && value > 0;
    }
}
