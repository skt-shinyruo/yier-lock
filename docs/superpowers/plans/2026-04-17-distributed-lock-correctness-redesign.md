# Distributed Lock Correctness Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove fake public lock policies, make Redis renewal failures deterministic, rebuild ZooKeeper read/write locking around a correct sequential-node protocol, and migrate every caller and test onto the reduced API.

**Architecture:** Implement the redesign from the API boundary inward. First remove the unused public policy and capability types and update compile-time callers. Then migrate core/runtime and the shared contract suite to the reduced session API. After that harden Redis loss handling, replace ZooKeeper read/write locking with queue-based sequential nodes, and finally update the Spring/examples/benchmark surfaces plus full regression coverage.

**Tech Stack:** Java 17, Maven multi-module build, Spring Boot 3.2, JUnit 5, AssertJ, Lettuce, Curator/ZooKeeper, Docker-backed Redis tests, ServiceLoader

---

## File Map

- Modify: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockClient.java`
  Responsibility: reduce `openSession(SessionRequest)` to `openSession()`.
- Modify: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockRequest.java`
  Responsibility: drop `leasePolicy` from the public request model.
- Delete: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LeasePolicy.java`
  Responsibility: remove the fake lease policy surface.
- Delete: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/SessionPolicy.java`
  Responsibility: remove the fake session policy surface.
- Delete: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/SessionRequest.java`
  Responsibility: remove the now-unused request wrapper.
- Delete: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockCapabilities.java`
  Responsibility: remove public capability signaling that callers cannot use meaningfully.
- Modify: `distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/ApiSurfaceTest.java`
  Responsibility: protect the reduced public surface and assert removed classes stay gone.

- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/SupportedLockModes.java`
  Responsibility: internal capability model used by the core validator.
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/LockBackend.java`
  Responsibility: remove public capability and session-request coupling from the backend port.
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/BackendSession.java`
  Responsibility: keep acquire/state/close semantics under the reduced API.
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockClient.java`
  Responsibility: open sessions without a request object and validate requests against internal supported modes.
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockSession.java`
  Responsibility: validate the reduced `LockRequest`.
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockExecutor.java`
  Responsibility: open reduced sessions and preserve `CurrentLockContext`.
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/LockRequestValidator.java`
  Responsibility: validate `LockMode` against `SupportedLockModes`.
- Modify: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/backend/LockBackendSurfaceTest.java`
  Responsibility: assert the backend port matches the reduced SPI.
- Modify: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockClientTest.java`
  Responsibility: compile against the reduced API and validate unsupported modes through internal capabilities.
- Modify: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockExecutorTest.java`
  Responsibility: compile against the reduced API and keep fencing-context assertions.

- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java`
  Responsibility: construct `DefaultLockClient` and `DefaultLockExecutor` without session-policy state.
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendCapabilities.java`
  Responsibility: shrink SPI capability metadata to `mutexSupported` and `readWriteSupported`.
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendModule.java`
  Responsibility: keep backend selection on the reduced capability shape.
- Modify: `distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java`
  Responsibility: protect the reduced runtime assembly path.

- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockClientContract.java`
  Responsibility: add read/write and cross-mode fencing contract coverage.
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryLockBackend.java`
  Responsibility: adopt the reduced backend SPI while keeping correct read/write semantics.
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryBackendModule.java`
  Responsibility: advertise reduced internal capabilities.
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/BlockingLeaseBackend.java`
  Responsibility: adopt the reduced backend SPI for core tests.
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/OwnershipLossLeaseBackend.java`
  Responsibility: adopt the reduced backend SPI for ownership-loss tests.
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/ReleaseFailureLeaseBackend.java`
  Responsibility: adopt the reduced backend SPI for release-failure tests.

- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`
  Responsibility: issue per-key fencing tokens and convert renewal failures into explicit session/lease loss.
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisTestSupport.java`
  Responsibility: expose deterministic container stop/start helpers for renewal-failure tests.
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisLockBackendContractTest.java`
  Responsibility: keep Redis under the expanded shared contract.
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisLeaseRenewalTest.java`
  Responsibility: keep the normal renewal happy path covered.
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisOwnershipLossTest.java`
  Responsibility: keep explicit ownership-loss behavior covered under the reduced API.
