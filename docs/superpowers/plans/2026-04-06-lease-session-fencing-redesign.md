# Lease/Session/Fencing Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the thread-bound lock API with a lease/session/fencing-token model, rebuild the core/runtime/backend contracts around that model, and migrate Spring/examples/benchmarks onto scoped execution.

**Architecture:** The implementation proceeds from the outside in. First replace the public API and runtime SPI with lease/session primitives, then rebuild the core client and shared contract suite, then migrate Redis and ZooKeeper to session-backed fenced leases, and finally move Spring/exemplars to scoped execution on top of the new client.

**Tech Stack:** Java 17, Maven multi-module build, Spring Boot 3.2, JUnit 5, AssertJ, Awaitility, Lettuce, Curator/ZooKeeper, ServiceLoader

---

## File Structure

### Public API

- Modify: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockManager.java`
  Responsibility: replace with `LockClient`.
- Modify: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/MutexLock.java`
  Responsibility: replace with `LockLease`.
- Modify: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/ReadWriteLock.java`
  Responsibility: replace with `LockSession`.
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockExecutor.java`
  Responsibility: synchronous scoped execution entrypoint.
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockedSupplier.java`
  Responsibility: synchronous scoped execution entrypoint.
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockKey.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockRequest.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/SessionRequest.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/FencingToken.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockMode.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/WaitPolicy.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LeasePolicy.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/SessionPolicy.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LeaseState.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/SessionState.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockCapabilities.java`
- Modify: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/LockAcquisitionTimeoutException.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/LockSessionLostException.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/UnsupportedLockCapabilityException.java`
- Modify: `distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/ApiSurfaceTest.java`

### Core and Runtime

- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultLockManager.java`
- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultMutexLock.java`
- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultReadWriteLock.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/LockBackend.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/BackendLockLease.java`
- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/LockMode.java`
- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/WaitPolicy.java`
- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/LockResource.java`
- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/SupportedLockModes.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/BackendSession.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/SessionAwareBackendLockLease.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockClient.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockSession.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockExecutor.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/LockRequestValidator.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/LeaseScopeRunner.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntime.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/DefaultLockRuntime.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendCapabilities.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendModule.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/ServiceLoaderBackendRegistry.java`

### Shared Tests and Testkit

- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockManagerContract.java`
  Responsibility: replace with `LockClientContract`.
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryLockBackend.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryBackendModule.java`
- Create: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryBackendSession.java`
- Create: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/FencedResource.java`
- Create: `distributed-lock-testkit/src/test/java/com/mycorp/distributedlock/testkit/InMemoryLockClientContractTest.java`
- Replace core tests under `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/...`

### Backend Adapters

- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendConfiguration.java`
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendModule.java`
- Rewrite: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendConfiguration.java`
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModule.java`
- Rewrite: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`
- Replace backend tests in both adapter modules

### Spring and Usage Surfaces

- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/annotation/DistributedLock.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/annotation/DistributedLockMode.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockAspect.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockAutoConfiguration.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockProperties.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/key/LockKeyResolver.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/key/SpelLockKeyResolver.java`
- Rewrite Spring starter tests
- Modify examples and benchmarks under `distributed-lock-examples` and `distributed-lock-benchmarks`
- Modify docs: `distributed-lock-spring-boot-starter/README.md`, `distributed-lock-examples/README.md`, `distributed-lock-benchmarks/README.md`, `distributed-lock-test-suite/README.md`, `distributed-lock-test-suite/TEST-CONFIGURATION.md`

## Task 1: Replace the Public API with Lease/Session/Fencing Primitives

