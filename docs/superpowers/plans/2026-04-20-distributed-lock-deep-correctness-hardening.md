# Distributed Lock Deep Correctness Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate async lock-scope escape, surface Redis/ZooKeeper ownership loss, make ZooKeeper sessions truly backend-local, and replace custom lock-key parsing with Spring template semantics.

**Architecture:** Tighten synchronous execution semantics in `distributed-lock-core`, propagate terminal loss through Redis close/release paths, refactor ZooKeeper so every `LockSession` owns its own Curator client and terminal lifecycle, and swap Spring starter key resolution to `ParserContext.TEMPLATE_EXPRESSION`. Drive each behavior change with focused red-green-refactor tests before full reactor verification.

**Tech Stack:** Java 17, JUnit 5, AssertJ, Mockito, Spring Expression, Lettuce Redis, Apache Curator/ZooKeeper, Maven

---

## File Map

- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockExecutor.java`
  Tighten `LockExecutor` to reject async result objects before the scope exits.
- Modify: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockExecutorTest.java`
  Add failing tests for `CompletionStage`, `Future`, and reactive `Publisher` rejection.
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`
  Record terminal loss causes, make lost leases/session close fail loudly, and keep teardown best-effort.
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisSessionLossTest.java`
  Assert lost leases and lost sessions surface the right exceptions during teardown.
- Create: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisExecutorOwnershipLossTest.java`
  Verify `LockExecutor.withLock(...)` fails if Redis ownership is lost while user code is still running.
- Create: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/CuratorBackedSession.java`
  Package-private internal seam exposing a session-owned Curator client to adapter tests.
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`
  Move Curator ownership into `ZooKeeperBackendSession`, add terminal session/lease states, and wire connection-state loss handling.
- Create: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperSessionIsolationTest.java`
  Verify independent Curator clients per session and that killing one session does not poison a sibling session.
- Modify: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperSessionLossTest.java`
  Verify session loss is terminal and lease state transitions to `LOST`.
- Modify: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackendBehaviorTest.java`
  Keep constructor/connectivity checks aligned with the new per-session client structure.
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/key/SpelLockKeyResolver.java`
  Replace manual `#{...}` scanning with Spring template parsing.
- Create: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/key/SpelLockKeyResolverTest.java`
  Cover literal keys, simple templates, and structured nested templates.
- Modify: `distributed-lock-spring-boot-starter/README.md`
  Document strict synchronous semantics and Spring-template key parsing.

### Task 1: Enforce Strictly Synchronous `LockExecutor` Results

**Files:**
- Modify: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockExecutorTest.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockExecutor.java`

- [x] **Step 1: Write the failing executor tests**

Add these imports and tests to `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockExecutorTest.java`:

```java
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import org.junit.jupiter.api.Assumptions;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
```