- Create: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisSessionLossTest.java`
  Responsibility: prove renewal exceptions transition sessions and leases to `LOST`.

- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`
  Responsibility: replace owner-path polling with ephemeral sequential contender nodes and predecessor watches.
- Modify: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackendContractTest.java`
  Responsibility: keep ZooKeeper under the expanded shared contract.
- Modify: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperSessionLossTest.java`
  Responsibility: keep session-loss semantics covered under the reduced API.
- Modify: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackendBehaviorTest.java`
  Responsibility: keep path-encoding and startup-failure coverage.
- Create: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperReadWriteConcurrencyTest.java`
  Responsibility: prove no reader/writer overlap and correct queue behavior under contention.

- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockAspect.java`
  Responsibility: create reduced `LockRequest` instances without `LeasePolicy`.
- Modify: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAspectIntegrationTest.java`
  Responsibility: compile against the reduced API.
- Modify: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAsyncGuardTest.java`
  Responsibility: preserve async-return rejection.
- Modify: `distributed-lock-redis-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/redis/springboot/integration/RedisBackendModuleAutoConfigurationTest.java`
  Responsibility: update stub backends to the reduced SPI.
- Modify: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/zookeeper/springboot/integration/ZooKeeperBackendModuleAutoConfigurationTest.java`
  Responsibility: update stub backends to the reduced SPI.
- Modify: `distributed-lock-redis-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/redis/springboot/integration/RedisStarterIntegrationTest.java`
  Responsibility: compile and run with reduced `LockRequest`.
- Modify: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/zookeeper/springboot/integration/ZooKeeperStarterIntegrationTest.java`
  Responsibility: compile and run with reduced `LockRequest`.
- Modify: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/ProgrammaticRedisExample.java`
  Responsibility: use reduced `LockRequest`.
- Modify: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/ProgrammaticZooKeeperExample.java`
  Responsibility: use reduced `openSession()` and `LockRequest`.
- Modify: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/spring/SpringBootRedisExampleApplication.java`
  Responsibility: use reduced `LockRequest`.
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/BenchmarkWorkloads.java`
  Responsibility: remove default `SessionRequest` state and use reduced `LockRequest`.
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/SpringBenchmarkApplication.java`
  Responsibility: use reduced `LockRequest`.
- Modify: `distributed-lock-benchmarks/src/test/java/com/mycorp/distributedlock/benchmarks/BenchmarkEnvironmentSmokeTest.java`
  Responsibility: compile against reduced `LockRequest`.
- Modify: `distributed-lock-spring-boot-starter/README.md`
- Modify: `distributed-lock-examples/README.md`
- Modify: `distributed-lock-test-suite/README.md`
- Modify: `distributed-lock-test-suite/TEST-CONFIGURATION.md`
  Responsibility: document only the supported synchronous API.

## Task 1: Reduce the Public API to Truthful Semantics

**Files:**
- Modify: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockClient.java`
- Modify: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockRequest.java`
- Delete: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LeasePolicy.java`
- Delete: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/SessionPolicy.java`
- Delete: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/SessionRequest.java`
- Delete: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockCapabilities.java`
- Modify: `distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/ApiSurfaceTest.java`

- [ ] **Step 1: Write the failing API surface test**

Replace the current surface assertions with this target shape:

```java
@Test
void apiShouldExposeOnlyTheReducedSessionSurface() throws Exception {
    assertThat(LockClient.class.getMethod("openSession").getReturnType()).isEqualTo(LockSession.class);
    assertThat(Arrays.stream(LockRequest.class.getRecordComponents())
        .map(RecordComponent::getName))
        .containsExactly("key", "mode", "waitPolicy");
    assertThat(Arrays.stream(LockRequest.class.getRecordComponents())
        .map(RecordComponent::getType))
        .containsExactly(LockKey.class, LockMode.class, WaitPolicy.class);
}

@Test
void removedPolicyAndCapabilityTypesShouldStayGone() {
    assertThatThrownBy(() -> Class.forName("com.mycorp.distributedlock.api.LeasePolicy"))
        .isInstanceOf(ClassNotFoundException.class);
    assertThatThrownBy(() -> Class.forName("com.mycorp.distributedlock.api.SessionPolicy"))
        .isInstanceOf(ClassNotFoundException.class);
    assertThatThrownBy(() -> Class.forName("com.mycorp.distributedlock.api.SessionRequest"))
        .isInstanceOf(ClassNotFoundException.class);
    assertThatThrownBy(() -> Class.forName("com.mycorp.distributedlock.api.LockCapabilities"))
        .isInstanceOf(ClassNotFoundException.class);
}
```