**Files:**
- Modify: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockManager.java`
- Modify: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/MutexLock.java`
- Modify: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/ReadWriteLock.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockExecutor.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockKey.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockRequest.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/SessionRequest.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/FencingToken.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockMode.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/WaitPolicy.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LeasePolicy.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/SessionPolicy.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LeaseState.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/SessionState.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockCapabilities.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/LockSessionLostException.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/UnsupportedLockCapabilityException.java`
- Modify: `distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/ApiSurfaceTest.java`

- [ ] **Step 1: Write the failing API surface test**

Add this test shape to `distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/ApiSurfaceTest.java`:

```java
class ApiSurfaceTest {

    @Test
    void apiShouldExposeLeaseSessionAndFencingTypes() throws Exception {
        assertThat(Class.forName("com.mycorp.distributedlock.api.LockClient")).isNotNull();
        assertThat(Class.forName("com.mycorp.distributedlock.api.LockSession")).isNotNull();
        assertThat(Class.forName("com.mycorp.distributedlock.api.LockLease")).isNotNull();
        assertThat(Class.forName("com.mycorp.distributedlock.api.FencingToken")).isNotNull();

        assertThat(Class.forName("com.mycorp.distributedlock.api.LockClient")
            .getMethod("openSession", Class.forName("com.mycorp.distributedlock.api.SessionRequest")))
            .isNotNull();
    }

    @Test
    void apiShouldExposeScopedExecutionAndCapabilityExceptions() throws Exception {
        assertThat(Class.forName("com.mycorp.distributedlock.api.LockExecutor")).isNotNull();
        assertThat(Class.forName("com.mycorp.distributedlock.api.exception.LockSessionLostException")).isNotNull();
        assertThat(Class.forName("com.mycorp.distributedlock.api.exception.UnsupportedLockCapabilityException")).isNotNull();
    }
}
```

- [ ] **Step 2: Run the API tests to verify they fail**

Run: `mvn -q -pl distributed-lock-api test -Dtest=ApiSurfaceTest`

Expected: FAIL with `ClassNotFoundException` or method lookup failures for the new API types.

- [ ] **Step 3: Replace the old public contracts with the new ones**

Write these files:

```java
package com.mycorp.distributedlock.api;

public interface LockClient extends AutoCloseable {
    LockSession openSession(SessionRequest request);
}
```

```java
package com.mycorp.distributedlock.api;

public interface LockSession extends AutoCloseable {
    LockLease acquire(LockRequest request) throws InterruptedException;
    SessionState state();
}
```

```java
package com.mycorp.distributedlock.api;

public interface LockLease extends AutoCloseable {
    LockKey key();
    LockMode mode();
    FencingToken fencingToken();
    LeaseState state();
    boolean isValid();
    void release();

    @Override
    default void close() {
        release();
    }
}
```

```java
package com.mycorp.distributedlock.api;

@FunctionalInterface
public interface LockExecutor {
    <T> T withLock(LockRequest request, LockedSupplier<T> action) throws Exception;
}
```

```java
package com.mycorp.distributedlock.api;

@FunctionalInterface
public interface LockedSupplier<T> {
    T get() throws Exception;
}
```

```java
package com.mycorp.distributedlock.api;

public record FencingToken(long value) {
    public FencingToken {
        if (value <= 0) {
            throw new IllegalArgumentException("fencing token must be positive");
        }
    }
}
```

```java
package com.mycorp.distributedlock.api;

public record LockKey(String value) {
    public LockKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("lock key cannot be blank");
        }
    }
}
```

```java
package com.mycorp.distributedlock.api;

public record LockRequest(
    LockKey key,
    LockMode mode,
    WaitPolicy waitPolicy,
    LeasePolicy leasePolicy
) {}
```

```java
package com.mycorp.distributedlock.api;

public record SessionRequest(SessionPolicy sessionPolicy) {}
```

```java
package com.mycorp.distributedlock.api;

public enum LockMode {
    MUTEX,
    READ,
    WRITE
}
```

```java
package com.mycorp.distributedlock.api;

import java.time.Duration;

public record WaitPolicy(Duration waitTime, boolean unbounded) {
    public static WaitPolicy indefinite() {
        return new WaitPolicy(Duration.ZERO, true);
    }

