# Distributed Lock API Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the distributed lock public API around explicit synchronous execution, lock context, request-level lease policy, try-once waiting, exception hierarchy, and non-reentrant semantics.

**Architecture:** The public API changes land first so compile failures identify every call site. Core owns synchronous lock-context binding, non-reentrant session tracking, and request validation. Runtime, Spring, observability, Redis, ZooKeeper, examples, benchmarks, and docs then migrate to the new API, with Redis supporting fixed request leases and ZooKeeper rejecting them before remote state is created.

**Tech Stack:** Java 17, Maven reactor, JUnit 5, AssertJ, Mockito, Spring Boot 3.2, Lettuce Redis, Apache Curator ZooKeeper.

---

## File Map

- Modify: `distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/ApiSurfaceTest.java`
  Locks the new API surface and removed types.
- Delete: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockExecutor.java`
- Delete: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockedSupplier.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/SynchronousLockExecutor.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockedAction.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockContext.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/WaitMode.java`
- Modify: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/WaitPolicy.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LeaseMode.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LeasePolicy.java`
- Modify: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockRequest.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/DistributedLockException.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/LockReentryException.java`
- Modify: all existing files in `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/`
  Make operational exceptions extend `DistributedLockException`.
- Rename: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockExecutor.java`
  to `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultSynchronousLockExecutor.java`.
- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/CurrentLockContext.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockSession.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/SessionBoundLockLease.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/LockRequestValidator.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/SupportedLockModes.java`
- Rename: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockExecutorTest.java`
  to `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultSynchronousLockExecutorTest.java`.
- Modify: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockSessionTest.java`
- Modify: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockClientTest.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntime.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/DefaultLockRuntime.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendCapabilities.java`
- Modify: `distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockClientContract.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryBackendModule.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryLockBackend.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/BlockingLeaseBackend.java`
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendModule.java`
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisServiceLoaderBackendModule.java`
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisLockBackendContractTest.java`
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisReadLockExpirationTest.java`
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisLeaseRenewalTest.java`
- Add tests in `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/` for fixed request lease behavior.
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModule.java`
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperServiceLoaderBackendModule.java`
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`
- Add tests in `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/` for fixed-lease rejection and try-once cleanup.
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/annotation/DistributedLock.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockAspect.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockAutoConfiguration.java`
- Modify: Spring integration tests under `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/`
- Modify: observability decorators and tests under `distributed-lock-extension-observability/`
- Modify: observability Spring tests under `distributed-lock-extension-observability-spring/`
- Modify: examples, benchmarks, `distributed-lock-spring-boot-starter/README.md`, `distributed-lock-examples/README.md`, `distributed-lock-test-suite/README.md`, and `distributed-lock-test-suite/TEST-CONFIGURATION.md`.

## Task 1: Public API Surface

**Files:**
- Modify: `distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/ApiSurfaceTest.java`
- Delete: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockExecutor.java`
- Delete: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockedSupplier.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/SynchronousLockExecutor.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockedAction.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockContext.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/WaitMode.java`
- Modify: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/WaitPolicy.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LeaseMode.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LeasePolicy.java`
- Modify: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockRequest.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/DistributedLockException.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/LockReentryException.java`
- Modify: existing exception classes in `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/`

- [ ] **Step 1: Replace API surface assertions with failing target assertions**

Rewrite `ApiSurfaceTest` around the hardened public API. Keep the existing style of reflection-based assertions, but assert these exact targets:

```java
assertThatThrownBy(() -> Class.forName("com.mycorp.distributedlock.api.LockExecutor"))
    .isInstanceOf(ClassNotFoundException.class);
assertThatThrownBy(() -> Class.forName("com.mycorp.distributedlock.api.LockedSupplier"))
    .isInstanceOf(ClassNotFoundException.class);
assertThat(SynchronousLockExecutor.class.getMethod("withLock", LockRequest.class, LockedAction.class).getReturnType())
    .isEqualTo(Object.class);
assertThat(LockedAction.class.getMethod("execute", LockLease.class).getExceptionTypes())
    .containsExactly(Exception.class);
assertThat(WaitMode.values()).containsExactly(WaitMode.TRY_ONCE, WaitMode.TIMED, WaitMode.INDEFINITE);
assertThat(LeaseMode.values()).containsExactly(LeaseMode.BACKEND_DEFAULT, LeaseMode.FIXED);
```

Add record component assertions:

```java
assertThat(Arrays.stream(LockRequest.class.getRecordComponents()).map(RecordComponent::getName))
    .containsExactly("key", "mode", "waitPolicy", "leasePolicy");
assertThat(new LockRequest(new LockKey("orders"), LockMode.MUTEX, WaitPolicy.tryOnce()).leasePolicy())
    .isEqualTo(LeasePolicy.backendDefault());
```

Add exception hierarchy assertions:

```java
assertThat(LockAcquisitionTimeoutException.class.getSuperclass()).isEqualTo(DistributedLockException.class);
assertThat(LockBackendException.class.getSuperclass()).isEqualTo(DistributedLockException.class);
assertThat(LockConfigurationException.class.getSuperclass()).isEqualTo(DistributedLockException.class);
assertThat(LockOwnershipLostException.class.getSuperclass()).isEqualTo(DistributedLockException.class);
assertThat(LockSessionLostException.class.getSuperclass()).isEqualTo(DistributedLockException.class);
assertThat(UnsupportedLockCapabilityException.class.getSuperclass()).isEqualTo(DistributedLockException.class);
assertThat(LockReentryException.class.getSuperclass()).isEqualTo(DistributedLockException.class);
```

Add value validation assertions:

```java
assertThat(WaitPolicy.tryOnce()).isEqualTo(new WaitPolicy(WaitMode.TRY_ONCE, Duration.ZERO));
assertThat(WaitPolicy.indefinite()).isEqualTo(new WaitPolicy(WaitMode.INDEFINITE, Duration.ZERO));
assertThatThrownBy(() -> WaitPolicy.timed(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
assertThatThrownBy(() -> new WaitPolicy(WaitMode.TIMED, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
assertThat(LeasePolicy.backendDefault()).isEqualTo(new LeasePolicy(LeaseMode.BACKEND_DEFAULT, Duration.ZERO));
assertThatThrownBy(() -> LeasePolicy.fixed(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
assertThatThrownBy(() -> new LeasePolicy(LeaseMode.BACKEND_DEFAULT, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
```

- [ ] **Step 2: Run API tests to verify failure**

Run:

```bash
mvn -q -pl distributed-lock-api test -Dtest=ApiSurfaceTest
```

Expected: FAIL because the new API types do not exist and the old executor types still exist.