- [ ] **Step 2: Run the API tests to verify they fail**

Run: `mvn -q -pl distributed-lock-api test -Dtest=ApiSurfaceTest`

Expected: FAIL because `openSession()` still requires `SessionRequest`, `LockRequest` still exposes `leasePolicy`, and the removed classes still exist.

- [ ] **Step 3: Implement the reduced public API**

Set the target files to these shapes and delete the removed types:

```java
package com.mycorp.distributedlock.api;

public interface LockClient extends AutoCloseable {
    LockSession openSession();

    @Override
    void close();
}
```

```java
package com.mycorp.distributedlock.api;

public record LockRequest(
    LockKey key,
    LockMode mode,
    WaitPolicy waitPolicy
) {
    public LockRequest {
        if (key == null) {
            throw new IllegalArgumentException("Lock key is required");
        }
        if (mode == null) {
            throw new IllegalArgumentException("Lock mode is required");
        }
        if (waitPolicy == null) {
            throw new IllegalArgumentException("Wait policy is required");
        }
    }
}
```

Delete these files outright:

```text
distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LeasePolicy.java
distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/SessionPolicy.java
distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/SessionRequest.java
distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockCapabilities.java
```

- [ ] **Step 4: Run the API tests to verify they pass**

Run: `mvn -q -pl distributed-lock-api test -Dtest=ApiSurfaceTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add distributed-lock-api
git commit -m "refactor: reduce public lock api surface"
```

## Task 2: Migrate Core and Runtime to the Reduced API

**Files:**
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/SupportedLockModes.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/LockBackend.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/BackendSession.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockClient.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockSession.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockExecutor.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/LockRequestValidator.java`
- Modify: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/backend/LockBackendSurfaceTest.java`
- Modify: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockClientTest.java`
- Modify: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockExecutorTest.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendCapabilities.java`
- Modify: `distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java`

- [ ] **Step 1: Write the failing core/runtime surface tests**

Replace the signatures asserted by the surface tests and switch the client/executor tests to the reduced API:

```java
@Test
void backendShouldOpenSessionsWithoutPublicRequestTypes() throws Exception {
    assertThat(Arrays.stream(LockBackend.class.getDeclaredMethods())
        .map(Method::getName))
        .containsExactly("close", "openSession");
    assertThat(LockBackend.class.getMethod("openSession").getReturnType())
        .isEqualTo(BackendSession.class);
}
```

```java
try (LockSession session = client.openSession();
     LockLease lease = session.acquire(new LockRequest(
         new LockKey("orders"),
         LockMode.MUTEX,
         WaitPolicy.timed(Duration.ofSeconds(1))
     ))) {
    assertThat(lease.fencingToken()).isEqualTo(new FencingToken(1L));
}
```

```java
LockExecutor executor = new DefaultLockExecutor(new DefaultLockClient(
    backend,
    new SupportedLockModes(true, true)
));
```

- [ ] **Step 2: Run the focused core/runtime tests to verify they fail**

Run: `mvn -q -pl distributed-lock-core,distributed-lock-runtime -am test -Dtest=LockBackendSurfaceTest,DefaultLockClientTest,DefaultLockExecutorTest,LockRuntimeBuilderTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because `LockBackend` still exposes `capabilities()` and `openSession(SessionRequest)`, and the core tests still compile against deleted public types.

- [ ] **Step 3: Implement the reduced core/runtime SPI**

Use these target shapes:

```java
package com.mycorp.distributedlock.core.backend;