    public static WaitPolicy timed(Duration waitTime) {
        return new WaitPolicy(waitTime, false);
    }
}
```

```java
package com.mycorp.distributedlock.api;

public record LockCapabilities(
    boolean mutexSupported,
    boolean readWriteSupported,
    boolean fencingSupported,
    boolean renewableSessionsSupported
) {}
```

```java
package com.mycorp.distributedlock.api.exception;

public class LockSessionLostException extends RuntimeException {
    public LockSessionLostException(String message) {
        super(message);
    }
}
```

```java
package com.mycorp.distributedlock.api.exception;

public class UnsupportedLockCapabilityException extends RuntimeException {
    public UnsupportedLockCapabilityException(String message) {
        super(message);
    }
}
```

Also add minimal enums/records for `LeasePolicy`, `SessionPolicy`, `LeaseState`, `SessionState`, and update `LockMode`/`WaitPolicy` imports so the API module, not `core`, owns the public request model.

- [ ] **Step 4: Run the API tests to verify they pass**

Run: `mvn -q -pl distributed-lock-api test -Dtest=ApiSurfaceTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add distributed-lock-api
git commit -m "feat: replace public lock api with lease session model"
```

## Task 2: Rebuild the Core and Runtime Around `LockClient`

**Files:**
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/LockBackend.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/BackendLockLease.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/BackendSession.java`
- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/LockMode.java`
- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/WaitPolicy.java`
- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/LockResource.java`
- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/SupportedLockModes.java`
- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultLockManager.java`
- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultMutexLock.java`
- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultReadWriteLock.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockClient.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockSession.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockExecutor.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/LockRequestValidator.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntime.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/DefaultLockRuntime.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendModule.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendCapabilities.java`
- Modify: `distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java`
- Create: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockClientTest.java`
- Create: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockExecutorTest.java`

- [ ] **Step 1: Write failing core/runtime tests for sessions, capabilities, and scoped execution**

Add tests like:

```java
class DefaultLockClientTest {

    @Test
    void sessionShouldAcquireLeaseWithoutUsingThreadOwnership() throws Exception {
        StubBackend backend = new StubBackend();
        LockClient client = new DefaultLockClient(backend);

        try (LockSession session = client.openSession(new SessionRequest(SessionPolicy.defaultPolicy()));
             LockLease lease = session.acquire(sampleRequest())) {
            assertThat(lease.fencingToken().value()).isEqualTo(1L);
            assertThat(lease.isValid()).isTrue();
        }
    }
}
```

```java
class DefaultLockExecutorTest {

    @Test
    void withLockShouldReleaseLeaseAfterAction() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        LockExecutor executor = new DefaultLockExecutor(new DefaultLockClient(backend));

        String result = executor.withLock(sampleRequest(), () -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(backend.releaseCount()).isEqualTo(1);
    }
}
```

Update `LockRuntimeBuilderTest` to assert `runtime.lockClient()` and `runtime.lockExecutor()` rather than `lockManager()`.

- [ ] **Step 2: Run the focused tests to verify they fail**

Run: `mvn -q -pl distributed-lock-core,distributed-lock-runtime -am test -Dtest=DefaultLockClientTest,DefaultLockExecutorTest,LockRuntimeBuilderTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because `DefaultLockClient`, `BackendSession`, and the new runtime surface do not exist.

- [ ] **Step 3: Implement the session-oriented backend/core/runtime contracts**

Use these shapes:

```java
package com.mycorp.distributedlock.core.backend;

import com.mycorp.distributedlock.api.LockCapabilities;
import com.mycorp.distributedlock.api.SessionRequest;

public interface LockBackend extends AutoCloseable {
    LockCapabilities capabilities();
    BackendSession openSession(SessionRequest request);
}
```

```java
package com.mycorp.distributedlock.core.backend;

import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;

public interface BackendSession extends AutoCloseable {
    BackendLockLease acquire(LockRequest request) throws InterruptedException;
    SessionState state();
}
```