- [ ] **Step 3: Create new API types and remove old executor types**

Delete:

```bash
git rm distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockExecutor.java
git rm distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockedSupplier.java
```

Create `SynchronousLockExecutor.java`:

```java
package com.mycorp.distributedlock.api;

@FunctionalInterface
public interface SynchronousLockExecutor {

    <T> T withLock(LockRequest request, LockedAction<T> action) throws Exception;
}
```

Create `LockedAction.java`:

```java
package com.mycorp.distributedlock.api;

@FunctionalInterface
public interface LockedAction<T> {

    T execute(LockLease lease) throws Exception;
}
```

Create `LockContext.java`:

```java
package com.mycorp.distributedlock.api;

import java.util.Objects;
import java.util.Optional;

public final class LockContext {

    private static final ThreadLocal<Frame> CURRENT_FRAME = new ThreadLocal<>();

    private LockContext() {
    }

    public static Optional<LockLease> currentLease() {
        return Optional.ofNullable(CURRENT_FRAME.get())
            .map(Frame::lease);
    }

    public static Optional<FencingToken> currentFencingToken() {
        return currentLease().map(LockLease::fencingToken);
    }

    public static boolean containsLease(LockKey key) {
        Objects.requireNonNull(key, "key");
        Frame frame = CURRENT_FRAME.get();
        while (frame != null) {
            if (frame.lease().key().equals(key)) {
                return true;
            }
            frame = frame.previous();
        }
        return false;
    }

    public static LockLease requireCurrentLease() {
        return currentLease()
            .orElseThrow(() -> new IllegalStateException("No lock lease is bound to the current thread"));
    }

    public static FencingToken requireCurrentFencingToken() {
        return currentFencingToken()
            .orElseThrow(() -> new IllegalStateException("No fencing token is bound to the current thread"));
    }

    public static Binding bind(LockLease lease) {
        Objects.requireNonNull(lease, "lease");
        Frame frame = new Frame(lease, CURRENT_FRAME.get());
        CURRENT_FRAME.set(frame);
        return new Binding(frame);
    }

    public static final class Binding implements AutoCloseable {
        private final Frame frame;
        private boolean closed;

        private Binding(Frame frame) {
            this.frame = frame;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (CURRENT_FRAME.get() != frame) {
                throw new IllegalStateException("Lock context bindings must be closed in LIFO order");
            }
            closed = true;
            if (frame.previous() == null) {
                CURRENT_FRAME.remove();
                return;
            }
            CURRENT_FRAME.set(frame.previous());
        }
    }

    private record Frame(LockLease lease, Frame previous) {
    }
}
```

- [ ] **Step 4: Replace wait and lease value objects**

Create `WaitMode.java`:

```java
package com.mycorp.distributedlock.api;

public enum WaitMode {
    TRY_ONCE,
    TIMED,
    INDEFINITE
}
```

Replace `WaitPolicy.java` with:

```java
package com.mycorp.distributedlock.api;

import java.time.Duration;

public record WaitPolicy(WaitMode mode, Duration timeout) {

    public WaitPolicy {
        if (mode == null) {
            throw new IllegalArgumentException("Wait mode is required");
        }
        if (timeout == null) {
            throw new IllegalArgumentException("Wait timeout is required");
        }
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Wait timeout must not be negative");
        }
        if (mode == WaitMode.TIMED && timeout.isZero()) {
            throw new IllegalArgumentException("Timed wait policy requires a positive timeout");
        }
        if (mode != WaitMode.TIMED && !timeout.isZero()) {
            throw new IllegalArgumentException("Non-timed wait policy must use Duration.ZERO");
        }
    }

    public static WaitPolicy tryOnce() {
        return new WaitPolicy(WaitMode.TRY_ONCE, Duration.ZERO);
    }

    public static WaitPolicy timed(Duration timeout) {
        if (timeout == null) {
            throw new IllegalArgumentException("Wait timeout is required");
        }
        return new WaitPolicy(WaitMode.TIMED, timeout);
    }

    public static WaitPolicy indefinite() {
        return new WaitPolicy(WaitMode.INDEFINITE, Duration.ZERO);
    }
}
```

Create `LeaseMode.java`:

```java
package com.mycorp.distributedlock.api;

public enum LeaseMode {
    BACKEND_DEFAULT,
    FIXED
}
```

Create `LeasePolicy.java`:

```java
package com.mycorp.distributedlock.api;

import java.time.Duration;

public record LeasePolicy(LeaseMode mode, Duration duration) {

    public LeasePolicy {
        if (mode == null) {
            throw new IllegalArgumentException("Lease mode is required");
        }
        if (duration == null) {
            throw new IllegalArgumentException("Lease duration is required");
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Lease duration must not be negative");
        }
        if (mode == LeaseMode.FIXED && duration.isZero()) {
            throw new IllegalArgumentException("Fixed lease policy requires a positive duration");
        }
        if (mode == LeaseMode.BACKEND_DEFAULT && !duration.isZero()) {
            throw new IllegalArgumentException("Backend-default lease policy must use Duration.ZERO");
        }
    }

    public static LeasePolicy backendDefault() {
        return new LeasePolicy(LeaseMode.BACKEND_DEFAULT, Duration.ZERO);
    }

    public static LeasePolicy fixed(Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException("Lease duration is required");
        }
        return new LeasePolicy(LeaseMode.FIXED, duration);
    }
}
```

Replace `LockRequest.java` with:

```java
package com.mycorp.distributedlock.api;

public record LockRequest(
    LockKey key,
    LockMode mode,
    WaitPolicy waitPolicy,
    LeasePolicy leasePolicy
) {

    public LockRequest(LockKey key, LockMode mode, WaitPolicy waitPolicy) {
        this(key, mode, waitPolicy, LeasePolicy.backendDefault());
    }

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
        if (leasePolicy == null) {
            throw new IllegalArgumentException("Lease policy is required");
        }
    }
}
```

- [ ] **Step 5: Add exception base type and reentry exception**

Create `DistributedLockException.java`:

```java
package com.mycorp.distributedlock.api.exception;

public class DistributedLockException extends RuntimeException {

    public DistributedLockException(String message) {
        super(message);
    }

    public DistributedLockException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

Create `LockReentryException.java`:

```java
package com.mycorp.distributedlock.api.exception;

public class LockReentryException extends DistributedLockException {

    public LockReentryException(String message) {
        super(message);
    }