public interface LockBackend extends AutoCloseable {
    BackendSession openSession();

    @Override
    default void close() {
    }
}
```

```java
package com.mycorp.distributedlock.core.client;

public record SupportedLockModes(
    boolean mutexSupported,
    boolean readWriteSupported
) {
}
```

```java
package com.mycorp.distributedlock.core.client;

public final class DefaultLockClient implements LockClient {
    private final LockBackend backend;
    private final SupportedLockModes supportedLockModes;
    private final LockRequestValidator validator;

    public DefaultLockClient(LockBackend backend, SupportedLockModes supportedLockModes) {
        this(backend, supportedLockModes, new LockRequestValidator());
    }

    @Override
    public LockSession openSession() {
        return new DefaultLockSession(supportedLockModes, backend.openSession(), validator);
    }
}
```

```java
package com.mycorp.distributedlock.core.client;

public final class DefaultLockExecutor implements LockExecutor {
    private final LockClient client;

    public DefaultLockExecutor(LockClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public <T> T withLock(LockRequest request, LockedSupplier<T> action) throws Exception {
        try (LockSession session = client.openSession();
             LockLease lease = session.acquire(request);
             CurrentLockContext.Binding ignored = CurrentLockContext.bind(lease)) {
            return action.get();
        }
    }
}
```

```java
package com.mycorp.distributedlock.runtime.spi;

public record BackendCapabilities(
    boolean mutexSupported,
    boolean readWriteSupported
) {
    public static BackendCapabilities standard() {
        return new BackendCapabilities(true, true);
    }
}
```

Also update `LockRuntimeBuilder` to pass `new SupportedLockModes(selectedModule.capabilities().mutexSupported(), selectedModule.capabilities().readWriteSupported())` into `DefaultLockClient`, and update every test double in `distributed-lock-core` and `distributed-lock-runtime` to the new constructors and request shape.

- [ ] **Step 4: Run the focused core/runtime tests to verify they pass**

Run: `mvn -q -pl distributed-lock-core,distributed-lock-runtime -am test -Dtest=LockBackendSurfaceTest,DefaultLockClientTest,DefaultLockExecutorTest,LockRuntimeBuilderTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add distributed-lock-core distributed-lock-runtime
git commit -m "refactor: migrate core runtime to reduced session api"
```

## Task 3: Expand the Shared Contract Suite Beyond Mutex-Only Behavior

**Files:**
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockClientContract.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryLockBackend.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryBackendModule.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/BlockingLeaseBackend.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/OwnershipLossLeaseBackend.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/ReleaseFailureLeaseBackend.java`
- Modify: `distributed-lock-testkit/src/test/java/com/mycorp/distributedlock/testkit/InMemoryLockClientContractTest.java`

- [ ] **Step 1: Write the failing shared contract tests**

Add these tests and helpers to `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockClientContract.java`:

```java
@Test
void readersShouldShareTheSameKeyAcrossSessions() throws Exception {
    runtime = createRuntime();
    try (LockSession first = runtime.lockClient().openSession();
         LockLease ignored = first.acquire(request("inventory:rw", LockMode.READ, Duration.ofSeconds(1)))) {
        assertThat(executor.submit(() -> tryAcquire("inventory:rw", LockMode.READ, Duration.ofMillis(200))).get())
            .isTrue();
    }
}

@Test
void writerShouldTimeOutWhileReaderIsHeld() throws Exception {
    runtime = createRuntime();
    try (LockSession reader = runtime.lockClient().openSession();
         LockLease ignored = reader.acquire(request("inventory:rw", LockMode.READ, Duration.ofSeconds(1)))) {
        assertThat(executor.submit(() -> tryAcquire("inventory:rw", LockMode.WRITE, Duration.ofMillis(100))).get())
            .isFalse();
    }
}

@Test
void fencingTokenShouldIncreaseAcrossModesForTheSameKey() throws Exception {
    runtime = createRuntime();
    try (LockSession session = runtime.lockClient().openSession()) {
        long first;
        try (LockLease lease = session.acquire(request("inventory:fence", LockMode.READ, Duration.ofSeconds(1)))) {
            first = lease.fencingToken().value();
        }
        try (LockLease lease = session.acquire(request("inventory:fence", LockMode.WRITE, Duration.ofSeconds(1)))) {
            assertThat(lease.fencingToken().value()).isGreaterThan(first);
        }
    }
}
```

- [ ] **Step 2: Run the testkit contract tests to verify they fail**

Run: `mvn -q -pl distributed-lock-testkit -am test -Dtest=InMemoryLockClientContractTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because the contract helpers and testkit backends still compile against `SessionRequest`, `LeasePolicy`, and the old backend SPI.

- [ ] **Step 3: Implement the reduced testkit helpers and backend signatures**

Update the shared helper API and support backends to this shape:

```java
protected LockRequest request(String key, LockMode mode, Duration waitTime) {
    return new LockRequest(
        new LockKey(key),
        mode,
        WaitPolicy.timed(waitTime)
    );
}

private boolean tryAcquire(String key, LockMode mode, Duration waitTime) throws Exception {
    try (LockSession contender = runtime.lockClient().openSession();
         LockLease ignored = contender.acquire(request(key, mode, waitTime))) {
        return true;
    } catch (LockAcquisitionTimeoutException exception) {
        return false;
    }
}
```

```java
package com.mycorp.distributedlock.testkit.support;

public final class InMemoryBackendModule implements BackendModule {
    @Override
    public BackendCapabilities capabilities() {
        return BackendCapabilities.standard();
    }