```java
package com.mycorp.distributedlock.core.client;

public final class DefaultLockClient implements LockClient {
    private final LockBackend backend;

    public DefaultLockClient(LockBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public LockSession openSession(SessionRequest request) {
        return new DefaultLockSession(backend, backend.openSession(request));
    }

    @Override
    public void close() throws Exception {
        backend.close();
    }
}
```

```java
package com.mycorp.distributedlock.core.client;

public final class DefaultLockExecutor implements LockExecutor {
    private final LockClient client;
    private final SessionRequest sessionRequest;

    public DefaultLockExecutor(LockClient client, SessionRequest sessionRequest) {
        this.client = client;
        this.sessionRequest = sessionRequest;
    }

    @Override
    public <T> T withLock(LockRequest request, LockedSupplier<T> action) throws Exception {
        try (LockSession session = client.openSession(sessionRequest);
             LockLease lease = session.acquire(request)) {
            return action.get();
        }
    }
}
```

Update `LockRuntime` to:

```java
public interface LockRuntime extends AutoCloseable {
    LockClient lockClient();
    LockExecutor lockExecutor();
}
```

Update `LockRuntimeBuilder` to select a `BackendModule`, call `createBackend()`, and expose `DefaultLockClient` plus a default `DefaultLockExecutor`.

- [ ] **Step 4: Run the focused tests to verify they pass**

Run: `mvn -q -pl distributed-lock-core,distributed-lock-runtime -am test -Dtest=DefaultLockClientTest,DefaultLockExecutorTest,LockRuntimeBuilderTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add distributed-lock-core distributed-lock-runtime
git commit -m "refactor: rebuild core runtime around lock client sessions"
```

## Task 3: Rewrite the Shared Contract Suite Around Leases and Fencing

**Files:**
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockManagerContract.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryLockBackend.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryBackendModule.java`
- Create: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryBackendSession.java`
- Create: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/FencedResource.java`
- Modify: `distributed-lock-testkit/src/test/java/com/mycorp/distributedlock/testkit/InMemoryLockManagerContractTest.java`

- [ ] **Step 1: Write failing shared contract tests**

Replace the old contract with tests like:

```java
public abstract class LockClientContract {

    protected abstract LockRuntime createRuntime() throws Exception;

    @Test
    void fencingTokenShouldIncreaseAcrossSequentialLeases() throws Exception {
        try (LockRuntime runtime = createRuntime();
             LockSession session = runtime.lockClient().openSession(new SessionRequest(SessionPolicy.defaultPolicy()))) {
            long first;
            try (LockLease lease = session.acquire(sampleRequest("inventory:1"))) {
                first = lease.fencingToken().value();
            }
            try (LockLease lease = session.acquire(sampleRequest("inventory:1"))) {
                assertThat(lease.fencingToken().value()).isGreaterThan(first);
            }
        }
    }
}
```

```java
@Test
void staleTokenShouldBeRejectedByGuardedResource() {
    FencedResource resource = new FencedResource();
    resource.write(new FencingToken(2L), "new");
    assertThatThrownBy(() -> resource.write(new FencingToken(1L), "old"))
        .isInstanceOf(IllegalStateException.class);
}
```

- [ ] **Step 2: Run the contract tests to verify they fail**

Run: `mvn -q -pl distributed-lock-testkit -am test -Dtest=InMemoryLockClientContractTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because the in-memory backend does not expose sessions or fencing tokens.

- [ ] **Step 3: Implement the in-memory session and fencing test backend**

Use a monotonic counter per key:

```java
final class InMemoryLockState {
    final ReentrantLock mutex = new ReentrantLock();
    final ReentrantReadWriteLock readWrite = new ReentrantReadWriteLock();
    final AtomicLong fencingCounter = new AtomicLong();
}
```

Return leases like:

