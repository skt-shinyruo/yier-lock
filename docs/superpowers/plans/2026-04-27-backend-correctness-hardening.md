# Backend Correctness Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden core/testkit/Redis/ZooKeeper lock correctness so lease ownership, exclusive progress, and session loss semantics are enforced by tests.

**Architecture:** Keep the public API unchanged and make focused backend-local changes. Core replaces busy-spin release coordination, testkit models owner-based leases, Redis generalizes writer intent into exclusive intent, and ZooKeeper makes acquisition waits and fenced lease issuance session-aware.

**Tech Stack:** Java 17, Maven, JUnit 5, AssertJ, Awaitility, Lettuce Redis client, Redis Lua scripts, Apache Curator, Curator TestingServer.

---

## File Map

- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/SessionBoundLockLease.java`
  Coordinates public lease release without CPU-spinning while another thread is running backend release.
- Create: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/SessionBoundLockLeaseConcurrencyTest.java`
  Proves concurrent release blocks and delegates release once.
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryLockBackend.java`
  Replaces Java thread-owned `ReentrantReadWriteLock` with owner-id state.
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/FencedResource.java`
  Makes fencing-token writes atomic.
- Create: `distributed-lock-testkit/src/test/java/com/mycorp/distributedlock/testkit/InMemoryLockBackendThreadOwnershipTest.java`
  Covers cross-thread release, cross-thread session close, and idle key cleanup through public API.
- Create: `distributed-lock-testkit/src/test/java/com/mycorp/distributedlock/testkit/FencedResourceConcurrencyTest.java`
  Covers concurrent stale-token rejection.
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendConfiguration.java`
  Adds `RedisKeyStrategy` with default `LEGACY` and opt-in `HASH_TAGGED`.
- Create: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisKeyStrategy.java`
  Names Redis key layout strategies.
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`
  Adds mutex pending-exclusive intent and instance key formatting.
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisReadWriteWriterPreferenceTest.java`
  Adds mutex starvation and pending-intent cleanup regressions.
- Create: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisKeyStrategyTest.java`
  Verifies legacy key compatibility and hash-tagged per-resource key co-slotting.
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendConfiguration.java`
  Validates and normalizes base paths.
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`
  Adds terminal wait signal, ownership rechecks around fencing, queue-node validation, and fenced counter retry backoff.
- Create: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperAcquireWaitLifecycleTest.java`
  Covers session-loss and close wakeups during indefinite acquire.
- Create: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperPathAndQueueValidationTest.java`
  Covers path normalization and malformed queue nodes.
- Create: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperFencingOwnershipRecheckTest.java`
  Covers contender disappearance before returning a fenced lease.
- Modify: `distributed-lock-spring-boot-starter/README.md`
  Documents Redis exclusive-preferred progress and ZooKeeper fail-safe suspended-session behavior.
- Modify: `distributed-lock-test-suite/README.md`
  Adds new focused regression commands.

## Task 1: Replace Busy-Spin Release Coordination