```java
@Test
void withLockShouldRejectCompletionStageResults() {
    TrackingBackend backend = new TrackingBackend();
    LockExecutor executor = new DefaultLockExecutor(new DefaultLockClient(
        backend,
        new SupportedLockModes(true, true)
    ));

    assertThatThrownBy(() -> executor.withLock(sampleRequest(), () -> CompletableFuture.completedFuture("ok")))
        .isInstanceOf(LockConfigurationException.class)
        .hasMessageContaining("CompletionStage");

    assertThat(backend.releaseCount()).hasValue(1);
    assertThat(backend.sessionCloseCount()).hasValue(1);
}

@Test
void withLockShouldRejectFutureResults() {
    TrackingBackend backend = new TrackingBackend();
    LockExecutor executor = new DefaultLockExecutor(new DefaultLockClient(
        backend,
        new SupportedLockModes(true, true)
    ));
    FutureTask<String> futureTask = new FutureTask<>(() -> "ok");
    futureTask.run();

    assertThatThrownBy(() -> executor.withLock(sampleRequest(), () -> futureTask))
        .isInstanceOf(LockConfigurationException.class)
        .hasMessageContaining("Future");

    assertThat(backend.releaseCount()).hasValue(1);
    assertThat(backend.sessionCloseCount()).hasValue(1);
}

@Test
void withLockShouldRejectReactivePublisherResultsWhenReactiveStreamsIsPresent() throws Exception {
    Assumptions.assumeTrue(isReactiveStreamsPresent());
    TrackingBackend backend = new TrackingBackend();
    LockExecutor executor = new DefaultLockExecutor(new DefaultLockClient(
        backend,
        new SupportedLockModes(true, true)
    ));

    ClassLoader classLoader = DefaultLockExecutorTest.class.getClassLoader();
    Class<?> publisherType = Class.forName("org.reactivestreams.Publisher", false, classLoader);
    Object publisher = Proxy.newProxyInstance(
        classLoader,
        new Class<?>[]{publisherType},
        (proxy, method, args) -> null
    );

    assertThatThrownBy(() -> executor.withLock(sampleRequest(), () -> publisher))
        .isInstanceOf(LockConfigurationException.class)
        .hasMessageContaining("Publisher");

    assertThat(backend.releaseCount()).hasValue(1);
    assertThat(backend.sessionCloseCount()).hasValue(1);
}

private static boolean isReactiveStreamsPresent() {
    try {
        Class.forName("org.reactivestreams.Publisher", false, DefaultLockExecutorTest.class.getClassLoader());
        return true;
    } catch (ClassNotFoundException exception) {
        return false;
    }
}
```

- [x] **Step 2: Run the focused core tests to verify they fail**

Run:

```bash
mvn -q -pl distributed-lock-core -am test -Dtest=DefaultLockExecutorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `DefaultLockExecutor` currently returns async result objects instead of rejecting them.

- [x] **Step 3: Implement async-result rejection in `DefaultLockExecutor`**

Replace `withLock(...)` in `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockExecutor.java` with:

```java
@Override
public <T> T withLock(LockRequest request, LockedSupplier<T> action) throws Exception {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(action, "action");
    try (LockSession session = client.openSession();
         LockLease lease = session.acquire(request);
         CurrentLockContext.Binding ignored = CurrentLockContext.bind(lease)) {
        T result = action.get();
        rejectAsyncResult(result);
        return result;
    }
}

private static void rejectAsyncResult(Object result) {
    if (result == null) {
        return;
    }
    if (result instanceof java.util.concurrent.CompletionStage<?>) {
        throw new com.mycorp.distributedlock.api.exception.LockConfigurationException(
            "LockExecutor only supports synchronous actions, but the action returned CompletionStage"
        );
    }
    if (result instanceof java.util.concurrent.Future<?>) {
        throw new com.mycorp.distributedlock.api.exception.LockConfigurationException(
            "LockExecutor only supports synchronous actions, but the action returned Future"
        );
    }
    if (isReactivePublisher(result)) {
        throw new com.mycorp.distributedlock.api.exception.LockConfigurationException(
            "LockExecutor only supports synchronous actions, but the action returned Publisher"
        );
    }
}

private static boolean isReactivePublisher(Object result) {
    try {
        Class<?> publisherType = Class.forName(
            "org.reactivestreams.Publisher",
            false,
            result.getClass().getClassLoader()
        );
        return publisherType.isInstance(result);
    } catch (ClassNotFoundException exception) {
        return false;
    }
}
```

- [x] **Step 4: Run the focused core tests to verify they pass**

Run:

```bash
mvn -q -pl distributed-lock-core -am test -Dtest=DefaultLockExecutorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [x] **Step 5: Commit the core behavior change**

Run:

```bash
git add distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockExecutor.java \
        distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockExecutorTest.java
git commit -m "fix: reject async lock executor results"
```

### Task 2: Surface Redis Ownership Loss at Scope Exit