```java
private record InMemoryLease(
    LockKey key,
    LockMode mode,
    FencingToken fencingToken,
    InMemoryLockState state,
    AtomicBoolean released
) implements BackendLockLease {

    @Override
    public boolean isValid() {
        return !released.get();
    }

    @Override
    public void release() {
        if (released.compareAndSet(false, true)) {
            unlockState(state, mode);
        }
    }
}
```

Add `FencedResource`:

```java
public final class FencedResource {
    private final AtomicLong latestToken = new AtomicLong();

    public void write(FencingToken token, String value) {
        long previous = latestToken.get();
        if (token.value() <= previous) {
            throw new IllegalStateException("stale fencing token");
        }
        latestToken.set(token.value());
    }
}
```

- [ ] **Step 4: Run the contract tests to verify they pass**

Run: `mvn -q -pl distributed-lock-testkit -am test -Dtest=InMemoryLockClientContractTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add distributed-lock-testkit
git commit -m "test: replace shared contract suite with lease fencing checks"
```

## Task 4: Rebuild Redis as a Renewable Session + Fenced Lease Backend

**Files:**
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendConfiguration.java`
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendModule.java`
- Rewrite: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisBackendModuleTest.java`
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisLockBackendContractTest.java`
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisLeaseRenewalTest.java`
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisOwnershipLossTest.java`

- [ ] **Step 1: Write failing Redis tests for sessions and fencing**

Add tests that assert:

```java
@Test
void redisShouldIssueMonotonicFencingTokens() throws Exception {
    try (LockRuntime runtime = createRuntime()) {
        long first;
        try (LockSession session = runtime.lockClient().openSession(defaultSession());
             LockLease lease = session.acquire(sampleRequest("redis:fence"))) {
            first = lease.fencingToken().value();
        }
        try (LockSession session = runtime.lockClient().openSession(defaultSession());
             LockLease lease = session.acquire(sampleRequest("redis:fence"))) {
            assertThat(lease.fencingToken().value()).isGreaterThan(first);
        }
    }
}
```

```java
@Test
void redisShouldInvalidateLeaseAfterTokenDeletion() throws Exception {
    // acquire lease, delete redis owner key, assert lease.isValid() becomes false
}
```

- [ ] **Step 2: Run the Redis tests to verify they fail**

Run: `mvn -q -pl distributed-lock-redis -am test -Dtest=RedisBackendModuleTest,RedisLockBackendContractTest,RedisLeaseRenewalTest,RedisOwnershipLossTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because Redis still implements thread-bound leases and has no fencing token counter.

- [ ] **Step 3: Implement Redis sessions and fencing**

Use Redis structures:

- owner key: `lock:{key}:{mode}:owner`
- session key: `session:{sessionId}`
- fencing key: `lock:{key}:{mode}:fence`

Core shapes:

```java
public final class RedisLockBackend implements LockBackend {
    @Override
    public LockCapabilities capabilities() {
        return new LockCapabilities(true, true, true, true);
    }

    @Override
    public BackendSession openSession(SessionRequest request) {
        return new RedisBackendSession(nextSessionId(), request.sessionPolicy());
    }
}
```

```java
private final class RedisBackendSession implements BackendSession {
    @Override
    public BackendLockLease acquire(LockRequest request) throws InterruptedException {
        // claim lock with Lua, increment fencing counter on success, schedule session renewal
    }
}
```

```java
private final class RedisLease implements BackendLockLease {
    @Override
    public FencingToken fencingToken() {
        return fencingToken;
    }