    public LockReentryException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

Change each existing operational exception to extend `DistributedLockException`. Preserve existing constructors and add missing cause constructors where tests assert them.

- [ ] **Step 6: Run API tests to verify pass**

Run:

```bash
mvn -q -pl distributed-lock-api test -Dtest=ApiSurfaceTest
```

Expected: PASS.

- [ ] **Step 7: Commit API surface**

Run:

```bash
git add distributed-lock-api/src/main/java/com/mycorp/distributedlock/api \
        distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception \
        distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/ApiSurfaceTest
git commit -m "feat: harden distributed lock api surface"
```

## Task 2: Core Executor, Lock Context, and Non-Reentrant Sessions

**Files:**
- Rename: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockExecutor.java` to `DefaultSynchronousLockExecutor.java`
- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/CurrentLockContext.java`
- Rename: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockExecutorTest.java` to `DefaultSynchronousLockExecutorTest.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockSession.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/SessionBoundLockLease.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/LockRequestValidator.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/SupportedLockModes.java`
- Modify: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockSessionTest.java`

- [ ] **Step 1: Write failing core executor tests**

Rename the test class and update tests to use `SynchronousLockExecutor`, `LockedAction`, and `LockContext`.
Add tests with these assertions:

```java
SynchronousLockExecutor executor = new DefaultSynchronousLockExecutor(new DefaultLockClient(
    backend,
    new SupportedLockModes(true, true, true)
));
AtomicReference<LockLease> callbackLease = new AtomicReference<>();
AtomicReference<FencingToken> contextToken = new AtomicReference<>();

String result = executor.withLock(sampleRequest("orders:lease"), lease -> {
    callbackLease.set(lease);
    contextToken.set(LockContext.requireCurrentFencingToken());
    return "ok";
});

assertThat(result).isEqualTo("ok");
assertThat(callbackLease.get()).isNotNull();
assertThat(contextToken.get()).isEqualTo(callbackLease.get().fencingToken());
assertThat(LockContext.currentLease()).isEmpty();
```

Add nested same-key rejection:

```java
assertThatThrownBy(() -> executor.withLock(sampleRequest("orders:nested"), outer ->
    executor.withLock(sampleRequest("orders:nested"), inner -> "nested")
)).isInstanceOf(LockReentryException.class)
  .hasMessageContaining("orders:nested");
```

Keep async-result rejection tests, but change lambdas to receive `lease`:

```java
assertThatThrownBy(() -> executor.withLock(sampleRequest("orders:future"), lease ->
    CompletableFuture.completedFuture("async")
)).isInstanceOf(LockConfigurationException.class)
  .hasMessageContaining("CompletionStage");
```

- [ ] **Step 2: Write failing session reentry and fixed-lease validation tests**

In `DefaultLockSessionTest`, add:

```java
try (LockSession session = client.openSession();
     LockLease ignored = session.acquire(sampleRequest("orders:reentry"))) {
    assertThatThrownBy(() -> session.acquire(sampleRequest("orders:reentry")))
        .isInstanceOf(LockReentryException.class)
        .hasMessageContaining("orders:reentry");
}
```

Add different-key allowed:

```java
try (LockSession session = client.openSession();
     LockLease first = session.acquire(sampleRequest("orders:first"));
     LockLease second = session.acquire(sampleRequest("orders:second"))) {
    assertThat(first.key()).isEqualTo(new LockKey("orders:first"));
    assertThat(second.key()).isEqualTo(new LockKey("orders:second"));
}
```

Add fixed lease capability rejection with `new SupportedLockModes(true, true, false)`:

```java
LockRequest fixedLeaseRequest = new LockRequest(
    new LockKey("orders:fixed"),
    LockMode.MUTEX,
    WaitPolicy.tryOnce(),
    LeasePolicy.fixed(Duration.ofSeconds(5))
);

assertThatThrownBy(() -> session.acquire(fixedLeaseRequest))
    .isInstanceOf(UnsupportedLockCapabilityException.class)
    .hasMessageContaining("fixed lease");
```

- [ ] **Step 3: Run core tests to verify failure**

Run:

```bash
mvn -q -pl distributed-lock-core -am test -Dtest=DefaultSynchronousLockExecutorTest,DefaultLockSessionTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `DefaultSynchronousLockExecutor`, new `SupportedLockModes` constructor, and reentry checks do not exist.

- [ ] **Step 4: Rename and implement default synchronous executor**

Run:

```bash
git mv distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockExecutor.java \
       distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultSynchronousLockExecutor.java
git rm distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/CurrentLockContext.java
```

Replace the class content with:

```java
package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockContext;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.LockedAction;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.api.exception.LockReentryException;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;

public final class DefaultSynchronousLockExecutor implements SynchronousLockExecutor {

    private final LockClient client;

    public DefaultSynchronousLockExecutor(LockClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public <T> T withLock(LockRequest request, LockedAction<T> action) throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(action, "action");
        rejectReentry(request);
        try (LockSession session = client.openSession();
             LockLease lease = session.acquire(request);
             LockContext.Binding ignored = LockContext.bind(lease)) {
            T result = action.execute(lease);
            rejectAsyncResult(result);
            return result;
        }
    }

    private static void rejectReentry(LockRequest request) {
        if (LockContext.containsLease(request.key())) {
            throw new LockReentryException("Lock key is already held in the current synchronous scope: " + request.key().value());
        }
    }

