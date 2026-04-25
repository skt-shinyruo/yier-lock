# Redis Read/Write Writer Starvation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Redis read/write locks writer-preferred under contention so continuous reader traffic cannot indefinitely starve a waiting writer.

**Architecture:** Add a Redis pending-writer zset per lock key. A write acquisition registers a stable writer intent before any conflict check that can make it wait; read acquisition cleans stale writer intents and refuses new readers while any live writer intent exists. The Java acquire loop keeps one writer intent id for the whole write attempt and removes it on timeout, interruption, backend failure, or successful acquisition.

**Tech Stack:** Java 17, Maven, JUnit 5, AssertJ, Lettuce Redis client, Redis Lua scripts

---

## File Map

- Create: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisReadWriteWriterPreferenceTest.java`
  Covers writer intent visibility, reader gating, writer progress after existing readers drain, writer-timeout cleanup, and live pending-intent expiry.
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`
  Adds pending-writer key/script support, passes the pending key into read/write acquisition scripts, and creates/cleans stable writer intent ids in the acquire loop.
- Modify: `distributed-lock-spring-boot-starter/README.md`
  Documents that Redis read/write locks are writer-preferred but not FIFO fair.

## Task 1: Add Redis Writer-Preference Regression Tests

**Files:**
- Create: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisReadWriteWriterPreferenceTest.java`

- [ ] **Step 1: Write the failing Redis writer-preference tests**

Create `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisReadWriteWriterPreferenceTest.java` with:

```java
package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RedisReadWriteWriterPreferenceTest {

    private static final String WRITER_PROGRESS_KEY = "redis:rw:writer-progress";
    private static final String WRITER_TIMEOUT_KEY = "redis:rw:writer-timeout";
    private static final String MANUAL_PENDING_KEY = "redis:rw:manual-pending";

    private static RedisTestSupport.RunningRedis redis;

    @BeforeAll
    static void startRedis() throws Exception {
        redis = RedisTestSupport.startRedis();
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (redis != null) {
            redis.close();
        }
    }

    @BeforeEach
    void resetRedis() {
        redis.flushAll();
    }

    @Test
    void pendingWriterShouldBlockLaterReadersAndAcquireAfterReadersDrain() throws Exception {
        try (RedisLockBackend backend = redis.newBackend(30L)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            BackendSession readerSession = backend.openSession();
            BackendLockLease readerLease = readerSession.acquire(readRequest(WRITER_PROGRESS_KEY, Duration.ofSeconds(1)));
            BackendSession writerSession = backend.openSession();
            Future<BackendLockLease> writerAttempt = executor.submit(() ->
                writerSession.acquire(writeRequest(WRITER_PROGRESS_KEY, Duration.ofSeconds(5)))
            );
            BackendLockLease writerLease = null;

            try {
                assertThat(awaitPendingWriterIntent(WRITER_PROGRESS_KEY)).isTrue();

                assertThat(tryAcquireRead(backend, WRITER_PROGRESS_KEY, Duration.ofMillis(150))).isFalse();
                assertThat(tryAcquireRead(backend, WRITER_PROGRESS_KEY, Duration.ofMillis(150))).isFalse();

                readerLease.release();
                writerLease = writerAttempt.get(2, TimeUnit.SECONDS);

                assertThat(writerLease.isValid()).isTrue();
                assertThat(pendingWriterCount(WRITER_PROGRESS_KEY)).isZero();
            } finally {
                closeQuietly(writerLease);
                writerAttempt.cancel(true);
                closeQuietly(writerSession);
                closeQuietly(readerLease);
                closeQuietly(readerSession);
                executor.shutdownNow();
            }
        }
    }

    @Test
    void writerTimeoutShouldRemovePendingIntent() throws Exception {
        try (RedisLockBackend backend = redis.newBackend(30L);
             BackendSession readerSession = backend.openSession();
             BackendLockLease ignored = readerSession.acquire(readRequest(WRITER_TIMEOUT_KEY, Duration.ofSeconds(1)))) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Boolean> writerAttempt = executor.submit(() ->
                tryAcquireWrite(backend, WRITER_TIMEOUT_KEY, Duration.ofMillis(300))
            );

            try {
                assertThat(awaitPendingWriterIntent(WRITER_TIMEOUT_KEY)).isTrue();
                assertThat(writerAttempt.get(2, TimeUnit.SECONDS)).isFalse();
                assertThat(pendingWriterCount(WRITER_TIMEOUT_KEY)).isZero();
            } finally {
                writerAttempt.cancel(true);
                executor.shutdownNow();
            }
        }
    }

    @Test
    void livePendingWriterIntentShouldBlockReadersUntilItExpires() throws Exception {
        String pendingKey = pendingWritersKey(MANUAL_PENDING_KEY);
        redis.commands().zadd(pendingKey, System.currentTimeMillis() + 250L, "manual-writer");
        redis.commands().pexpire(pendingKey, 1000L);

        try (RedisLockBackend backend = redis.newBackend(30L)) {
            assertThat(tryAcquireRead(backend, MANUAL_PENDING_KEY, Duration.ofMillis(150))).isFalse();

            Thread.sleep(350L);

            assertThat(tryAcquireRead(backend, MANUAL_PENDING_KEY, Duration.ofMillis(500))).isTrue();
            assertThat(pendingWriterCount(MANUAL_PENDING_KEY)).isZero();
        }
    }

    private static boolean tryAcquireRead(RedisLockBackend backend, String key, Duration waitTime) throws Exception {
        try (BackendSession session = backend.openSession();
             BackendLockLease lease = session.acquire(readRequest(key, waitTime))) {
            return lease.isValid();
        } catch (LockAcquisitionTimeoutException exception) {
            return false;
        }
    }

    private static boolean tryAcquireWrite(RedisLockBackend backend, String key, Duration waitTime) throws Exception {
        try (BackendSession session = backend.openSession();
             BackendLockLease lease = session.acquire(writeRequest(key, waitTime))) {
            return lease.isValid();
        } catch (LockAcquisitionTimeoutException exception) {
            return false;
        }
    }

    private static boolean awaitPendingWriterIntent(String key) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadlineNanos) {
            if (pendingWriterCount(key) > 0) {
                return true;
            }
            Thread.sleep(25L);
        }
        return false;
    }

    private static long pendingWriterCount(String key) {
        Long count = redis.commands().zcard(pendingWritersKey(key));
        return count == null ? 0L : count;
    }

    private static String pendingWritersKey(String key) {
        return "lock:%s:write:pending".formatted(key);
    }

    private static LockRequest readRequest(String key, Duration waitTime) {
        return new LockRequest(
            new LockKey(key),
            LockMode.READ,
            WaitPolicy.timed(waitTime)
        );
    }

    private static LockRequest writeRequest(String key, Duration waitTime) {
        return new LockRequest(
            new LockKey(key),
            LockMode.WRITE,
            WaitPolicy.timed(waitTime)
        );
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
```

- [ ] **Step 2: Run the focused Redis writer-preference tests to verify they fail**

Run:

```bash
mvn -q -pl distributed-lock-redis -am test -Dtest=RedisReadWriteWriterPreferenceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL. At least `pendingWriterShouldBlockLaterReadersAndAcquireAfterReadersDrain` should fail because no pending-writer zset is registered by the current implementation.

- [ ] **Step 3: Commit the failing tests**

```bash
git add distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisReadWriteWriterPreferenceTest.java
git commit -m "test: reproduce redis writer starvation"
```

## Task 2: Implement Redis Pending-Writer Intent

**Files:**
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`
- Test: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisReadWriteWriterPreferenceTest.java`

- [ ] **Step 1: Update Redis acquire scripts and helper keys**

In `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`, replace `READ_ACQUIRE_SCRIPT` and `WRITE_ACQUIRE_SCRIPT`, add `PENDING_WRITER_CLEANUP_SCRIPT`, and add `pendingWritersKey(String)`:

```java
private static final String READ_ACQUIRE_SCRIPT =
    "local now = redis.call('time') "
        + "local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) "
        + "local readerType = redis.call('type', KEYS[3]).ok "
        + "if readerType == 'hash' then return 0 end "
        + "if readerType ~= 'none' and readerType ~= 'zset' then return 0 end "
        + "if readerType == 'zset' then redis.call('zremrangebyscore', KEYS[3], '-inf', nowMs) end "
        + "local pendingType = redis.call('type', KEYS[5]).ok "
        + "if pendingType ~= 'none' and pendingType ~= 'zset' then return 0 end "
        + "if pendingType == 'zset' then redis.call('zremrangebyscore', KEYS[5], '-inf', nowMs) end "
        + "if pendingType == 'zset' and redis.call('zcard', KEYS[5]) == 0 then redis.call('del', KEYS[5]) end "
        + "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
        + "if redis.call('exists', KEYS[2]) == 1 then return 0 end "
        + "if redis.call('exists', KEYS[5]) == 1 then return 0 end "
        + "local fence = redis.call('incr', KEYS[4]) "
        + "local owner = ARGV[1] .. ':' .. fence "
        + "local ttlMs = tonumber(ARGV[2]) * 1000 "
        + "redis.call('zadd', KEYS[3], nowMs + ttlMs, owner) "
        + "redis.call('pexpire', KEYS[3], ttlMs) "
        + "return fence";

private static final String WRITE_ACQUIRE_SCRIPT =
    "local now = redis.call('time') "
        + "local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) "
        + "local ttlMs = tonumber(ARGV[2]) * 1000 "
        + "local readerType = redis.call('type', KEYS[3]).ok "
        + "if readerType == 'hash' then return 0 end "
        + "if readerType ~= 'none' and readerType ~= 'zset' then return 0 end "
        + "if readerType == 'zset' then redis.call('zremrangebyscore', KEYS[3], '-inf', nowMs) end "
        + "local pendingType = redis.call('type', KEYS[5]).ok "
        + "if pendingType ~= 'none' and pendingType ~= 'zset' then return 0 end "
        + "if pendingType == 'zset' then redis.call('zremrangebyscore', KEYS[5], '-inf', nowMs) end "
        + "redis.call('zadd', KEYS[5], nowMs + ttlMs, ARGV[3]) "
        + "redis.call('pexpire', KEYS[5], ttlMs) "
        + "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
        + "if redis.call('exists', KEYS[2]) == 1 then return 0 end "
        + "if redis.call('zcard', KEYS[3]) > 0 then return 0 end "
        + "redis.call('zrem', KEYS[5], ARGV[3]) "
        + "if redis.call('zcard', KEYS[5]) == 0 then redis.call('del', KEYS[5]) end "
        + "local fence = redis.call('incr', KEYS[4]) "
        + "local owner = ARGV[1] .. ':' .. fence "
        + "redis.call('set', KEYS[2], owner, 'EX', tonumber(ARGV[2])) "
        + "return fence";

private static final String PENDING_WRITER_CLEANUP_SCRIPT =
    "if redis.call('type', KEYS[1]).ok ~= 'zset' then return 0 end "
        + "local removed = redis.call('zrem', KEYS[1], ARGV[1]) "
        + "if redis.call('zcard', KEYS[1]) == 0 then redis.call('del', KEYS[1]) end "
        + "return removed";
```

Add this helper near `readersKey(String)`:

```java
private static String pendingWritersKey(String key) {
    return "lock:%s:write:pending".formatted(key);
}
```

- [ ] **Step 2: Give each write acquisition one stable writer intent id**

Replace `RedisBackendSession.acquire(LockRequest request)` with:

```java
@Override
public BackendLockLease acquire(LockRequest request) throws InterruptedException {
    ensureActive();

    String writerIntent = request.mode() == LockMode.WRITE
        ? writerIntentValue(sessionId, nextSessionId())
        : null;
    boolean acquired = false;

    try {
        long deadlineNanos = request.waitPolicy().unbounded()
            ? Long.MAX_VALUE
            : System.nanoTime() + request.waitPolicy().waitTime().toNanos();

        do {
            ensureActive();
            RedisLease lease = tryAcquire(request, writerIntent);
            if (lease != null) {
                acquired = true;
                activeLeases.put(lease.ownerValue(), lease);
                return lease;
            }
            if (!request.waitPolicy().unbounded() && System.nanoTime() >= deadlineNanos) {
                throw new LockAcquisitionTimeoutException("Timed out acquiring Redis lock for key " + request.key().value());
            }
            Thread.sleep(request.waitPolicy().unbounded()
                ? 25L
                : Math.min(25L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()))));
        } while (true);
    } finally {
        if (!acquired && writerIntent != null) {
            clearPendingWriterIntent(request.key().value(), writerIntent);
        }
    }
}
```

- [ ] **Step 3: Pass writer intent through acquisition dispatch**

Replace `tryAcquire(LockRequest request)`, `tryAcquireRead(String key)`, and `tryAcquireWrite(String key)` with:

```java
private RedisLease tryAcquire(LockRequest request, String writerIntent) {
    long fence = switch (request.mode()) {
        case MUTEX -> tryAcquireMutex(request.key().value());
        case READ -> tryAcquireRead(request.key().value());
        case WRITE -> tryAcquireWrite(request.key().value(), Objects.requireNonNull(writerIntent, "writerIntent"));
    };
    if (fence <= 0) {
        return null;
    }

    return new RedisLease(
        request.key(),
        request.mode(),
        new FencingToken(fence),
        ownerValue(sessionId, fence),
        this
    );
}

private long tryAcquireRead(String key) {
    try {
        Long result = commands.eval(
            READ_ACQUIRE_SCRIPT,
            ScriptOutputType.INTEGER,
            new String[]{
                ownerKey(key, LockMode.MUTEX),
                ownerKey(key, LockMode.WRITE),
                readersKey(key),
                fenceKey(key),
                pendingWritersKey(key)
            },
            sessionId,
            String.valueOf(configuration.leaseSeconds())
        );
        return result == null ? 0L : result;
    } catch (RuntimeException exception) {
        throw new LockBackendException("Failed to acquire Redis read lock for key " + key, exception);
    }
}

private long tryAcquireWrite(String key, String writerIntent) {
    try {
        Long result = commands.eval(
            WRITE_ACQUIRE_SCRIPT,
            ScriptOutputType.INTEGER,
            new String[]{
                ownerKey(key, LockMode.MUTEX),
                ownerKey(key, LockMode.WRITE),
                readersKey(key),
                fenceKey(key),
                pendingWritersKey(key)
            },
            sessionId,
            String.valueOf(configuration.leaseSeconds()),
            writerIntent
        );
        return result == null ? 0L : result;
    } catch (RuntimeException exception) {
        throw new LockBackendException("Failed to acquire Redis write lock for key " + key, exception);
    }
}
```

- [ ] **Step 4: Add best-effort writer intent cleanup helpers**

Add these methods inside `RedisBackendSession`, near the other private helpers:

```java
private void clearPendingWriterIntent(String key, String writerIntent) {
    try {
        commands.eval(
            PENDING_WRITER_CLEANUP_SCRIPT,
            ScriptOutputType.INTEGER,
            new String[]{pendingWritersKey(key)},
            writerIntent
        );
    } catch (RuntimeException ignored) {
    }
}
```

Add this static helper near `ownerValue(String sessionId, long fence)` at the end of the class:

```java
private static String writerIntentValue(String sessionId, String attemptId) {
    return sessionId + ":writer:" + attemptId;
}
```

- [ ] **Step 5: Run the focused Redis writer-preference tests to verify they pass**

Run:

```bash
mvn -q -pl distributed-lock-redis -am test -Dtest=RedisReadWriteWriterPreferenceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 6: Run existing focused Redis lock tests**

Run:

```bash
mvn -q -pl distributed-lock-redis -am test -Dtest=RedisLockBackendContractTest,RedisReadLockExpirationTest,RedisLeaseRenewalTest,RedisOwnershipLossTest,RedisSessionLossTest,RedisExecutorOwnershipLossTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 7: Commit the Redis implementation**

```bash
git add distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisReadWriteWriterPreferenceTest.java
git commit -m "fix: prevent redis writer starvation"
```

## Task 3: Document Redis Read/Write Progress Semantics

**Files:**
- Modify: `distributed-lock-spring-boot-starter/README.md`
- Test: documentation review by exact text search

- [ ] **Step 1: Add Redis writer-preference wording to the starter README**

In `distributed-lock-spring-boot-starter/README.md`, after the existing paragraph that begins `Lock modes protect the same resource identity`, add:

```markdown
Backend progress semantics are backend-specific. Redis read/write locks are writer-preferred once a writer has registered pending intent, but they are not FIFO fair: later readers wait while existing readers drain, and multiple waiting writers are resolved by Redis polling and script execution order rather than a strict queue.
```

- [ ] **Step 2: Verify the README contains the documented Redis semantics**

Run:

```bash
rg -n "writer-preferred|FIFO fair|pending intent|strict queue" distributed-lock-spring-boot-starter/README.md
```

Expected: output includes the new Redis read/write semantics paragraph.

- [ ] **Step 3: Commit the documentation update**

```bash
git add distributed-lock-spring-boot-starter/README.md
git commit -m "docs: clarify redis read write progress semantics"
```

## Task 4: Final Verification

**Files:**
- Verify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`
- Verify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisReadWriteWriterPreferenceTest.java`
- Verify: `distributed-lock-spring-boot-starter/README.md`

- [ ] **Step 1: Run the Redis module test suite**

Run:

```bash
mvn -q -pl distributed-lock-redis -am test
```

Expected: PASS.

- [ ] **Step 2: Run the starter README text check**

Run:

```bash
rg -n "Redis read/write locks are writer-preferred" distributed-lock-spring-boot-starter/README.md
```

Expected: output includes the Redis writer-preference sentence.

- [ ] **Step 3: Inspect the final diff**

Run:

```bash
git diff --stat HEAD
git diff -- distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisReadWriteWriterPreferenceTest.java distributed-lock-spring-boot-starter/README.md
```

Expected: diff contains only the Redis pending-writer implementation, Redis writer-preference tests, and the README wording.

- [ ] **Step 4: Commit any verification fixes**

If Step 1 or Step 2 required a small correction, commit only the corrected files:

```bash
git add distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisReadWriteWriterPreferenceTest.java distributed-lock-spring-boot-starter/README.md
git commit -m "test: cover redis writer preference"
```

If no files changed after Step 3, skip this commit step.