    @Override
    public boolean isValid() {
        return sessionValid.get() && ownerTokenMatchesRedis();
    }
}
```

Use Lua to atomically:

1. check owner absence or compatible read owner state
2. increment fence counter with `INCR`
3. write owner/session token with expiry
4. return fencing token

- [ ] **Step 4: Run the Redis tests to verify they pass**

Run: `mvn -q -pl distributed-lock-redis -am test -Dtest=RedisBackendModuleTest,RedisLockBackendContractTest,RedisLeaseRenewalTest,RedisOwnershipLossTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add distributed-lock-redis
git commit -m "feat: add redis session and fencing lock backend"
```

## Task 5: Rebuild ZooKeeper as a Session + Fenced Lease Backend

**Files:**
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendConfiguration.java`
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModule.java`
- Rewrite: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`
- Modify: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModuleTest.java`
- Modify: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackendContractTest.java`
- Modify: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperSessionLossTest.java`

- [ ] **Step 1: Write failing ZooKeeper tests for fencing and session loss**

Add tests that assert:

```java
@Test
void zookeeperShouldIssueMonotonicFencingTokens() throws Exception {
    // acquire, release, reacquire, assert token increases
}
```

```java
@Test
void zookeeperLeaseShouldBecomeInvalidAfterSessionLoss() throws Exception {
    // invalidate Curator session seam, assert lease.isValid() is false
}
```

- [ ] **Step 2: Run the ZooKeeper tests to verify they fail**

Run: `mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperBackendModuleTest,ZooKeeperLockBackendContractTest,ZooKeeperSessionLossTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because ZooKeeper currently has no fencing token support and still models ownership around the current thread.

- [ ] **Step 3: Implement ZooKeeper session-backed fenced leases**

Use one monotonically increasing persistent sequence per lock domain:

```java
private long nextFence(String key, LockMode mode) throws Exception {
    String counterPath = fenceCounterPath(key, mode);
    ensurePersistentCounter(counterPath);
    byte[] updated = curatorFramework.setData()
        .forPath(counterPath, nextCounterValue(counterPath));
    return bytesToLong(updated);
}
```

Represent ownership with ephemeral nodes tied to the Curator session and include the fence in node data.

Lease validity:

```java
@Override
public boolean isValid() {
    return sessionValidSupplier.getAsBoolean() && ownerNodeStillBelongsToSession();
}
```

- [ ] **Step 4: Run the ZooKeeper tests to verify they pass**

Run: `mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperBackendModuleTest,ZooKeeperLockBackendContractTest,ZooKeeperSessionLossTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add distributed-lock-zookeeper
git commit -m "feat: add zookeeper session and fencing lock backend"
```

## Task 6: Move Spring to Scoped Execution on `LockExecutor`

**Files:**
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/annotation/DistributedLock.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/annotation/DistributedLockMode.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockAspect.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockAutoConfiguration.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockProperties.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/key/LockKeyResolver.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/key/SpelLockKeyResolver.java`
- Modify: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAutoConfigurationIntegrationTest.java`
- Modify: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAspectIntegrationTest.java`
- Modify: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAsyncGuardTest.java`

- [ ] **Step 1: Write failing Spring tests for `LockClient`/`LockExecutor` exposure and scoped execution**

Replace assertions with:

```java
assertThat(context).hasSingleBean(LockClient.class);
assertThat(context).hasSingleBean(LockExecutor.class);
assertThat(context).doesNotHaveBean(LockManager.class);
```

Add an aspect test that verifies:

```java
@DistributedLock(key = "order:#{#p0}", mode = DistributedLockMode.MUTEX)
public String process(String orderId) {
    return guardedResource.writeAndReturn(orderId);
}
```

and assert the guarded resource sees a positive fencing token during invocation.

- [ ] **Step 2: Run the Spring starter tests to verify they fail**

Run: `mvn -q -pl distributed-lock-spring-boot-starter -am test -Dtest=DistributedLockAutoConfigurationIntegrationTest,DistributedLockAspectIntegrationTest,DistributedLockAsyncGuardTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because the starter still exposes `LockManager` and the aspect still uses `MutexLock`.

- [ ] **Step 3: Rebuild the Spring starter around `LockExecutor`**

Use:

```java
@AutoConfiguration
public class DistributedLockAutoConfiguration {

    @Bean(destroyMethod = "close")
    public LockRuntime lockRuntime(DistributedLockProperties properties, ObjectProvider<BackendModule> modules) {
        return LockRuntimeBuilder.create()
            .backend(properties.getBackend())
            .backendModules(modules.orderedStream().toList())
            .build();
    }