    private static void rejectAsyncResult(Object result) {
        if (result == null) {
            return;
        }
        if (result instanceof CompletionStage<?>) {
            throw new LockConfigurationException(
                "SynchronousLockExecutor only supports synchronous actions, but the action returned CompletionStage"
            );
        }
        if (result instanceof Future<?>) {
            throw new LockConfigurationException(
                "SynchronousLockExecutor only supports synchronous actions, but the action returned Future"
            );
        }
        if (isReactivePublisher(result)) {
            throw new LockConfigurationException(
                "SynchronousLockExecutor only supports synchronous actions, but the action returned Publisher"
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
}
```

- [ ] **Step 5: Add fixed-lease capability to supported modes and validator**

Replace `SupportedLockModes` with a three-boolean record:

```java
package com.mycorp.distributedlock.core.client;

public record SupportedLockModes(
    boolean mutexSupported,
    boolean readWriteSupported,
    boolean fixedLeaseDurationSupported
) {
}
```

Update all current `new SupportedLockModes(true, true)` call sites in core tests to `new SupportedLockModes(true, true, true)` unless the test is explicitly checking fixed-lease rejection.

Update `LockRequestValidator.validate(...)`:

```java
LockMode mode = request.mode();
if (mode == LockMode.MUTEX && !supportedLockModes.mutexSupported()) {
    throw new UnsupportedLockCapabilityException("Backend does not support " + mode + " mode");
}
if ((mode == LockMode.READ || mode == LockMode.WRITE) && !supportedLockModes.readWriteSupported()) {
    throw new UnsupportedLockCapabilityException("Backend does not support " + mode + " mode");
}
if (request.leasePolicy().mode() == LeaseMode.FIXED && !supportedLockModes.fixedLeaseDurationSupported()) {
    throw new UnsupportedLockCapabilityException("Backend does not support fixed lease duration");
}
```

- [ ] **Step 6: Add session key tracking**

In `DefaultLockSession`, add:

```java
private final Set<LockKey> activeLeaseKeys = ConcurrentHashMap.newKeySet();
```

Update acquisition to register by key before calling the backend:

```java
public LockLease acquire(LockRequest request) throws InterruptedException {
    if (closed.get()) {
        throw new IllegalStateException("Lock session is already closed");
    }
    validator.validate(supportedLockModes, request);
    registerKey(request.key());
    boolean leaseCreated = false;
    try {
        BackendLockLease backendLease = backendSession.acquire(request);
        SessionBoundLockLease lease = new SessionBoundLockLease(backendLease, this::forgetLease);
        if (!registerLease(lease)) {
            throw closeLateAcquiredLease(lease);
        }
        leaseCreated = true;
        return lease;
    } finally {
        if (!leaseCreated) {
            activeLeaseKeys.remove(request.key());
        }
    }
}
```

Add:

```java
private void registerKey(LockKey key) {
    if (!activeLeaseKeys.add(key)) {
        throw new LockReentryException("Lock key is already held by this session: " + key.value());
    }
}

private void forgetLease(SessionBoundLockLease lease) {
    activeLeases.remove(lease);
    activeLeaseKeys.remove(lease.key());
}
```

Keep `SessionBoundLockLease` calling `unregister.accept(this)` only on terminal release/loss. No public API changes are needed inside the wrapper.

- [ ] **Step 7: Run core tests to verify pass**

Run:

```bash
mvn -q -pl distributed-lock-core -am test -Dtest=DefaultSynchronousLockExecutorTest,DefaultLockSessionTest,DefaultLockClientTest,LockBackendSurfaceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 8: Commit core migration**

Run:

```bash
git add distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client \
        distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client
git commit -m "feat: add synchronous executor and non-reentrant sessions"
```

## Task 3: Runtime and Capability Metadata

**Files:**
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendCapabilities.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntime.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/DefaultLockRuntime.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java`
- Modify: `distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java`
- Modify backend module classes in Redis, ZooKeeper, and testkit to compile with the new capabilities constructor.

- [ ] **Step 1: Update runtime tests first**

In `LockRuntimeBuilderTest`, change all `runtime.lockExecutor()` assertions and invocations to `runtime.synchronousLockExecutor()` and pass `lease -> "ok"` callbacks.

Change capability constructors:

```java
new BackendCapabilities(true, false, true, true, false)
BackendCapabilities.standard()
```

Add a test proving fixed lease support is not a startup requirement:

```java
try (LockRuntime runtime = LockRuntimeBuilder.create()
    .backend("zookeeper")
    .backendModules(List.of(new StubBackendModule(
        "zookeeper",
        new BackendCapabilities(true, true, true, true, false)
    )))
    .build()) {
    assertThat(runtime.synchronousLockExecutor()).isNotNull();
}
```

- [ ] **Step 2: Run runtime tests to verify failure**

Run:

```bash
mvn -q -pl distributed-lock-runtime -am test -Dtest=LockRuntimeBuilderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because runtime still exposes `lockExecutor()` and `BackendCapabilities` has four fields.

- [ ] **Step 3: Update runtime production code**

Replace `BackendCapabilities` with:

```java
package com.mycorp.distributedlock.runtime.spi;

public record BackendCapabilities(
    boolean mutexSupported,
    boolean readWriteSupported,
    boolean fencingSupported,
    boolean renewableSessionsSupported,
    boolean fixedLeaseDurationSupported
) {

    public static BackendCapabilities standard() {
        return new BackendCapabilities(true, true, true, true, true);
    }
}
```

Replace `LockRuntime` with:

```java
package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;

public interface LockRuntime extends AutoCloseable {

    LockClient lockClient();

    SynchronousLockExecutor synchronousLockExecutor();
}
```

Update `DefaultLockRuntime` fields and constructor to use `SynchronousLockExecutor`.
Update `LockRuntimeBuilder` imports and build method:

```java
SynchronousLockExecutor lockExecutor = new DefaultSynchronousLockExecutor(lockClient);
return new DefaultLockRuntime(lockClient, lockExecutor);
```

Pass all three supported-mode booleans:

```java
SupportedLockModes supportedLockModes = new SupportedLockModes(
    selectedModule.capabilities().mutexSupported(),
    selectedModule.capabilities().readWriteSupported(),
    selectedModule.capabilities().fixedLeaseDurationSupported()
);
```

Do not add `fixedLeaseDurationSupported` to the startup missing-requirements list.

- [ ] **Step 4: Update backend modules for five-field capabilities**

Set capabilities as follows:

```java
// RedisBackendModule and RedisServiceLoaderBackendModule
return BackendCapabilities.standard();

// ZooKeeperBackendModule and ZooKeeperServiceLoaderBackendModule
return new BackendCapabilities(true, true, true, true, false);

// testkit InMemoryBackendModule
return BackendCapabilities.standard();
```

Update all test stub constructors to include the fifth boolean.

- [ ] **Step 5: Run runtime tests to verify pass**

Run:

```bash
mvn -q -pl distributed-lock-runtime -am test -Dtest=LockRuntimeBuilderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 6: Commit runtime migration**

Run:

```bash
git add distributed-lock-runtime \
        distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/*BackendModule.java \
        distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/*BackendModule.java \
        distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryBackendModule.java
git commit -m "feat: expose synchronous runtime executor"
```

## Task 4: Testkit and Shared Contract Semantics

**Files:**
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockClientContract.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryLockBackend.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/BlockingLeaseBackend.java`
- Modify: `distributed-lock-testkit/src/test/java/com/mycorp/distributedlock/testkit/InMemoryLockClientContractTest.java`

- [ ] **Step 1: Update in-memory wait handling for `WaitMode`**

Replace every `waitPolicy.unbounded()` and `waitPolicy.waitTime()` branch in `InMemoryLockBackend` with:

```java
private boolean tryLock(java.util.concurrent.locks.Lock lock, WaitPolicy waitPolicy) throws InterruptedException {
    return switch (waitPolicy.mode()) {
        case TRY_ONCE -> lock.tryLock();
        case TIMED -> lock.tryLock(waitPolicy.timeout().toMillis(), TimeUnit.MILLISECONDS);
        case INDEFINITE -> {
            lock.lockInterruptibly();
            yield true;
        }
    };
}
```

Use this helper for mutex, read, and write acquisition.

Update `BlockingLeaseBackend` sleep logic:

```java
long sleepMillis = switch (lockRequest.waitPolicy().mode()) {
    case TRY_ONCE -> 0L;
    case TIMED -> lockRequest.waitPolicy().timeout().toMillis();
    case INDEFINITE -> 5_000L;
};
Thread.sleep(sleepMillis);
```

- [ ] **Step 2: Extend shared contract tests**

In `LockClientContract`, add:

```java
@Test
void tryOnceShouldFailImmediatelyWhenKeyIsHeld() throws Exception {
    runtime = createRuntime();
    try (LockSession holder = runtime.lockClient().openSession();
         LockLease ignored = holder.acquire(request("inventory:try-once", LockMode.MUTEX, WaitPolicy.indefinite()))) {
        long startedNanos = System.nanoTime();
        assertThatThrownBy(() -> tryAcquire("inventory:try-once", LockMode.MUTEX, WaitPolicy.tryOnce()))
            .isInstanceOf(LockAcquisitionTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - startedNanos)).isLessThan(Duration.ofMillis(100));
    }
}
```

Add non-reentry and different-key tests:

```java
@Test
void sameSessionShouldRejectSameKeyReentry() throws Exception {
    runtime = createRuntime();
    try (LockSession session = runtime.lockClient().openSession();
         LockLease ignored = session.acquire(request("inventory:reentry", LockMode.MUTEX, WaitPolicy.timed(Duration.ofSeconds(1))))) {
        assertThatThrownBy(() -> session.acquire(request("inventory:reentry", LockMode.MUTEX, WaitPolicy.tryOnce())))
            .isInstanceOf(LockReentryException.class);
    }
}

@Test
void sameSessionShouldAllowDifferentKeys() throws Exception {
    runtime = createRuntime();
    try (LockSession session = runtime.lockClient().openSession();
         LockLease first = session.acquire(request("inventory:first", LockMode.MUTEX, WaitPolicy.timed(Duration.ofSeconds(1))));
         LockLease second = session.acquire(request("inventory:second", LockMode.MUTEX, WaitPolicy.timed(Duration.ofSeconds(1))))) {
        assertThat(first.key()).isEqualTo(new LockKey("inventory:first"));
        assertThat(second.key()).isEqualTo(new LockKey("inventory:second"));
    }
}
```

Change helper overloads:

```java
protected LockRequest request(String key, LockMode mode, Duration waitTime) {
    return request(key, mode, WaitPolicy.timed(waitTime));
}

protected LockRequest request(String key, LockMode mode, WaitPolicy waitPolicy) {
    return new LockRequest(new LockKey(key), mode, waitPolicy);
}

private boolean tryAcquire(String key, LockMode mode, WaitPolicy waitPolicy) throws Exception {
    try (LockSession contender = runtime.lockClient().openSession();
         LockLease ignored = contender.acquire(request(key, mode, waitPolicy))) {
        return true;
    } catch (LockAcquisitionTimeoutException exception) {
        return false;
    }
}
```

- [ ] **Step 3: Run testkit tests**

Run:

```bash
mvn -q -pl distributed-lock-testkit -am test -Dtest=InMemoryLockClientContractTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 4: Commit testkit migration**

Run:

```bash
git add distributed-lock-testkit
git commit -m "test: cover try-once and non-reentrant contract"
```

## Task 5: Redis Fixed Lease Policy and Wait Modes

**Files:**
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`
- Modify: Redis tests under `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/`

- [ ] **Step 1: Add failing Redis fixed-lease tests**

Create or extend Redis tests with three focused assertions:

```java
LockRequest fixed = new LockRequest(
    new LockKey("redis:fixed:mutex"),
    LockMode.MUTEX,
    WaitPolicy.timed(Duration.ofSeconds(1)),
    LeasePolicy.fixed(Duration.ofSeconds(2))
);
try (LockSession session = runtime.lockClient().openSession();
     LockLease ignored = session.acquire(fixed)) {
    Long ttl = redis.commands().ttl(RedisLockBackend.ownerKey("redis:fixed:mutex", LockMode.MUTEX));
    assertThat(ttl).isBetween(1L, 2L);
}
```

For reader expiry:

```java
LockRequest fixedRead = new LockRequest(
    new LockKey("redis:fixed:read"),
    LockMode.READ,
    WaitPolicy.timed(Duration.ofSeconds(1)),
    LeasePolicy.fixed(Duration.ofSeconds(2))
);
try (LockSession session = runtime.lockClient().openSession();
     LockLease ignored = session.acquire(fixedRead)) {
    Long ttl = redis.commands().ttl("lock:redis:fixed:read:read:owners");
    assertThat(ttl).isBetween(1L, 2L);
}
```

For default lease:

```java
LockRequest defaultLease = new LockRequest(
    new LockKey("redis:default:mutex"),
    LockMode.MUTEX,
    WaitPolicy.timed(Duration.ofSeconds(1))
);
try (LockSession session = runtime.lockClient().openSession();
     LockLease ignored = session.acquire(defaultLease)) {
    Long ttl = redis.commands().ttl(RedisLockBackend.ownerKey("redis:default:mutex", LockMode.MUTEX));
    assertThat(ttl).isBetween(25L, 30L);
}
```

Add a try-once timeout test that measures elapsed duration below 100 ms while another session holds the key.

- [ ] **Step 2: Run Redis tests to verify failure**

Run:

```bash
mvn -q -pl distributed-lock-redis -am test -Dtest=RedisLockBackendContractTest,RedisReadLockExpirationTest,RedisLeaseRenewalTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because Redis still reads `waitPolicy().unbounded()` and always uses `configuration.leaseSeconds()`.

- [ ] **Step 3: Add effective lease helpers in Redis backend**

In `RedisBackendSession`, add:

```java
private long effectiveLeaseMillis(LockRequest request) {
    return switch (request.leasePolicy().mode()) {
        case BACKEND_DEFAULT -> TimeUnit.SECONDS.toMillis(configuration.leaseSeconds());
        case FIXED -> request.leasePolicy().duration().toMillis();
    };
}

private long deadlineNanos(WaitPolicy waitPolicy) {
    return switch (waitPolicy.mode()) {
        case TRY_ONCE -> System.nanoTime();
        case TIMED -> System.nanoTime() + waitPolicy.timeout().toNanos();
        case INDEFINITE -> Long.MAX_VALUE;
    };
}
```

Use one `long leaseMillis = effectiveLeaseMillis(request);` inside `acquire(...)` and pass it to `tryAcquire(...)`.

- [ ] **Step 4: Replace Redis acquire loop with wait-mode switch**

Change the acquire loop to:

```java
long deadlineNanos = deadlineNanos(request.waitPolicy());
do {
    ensureActive();
    RedisLease lease = tryAcquire(request, writerIntent, leaseMillis);
    if (lease != null) {
        acquired = true;
        activeLeases.put(lease.ownerValue(), lease);
        return lease;
    }
    if (request.waitPolicy().mode() == WaitMode.TRY_ONCE || System.nanoTime() >= deadlineNanos) {
        throw new LockAcquisitionTimeoutException("Timed out acquiring Redis lock for key " + request.key().value());
    }
    Thread.sleep(request.waitPolicy().mode() == WaitMode.INDEFINITE
        ? 25L
        : Math.min(25L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()))));
} while (true);
```

- [ ] **Step 5: Pass effective lease duration into Redis scripts and lease objects**

Update method signatures:

```java
private RedisLease tryAcquire(LockRequest request, String writerIntent, long leaseMillis)
private long tryAcquireMutex(String key, long leaseMillis)
private long tryAcquireRead(String key, long leaseMillis)
private long tryAcquireWrite(String key, String writerIntent, long leaseMillis)
```

Pass the millisecond `leaseMillis` value to Redis scripts so fixed request leases keep sub-second precision.
For read locks, keep each reader member score based on its own `leaseMillis`, but set the shared reader zset key expiry to the furthest live reader score.
For write acquisition, keep pending writer intent expiry on the backend/session intent TTL instead of the acquired lease TTL; otherwise a very short fixed write lease can let later readers bypass a waiting writer between retries.
Construct `RedisLease` with `leaseMillis`:

```java
return new RedisLease(
    request.key(),
    request.mode(),
    new FencingToken(fence),
    ownerValue(sessionId, fence),
    leaseMillis,
    this
);
```

Add a field to `RedisLease`:

```java
private final long leaseMillis;
```

Use `leaseMillis` inside `refresh()` instead of `configuration.leaseSeconds()`.

- [ ] **Step 6: Keep session renewal on backend default**

Leave session key creation and session renewal on `configuration.leaseSeconds()` because request-level lease duration applies to leases, not Redis backend session lifetime:

```java
commands.setex(sessionKey(sessionId), configuration.leaseSeconds(), "1");
commands.expire(sessionKey(sessionId), configuration.leaseSeconds());
```

Keep `scheduleRenewal()` on the backend default session cadence. Per-lease refresh uses each lease's own `leaseMillis`.

- [ ] **Step 7: Run Redis tests**

Run:

```bash
mvn -q -pl distributed-lock-redis -am test -Dtest=RedisLockBackendContractTest,RedisReadLockExpirationTest,RedisLeaseRenewalTest,RedisReadWriteWriterPreferenceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 8: Commit Redis fixed lease support**

Run:

```bash
git add distributed-lock-redis
git commit -m "feat: support redis fixed request leases"
```

## Task 6: ZooKeeper Fixed-Lease Rejection and Wait Modes

**Files:**
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`
- Modify: ZooKeeper tests under `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/`

- [ ] **Step 1: Add failing ZooKeeper fixed-lease and try-once tests**

Add a test using `LockRuntimeBuilder` with the ZooKeeper module:

```java
LockRequest fixedLease = new LockRequest(
    new LockKey("zk:fixed:reject"),
    LockMode.MUTEX,
    WaitPolicy.tryOnce(),
    LeasePolicy.fixed(Duration.ofSeconds(5))
);

try (LockSession session = runtime.lockClient().openSession()) {
    assertThatThrownBy(() -> session.acquire(fixedLease))
        .isInstanceOf(UnsupportedLockCapabilityException.class)
        .hasMessageContaining("fixed lease");
}
```

Add try-once cleanup while a holder owns the key:

```java
try (LockSession holder = runtime.lockClient().openSession();
     LockLease ignored = holder.acquire(new LockRequest(new LockKey("zk:try-once"), LockMode.MUTEX, WaitPolicy.indefinite()));
     LockSession contender = runtime.lockClient().openSession()) {
    assertThatThrownBy(() -> contender.acquire(new LockRequest(new LockKey("zk:try-once"), LockMode.MUTEX, WaitPolicy.tryOnce())))
        .isInstanceOf(LockAcquisitionTimeoutException.class);
    assertThat(childrenUnderQueueRoot("zk:try-once")).hasSize(1);
}
```

- [ ] **Step 2: Run ZooKeeper tests to verify failure**

Run:

```bash
mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperLockBackendBehaviorTest,ZooKeeperLockBackendContractTest,ZooKeeperReadWriteConcurrencyTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because wait mode and fixed-lease validation are not migrated.

- [ ] **Step 3: Update ZooKeeper wait-mode handling**

In `ZooKeeperBackendSession.acquireQueued(...)`, replace deadline construction with:

```java
long deadlineNanos = switch (request.waitPolicy().mode()) {
    case TRY_ONCE -> System.nanoTime();
    case TIMED -> System.nanoTime() + request.waitPolicy().timeout().toNanos();
    case INDEFINITE -> Long.MAX_VALUE;
};
```

Replace timeout checks:

```java
if (request.waitPolicy().mode() == WaitMode.TRY_ONCE || remainingNanos <= 0L) {
    deleteIfExists(contenderPath);
    throw timeout(request.key());
}
```

For `INDEFINITE`, `remainingNanos(deadlineNanos)` still returns `Long.MAX_VALUE`, so the existing watcher wait path can remain.

- [ ] **Step 4: Rely on core fixed-lease rejection before remote state**

No fixed-lease validation should be added inside `ZooKeeperLockBackend`.
The rejection must happen in core through `SupportedLockModes.fixedLeaseDurationSupported() == false`.
Verify `ZooKeeperBackendModule.capabilities()` returns:

```java
return new BackendCapabilities(true, true, true, true, false);
```

- [ ] **Step 5: Run ZooKeeper tests**

Run:

```bash
mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperBackendModuleTest,ZooKeeperLockBackendBehaviorTest,ZooKeeperLockBackendContractTest,ZooKeeperReadWriteConcurrencyTest,ZooKeeperSessionIsolationTest,ZooKeeperSessionLossTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 6: Commit ZooKeeper migration**

Run:

```bash
git add distributed-lock-zookeeper
git commit -m "feat: migrate zookeeper wait modes"
```

## Task 7: Spring Starter Migration

**Files:**
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/annotation/DistributedLock.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockAspect.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockAutoConfiguration.java`
- Modify: Spring starter integration tests.

- [ ] **Step 1: Add failing Spring integration tests**

Update auto-configuration tests:

```java
assertThat(context).hasSingleBean(SynchronousLockExecutor.class);
assertThat(context).doesNotHaveBean(LockExecutor.class);
```

Add annotation behavior tests:

```java
@DistributedLock(key = "order:#{#p0}", waitFor = "0s", leaseFor = "10s")
String updateWithLease(String id) {
    return LockContext.requireCurrentFencingToken().value() > 0 ? "ok" : "bad";
}
```

Verify zero/negative `leaseFor` fails:

```java
@DistributedLock(key = "order:#{#p0}", leaseFor = "0s")
void invalidLease(String id) {
}
```

Assert invocation throws `IllegalArgumentException`.

- [ ] **Step 2: Run Spring starter tests to verify failure**

Run:

```bash
mvn -q -pl distributed-lock-spring-boot-starter -am test -Dtest=DistributedLockAutoConfigurationIntegrationTest,DistributedLockAspectIntegrationTest,DistributedLockAsyncGuardTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because annotation and bean types are not migrated.

- [ ] **Step 3: Update annotation and auto-configuration**

Add `leaseFor`:

```java
String leaseFor() default "";
```

Change bean exposure:

```java
@Bean
@ConditionalOnMissingBean
public SynchronousLockExecutor synchronousLockExecutor(LockRuntime runtime) {
    return runtime.synchronousLockExecutor();
}
```

Change `DistributedLockAspect` constructor and field from `LockExecutor` to `SynchronousLockExecutor`.

- [ ] **Step 4: Update aspect request mapping**

Add these imports when they are not already present:

```java
import com.mycorp.distributedlock.api.LeasePolicy;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
```

Replace `resolveWaitPolicy(...)`:

```java
private WaitPolicy resolveWaitPolicy(DistributedLock distributedLock) {
    Duration waitTimeout = resolveWaitTimeout(distributedLock);
    if (waitTimeout == null) {
        return WaitPolicy.indefinite();
    }
    if (waitTimeout.isZero()) {
        return WaitPolicy.tryOnce();
    }
    return WaitPolicy.timed(waitTimeout);
}
```

Add lease mapping:

```java
private LeasePolicy resolveLeasePolicy(DistributedLock distributedLock) {
    if (distributedLock.leaseFor() == null || distributedLock.leaseFor().isBlank()) {
        return LeasePolicy.backendDefault();
    }
    Duration leaseDuration = DurationStyle.detectAndParse(distributedLock.leaseFor());
    return LeasePolicy.fixed(leaseDuration);
}
```

Build `LockRequest` with four arguments:

```java
return new LockRequest(
    new LockKey(key),
    resolveMode(distributedLock.mode()),
    resolveWaitPolicy(distributedLock),
    resolveLeasePolicy(distributedLock)
);
```

Change invocation to:

```java
return lockExecutor.withLock(request, lease -> proceed(joinPoint));
```

- [ ] **Step 5: Run Spring starter tests**

Run:

```bash
mvn -q -pl distributed-lock-spring-boot-starter -am test -Dtest=DistributedLockAutoConfigurationIntegrationTest,DistributedLockAspectIntegrationTest,DistributedLockAsyncGuardTest,SpelLockKeyResolverTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 6: Commit Spring migration**

Run:

```bash
git add distributed-lock-spring-boot-starter
git commit -m "feat: migrate spring starter to synchronous executor"
```

## Task 8: Observability Migration

**Files:**
- Modify: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockExecutor.java`
- Modify: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockRuntime.java`
- Modify: `distributed-lock-extension-observability/src/test/java/com/mycorp/distributedlock/observability/ObservedLockExecutorTest.java`
- Modify: `distributed-lock-extension-observability-spring/src/test/java/com/mycorp/distributedlock/observability/springboot/integration/DistributedLockObservabilityAutoConfigurationTest.java`

- [ ] **Step 1: Update observability tests to the new executor**

Replace `LockExecutor` with `SynchronousLockExecutor` and callbacks with `lease ->`.
For fake delegates, use:

```java
SynchronousLockExecutor delegate = new SynchronousLockExecutor() {
    @Override
    public <T> T withLock(LockRequest request, LockedAction<T> action) throws Exception {
        return action.execute(mock(LockLease.class));
    }
};
```

Change runtime assertions:

```java
when(delegate.synchronousLockExecutor()).thenReturn(mock(SynchronousLockExecutor.class));
String result = observedRuntime.synchronousLockExecutor().withLock(sampleRequest(), lease -> "ok");
verify(delegate, never()).synchronousLockExecutor();
```

- [ ] **Step 2: Run observability tests to verify failure**

Run:

```bash
mvn -q -pl distributed-lock-extension-observability,distributed-lock-extension-observability-spring -am test -Dtest=ObservedLockExecutorTest,ObservedLockSessionTest,DistributedLockObservabilityAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because observability still imports `LockExecutor`.

- [ ] **Step 3: Update observability production code**

Change `ObservedLockExecutor`:

```java
public final class ObservedLockExecutor implements SynchronousLockExecutor {
    private final SynchronousLockExecutor delegate;
    private final LockObservationSink sink;
    private final String backendId;
    private final boolean includeKey;

    @Override
    public <T> T withLock(LockRequest request, LockedAction<T> action) throws Exception {
        long startedNanos = System.nanoTime();
        try {
            T result = delegate.withLock(request, action);
            LockObservationSupport.publishSafely(sink, new LockObservationEvent(
                backendId,
                "executor",
                "scope",
                "success",
                request.mode(),
                LockObservationSupport.keyFor(request, includeKey),
                LockObservationSupport.durationSince(startedNanos),
                null
            ));
            return result;
        } catch (Exception exception) {
            LockObservationSupport.publishSafely(sink, new LockObservationEvent(
                backendId,
                "executor",
                "scope",
                LockObservationSupport.scopeOutcomeFor(exception),
                request.mode(),
                LockObservationSupport.keyFor(request, includeKey),
                LockObservationSupport.durationSince(startedNanos),
                exception
            ));
            throw exception;
        }
    }
}
```

Change `ObservedLockRuntime` fields and method:

```java
private final SynchronousLockExecutor lockExecutor;
this.lockExecutor = new ObservedLockExecutor(new DefaultSynchronousLockExecutor(lockClient), sink, backendId, includeKey);

@Override
public SynchronousLockExecutor synchronousLockExecutor() {
    return lockExecutor;
}
```

- [ ] **Step 4: Run observability tests**

Run:

```bash
mvn -q -pl distributed-lock-extension-observability,distributed-lock-extension-observability-spring -am test -Dtest=ObservedLockExecutorTest,ObservedLockSessionTest,DistributedLockObservabilityAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 5: Commit observability migration**

Run:

```bash
git add distributed-lock-extension-observability distributed-lock-extension-observability-spring
git commit -m "feat: migrate observability to synchronous executor"
```

## Task 9: Examples, Benchmarks, and Documentation

**Files:**
- Modify: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/ProgrammaticRedisExample.java`
- Modify: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/ProgrammaticZooKeeperExample.java`
- Modify: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/spring/SpringBootRedisExampleApplication.java`
- Modify: `distributed-lock-examples/README.md`
- Modify: `distributed-lock-spring-boot-starter/README.md`
- Modify: `distributed-lock-test-suite/README.md`
- Modify: `distributed-lock-test-suite/TEST-CONFIGURATION.md`
- Modify: benchmark sources under `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/`
- Modify: `distributed-lock-benchmarks/src/test/java/com/mycorp/distributedlock/benchmarks/BenchmarkEnvironmentSmokeTest.java`

- [ ] **Step 1: Update examples and benchmark call sites**

Replace imports and method calls:

```java
import com.mycorp.distributedlock.api.SynchronousLockExecutor;

String result = runtime.synchronousLockExecutor().withLock(
    sampleRequest("example:redis:order-42"),
    lease -> "Redis lease acquired with fencing token " + lease.fencingToken().value()
);
```

For benchmark helper interfaces and environments, rename accessor methods from `lockExecutor()` to `synchronousLockExecutor()` or keep the method name only if it is local to benchmark code and returns `SynchronousLockExecutor`.

Update all callbacks from `() -> value` to `lease -> value`.

- [ ] **Step 2: Update Spring README with hardened semantics**

In `distributed-lock-spring-boot-starter/README.md`, replace `LockExecutor` references with `SynchronousLockExecutor`.
Add these explicit bullets:

```markdown
- The public lock model is non-reentrant. A session or synchronous scope cannot acquire the same `LockKey` twice.
- `SynchronousLockExecutor` and `@DistributedLock` are synchronous-only. Async work must acquire and hold its own lease for its full lifetime.
- `WaitPolicy.tryOnce()` performs an immediate acquisition attempt.
- `LeasePolicy.fixed(...)` is backend capability-checked. Redis supports it; ZooKeeper rejects it in this release.
- Fencing tokens only protect external state when the protected resource rejects stale tokens.
```

Add annotation fencing example using:

```java
FencingToken token = LockContext.requireCurrentFencingToken();
```

Add the fenced SQL update example from the spec.

- [ ] **Step 3: Update test-suite docs**

Replace `DefaultLockExecutorTest` with `DefaultSynchronousLockExecutorTest`.
Add `LockReentryException`, `WaitPolicy.tryOnce()`, and `LeasePolicy.fixed(...)` to the documented test coverage.

- [ ] **Step 4: Run examples and benchmark smoke tests**

Run:

```bash
mvn -q -pl distributed-lock-examples -am test -DskipITs -Dsurefire.failIfNoSpecifiedTests=false
mvn -q install -DskipTests
mvn -q -f distributed-lock-benchmarks/pom.xml -Dtest=BenchmarkEnvironmentSmokeTest test -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. `distributed-lock-benchmarks` is intentionally outside the root reactor, so the smoke test runs through its module POM after current snapshots are installed. The benchmark smoke test expects Redis at `127.0.0.1:6379` and ZooKeeper at `127.0.0.1:2181`, or equivalent `benchmark.*` system properties / `BENCHMARK_*` environment variables.

- [ ] **Step 5: Commit docs and examples**

Run:

```bash
git add distributed-lock-examples distributed-lock-benchmarks distributed-lock-spring-boot-starter/README.md distributed-lock-test-suite
git commit -m "docs: document hardened lock api"
```

## Task 10: Reactor Verification and Cleanup

**Files:**
- All modules touched by previous tasks.

- [ ] **Step 1: Search for removed API names**

Run:

```bash
rg -n "LockExecutor|LockedSupplier|DefaultLockExecutor|CurrentLockContext|lockExecutor\\(|waitTime\\(|unbounded\\(" distributed-lock-* docs/superpowers -g '*.java' -g '*.md'
```

Expected: no matches in production/test/example docs except historical design and plan files under `docs/superpowers`.

- [ ] **Step 2: Run focused module tests**

Run:

```bash
mvn -q -pl distributed-lock-api,distributed-lock-core,distributed-lock-runtime,distributed-lock-testkit -am test \
  -Dtest=ApiSurfaceTest,DefaultSynchronousLockExecutorTest,DefaultLockSessionTest,LockRuntimeBuilderTest,InMemoryLockClientContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 3: Run backend and Spring tests**

Run:

```bash
mvn -q -pl distributed-lock-redis,distributed-lock-zookeeper,distributed-lock-spring-boot-starter,distributed-lock-extension-observability,distributed-lock-extension-observability-spring -am test \
  -Dtest=RedisLockBackendContractTest,RedisReadLockExpirationTest,RedisLeaseRenewalTest,ZooKeeperLockBackendContractTest,ZooKeeperLockBackendBehaviorTest,ZooKeeperReadWriteConcurrencyTest,DistributedLockAutoConfigurationIntegrationTest,DistributedLockAspectIntegrationTest,DistributedLockAsyncGuardTest,ObservedLockExecutorTest,ObservedLockSessionTest,DistributedLockObservabilityAutoConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 4: Run full reactor tests**

Run:

```bash
mvn test
```

Expected: PASS.

- [ ] **Step 5: Review git diff**

Run:

```bash
git status --short
git diff --stat
```

Expected: only intentional files from this plan are modified.

- [ ] **Step 6: Commit final cleanup when Step 5 shows remaining edits**

When Step 5 shows formatting, import cleanup, or doc wording edits not already committed, run:

```bash
git add distributed-lock-api distributed-lock-core distributed-lock-runtime distributed-lock-testkit distributed-lock-redis distributed-lock-zookeeper distributed-lock-spring-boot-starter distributed-lock-extension-observability distributed-lock-extension-observability-spring distributed-lock-examples distributed-lock-benchmarks distributed-lock-test-suite
git commit -m "chore: finish api hardening migration"
```

Expected: a final cleanup commit exists when Step 5 showed remaining intentional edits; otherwise no command is run for this step.

## Self-Review Checklist

- Spec coverage: public API rename, lock context, wait modes, lease policy, exception hierarchy, non-reentry, Redis fixed leases, ZooKeeper rejection, Spring `leaseFor`, observability, examples, docs, and verification are covered.
- Open-slot scan: the plan contains no marker text or open-ended implementation slots.
- Type consistency: the plan uses `SynchronousLockExecutor`, `LockedAction`, `LockContext`, `WaitMode`, `LeaseMode`, `LeasePolicy`, `LockReentryException`, and `synchronousLockExecutor()` consistently.
- Scope: this plan intentionally excludes async/reactive APIs, reentrant locks, upgrade/downgrade semantics, and resource-specific fencing adapters, matching the approved spec.