**Files:**
- Create: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/SessionBoundLockLeaseConcurrencyTest.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/SessionBoundLockLease.java`

- [ ] **Step 1: Write the failing concurrent release test**

Create `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/SessionBoundLockLeaseConcurrencyTest.java` with:

```java
package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SessionBoundLockLeaseConcurrencyTest {

    @Test
    void concurrentReleaseShouldRunDelegateOnceAndBlockSecondCaller() throws Exception {
        ThreadMXBean threadMxBean = ManagementFactory.getThreadMXBean();
        assumeTrue(threadMxBean.isThreadCpuTimeSupported(), "thread CPU time is required for this regression");
        threadMxBean.setThreadCpuTimeEnabled(true);

        BlockingLease delegate = new BlockingLease();
        AtomicInteger unregisterCount = new AtomicInteger();
        AtomicLong secondThreadId = new AtomicLong(-1L);
        SessionBoundLockLease lease = new SessionBoundLockLease(delegate, ignored -> unregisterCount.incrementAndGet());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(lease::release);
            assertThat(delegate.awaitReleaseStarted()).isTrue();

            Future<?> second = executor.submit(() -> {
                secondThreadId.set(Thread.currentThread().getId());
                lease.release();
            });
            while (secondThreadId.get() < 0L) {
                Thread.sleep(10L);
            }
            long beforeCpuNanos = threadMxBean.getThreadCpuTime(secondThreadId.get());
            Thread.sleep(100L);
            long afterCpuNanos = threadMxBean.getThreadCpuTime(secondThreadId.get());
            assertThat(second.isDone()).isFalse();
            assertThat(afterCpuNanos - beforeCpuNanos).isLessThan(TimeUnit.MILLISECONDS.toNanos(25L));

            delegate.finishRelease();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);

            assertThat(delegate.releaseCount()).isEqualTo(1);
            assertThat(unregisterCount.get()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class BlockingLease implements BackendLockLease {
        private final CountDownLatch releaseStarted = new CountDownLatch(1);
        private final CountDownLatch releaseMayFinish = new CountDownLatch(1);
        private final AtomicInteger releaseCount = new AtomicInteger();
        private volatile LeaseState state = LeaseState.ACTIVE;

        @Override
        public LockKey key() {
            return new LockKey("core:release");
        }

        @Override
        public LockMode mode() {
            return LockMode.MUTEX;
        }

        @Override
        public FencingToken fencingToken() {
            return new FencingToken(1L);
        }

        @Override
        public LeaseState state() {
            return state;
        }

        @Override
        public boolean isValid() {
            return state == LeaseState.ACTIVE;
        }

        @Override
        public void release() {
            releaseCount.incrementAndGet();
            releaseStarted.countDown();
            try {
                if (!releaseMayFinish.await(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("release was not allowed to finish");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            state = LeaseState.RELEASED;
        }

        boolean awaitReleaseStarted() throws InterruptedException {
            return releaseStarted.await(1, TimeUnit.SECONDS);
        }

        void finishRelease() {
            releaseMayFinish.countDown();
        }

        int releaseCount() {
            return releaseCount.get();
        }
    }
}
```

- [ ] **Step 2: Run the focused test to verify current behavior**

Run:

```bash
mvn -q -pl distributed-lock-core -Dtest=SessionBoundLockLeaseConcurrencyTest test
```

Expected: FAIL because the second release thread consumes CPU while the first release is blocked.

- [ ] **Step 3: Replace spin coordination with synchronized wait/notify**

In `SessionBoundLockLease.java`, keep the existing public methods and replace the `AtomicReference<LifecycleState>` field plus `beginRelease()` and `finishRelease(...)` with this synchronized state:

```java
private LifecycleState lifecycle = LifecycleState.ACTIVE;

private boolean beginRelease() {
    synchronized (this) {
        while (lifecycle == LifecycleState.RELEASING) {
            try {
                wait();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new LockBackendException("Interrupted while waiting for lock release", exception);
            }
        }
        if (lifecycle == LifecycleState.TERMINAL) {
            return false;
        }
        lifecycle = LifecycleState.RELEASING;
        return true;
    }
}

private void finishRelease(boolean terminal) {
    synchronized (this) {
        if (terminal) {
            lifecycle = LifecycleState.TERMINAL;
        } else {
            lifecycle = LifecycleState.ACTIVE;
        }
        notifyAll();
    }
    if (terminal) {
        unregister.accept(this);
    }
}
```

Add this import:

```java
import com.mycorp.distributedlock.api.exception.LockBackendException;
```

Remove the unused `AtomicReference` import.

- [ ] **Step 4: Run core tests**

Run:

```bash
mvn -q -pl distributed-lock-core -am test -Dtest=SessionBoundLockLeaseConcurrencyTest,DefaultLockClientTest,DefaultLockExecutorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 5: Commit core release coordination**

```bash
git add distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/SessionBoundLockLease.java distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/SessionBoundLockLeaseConcurrencyTest.java
git commit -m "fix: block concurrent lease release without spinning"
```

## Task 2: Make Testkit Ownership Lease-Based

**Files:**
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryLockBackend.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/FencedResource.java`
- Create: `distributed-lock-testkit/src/test/java/com/mycorp/distributedlock/testkit/InMemoryLockBackendThreadOwnershipTest.java`
- Create: `distributed-lock-testkit/src/test/java/com/mycorp/distributedlock/testkit/FencedResourceConcurrencyTest.java`

- [ ] **Step 1: Add cross-thread ownership regression tests**

Create `distributed-lock-testkit/src/test/java/com/mycorp/distributedlock/testkit/InMemoryLockBackendThreadOwnershipTest.java` with:

```java
package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.testkit.support.InMemoryBackendModule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryLockBackendThreadOwnershipTest extends LockClientContract {

    @Override
    protected LockRuntime createRuntime() {
        return LockRuntimeBuilder.create()
            .backend("in-memory")
            .backendModules(java.util.List.of(new InMemoryBackendModule("in-memory")))
            .build();
    }

    @Test
    void leaseCanBeReleasedFromADifferentThread() throws Exception {
        runtime = createRuntime();
        ExecutorService releaseExecutor = Executors.newSingleThreadExecutor();
        try (LockSession session = runtime.lockClient().openSession()) {
            LockLease lease = session.acquire(request("in-memory:cross-thread-release", LockMode.MUTEX, Duration.ofSeconds(1)));
            Future<?> release = releaseExecutor.submit(lease::release);
            release.get(1, TimeUnit.SECONDS);

            assertThat(executor.submit(() -> tryAcquire("in-memory:cross-thread-release", LockMode.MUTEX, Duration.ofMillis(200))).get())
                .isTrue();
        } finally {
            releaseExecutor.shutdownNow();
        }
    }

    @Test
    void sessionCanBeClosedFromADifferentThread() throws Exception {
        runtime = createRuntime();
        ExecutorService closeExecutor = Executors.newSingleThreadExecutor();
        LockSession session = runtime.lockClient().openSession();
        session.acquire(request("in-memory:cross-thread-close", LockMode.MUTEX, Duration.ofSeconds(1)));
        try {
            Future<?> close = closeExecutor.submit(session::close);
            close.get(1, TimeUnit.SECONDS);

            assertThat(executor.submit(() -> tryAcquire("in-memory:cross-thread-close", LockMode.MUTEX, Duration.ofMillis(200))).get())
                .isTrue();
        } finally {
            closeExecutor.shutdownNow();
        }
    }
}
```

- [ ] **Step 2: Add concurrent fencing-resource test**

Create `distributed-lock-testkit/src/test/java/com/mycorp/distributedlock/testkit/FencedResourceConcurrencyTest.java` with:

```java
package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.testkit.support.FencedResource;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class FencedResourceConcurrencyTest {

    @Test
    void concurrentWritesShouldAcceptOnlyMonotonicallyNewerToken() throws Exception {
        FencedResource resource = new FencedResource();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> stale = executor.submit(() -> writeAfterStart(resource, new FencingToken(1L), start));
            Future<Boolean> fresh = executor.submit(() -> writeAfterStart(resource, new FencingToken(2L), start));
            start.countDown();

            int accepted = 0;
            accepted += stale.get() ? 1 : 0;
            accepted += fresh.get() ? 1 : 0;

            assertThat(accepted).isBetween(1, 2);
            resource.write(new FencingToken(3L), "newest");
            assertThat(write(resource, new FencingToken(1L))).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean writeAfterStart(FencedResource resource, FencingToken token, CountDownLatch start) throws Exception {
        start.await();
        return write(resource, token);
    }

    private static boolean write(FencedResource resource, FencingToken token) {
        try {
            resource.write(token, "value-" + token.value());
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }
}
```

- [ ] **Step 3: Run testkit tests to verify failures**

Run:

```bash
mvn -q -pl distributed-lock-testkit -am test -Dtest=InMemoryLockBackendThreadOwnershipTest,FencedResourceConcurrencyTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL. Cross-thread release or close should fail with the current thread-owned in-memory backend.

- [ ] **Step 4: Replace in-memory state with owner ids**

In `InMemoryLockBackend.java`, remove `ReentrantReadWriteLock` usage and implement explicit owner state. Use this structure:

```java
static BackendLockLease acquireLease(Map<String, InMemoryLockState> lockStates, LockRequest request)
    throws InterruptedException {
    String keyValue = request.key().value();
    InMemoryLockState state = lockStates.computeIfAbsent(keyValue, ignored -> new InMemoryLockState());
    String ownerId = java.util.UUID.randomUUID().toString();
    boolean acquired = state.acquire(ownerId, request.mode(), request.waitPolicy());
    if (!acquired) {
        throw new LockAcquisitionTimeoutException("Timed out acquiring lock for " + keyValue);
    }
    try {
        return new InMemoryLease(
            request.key(),
            request.mode(),
            new FencingToken(state.nextFence()),
            keyValue,
            ownerId,
            state,
            lockStates,
            new AtomicBoolean(false)
        );
    } catch (RuntimeException exception) {
        state.release(ownerId, request.mode());
        removeIfIdle(lockStates, keyValue, state);
        throw exception;
    }
}
```

Add these helpers and state methods in the same file:

```java
private static void removeIfIdle(Map<String, InMemoryLockState> lockStates, String key, InMemoryLockState state) {
    if (state.isIdle()) {
        lockStates.remove(key, state);
    }
}

static final class InMemoryLockState {
    private final java.util.Map<String, LockMode> owners = new java.util.HashMap<>();
    private final AtomicLong fencingCounter = new AtomicLong();
    private String exclusiveOwner;

    synchronized boolean acquire(String ownerId, LockMode mode, WaitPolicy waitPolicy) throws InterruptedException {
        if (waitPolicy.unbounded()) {
            while (!canAcquire(mode)) {
                wait();
            }
            addOwner(ownerId, mode);
            return true;
        }
        long remainingNanos = waitPolicy.waitTime().toNanos();
        long deadlineNanos = System.nanoTime() + remainingNanos;
        while (!canAcquire(mode)) {
            if (remainingNanos <= 0L) {
                return false;
            }
            TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
            remainingNanos = deadlineNanos - System.nanoTime();
        }
        addOwner(ownerId, mode);
        return true;
    }

    synchronized long nextFence() {
        return fencingCounter.incrementAndGet();
    }

    synchronized void release(String ownerId, LockMode mode) {
        LockMode removed = owners.remove(ownerId);
        if (removed == null) {
            return;
        }
        if (removed != mode) {
            throw new IllegalStateException("lock mode mismatch for in-memory owner");
        }
        if (ownerId.equals(exclusiveOwner)) {
            exclusiveOwner = null;
        }
        notifyAll();
    }

    synchronized boolean isIdle() {
        return owners.isEmpty();
    }

    private boolean canAcquire(LockMode mode) {
        return switch (mode) {
            case MUTEX, WRITE -> owners.isEmpty();
            case READ -> exclusiveOwner == null;
        };
    }

    private void addOwner(String ownerId, LockMode mode) {
        owners.put(ownerId, mode);
        if (mode == LockMode.MUTEX || mode == LockMode.WRITE) {
            exclusiveOwner = ownerId;
        }
    }
}
```

Update `InMemoryLease` to store `keyValue`, `ownerId`, and `lockStates`, then release by owner id:

```java
private record InMemoryLease(
    LockKey key,
    LockMode mode,
    FencingToken fencingToken,
    String keyValue,
    String ownerId,
    InMemoryLockState lockState,
    Map<String, InMemoryLockState> lockStates,
    AtomicBoolean released
) implements BackendLockLease {

    @Override
    public LeaseState state() {
        return released.get() ? LeaseState.RELEASED : LeaseState.ACTIVE;
    }

    @Override
    public boolean isValid() {
        return !released.get();
    }

    @Override
    public void release() {
        if (released.compareAndSet(false, true)) {
            lockState.release(ownerId, mode);
            removeIfIdle(lockStates, keyValue, lockState);
        }
    }
}
```

- [ ] **Step 5: Make `FencedResource` CAS-based**

Replace `write(...)` in `FencedResource.java` with:

```java
public void write(FencingToken token, String value) {
    while (true) {
        long previous = latestToken.get();
        if (token.value() <= previous) {
            throw new IllegalStateException("stale fencing token");
        }
        if (latestToken.compareAndSet(previous, token.value())) {
            return;
        }
    }
}
```

- [ ] **Step 6: Run testkit contract tests**

Run:

```bash
mvn -q -pl distributed-lock-testkit -am test -Dtest=InMemoryLockClientContractTest,InMemoryLockBackendThreadOwnershipTest,FencedResourceConcurrencyTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 7: Commit testkit ownership hardening**

```bash
git add distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryLockBackend.java distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/FencedResource.java distributed-lock-testkit/src/test/java/com/mycorp/distributedlock/testkit/InMemoryLockBackendThreadOwnershipTest.java distributed-lock-testkit/src/test/java/com/mycorp/distributedlock/testkit/FencedResourceConcurrencyTest.java
git commit -m "fix: make in-memory locks owner based"
```

## Task 3: Extend Redis Exclusive Intent And Key Strategy

**Files:**
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendConfiguration.java`
- Create: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisKeyStrategy.java`
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisReadWriteWriterPreferenceTest.java`
- Create: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisKeyStrategyTest.java`

- [ ] **Step 1: Add Redis mutex pending-intent tests**

Append these tests and helper to `RedisReadWriteWriterPreferenceTest.java`:

```java
@Test
void pendingMutexShouldBlockLaterReadersAndAcquireAfterReadersDrain() throws Exception {
    String key = "redis:rw:mutex-progress";
    try (RedisLockBackend backend = redis.newBackend(30L)) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        BackendSession readerSession = backend.openSession();
        BackendLockLease readerLease = readerSession.acquire(readRequest(key, Duration.ofSeconds(1)));
        BackendSession mutexSession = backend.openSession();
        Future<BackendLockLease> mutexAttempt = executor.submit(() ->
            mutexSession.acquire(mutexRequest(key, Duration.ofSeconds(5)))
        );
        BackendLockLease mutexLease = null;
        try {
            assertThat(awaitPendingWriterIntent(key)).isTrue();
            assertThat(tryAcquireRead(backend, key, Duration.ofMillis(150))).isFalse();
            readerLease.release();
            mutexLease = mutexAttempt.get(2, TimeUnit.SECONDS);
            assertThat(mutexLease.isValid()).isTrue();
            assertThat(pendingWriterCount(key)).isZero();
        } finally {
            closeQuietly(mutexLease);
            mutexAttempt.cancel(true);
            closeQuietly(mutexSession);
            closeQuietly(readerLease);
            closeQuietly(readerSession);
            executor.shutdownNow();
        }
    }
}

@Test
void mutexTimeoutShouldRemovePendingIntent() throws Exception {
    String key = "redis:rw:mutex-timeout";
    try (RedisLockBackend backend = redis.newBackend(30L);
         BackendSession readerSession = backend.openSession();
         BackendLockLease ignored = readerSession.acquire(readRequest(key, Duration.ofSeconds(1)))) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Boolean> mutexAttempt = executor.submit(() -> tryAcquireMutex(backend, key, Duration.ofMillis(300)));
        try {
            assertThat(awaitPendingWriterIntent(key)).isTrue();
            assertThat(mutexAttempt.get(2, TimeUnit.SECONDS)).isFalse();
            assertThat(pendingWriterCount(key)).isZero();
        } finally {
            mutexAttempt.cancel(true);
            executor.shutdownNow();
        }
    }
}

private static boolean tryAcquireMutex(RedisLockBackend backend, String key, Duration waitTime) throws Exception {
    try (BackendSession session = backend.openSession();
         BackendLockLease lease = session.acquire(mutexRequest(key, waitTime))) {
        return lease.isValid();
    } catch (LockAcquisitionTimeoutException exception) {
        return false;
    }
}

private static LockRequest mutexRequest(String key, Duration waitTime) {
    return new LockRequest(new LockKey(key), LockMode.MUTEX, WaitPolicy.timed(waitTime));
}
```

- [ ] **Step 2: Add Redis key strategy unit test**

Create `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisKeyStrategyTest.java` with:

```java
package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.LockMode;
import io.lettuce.core.cluster.SlotHash;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisKeyStrategyTest {

    @Test
    void legacyStaticOwnerKeyShouldRemainUnchanged() {
        assertThat(RedisLockBackend.ownerKey("orders:42", LockMode.MUTEX))
            .isEqualTo("lock:orders:42:mutex:owner");
    }

    @Test
    void hashTaggedKeysForSameResourceShouldUseOneRedisClusterSlot() {
        RedisLockBackend.RedisKeys keys = RedisLockBackend.RedisKeys.forKey("orders:42", RedisKeyStrategy.HASH_TAGGED);

        int ownerSlot = SlotHash.getSlot(keys.ownerKey(LockMode.MUTEX));
        assertThat(SlotHash.getSlot(keys.ownerKey(LockMode.WRITE))).isEqualTo(ownerSlot);
        assertThat(SlotHash.getSlot(keys.readersKey())).isEqualTo(ownerSlot);
        assertThat(SlotHash.getSlot(keys.fenceKey())).isEqualTo(ownerSlot);
        assertThat(SlotHash.getSlot(keys.pendingWritersKey())).isEqualTo(ownerSlot);
    }
}
```

- [ ] **Step 3: Run Redis tests to verify failures**

Run:

```bash
mvn -q -pl distributed-lock-redis -am test -Dtest=RedisReadWriteWriterPreferenceTest,RedisKeyStrategyTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because mutex pending intent and `RedisKeyStrategy` do not exist yet.

- [ ] **Step 4: Add Redis key strategy configuration**

Create `RedisKeyStrategy.java`:

```java
package com.mycorp.distributedlock.redis;

public enum RedisKeyStrategy {
    LEGACY,
    HASH_TAGGED
}
```

Change `RedisBackendConfiguration` to:

```java
package com.mycorp.distributedlock.redis;

import java.util.Objects;

public record RedisBackendConfiguration(String redisUri, long leaseSeconds, RedisKeyStrategy keyStrategy) {

    public RedisBackendConfiguration(String redisUri, long leaseSeconds) {
        this(redisUri, leaseSeconds, RedisKeyStrategy.LEGACY);
    }

    public RedisBackendConfiguration {
        Objects.requireNonNull(redisUri, "redisUri");
        Objects.requireNonNull(keyStrategy, "keyStrategy");
        if (redisUri.isBlank()) {
            throw new IllegalArgumentException("redisUri cannot be blank");
        }
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("leaseSeconds must be positive");
        }
    }
}
```

- [ ] **Step 5: Add Redis key helper and use it from scripts**

In `RedisLockBackend`, add package-visible `RedisKeys` and keep legacy static helpers:

```java
static String ownerKey(String key, LockMode mode) {
    return RedisKeys.forKey(key, RedisKeyStrategy.LEGACY).ownerKey(mode);
}

static final class RedisKeys {
    private final String prefix;

    private RedisKeys(String prefix) {
        this.prefix = prefix;
    }

    static RedisKeys forKey(String key, RedisKeyStrategy strategy) {
        return switch (strategy) {
            case LEGACY -> new RedisKeys("lock:" + key);
            case HASH_TAGGED -> new RedisKeys("lock:{" + encodeKey(key) + "}");
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

    private static String encodeKey(String key) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}

private RedisKeys keys(String key) {
    return RedisKeys.forKey(key, configuration.keyStrategy());
}
```

Replace script key arrays to use `RedisKeys keys = keys(key);` and then `keys.ownerKey(...)`, `keys.readersKey()`, `keys.fenceKey()`, and `keys.pendingWritersKey()`.

- [ ] **Step 6: Register pending exclusive intent for mutex and write**

In `RedisBackendSession.acquire(...)`, compute pending intent for both exclusive modes:

```java
String exclusiveIntent = request.mode() == LockMode.WRITE || request.mode() == LockMode.MUTEX
    ? writerIntentValue(sessionId, nextSessionId())
    : null;
```

Pass `exclusiveIntent` into `tryAcquire(...)`, `tryAcquireMutex(...)`, and `tryAcquireWrite(...)`. Update `MUTEX_ACQUIRE_SCRIPT` so it accepts `KEYS[5]` and `ARGV[3]`, cleans stale pending intent, adds the intent before checking readers, removes the intent after success, and returns the fence. The mutex script must mirror the writer script's pending-intent cleanup and removal behavior.

When acquisition exits without success, call:

```java
if (!acquired && exclusiveIntent != null) {
    clearPendingWriterIntent(request.key().value(), exclusiveIntent);
}
```

- [ ] **Step 7: Run Redis focused tests**

Run:

```bash
mvn -q -pl distributed-lock-redis -am test -Dtest=RedisReadWriteWriterPreferenceTest,RedisKeyStrategyTest,RedisLockBackendContractTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 8: Commit Redis exclusive intent hardening**

```bash
git add distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendConfiguration.java distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisKeyStrategy.java distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisReadWriteWriterPreferenceTest.java distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisKeyStrategyTest.java
git commit -m "fix: make redis mutex acquisitions block later readers"
```

## Task 4: Make ZooKeeper Acquisition Session-Aware

**Files:**
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`
- Create: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperAcquireWaitLifecycleTest.java`

- [ ] **Step 1: Add session-loss and close wakeup tests**

Create `ZooKeeperAcquireWaitLifecycleTest.java` with:

```java
package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockSessionLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.test.KillSession;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ZooKeeperAcquireWaitLifecycleTest {

    @Test
    void indefiniteAcquireShouldWakeWhenWaitingSessionIsLost() throws Exception {
        try (TestingServer server = new TestingServer();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks"));
             BackendSession holder = backend.openSession();
             BackendLockLease ignored = holder.acquire(request("zk:wait:lost"));
             BackendSession waiter = backend.openSession()) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Throwable> result = executor.submit(() -> acquireAndReturnFailure(waiter, "zk:wait:lost"));
                Thread.sleep(250L);
                CuratorFramework curator = ((CuratorBackedSession) waiter).curatorFramework();
                KillSession.kill(curator.getZookeeperClient().getZooKeeper(), server.getConnectString());

                Throwable failure = result.get(3, TimeUnit.SECONDS);
                assertThat(failure).isInstanceOf(LockSessionLostException.class);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void indefiniteAcquireShouldWakeWhenWaitingSessionIsClosed() throws Exception {
        try (TestingServer server = new TestingServer();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks"));
             BackendSession holder = backend.openSession();
             BackendLockLease ignored = holder.acquire(request("zk:wait:closed"));
             BackendSession waiter = backend.openSession()) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Throwable> result = executor.submit(() -> acquireAndReturnFailure(waiter, "zk:wait:closed"));
                Thread.sleep(250L);
                waiter.close();

                Throwable failure = result.get(3, TimeUnit.SECONDS);
                assertThat(failure).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already closed");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static Throwable acquireAndReturnFailure(BackendSession session, String key) {
        try {
            session.acquire(request(key));
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static LockRequest request(String key) {
        return new LockRequest(new LockKey(key), LockMode.MUTEX, WaitPolicy.indefinite());
    }
}
```

- [ ] **Step 2: Run ZooKeeper wait lifecycle test to verify failure**

Run:

```bash
mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperAcquireWaitLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL or timeout because indefinite waits are not woken by terminal session state.

- [ ] **Step 3: Add terminal wait signal to `ZooKeeperBackendSession`**

In `ZooKeeperBackendSession`, add:

```java
private final Object terminalMonitor = new Object();

private void signalTerminalWaiters() {
    synchronized (terminalMonitor) {
        terminalMonitor.notifyAll();
    }
}
```

Call `signalTerminalWaiters()` after state changes to `LOST` in `markSessionLost(...)` and after state changes to `CLOSED` in `close()`.

- [ ] **Step 4: Replace unbounded latch await with bounded session-aware wait**

Replace `awaitNodeDeletion(...)` with:

```java
private boolean awaitNodeDeletion(String path, long remainingNanos) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    CuratorWatcher watcher = event -> latch.countDown();
    Stat stat = curatorFramework.checkExists().usingWatcher(watcher).forPath(path);
    if (stat == null) {
        return true;
    }
    long waitNanos = remainingNanos == Long.MAX_VALUE
        ? TimeUnit.MILLISECONDS.toNanos(250L)
        : Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(250L));
    synchronized (terminalMonitor) {
        if (state.get() != SessionState.ACTIVE) {
            ensureActive();
        }
        terminalMonitor.wait(TimeUnit.NANOSECONDS.toMillis(waitNanos), (int) (waitNanos % 1_000_000L));
    }
    ensureActive();
    return latch.getCount() == 0L || curatorFramework.checkExists().forPath(path) == null;
}
```

Keep the existing acquire loop so it recomputes the queue after `awaitNodeDeletion(...)` returns.

- [ ] **Step 5: Run ZooKeeper wait tests**

Run:

```bash
mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperAcquireWaitLifecycleTest,ZooKeeperSessionLossTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 6: Commit ZooKeeper wait lifecycle hardening**

```bash
git add distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperAcquireWaitLifecycleTest.java
git commit -m "fix: wake zookeeper acquires on session termination"
```

## Task 5: Harden ZooKeeper Paths, Queue Parsing, And Fencing Rechecks

**Files:**
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendConfiguration.java`
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`
- Create: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperPathAndQueueValidationTest.java`
- Create: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperFencingOwnershipRecheckTest.java`

- [ ] **Step 1: Add path and queue validation tests**

Create `ZooKeeperPathAndQueueValidationTest.java` with:

```java
package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.core.backend.BackendSession;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZooKeeperPathAndQueueValidationTest {

    @Test
    void basePathShouldRejectInvalidZookeeperPaths() {
        assertThatThrownBy(() -> new ZooKeeperBackendConfiguration("127.0.0.1:2181", "locks"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ZooKeeperBackendConfiguration("127.0.0.1:2181", "/locks//bad"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trailingSlashShouldBeNormalized() {
        ZooKeeperBackendConfiguration configuration = new ZooKeeperBackendConfiguration("127.0.0.1:2181", "/locks/");
        assertThat(configuration.basePath()).isEqualTo("/locks");
    }

    @Test
    void malformedQueueNodeShouldFailWithBackendException() throws Exception {
        try (TestingServer server = new TestingServer();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks"));
             CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), new ExponentialBackoffRetry(1_000, 3))) {
            client.start();
            assertThat(client.blockUntilConnected(10, TimeUnit.SECONDS)).isTrue();
            String queueRoot = "/distributed-locks/rw/" + encode("zk:bad-node") + "/locks";
            client.create().creatingParentsIfNeeded().forPath(queueRoot + "/read-bad");

            try (BackendSession session = backend.openSession()) {
                assertThatThrownBy(() -> session.acquire(new LockRequest(
                    new LockKey("zk:bad-node"),
                    LockMode.READ,
                    WaitPolicy.tryOnce()
                )))
                    .isInstanceOf(LockBackendException.class)
                    .hasMessageContaining("read-bad");
            }
        }
    }

    private static String encode(String key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 2: Add fencing ownership recheck test seam**

Make `ZooKeeperLockBackend` non-final so the test can override the hook:

```java
public class ZooKeeperLockBackend implements LockBackend {
```

Add this package-private hook to `ZooKeeperLockBackend`:

```java
void beforeFenceIssued(String contenderPath) {
}
```

Create `ZooKeeperFencingOwnershipRecheckTest.java` with:

```java
package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockSessionLostException;
import com.mycorp.distributedlock.core.backend.BackendSession;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZooKeeperFencingOwnershipRecheckTest {

    @Test
    void contenderDeletionBeforeFenceShouldNotReturnLease() throws Exception {
        try (TestingServer server = new TestingServer();
             DeletingBackend backend = new DeletingBackend(new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks"))) {
            try (BackendSession session = backend.openSession()) {
                assertThatThrownBy(() -> session.acquire(new LockRequest(
                    new LockKey("zk:fence:deleted"),
                    LockMode.MUTEX,
                    WaitPolicy.tryOnce()
                )))
                    .isInstanceOf(LockSessionLostException.class);
            }
        }
    }

    private static final class DeletingBackend extends ZooKeeperLockBackend {
        private DeletingBackend(ZooKeeperBackendConfiguration configuration) {
            super(configuration);
        }

        @Override
        void beforeFenceIssued(String contenderPath) {
            try (BackendSession session = openSession()) {
                ((CuratorBackedSession) session).curatorFramework().delete().forPath(contenderPath);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }
    }
}
```

- [ ] **Step 3: Run ZooKeeper validation tests to verify failures**

Run:

```bash
mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperPathAndQueueValidationTest,ZooKeeperFencingOwnershipRecheckTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because path normalization, queue validation, and ownership recheck hook are not implemented.

- [ ] **Step 4: Normalize and validate base paths**

Update `ZooKeeperBackendConfiguration` compact constructor:

```java
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
```

Add a private `joinPath(String first, String second)` helper in `ZooKeeperLockBackend` and use it from `queueRootPath(...)` and `fenceCounterPath(...)`.

- [ ] **Step 5: Validate queue node sequence suffixes**

Change `queueNode(String name)` and `sequence(...)` so malformed names fail with `LockBackendException`:

```java
private QueueNode queueNode(String name) {
    if (name.startsWith("mutex-")) {
        return new QueueNode(name, LockMode.MUTEX, sequence(name));
    }
    if (name.startsWith("read-")) {
        return new QueueNode(name, LockMode.READ, sequence(name));
    }
    if (name.startsWith("write-")) {
        return new QueueNode(name, LockMode.WRITE, sequence(name));
    }
    return null;
}

private long sequence(String nodeName) {
    String suffix = nodeName.substring(nodeName.lastIndexOf('-') + 1);
    if (!suffix.matches("\\d+")) {
        throw new LockBackendException("Malformed ZooKeeper lock queue node: " + nodeName);
    }
    return Long.parseLong(suffix);
}
```

- [ ] **Step 6: Recheck ownership around fencing issuance**

In `acquireQueued(...)`, replace the direct `nextFence(...)` block with:

```java
if (canAcquire(nodes, current)) {
    verifyContenderStillOwned(contenderPath, nodeOwnerData, request.key().value());
    beforeFenceIssued(contenderPath);
    long fence = nextFence(request.key().value());
    verifyContenderStillOwned(contenderPath, nodeOwnerData, request.key().value());
    List<QueueNode> afterFenceNodes = queueNodes(request.key().value());
    QueueNode afterFenceCurrent = currentNode(afterFenceNodes, nodeName);
    if (afterFenceCurrent == null || !canAcquire(afterFenceNodes, afterFenceCurrent)) {
        throw new LockSessionLostException("ZooKeeper contender node no longer owns lock for key " + request.key().value());
    }
    ZooKeeperLease lease = new ZooKeeperLease(
        request.key(),
        request.mode(),
        new FencingToken(fence),
        contenderPath,
        nodeOwnerData,
        this
    );
    activeLeases.put(lease.ownerPath(), lease);
    return lease;
}
```

Add:

```java
private void verifyContenderStillOwned(String contenderPath, byte[] ownerData, String key) throws Exception {
    try {
        byte[] currentData = curatorFramework.getData().forPath(contenderPath);
        if (!Arrays.equals(currentData, ownerData)) {
            throw new LockSessionLostException("ZooKeeper contender node ownership changed for key " + key);
        }
    } catch (KeeperException.NoNodeException exception) {
        throw new LockSessionLostException("ZooKeeper contender node disappeared for key " + key, exception);
    }
}
```

- [ ] **Step 7: Add backoff to fencing counter retries**

In `nextFence(...)`, inside the `BadVersionException` catch block, call `ensureActive()` and sleep briefly:

```java
} catch (KeeperException.BadVersionException ignored) {
    ensureActive();
    Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(1L, 6L));
}
```

- [ ] **Step 8: Run ZooKeeper focused tests**

Run:

```bash
mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperAcquireWaitLifecycleTest,ZooKeeperPathAndQueueValidationTest,ZooKeeperFencingOwnershipRecheckTest,ZooKeeperLockBackendContractTest,ZooKeeperSessionLossTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 9: Commit ZooKeeper hardening**

```bash
git add distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendConfiguration.java distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperPathAndQueueValidationTest.java distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperFencingOwnershipRecheckTest.java
git commit -m "fix: harden zookeeper acquisition ownership checks"
```

## Task 6: Update Docs And Run Backend Regression

**Files:**
- Modify: `distributed-lock-spring-boot-starter/README.md`
- Modify: `distributed-lock-test-suite/README.md`

- [ ] **Step 1: Update backend semantics documentation**

In `distributed-lock-spring-boot-starter/README.md`, update the backend progress paragraph to say:

```markdown
Backend progress semantics are backend-specific. Redis read/write locks are exclusive-preferred once a `WRITE` or `MUTEX` acquisition has registered pending intent, but they are not FIFO fair: later readers wait while existing readers drain, and multiple waiting exclusive acquisitions are resolved by Redis polling and script execution order rather than a strict queue. ZooKeeper uses fail-safe session semantics: `SUSPENDED` and `LOST` Curator states both make the lock session terminally lost because ownership cannot be proven while disconnected.
```

- [ ] **Step 2: Update test-suite commands**

Add the new test class names to `distributed-lock-test-suite/README.md` maintained tests and commands:

```markdown
- `SessionBoundLockLeaseConcurrencyTest`
- `InMemoryLockBackendThreadOwnershipTest`
- `FencedResourceConcurrencyTest`
- `RedisKeyStrategyTest`
- `ZooKeeperAcquireWaitLifecycleTest`
- `ZooKeeperPathAndQueueValidationTest`
- `ZooKeeperFencingOwnershipRecheckTest`
```

- [ ] **Step 3: Run backend-focused regression**

Run:

```bash
mvn -pl distributed-lock-core,distributed-lock-testkit,distributed-lock-redis,distributed-lock-zookeeper -am test -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 4: Run default reactor tests**

Run:

```bash
mvn test
```

Expected: PASS.

- [ ] **Step 5: Commit docs and regression list**

```bash
git add distributed-lock-spring-boot-starter/README.md distributed-lock-test-suite/README.md
git commit -m "docs: document backend correctness semantics"
```