    @Bean
    public LockClient lockClient(LockRuntime runtime) {
        return runtime.lockClient();
    }

    @Bean
    public LockExecutor lockExecutor(LockRuntime runtime) {
        return runtime.lockExecutor();
    }
}
```

Aspect shape:

```java
@Around("@annotation(distributedLock)")
public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
    LockRequest request = requestFactory.create(joinPoint, distributedLock);
    return lockExecutor.withLock(request, joinPoint::proceed);
}
```

Keep async rejection in this first pass for Spring AOP methods returning `CompletionStage` or reactive publishers until a dedicated async Spring integration exists.

- [ ] **Step 4: Run the Spring starter tests to verify they pass**

Run: `mvn -q -pl distributed-lock-spring-boot-starter -am test -Dtest=DistributedLockAutoConfigurationIntegrationTest,DistributedLockAspectIntegrationTest,DistributedLockAsyncGuardTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add distributed-lock-spring-boot-starter
git commit -m "refactor: move spring starter to scoped lock execution"
```

## Task 7: Update Backend-Specific Spring Auto-Configuration and Integration Tests

**Files:**
- Modify: `distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockAutoConfiguration.java`
- Modify: `distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockProperties.java`
- Modify: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/zookeeper/springboot/config/ZooKeeperDistributedLockAutoConfiguration.java`
- Modify: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/zookeeper/springboot/config/ZooKeeperDistributedLockProperties.java`
- Modify: `distributed-lock-redis-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/redis/springboot/integration/RedisBackendModuleAutoConfigurationTest.java`
- Modify: `distributed-lock-redis-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/redis/springboot/integration/RedisStarterIntegrationTest.java`
- Modify: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/zookeeper/springboot/integration/ZooKeeperBackendModuleAutoConfigurationTest.java`
- Modify: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/zookeeper/springboot/integration/ZooKeeperStarterIntegrationTest.java`

- [ ] **Step 1: Write failing backend Spring tests for the new runtime beans**

In the starter integration tests, replace `LockManager` use with:

```java
LockExecutor executor = context.getBean(LockExecutor.class);
String result = executor.withLock(sampleRequest("redis-starter-test"), () -> "ok");
assertThat(result).isEqualTo("ok");
```

Also assert the runtime exposes `LockClient`.

- [ ] **Step 2: Run the backend Spring tests to verify they fail**

Run: `mvn -q -pl distributed-lock-redis-spring-boot-autoconfigure,distributed-lock-zookeeper-spring-boot-autoconfigure -am test -Dtest=RedisBackendModuleAutoConfigurationTest,RedisStarterIntegrationTest,ZooKeeperBackendModuleAutoConfigurationTest,ZooKeeperStarterIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because the integration tests and bean graph still assume `LockManager`.

- [ ] **Step 3: Update backend Spring modules to build session-capable backends**

Keep the modules thin:

```java
@Bean
BackendModule redisBackendModule(RedisDistributedLockProperties properties) {
    return new RedisBackendModule(new RedisBackendConfiguration(
        properties.getUri(),
        toLeaseSeconds(properties.getLeaseTime())
    ));
}
```

No generic starter changes here beyond the bean type expectations; the main work is migrating tests and ensuring the new runtime surface wires through cleanly.

- [ ] **Step 4: Run the backend Spring tests to verify they pass**

Run: `mvn -q -pl distributed-lock-redis-spring-boot-autoconfigure,distributed-lock-zookeeper-spring-boot-autoconfigure -am test -Dtest=RedisBackendModuleAutoConfigurationTest,RedisStarterIntegrationTest,ZooKeeperBackendModuleAutoConfigurationTest,ZooKeeperStarterIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add distributed-lock-redis-spring-boot-autoconfigure distributed-lock-zookeeper-spring-boot-autoconfigure
git commit -m "test: migrate backend spring integrations to lock executor"
```