**Files:**
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisSessionLossTest.java`
- Create: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisExecutorOwnershipLossTest.java`
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`

- [x] **Step 1: Write the failing Redis loss-propagation tests**

Add this test to `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisSessionLossTest.java`:

```java
@Test
void releaseAndCloseShouldReportLossAfterSessionIsMarkedLost() throws Exception {
    try (RedisTestSupport.RunningRedis redis = RedisTestSupport.startRedis();
         RedisLockBackend backend = redis.newBackend(1L)) {
        BackendSession session = backend.openSession();
        BackendLockLease lease = session.acquire(new LockRequest(
            new LockKey("redis:session-loss:close"),
            LockMode.MUTEX,
            WaitPolicy.indefinite()
        ));
        try {
            redis.stopContainer();
            waitUntilLost(session, lease);

            assertThat(lease.state()).isEqualTo(LeaseState.LOST);
            assertThatThrownBy(lease::release)
                .isInstanceOf(LockOwnershipLostException.class)
                .hasMessageContaining("redis:session-loss:close");
            assertThatThrownBy(session::close)
                .isInstanceOf(LockSessionLostException.class)
                .hasMessageContaining("Redis session lost");
        } finally {
            try {
                lease.close();
            } catch (RuntimeException ignored) {
            }
            try {
                session.close();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
```

Create `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisExecutorOwnershipLossTest.java` with:

```java
package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.LockExecutor;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.core.client.DefaultLockClient;
import com.mycorp.distributedlock.core.client.DefaultLockExecutor;
import com.mycorp.distributedlock.core.client.SupportedLockModes;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisExecutorOwnershipLossTest {

    @Test
    void withLockShouldFailIfOwnershipIsLostDuringAction() throws Exception {
        try (RedisTestSupport.RunningRedis redis = RedisTestSupport.startRedis();
             RedisLockBackend backend = redis.newBackend(1L)) {
            LockExecutor executor = new DefaultLockExecutor(new DefaultLockClient(
                backend,
                new SupportedLockModes(true, true)
            ));

            assertThatThrownBy(() -> executor.withLock(request("redis:executor-loss"), () -> {
                redis.stopContainer();
                Thread.sleep(Duration.ofSeconds(2).toMillis());
                return "ok";
            }))
                .isInstanceOf(LockOwnershipLostException.class)
                .hasMessageContaining("redis:executor-loss");
        }
    }

    private static LockRequest request(String key) {
        return new LockRequest(
            new LockKey(key),
            LockMode.MUTEX,
            WaitPolicy.timed(Duration.ofSeconds(3))
        );
    }
}
```

- [x] **Step 2: Run the focused Redis tests to verify they fail**

Run:

```bash
mvn -q -pl distributed-lock-redis -am test -Dtest=RedisSessionLossTest,RedisExecutorOwnershipLossTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because lost leases can still close silently and `withLock(...)` does not currently surface in-flight ownership loss.

- [x] **Step 3: Implement terminal Redis loss reporting**

In `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`, add a terminal loss-cause field to `RedisBackendSession` and tighten `close()`, `markSessionLost(...)`, and `RedisLease.release()`:

```java
private final AtomicReference<RuntimeException> lossCause = new AtomicReference<>();
```

```java
@Override
public void close() {
    if (!closed.compareAndSet(false, true)) {
        return;
    }

    renewalTask.cancel(false);
    RuntimeException failure = null;
    for (RedisLease lease : new ArrayList<>(activeLeases.values())) {
        try {
            lease.release();
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
    }

    try {
        commands.del(sessionKey(sessionId));
    } catch (RuntimeException exception) {
        if (failure == null) {
            failure = new LockBackendException("Failed to close Redis session " + sessionId, exception);
        } else {
            failure.addSuppressed(exception);
        }
    }

    if (failure != null) {
        throw failure;
    }
    if (!valid.get()) {
        throw lossCause();
    }
}

private RuntimeException lossCause() {
    RuntimeException cause = lossCause.get();
    return cause != null ? cause : new LockSessionLostException("Redis session lost: " + sessionId);
}

private void markSessionLost(RuntimeException cause) {
    lossCause.compareAndSet(null, cause);
    if (!valid.compareAndSet(true, false)) {
        return;
    }
    for (RedisLease lease : new ArrayList<>(activeLeases.values())) {
        lease.markLost();
    }
    renewalTask.cancel(false);
}
```

Update `RedisLease.release()` to reject `LOST` explicitly:

```java
@Override
public void release() {
    LeaseState current = state.get();
    if (current == LeaseState.RELEASED) {
        return;
    }
    if (current == LeaseState.LOST) {
        session.forgetLease(this);
        throw new LockOwnershipLostException("Redis lock ownership lost for key " + key.value());
    }

    try {
        Long result = switch (mode) {
            case MUTEX -> commands.eval(
                VALUE_RELEASE_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{ownerKey(key.value(), LockMode.MUTEX)},
                ownerValue
            );
            case READ -> commands.eval(
                HASH_RELEASE_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{readersKey(key.value())},
                ownerValue
            );
            case WRITE -> commands.eval(
                VALUE_RELEASE_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{ownerKey(key.value(), LockMode.WRITE)},
                ownerValue
            );
        };
        if (result == null || result == 0L) {
            markLost();
            throw new LockOwnershipLostException("Redis lock ownership lost for key " + key.value());
        }
        state.set(LeaseState.RELEASED);
        session.forgetLease(this);
    } catch (RuntimeException exception) {
        if (exception instanceof LockOwnershipLostException || exception instanceof LockBackendException) {
            throw exception;
        }
        throw new LockBackendException("Failed to release Redis lock for key " + key.value(), exception);
    }
}
```

- [x] **Step 4: Run the focused Redis tests to verify they pass**

Run:

```bash
mvn -q -pl distributed-lock-redis -am test -Dtest=RedisSessionLossTest,RedisExecutorOwnershipLossTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [x] **Step 5: Commit the Redis loss-propagation change**

Run:

```bash
git add distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java \
        distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisSessionLossTest.java \
        distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisExecutorOwnershipLossTest.java
git commit -m "fix: surface redis ownership loss at scope exit"
```

### Task 3: Give Each ZooKeeper Session Its Own Curator Client

**Files:**
- Create: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/CuratorBackedSession.java`
- Create: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperSessionIsolationTest.java`
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`

- [x] **Step 1: Write the failing ZooKeeper session-isolation tests**

Create `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperSessionIsolationTest.java` with:

```java
package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.test.KillSession;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ZooKeeperSessionIsolationTest {

    @Test
    void sessionsShouldOwnIndependentCuratorClients() throws Exception {
        try (TestingServer server = new TestingServer();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(
                 new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks")
             );
             BackendSession first = backend.openSession();
             BackendSession second = backend.openSession()) {
            CuratorFramework firstClient = ((CuratorBackedSession) first).curatorFramework();
            CuratorFramework secondClient = ((CuratorBackedSession) second).curatorFramework();

            assertThat(firstClient).isNotSameAs(secondClient);
        }
    }

    @Test
    void killingOneSessionShouldNotInvalidateSiblingSession() throws Exception {
        try (TestingServer server = new TestingServer();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(
                 new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks")
             );
             BackendSession first = backend.openSession();
             BackendSession second = backend.openSession();
             BackendLockLease secondLease = second.acquire(new LockRequest(
                 new LockKey("zk:isolation:second"),
                 LockMode.MUTEX,
                 WaitPolicy.timed(Duration.ofSeconds(1))
             ))) {
            CuratorFramework firstClient = ((CuratorBackedSession) first).curatorFramework();
            KillSession.kill(firstClient.getZookeeperClient().getZooKeeper(), server.getConnectString());

            waitUntilState(first, SessionState.LOST);
            assertThat(second.state()).isEqualTo(SessionState.ACTIVE);
            assertThat(secondLease.isValid()).isTrue();
        }
    }

    private static void waitUntilState(BackendSession session, SessionState expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (session.state() == expected) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("session did not reach state " + expected);
    }
}
```

- [x] **Step 2: Run the focused ZooKeeper isolation tests to verify they fail**

Run:

```bash
mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperSessionIsolationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the backend does not yet expose `CuratorBackedSession`, and all logical sessions still share one Curator client.

- [x] **Step 3: Refactor `ZooKeeperLockBackend` so each session owns its client**

First create `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/CuratorBackedSession.java`:

```java
package com.mycorp.distributedlock.zookeeper;

import org.apache.curator.framework.CuratorFramework;

interface CuratorBackedSession {

    CuratorFramework curatorFramework();
}
```

Then, in `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`, remove the backend-level `CuratorFramework` field and build the client inside `openSession()`:

```java
public final class ZooKeeperLockBackend implements LockBackend {

    private final ZooKeeperBackendConfiguration configuration;

    public ZooKeeperLockBackend(ZooKeeperBackendConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    @Override
    public BackendSession openSession() {
        CuratorFramework curatorFramework = CuratorFrameworkFactory.newClient(
            configuration.connectString(),
            new ExponentialBackoffRetry(1_000, 3)
        );
        curatorFramework.start();
        awaitConnected(curatorFramework);
        return new ZooKeeperBackendSession(UUID.randomUUID().toString(), curatorFramework);
    }

    @Override
    public void close() {
        // No shared backend resources remain once each session owns its own client.
    }

    private void awaitConnected(CuratorFramework curatorFramework) {
        try {
            boolean connected = curatorFramework.blockUntilConnected(10, TimeUnit.SECONDS);
            if (!connected) {
                curatorFramework.close();
                throw new LockBackendException(
                    "Failed to connect to ZooKeeper within 10 seconds: " + configuration.connectString()
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            curatorFramework.close();
            throw new LockBackendException("Interrupted while connecting to ZooKeeper", exception);
        }
    }
```

Update the inner session declaration to own the client and implement the new interface:

```java
private final class ZooKeeperBackendSession implements BackendSession, CuratorBackedSession {

    private final String sessionId;
    private final CuratorFramework curatorFramework;
    private final ConcurrentMap<String, ZooKeeperLease> activeLeases = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private ZooKeeperBackendSession(String sessionId, CuratorFramework curatorFramework) {
        this.sessionId = sessionId;
        this.curatorFramework = curatorFramework;
    }

    @Override
    public CuratorFramework curatorFramework() {
        return curatorFramework;
    }
```

Also replace every backend-level `curatorFramework` use in the file with the session-owned field.

- [x] **Step 4: Run the focused ZooKeeper isolation tests to verify they pass**

Run:

```bash
mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperSessionIsolationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [x] **Step 5: Commit the ZooKeeper per-session client refactor**

Run:

```bash
git add distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/CuratorBackedSession.java \
        distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java \
        distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperSessionIsolationTest.java
git commit -m "refactor: give zookeeper sessions independent clients"
```

### Task 4: Make ZooKeeper Loss Terminal and Align Lease State Semantics

**Files:**
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`
- Modify: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperSessionLossTest.java`

- [x] **Step 1: Write the failing ZooKeeper loss-state tests**

Replace the boolean-supplier test in `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperSessionLossTest.java` with:

```java
package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.api.exception.LockSessionLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.test.KillSession;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZooKeeperSessionLossTest {

    @Test
    void lostSessionShouldRemainLostAndRejectNewAcquires() throws Exception {
        try (TestingServer server = new TestingServer();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(
                 new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks")
             );
             BackendSession session = backend.openSession()) {
            CuratorFramework curatorFramework = ((CuratorBackedSession) session).curatorFramework();
            KillSession.kill(curatorFramework.getZookeeperClient().getZooKeeper(), server.getConnectString());

            waitUntilState(session, SessionState.LOST);
            assertThat(session.state()).isEqualTo(SessionState.LOST);
            Thread.sleep(250L);
            assertThat(session.state()).isEqualTo(SessionState.LOST);
            assertThatThrownBy(() -> session.acquire(new LockRequest(
                new LockKey("zk:lost:acquire"),
                LockMode.MUTEX,
                WaitPolicy.indefinite()
            ))).isInstanceOf(LockSessionLostException.class);
        }
    }

    @Test
    void lostLeaseShouldExposeLostStateAndRejectRelease() throws Exception {
        try (TestingServer server = new TestingServer();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(
                 new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks")
             );
             BackendSession session = backend.openSession();
             BackendLockLease lease = session.acquire(new LockRequest(
                 new LockKey("zk:lost:lease"),
                 LockMode.MUTEX,
                 WaitPolicy.indefinite()
             ))) {
            CuratorFramework curatorFramework = ((CuratorBackedSession) session).curatorFramework();
            KillSession.kill(curatorFramework.getZookeeperClient().getZooKeeper(), server.getConnectString());

            waitUntilState(session, SessionState.LOST);
            assertThat(lease.isValid()).isFalse();
            assertThat(lease.state()).isEqualTo(LeaseState.LOST);
            assertThatThrownBy(lease::release)
                .isInstanceOf(LockOwnershipLostException.class)
                .hasMessageContaining("zk:lost:lease");
        }
    }

    private static void waitUntilState(BackendSession session, SessionState expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (session.state() == expected) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("session did not reach state " + expected);
    }
}
```

Keep `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackendBehaviorTest.java` aligned by preserving the constructor-failure test and the distinct-key encoding test unchanged.

- [x] **Step 2: Run the focused ZooKeeper loss tests to verify they fail**

Run:

```bash
mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperSessionLossTest,ZooKeeperLockBackendBehaviorTest,ZooKeeperReadWriteConcurrencyTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because session loss can still recover, lease state is not materialized to `LOST`, and `release()` on lost ownership is not uniformly enforced.

- [x] **Step 3: Implement terminal ZooKeeper session and lease state machines**

In `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`, add explicit session state and loss-cause tracking inside `ZooKeeperBackendSession`:

```java
private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.ACTIVE);
private final AtomicReference<RuntimeException> lossCause = new AtomicReference<>();

private ZooKeeperBackendSession(String sessionId, CuratorFramework curatorFramework) {
    this.sessionId = sessionId;
    this.curatorFramework = curatorFramework;
    this.curatorFramework.getConnectionStateListenable().addListener((client, newState) -> {
        if (newState == org.apache.curator.framework.state.ConnectionState.LOST
            || newState == org.apache.curator.framework.state.ConnectionState.SUSPENDED) {
            markSessionLost(new LockSessionLostException("ZooKeeper session lost: " + sessionId));
        }
    });
}

@Override
public SessionState state() {
    return state.get();
}

@Override
public void close() {
    SessionState current = state.get();
    if (current == SessionState.CLOSED) {
        return;
    }
    if (current == SessionState.ACTIVE) {
        state.compareAndSet(SessionState.ACTIVE, SessionState.CLOSED);
    }

    RuntimeException failure = null;
    for (ZooKeeperLease lease : new ArrayList<>(activeLeases.values())) {
        try {
            lease.release();
        } catch (LockOwnershipLostException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
    }

    curatorFramework.close();

    if (failure != null) {
        throw failure;
    }
    if (current == SessionState.LOST) {
        throw lossCause();
    }
}

private RuntimeException lossCause() {
    RuntimeException cause = lossCause.get();
    return cause != null ? cause : new LockSessionLostException("ZooKeeper session lost: " + sessionId);
}

private void markSessionLost(RuntimeException cause) {
    lossCause.compareAndSet(null, cause);
    if (!state.compareAndSet(SessionState.ACTIVE, SessionState.LOST)) {
        return;
    }
    for (ZooKeeperLease lease : new ArrayList<>(activeLeases.values())) {
        lease.markLost();
    }
}

private void ensureActive() {
    SessionState current = state.get();
    if (current == SessionState.CLOSED) {
        throw new IllegalStateException("ZooKeeper session is already closed");
    }
    if (current == SessionState.LOST) {
        throw new LockSessionLostException("ZooKeeper session lost: " + sessionId);
    }
}
```

Tighten `ZooKeeperLease` so `LOST` is explicit and terminal:

```java
@Override
public boolean isValid() {
    if (state.get() != LeaseState.ACTIVE) {
        return false;
    }
    if (session.state() != SessionState.ACTIVE) {
        markLost();
        return false;
    }
    if (!ownerNodeStillBelongsToSession()) {
        markLost();
        return false;
    }
    return true;
}

@Override
public void release() {
    LeaseState current = state.get();
    if (current == LeaseState.RELEASED) {
        return;
    }
    if (current == LeaseState.LOST) {
        session.forgetLease(this);
        throw new LockOwnershipLostException("ZooKeeper lock ownership lost for key " + key.value());
    }

    try {
        Stat stat = curatorFramework.checkExists().forPath(ownerPath);
        if (stat == null || !ownerNodeStillBelongsToSession()) {
            markLost();
            throw new LockOwnershipLostException("ZooKeeper lock ownership lost for key " + key.value());
        }
        curatorFramework.delete().forPath(ownerPath);
        state.set(LeaseState.RELEASED);
        session.forgetLease(this);
    } catch (LockOwnershipLostException exception) {
        throw exception;
    } catch (KeeperException.NoNodeException exception) {
        markLost();
        throw new LockOwnershipLostException("ZooKeeper lock ownership lost for key " + key.value());
    } catch (Exception exception) {
        throw new LockBackendException("Failed to release ZooKeeper lock for key " + key.value(), exception);
    }
}

private void markLost() {
    state.compareAndSet(LeaseState.ACTIVE, LeaseState.LOST);
    session.forgetLease(this);
}
```

- [x] **Step 4: Run the focused ZooKeeper tests to verify they pass**

Run:

```bash
mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperSessionIsolationTest,ZooKeeperSessionLossTest,ZooKeeperLockBackendBehaviorTest,ZooKeeperReadWriteConcurrencyTest,ZooKeeperLockBackendContractTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [x] **Step 5: Commit the ZooKeeper terminal-loss behavior change**

Run:

```bash
git add distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java \
        distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperSessionIsolationTest.java \
        distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperSessionLossTest.java
git commit -m "fix: make zookeeper session loss terminal"
```

### Task 5: Replace Custom SpEL Parsing with Spring Template Semantics

**Files:**
- Create: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/key/SpelLockKeyResolverTest.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/key/SpelLockKeyResolver.java`
- Modify: `distributed-lock-spring-boot-starter/README.md`

- [x] **Step 1: Write the failing SpEL resolver tests**

Create `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/key/SpelLockKeyResolverTest.java` with:

```java
package com.mycorp.distributedlock.springboot.key;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class SpelLockKeyResolverTest {

    private final SpelLockKeyResolver resolver = new SpelLockKeyResolver();

    @Test
    void shouldReturnLiteralKeyWhenNoTemplateMarkersExist() throws Exception {
        ProceedingJoinPoint joinPoint = joinPoint("42", "cn");

        assertThat(resolver.resolveKey(joinPoint, "order:42")).isEqualTo("order:42");
    }

    @Test
    void shouldResolveSimpleTemplateExpression() throws Exception {
        ProceedingJoinPoint joinPoint = joinPoint("42", "cn");

        assertThat(resolver.resolveKey(joinPoint, "order:#{#p0}")).isEqualTo("order:42");
    }

    @Test
    void shouldResolveStructuredTemplateExpression() throws Exception {
        ProceedingJoinPoint joinPoint = joinPoint("42", "cn");

        assertThat(resolver.resolveKey(
            joinPoint,
            "order:#{ {'id': #p0, 'region': #p1.toUpperCase()}['id'] }-#{#p1.toUpperCase()}"
        )).isEqualTo("order:42-CN");
    }

    private ProceedingJoinPoint joinPoint(Object... args) throws Exception {
        Method method = TestTarget.class.getDeclaredMethod("process", String.class, String.class);
        MethodSignature signature = Mockito.mock(MethodSignature.class);
        Mockito.when(signature.getMethod()).thenReturn(method);

        ProceedingJoinPoint joinPoint = Mockito.mock(ProceedingJoinPoint.class);
        Mockito.when(joinPoint.getSignature()).thenReturn(signature);
        Mockito.when(joinPoint.getArgs()).thenReturn(args);
        return joinPoint;
    }

    static final class TestTarget {
        String process(String orderId, String region) {
            return orderId + ":" + region;
        }
    }
}
```

- [x] **Step 2: Run the focused Spring starter tests to verify they fail**

Run:

```bash
mvn -q -pl distributed-lock-spring-boot-starter -am test -Dtest=SpelLockKeyResolverTest,DistributedLockAspectIntegrationTest,DistributedLockAsyncGuardTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the resolver still uses manual `#{...}` substring scanning.

- [x] **Step 3: Switch `SpelLockKeyResolver` to `ParserContext.TEMPLATE_EXPRESSION`**

Update `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/key/SpelLockKeyResolver.java`:

```java
import org.springframework.expression.ParserContext;
```

```java
private static final ParserContext TEMPLATE_CONTEXT = ParserContext.TEMPLATE_EXPRESSION;
```

Replace the manual `while (evaluated.contains("#{")) { ... }` block with:

```java
if (!expression.contains("#{")) {
    return expression;
}

Object value = parser.parseExpression(expression, TEMPLATE_CONTEXT).getValue(context);
return value == null ? "null" : value.toString();
```

Update the README section in `distributed-lock-spring-boot-starter/README.md` to explicitly document template semantics:

```md
Lock keys follow Spring template-expression semantics. Literal keys such as `order:42` pass through unchanged, while templates such as `order:#{#p0}` are evaluated against the intercepted method arguments.
```

- [x] **Step 4: Run the focused Spring starter tests to verify they pass**

Run:

```bash
mvn -q -pl distributed-lock-spring-boot-starter -am test -Dtest=SpelLockKeyResolverTest,DistributedLockAspectIntegrationTest,DistributedLockAsyncGuardTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [x] **Step 5: Commit the Spring key-resolution change**

Run:

```bash
git add distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/key/SpelLockKeyResolver.java \
        distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/key/SpelLockKeyResolverTest.java \
        distributed-lock-spring-boot-starter/README.md
git commit -m "fix: use spring template parsing for lock keys"
```

### Task 6: Run Final Verification

**Files:**
- Verify only: full reactor and targeted modules

- [x] **Step 1: Run the focused regression suites**

Run:

```bash
mvn -q -pl distributed-lock-core -am test -Dtest=DefaultLockExecutorTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -q -pl distributed-lock-redis -am test -Dtest=RedisSessionLossTest,RedisExecutorOwnershipLossTest,RedisLockBackendContractTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperSessionIsolationTest,ZooKeeperSessionLossTest,ZooKeeperLockBackendBehaviorTest,ZooKeeperReadWriteConcurrencyTest,ZooKeeperLockBackendContractTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -q -pl distributed-lock-spring-boot-starter -am test -Dtest=SpelLockKeyResolverTest,DistributedLockAspectIntegrationTest,DistributedLockAsyncGuardTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS in every command.

- [x] **Step 2: Run the full reactor test suite**

Run:

```bash
mvn test
```

Expected: `BUILD SUCCESS`.

- [x] **Step 3: Confirm the worktree is clean after the task commits**

Run:

```bash
git status --short
```

Expected: no output.