    @Override
    public LockBackend createBackend() {
        return new InMemoryLockBackend();
    }
}
```

```java
package com.mycorp.distributedlock.testkit.support;

public final class InMemoryLockBackend implements LockBackend {
    @Override
    public InMemoryBackendSession openSession() {
        return new InMemoryBackendSession(lockStates);
    }
}
```

Apply the same `openSession()` signature change to `BlockingLeaseBackend`, `OwnershipLossLeaseBackend`, and `ReleaseFailureLeaseBackend`.

- [ ] **Step 4: Run the testkit contract tests to verify they pass**

Run: `mvn -q -pl distributed-lock-testkit -am test -Dtest=InMemoryLockClientContractTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add distributed-lock-testkit
git commit -m "test: expand shared lock client contract coverage"
```

## Task 4: Harden Redis Renewal and Session-Loss Behavior

**Files:**
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisTestSupport.java`
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisLockBackendContractTest.java`
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisLeaseRenewalTest.java`
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisOwnershipLossTest.java`
- Create: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisSessionLossTest.java`

- [ ] **Step 1: Write the failing Redis loss-handling tests**

Create `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisSessionLossTest.java` with this shape:

```java
class RedisSessionLossTest {

    @Test
    void renewalFailureShouldMarkSessionAndLeaseLost() throws Exception {
        try (RedisTestSupport.RunningRedis redis = RedisTestSupport.startRedis();
             RedisLockBackend backend = redis.newBackend(1L);
             BackendSession session = backend.openSession();
             BackendLockLease lease = session.acquire(new LockRequest(
                 new LockKey("redis:session-loss"),
                 LockMode.MUTEX,
                 WaitPolicy.indefinite()
             ))) {
            redis.stopContainer();

            waitUntilLost(session, lease);

            assertThat(session.state()).isEqualTo(SessionState.LOST);
            assertThat(lease.state()).isEqualTo(LeaseState.LOST);
            assertThatThrownBy(() -> session.acquire(new LockRequest(
                new LockKey("redis:session-loss"),
                LockMode.MUTEX,
                WaitPolicy.timed(Duration.ofMillis(50))
            ))).isInstanceOf(LockSessionLostException.class);
        }
    }

    private static void waitUntilLost(BackendSession session, BackendLockLease lease) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (session.state() == SessionState.LOST && lease.state() == LeaseState.LOST) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("session and lease did not transition to LOST");
    }
}
```

Extend `RedisLockBackendContractTest` to assert cross-mode fencing:

```java
try (LockSession session = runtime.lockClient().openSession()) {
    long first;
    try (LockLease lease = session.acquire(new LockRequest(new LockKey("redis:fence"), LockMode.READ, WaitPolicy.timed(Duration.ofSeconds(1))))) {
        first = lease.fencingToken().value();
    }
    try (LockLease lease = session.acquire(new LockRequest(new LockKey("redis:fence"), LockMode.WRITE, WaitPolicy.timed(Duration.ofSeconds(1))))) {
        assertThat(lease.fencingToken().value()).isGreaterThan(first);
    }
}
```

- [ ] **Step 2: Run the focused Redis tests to verify they fail**

Run: `mvn -q -pl distributed-lock-redis -am test -Dtest=RedisLockBackendContractTest,RedisLeaseRenewalTest,RedisOwnershipLossTest,RedisSessionLossTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because Redis still fences by `key + mode`, still expects `SessionRequest`, and renewal failures still escape the scheduled task.

- [ ] **Step 3: Implement deterministic Redis loss handling**

Use these target snippets in `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`:

```java
private static String fenceKey(String key) {
    return "lock:%s:fence".formatted(key);
}
```

```java
private void renew() {
    if (closed.get()) {
        return;
    }
    try {
        if (!renewSessionKey()) {
            markSessionLost(new LockSessionLostException("Redis session lost: " + sessionId));
            return;
        }
        for (RedisLease lease : activeLeases.values()) {
            lease.refresh();
        }
    } catch (RuntimeException exception) {
        markSessionLost(new LockBackendException("Failed to renew Redis session " + sessionId, exception));
    }
}
```

```java
private void markSessionLost(RuntimeException cause) {
    if (!valid.compareAndSet(true, false)) {
        return;
    }
    for (RedisLease lease : new ArrayList<>(activeLeases.values())) {
        lease.markLost();
    }
    renewalTask.cancel(false);
}
```

```java
void stopContainer() throws Exception {
    run("docker", "stop", containerId);
}

void startContainer() throws Exception {
    run("docker", "start", containerId);
    awaitReady();
}
```

Also update every Redis test to call `backend.openSession()` and to build `new LockRequest(new LockKey(...), LockMode..., WaitPolicy...)` without `LeasePolicy`.

- [ ] **Step 4: Run the focused Redis tests to verify they pass**

Run: `mvn -q -pl distributed-lock-redis -am test -Dtest=RedisLockBackendContractTest,RedisLeaseRenewalTest,RedisOwnershipLossTest,RedisSessionLossTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add distributed-lock-redis
git commit -m "fix: harden redis lease renewal loss handling"
```

## Task 5: Replace ZooKeeper Read/Write Locking with Sequential Queue Nodes

**Files:**
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`
- Modify: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackendContractTest.java`
- Modify: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperSessionLossTest.java`
- Modify: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackendBehaviorTest.java`
- Create: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperReadWriteConcurrencyTest.java`

- [ ] **Step 1: Write the failing ZooKeeper concurrency tests**

Create `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperReadWriteConcurrencyTest.java` with this shape:

```java
class ZooKeeperReadWriteConcurrencyTest {

    @Test
    void writerShouldTimeOutWhileReaderIsHeld() throws Exception {
        try (TestingServer server = new TestingServer();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(
                 new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks")
             );
             BackendSession readerSession = backend.openSession();
             BackendLockLease ignored = readerSession.acquire(new LockRequest(
                 new LockKey("zk:rw"),
                 LockMode.READ,
                 WaitPolicy.indefinite()
             ));
             BackendSession writerSession = backend.openSession()) {
            assertThatThrownBy(() -> writerSession.acquire(new LockRequest(
                new LockKey("zk:rw"),
                LockMode.WRITE,
                WaitPolicy.timed(Duration.ofMillis(100))
            ))).isInstanceOf(LockAcquisitionTimeoutException.class);
        }
    }

    @Test
    void readerAndWriterShouldNeverOverlapUnderContention() throws Exception {
        try (TestingServer server = new TestingServer();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(
                 new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks")
             );
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 25; round++) {
                Future<Boolean> readResult = executor.submit(() -> tryAcquire(backend, LockMode.READ));
                Future<Boolean> writeResult = executor.submit(() -> tryAcquire(backend, LockMode.WRITE));

                assertThat(readResult.get() && writeResult.get()).isFalse();
            }
        }
    }

    private boolean tryAcquire(ZooKeeperLockBackend backend, LockMode mode) throws Exception {
        try (BackendSession session = backend.openSession();
             BackendLockLease lease = session.acquire(new LockRequest(
                 new LockKey("zk:rw-race"),
                 mode,
                 WaitPolicy.timed(Duration.ofMillis(200))
             ))) {
            Thread.sleep(20L);
            return true;
        } catch (LockAcquisitionTimeoutException exception) {
            return false;
        }
    }
}
```

Extend `ZooKeeperLockBackendContractTest` with the same cross-mode fencing assertion used in Redis.

- [ ] **Step 2: Run the focused ZooKeeper tests to verify they fail**

Run: `mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperLockBackendContractTest,ZooKeeperSessionLossTest,ZooKeeperLockBackendBehaviorTest,ZooKeeperReadWriteConcurrencyTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because the current check-then-create algorithm still permits reader/writer races and the module still compiles against `SessionRequest`.

- [ ] **Step 3: Implement the sequential-node queue protocol**

Rebuild `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java` around these helpers:

```java
private record QueueNode(String name, LockMode mode, long sequence) {
}
```

```java
private ZooKeeperLease acquireQueuedLock(LockRequest request) throws InterruptedException {
    String contenderPath = createContenderNode(request);
    try {
        return waitForTurn(request, contenderPath);
    } catch (InterruptedException exception) {
        deleteIfExists(contenderPath);
        throw exception;
    } catch (RuntimeException exception) {
        deleteIfExists(contenderPath);
        throw exception;
    } catch (Exception exception) {
        deleteIfExists(contenderPath);
        throw new LockBackendException("Failed to acquire ZooKeeper lock for key " + request.key().value(), exception);
    }
}
```

```java
private boolean canAcquire(List<QueueNode> nodes, QueueNode current) {
    if (current.mode() == LockMode.WRITE) {
        return nodes.stream()
            .takeWhile(node -> node.sequence() < current.sequence())
            .findAny()
            .isEmpty();
    }
    return nodes.stream()
        .takeWhile(node -> node.sequence() < current.sequence())
        .noneMatch(node -> node.mode() == LockMode.WRITE);
}
```

```java
private String watchedPredecessor(String key, List<QueueNode> nodes, QueueNode current) {
    if (current.mode() == LockMode.WRITE) {
        return nodes.stream()
            .filter(node -> node.sequence() < current.sequence())
            .max(Comparator.comparingLong(QueueNode::sequence))
            .map(node -> queueRootPath(key) + "/" + node.name())
            .orElse(null);
    }
    return nodes.stream()
        .filter(node -> node.sequence() < current.sequence() && node.mode() == LockMode.WRITE)
        .max(Comparator.comparingLong(QueueNode::sequence))
        .map(node -> queueRootPath(key) + "/" + node.name())
        .orElse(null);
}
```

Use a per-key fencing path such as `configuration.basePath() + "/fence/" + encodeKeySegment(key)` so `READ` and `WRITE` share the same counter. Remove the fixed owner-path algorithm entirely.

- [ ] **Step 4: Run the focused ZooKeeper tests to verify they pass**

Run: `mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperLockBackendContractTest,ZooKeeperSessionLossTest,ZooKeeperLockBackendBehaviorTest,ZooKeeperReadWriteConcurrencyTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add distributed-lock-zookeeper
git commit -m "fix: rebuild zookeeper read write locking protocol"
```

## Task 6: Migrate Spring, Examples, Benchmarks, and Docs to the Reduced API

**Files:**
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockAspect.java`
- Modify: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAspectIntegrationTest.java`
- Modify: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAsyncGuardTest.java`
- Modify: `distributed-lock-redis-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/redis/springboot/integration/RedisBackendModuleAutoConfigurationTest.java`
- Modify: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/zookeeper/springboot/integration/ZooKeeperBackendModuleAutoConfigurationTest.java`
- Modify: `distributed-lock-redis-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/redis/springboot/integration/RedisStarterIntegrationTest.java`
- Modify: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/zookeeper/springboot/integration/ZooKeeperStarterIntegrationTest.java`
- Modify: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/ProgrammaticRedisExample.java`
- Modify: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/ProgrammaticZooKeeperExample.java`
- Modify: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/spring/SpringBootRedisExampleApplication.java`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/BenchmarkWorkloads.java`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/SpringBenchmarkApplication.java`
- Modify: `distributed-lock-benchmarks/src/test/java/com/mycorp/distributedlock/benchmarks/BenchmarkEnvironmentSmokeTest.java`
- Modify: `distributed-lock-spring-boot-starter/README.md`
- Modify: `distributed-lock-examples/README.md`
- Modify: `distributed-lock-test-suite/README.md`
- Modify: `distributed-lock-test-suite/TEST-CONFIGURATION.md`

- [ ] **Step 1: Run the failing starter/example/benchmark checks**

Run:

```bash
mvn -q -pl distributed-lock-spring-boot-starter,distributed-lock-redis-spring-boot-autoconfigure,distributed-lock-zookeeper-spring-boot-autoconfigure,distributed-lock-examples,distributed-lock-benchmarks -am test -Dtest=DistributedLockAspectIntegrationTest,DistributedLockAsyncGuardTest,RedisBackendModuleAutoConfigurationTest,RedisStarterIntegrationTest,ZooKeeperBackendModuleAutoConfigurationTest,ZooKeeperStarterIntegrationTest,BenchmarkEnvironmentSmokeTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because these modules still import `LeasePolicy`, `SessionPolicy`, `SessionRequest`, or `LockCapabilities`.

- [ ] **Step 2: Update the call sites and integration tests**

Use these target snippets:

```java
return new LockRequest(
    new LockKey(key),
    resolveMode(distributedLock.mode()),
    resolveWaitPolicy(distributedLock)
);
```

```java
try (LockRuntime runtime = LockRuntimeBuilder.create()
    .backend("zookeeper")
    .backendModules(List.of(new ZooKeeperBackendModule(new ZooKeeperBackendConfiguration(
        "127.0.0.1:2181",
        "/distributed-locks"
    ))))
    .build();
     LockSession session = runtime.lockClient().openSession();
     LockLease lease = session.acquire(sampleRequest("example:zk:inventory-7"))) {
    System.out.println("ZooKeeper lease acquired for " + lease.key().value()
        + " with fencing token " + lease.fencingToken().value());
}
```

```java
private static LockRequest mutexRequest(String key, WaitPolicy waitPolicy) {
    return new LockRequest(new LockKey(key), LockMode.MUTEX, waitPolicy);
}
```

```java
@Override
public LockBackend createBackend() {
    return new LockBackend() {
        @Override
        public BackendSession openSession() {
            throw new UnsupportedOperationException("not used in test");
        }
    };
}
```

Also revise all README snippets to show only:

```java
runtime.lockExecutor().withLock(
    new LockRequest(new LockKey("orders:42"), LockMode.MUTEX, WaitPolicy.timed(Duration.ofSeconds(2))),
    () -> "ok"
);
```

- [ ] **Step 3: Run the starter/example/benchmark checks to verify they pass**

Run:

```bash
mvn -q -pl distributed-lock-spring-boot-starter,distributed-lock-redis-spring-boot-autoconfigure,distributed-lock-zookeeper-spring-boot-autoconfigure,distributed-lock-examples,distributed-lock-benchmarks -am test -Dtest=DistributedLockAspectIntegrationTest,DistributedLockAsyncGuardTest,RedisBackendModuleAutoConfigurationTest,RedisStarterIntegrationTest,ZooKeeperBackendModuleAutoConfigurationTest,ZooKeeperStarterIntegrationTest,BenchmarkEnvironmentSmokeTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -q -pl distributed-lock-examples -am -DskipTests compile
```

Expected: PASS.

- [ ] **Step 4: Run the full regression**

Run: `mvn test -q`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add distributed-lock-spring-boot-starter distributed-lock-redis-spring-boot-autoconfigure distributed-lock-zookeeper-spring-boot-autoconfigure distributed-lock-examples distributed-lock-benchmarks distributed-lock-test-suite
git commit -m "refactor: migrate integrations to reduced lock api"
```