## Task 8: Migrate Examples, Benchmarks, and Documentation

**Files:**
- Modify: `distributed-lock-examples/pom.xml`
- Modify: `distributed-lock-examples/README.md`
- Modify: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/ProgrammaticRedisExample.java`
- Modify: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/ProgrammaticZooKeeperExample.java`
- Modify: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/spring/SpringBootRedisExampleApplication.java`
- Modify: `distributed-lock-examples/src/main/resources/application.yml`
- Modify: `distributed-lock-benchmarks/pom.xml`
- Modify: `distributed-lock-benchmarks/README.md`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/MutexContentionBenchmark.java`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/MutexLifecycleBenchmark.java`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/ReadWriteLockBenchmark.java`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/RuntimeLifecycleBenchmark.java`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/SpringStarterBenchmark.java`
- Modify: benchmark support classes under `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support`
- Modify: `distributed-lock-benchmarks/src/test/java/com/mycorp/distributedlock/benchmarks/BenchmarkEnvironmentSmokeTest.java`
- Modify: `distributed-lock-spring-boot-starter/README.md`
- Modify: `distributed-lock-test-suite/README.md`
- Modify: `distributed-lock-test-suite/TEST-CONFIGURATION.md`

- [ ] **Step 1: Write failing example and benchmark smoke checks**

Run:

```bash
mvn -q -pl distributed-lock-examples -am -DskipTests compile
mvn -q -f distributed-lock-benchmarks/pom.xml test -Dtest=BenchmarkEnvironmentSmokeTest
```

Expected: FAIL because the examples and benchmark helpers still compile against `LockManager`/`MutexLock`.

- [ ] **Step 2: Rewrite the examples to use `LockClient` and `LockExecutor`**

Programmatic example shape:

```java
try (LockRuntime runtime = LockRuntimeBuilder.create()
    .backend("redis")
    .backendModules(List.of(new RedisBackendModule(new RedisBackendConfiguration("redis://127.0.0.1:6379", 30L))))
    .build()) {
    String result = runtime.lockExecutor().withLock(
        new LockRequest(new LockKey("example:redis:inventory-42"), LockMode.MUTEX, WaitPolicy.timed(Duration.ofSeconds(2)), LeasePolicy.defaultPolicy()),
        () -> "Redis lease acquired"
    );
    System.out.println(result);
}
```

Spring example should inject `LockExecutor` and demonstrate scoped execution rather than exposing `MutexLock`.

- [ ] **Step 3: Rewrite the benchmarks to measure lease/scoped execution**

Benchmark hot path shape:

```java
@Benchmark
public String redisMutexLifecycle() throws Exception {
    return redisRuntime.lockExecutor().withLock(redisMutexRequest, () -> BenchmarkWorkloads.smallCriticalSection());
}
```

For contention tests that need manual coordination, use explicit `LockSession` + `LockLease`.

- [ ] **Step 4: Update docs and test-suite README**

Revise all README snippets to use:

- `LockRuntime.lockClient()`
- `LockRuntime.lockExecutor()`
- `LockSession.acquire(...)`
- `LockLease.fencingToken()`

Update `distributed-lock-test-suite/README.md` to list the new contract and integration tests that replace the `LockManager`-based suite.

- [ ] **Step 5: Run compile and smoke checks to verify they pass**

Run:

```bash
mvn -q -pl distributed-lock-examples -am -DskipTests compile
mvn -q -f distributed-lock-benchmarks/pom.xml test -Dtest=BenchmarkEnvironmentSmokeTest
```

Expected: PASS.

- [ ] **Step 6: Run the full regression**

Run: `mvn test -q`

Expected: PASS across the reactor.

- [ ] **Step 7: Commit**

```bash
git add distributed-lock-examples distributed-lock-benchmarks distributed-lock-spring-boot-starter/README.md distributed-lock-test-suite
git commit -m "docs: migrate examples benchmarks and docs to lease api"
```
