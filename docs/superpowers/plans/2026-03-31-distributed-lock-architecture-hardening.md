# Distributed Lock Architecture Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the distributed lock 2.0 implementation so lock ownership is lease-based, `core` release progress is never blocked by waiting contenders, runtime and generic Spring integration stop transporting backend-specific configuration, and Redis/ZooKeeper/Spring entrypoints remain usable through the existing synchronous API.

**Architecture:** Migrate the repository in seven slices: first replace passive backend handles with active leases, then rewrite the `core` state machine, expand the shared contract suite, simplify runtime plus Spring module boundaries, harden Redis and ZooKeeper adapters, and finally update examples, benchmarks, and docs. Keep public lock usage stable while moving backend truth, release semantics, and typed configuration ownership back to the correct module boundaries.

**Tech Stack:** Java 17, Maven multi-module build, Spring Boot 3.2, JUnit 5, AssertJ, Awaitility, Lettuce, Curator `TestingServer`, ServiceLoader

---

## Scope Boundary

This plan covers:

- `distributed-lock-api`
- `distributed-lock-core`
- `distributed-lock-runtime`
- `distributed-lock-testkit`
- `distributed-lock-redis`
- `distributed-lock-zookeeper`
- `distributed-lock-spring-boot-starter`
- two new backend-specific Spring Boot auto-config modules
- root/module `pom.xml` wiring
- examples, benchmarks, and test-suite docs

This plan does not cover:

- async lock APIs
- observability or health features
- any 3.0 public API rewrite
- keeping `LockRuntimeBuilder.configuration(Object)` for compatibility

## File Structure

### Reactor and Build Wiring

- Modify: `pom.xml`
  Responsibility: add the two new backend Spring modules to dependency management and the root reactor.
- Create: `distributed-lock-redis-spring-boot-autoconfigure/pom.xml`
  Responsibility: package Redis-specific Spring properties binding and `BackendModule` bean exposure.
- Create: `distributed-lock-zookeeper-spring-boot-autoconfigure/pom.xml`
  Responsibility: package ZooKeeper-specific Spring properties binding and `BackendModule` bean exposure.

### Public API

- Modify: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/MutexLock.java`
  Responsibility: keep the same public shape while allowing implementations to surface ownership loss on `close()`.
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/LockOwnershipLostException.java`
  Responsibility: signal that local code attempted to use a lock whose backend lease is no longer valid.
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/LockAcquisitionTimeoutException.java`
  Responsibility: signal timed acquisition failure in annotation-driven and future convenience paths.
- Modify: `distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/ApiSurfaceTest.java`
  Responsibility: pin the updated public surface and new exception types.

### Core Backend Contracts and State Machine

- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/BackendLockHandle.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/BackendLockLease.java`
  Responsibility: carry backend-specific ownership, release, and validity logic with the acquired lease itself.
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/LockBackend.java`
  Responsibility: return `BackendLockLease` and remove split release/ownership callbacks.
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/SupportedLockModes.java`
  Responsibility: give `core` a runtime-independent way to enforce backend capabilities.
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultLockManager.java`
  Responsibility: move from monitor-blocking state to per-key coordinators that never call backend acquire/release while holding local synchronization.
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultMutexLock.java`
  Responsibility: route `close()` through manager logic that can distinguish “no local hold” from “lost backend hold”.
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultReadWriteLock.java`
  Responsibility: remain thin while delegating unsupported mode checks back to the manager.
- Create: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/backend/LockBackendSurfaceTest.java`
  Responsibility: pin the new backend lease surface.
- Create: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/manager/DefaultLockManagerBlockingTest.java`
  Responsibility: prove waiting acquisition never blocks owner release.
- Create: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/manager/DefaultLockManagerOwnershipLossTest.java`
  Responsibility: prove stale local holds are detected and cleaned.
- Create: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/manager/DefaultLockManagerCapabilitiesTest.java`
  Responsibility: prove unsupported read/write modes fail fast.
- Create: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/manager/DefaultLockManagerReleaseFailureTest.java`
  Responsibility: prove failed release does not leave active ownership behind.
- Modify: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/manager/DefaultLockManagerTest.java`
  Responsibility: keep the existing same-thread reentry regression after the coordinator rewrite.

### Shared Testkit

- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockManagerContract.java`
  Responsibility: add core contract coverage beyond basic mutex exclusion.
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryLockBackend.java`
  Responsibility: implement the new lease contract while remaining deterministic for unit tests.
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryBackendModule.java`
  Responsibility: adapt to the simplified `BackendModule` SPI.
- Create: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/BlockingLeaseBackend.java`
  Responsibility: synthetic backend for release-progress and contention tests.
- Create: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/OwnershipLossLeaseBackend.java`
  Responsibility: synthetic backend for stale-hold and ownership-loss tests.
- Create: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/ReleaseFailureLeaseBackend.java`
  Responsibility: synthetic backend for release failure tests.
- Modify: `distributed-lock-testkit/src/test/java/com/mycorp/distributedlock/testkit/InMemoryLockManagerContractTest.java`
  Responsibility: run the expanded contract suite against the in-memory backend.

### Runtime SPI

- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendModule.java`
  Responsibility: remove `BackendContext` and require typed modules to own their own configuration.
- Delete: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendContext.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java`
  Responsibility: drop `configuration(Object)`, keep module selection and lifecycle ownership only.
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/DefaultLockRuntime.java`
  Responsibility: continue to own backend shutdown after the builder changes.
- Modify: `distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java`
  Responsibility: pin the new no-context SPI shape and capability forwarding.

### Redis Adapter

- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendModule.java`
  Responsibility: remove `Object` and `Map` parsing and construct backends from typed config only.
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`
  Responsibility: return `RedisLease`, track lease validity, and renew TTL while held.
- Create: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisTestSupport.java`
  Responsibility: share Redis test container lifecycle helpers between the renewal and ownership-loss tests.
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisBackendModuleTest.java`
  Responsibility: pin typed module construction and capabilities.
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisLockBackendContractTest.java`
  Responsibility: run the expanded shared contract suite.
- Create: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisLeaseRenewalTest.java`
  Responsibility: verify long critical sections outlive the base lease window.
- Create: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisOwnershipLossTest.java`
  Responsibility: verify explicit ownership-loss signaling after token removal.
- Modify: `distributed-lock-redis/src/main/resources/META-INF/services/com.mycorp.distributedlock.runtime.spi.BackendModule`
  Responsibility: keep ServiceLoader registration valid after SPI changes.

### ZooKeeper Adapter

- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModule.java`
  Responsibility: remove `Object` and `Map` parsing and construct backends from typed config only.
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`
  Responsibility: return session-aware leases and expose a small seam for session validity tests.
- Modify: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModuleTest.java`
  Responsibility: pin typed module construction and capabilities.
- Modify: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackendContractTest.java`
  Responsibility: run the expanded shared contract suite.
- Create: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperSessionLossTest.java`
  Responsibility: verify explicit ownership-loss signaling after simulated session invalidation.
- Modify: `distributed-lock-zookeeper/src/main/resources/META-INF/services/com.mycorp.distributedlock.runtime.spi.BackendModule`
  Responsibility: keep ServiceLoader registration valid after SPI changes.

### Generic Spring Starter

- Modify: `distributed-lock-spring-boot-starter/pom.xml`
  Responsibility: stay generic, keep only API/runtime dependencies and Spring infrastructure.
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockProperties.java`
  Responsibility: remove Redis/ZooKeeper nested properties and keep only generic settings.
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockAutoConfiguration.java`
  Responsibility: consume only generic properties plus discovered `BackendModule` beans.
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockAspect.java`
  Responsibility: throw explicit timeout/configuration failures and reject async-returning methods.
- Modify: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAutoConfigurationIntegrationTest.java`
  Responsibility: verify the generic starter registers only generic beans.
- Modify: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAspectIntegrationTest.java`
  Responsibility: verify timeout failures and synchronous annotation behavior.
- Create: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAsyncGuardTest.java`
  Responsibility: prove async return types are rejected.
- Delete: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/RedisStarterIntegrationTest.java`
- Delete: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/ZooKeeperStarterIntegrationTest.java`

### Redis Spring Auto-Configuration Module

- Create: `distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockProperties.java`
- Create: `distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockAutoConfiguration.java`
- Create: `distributed-lock-redis-spring-boot-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `distributed-lock-redis-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/redis/springboot/integration/RedisBackendModuleAutoConfigurationTest.java`
- Create: `distributed-lock-redis-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/redis/springboot/integration/RedisStarterIntegrationTest.java`

### ZooKeeper Spring Auto-Configuration Module

- Create: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/zookeeper/springboot/config/ZooKeeperDistributedLockProperties.java`
- Create: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/zookeeper/springboot/config/ZooKeeperDistributedLockAutoConfiguration.java`
- Create: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/zookeeper/springboot/integration/ZooKeeperBackendModuleAutoConfigurationTest.java`
- Create: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/zookeeper/springboot/integration/ZooKeeperStarterIntegrationTest.java`

### Examples, Benchmarks, and Docs

- Modify: `distributed-lock-examples/pom.xml`
- Modify: `distributed-lock-examples/README.md`
- Modify: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/ProgrammaticRedisExample.java`
- Modify: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/ProgrammaticZooKeeperExample.java`
- Modify: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/spring/SpringBootRedisExampleApplication.java`
- Modify: `distributed-lock-examples/src/main/resources/application.yml`
- Modify: `distributed-lock-benchmarks/pom.xml`
- Modify: `distributed-lock-benchmarks/README.md`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/RedisBenchmarkEnvironment.java`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/ZooKeeperBenchmarkEnvironment.java`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/SpringBenchmarkEnvironment.java`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/SpringBenchmarkApplication.java`
- Modify: `distributed-lock-benchmarks/src/test/java/com/mycorp/distributedlock/benchmarks/BenchmarkEnvironmentSmokeTest.java`
- Modify: `distributed-lock-spring-boot-starter/README.md`
- Modify: `distributed-lock-test-suite/README.md`
- Modify: `distributed-lock-test-suite/TEST-CONFIGURATION.md`

## Task 1: Migrate the Public API and Backend Contract to Leases

**Files:**
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/LockOwnershipLostException.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/LockAcquisitionTimeoutException.java`
- Modify: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/MutexLock.java`
- Modify: `distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/ApiSurfaceTest.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/BackendLockLease.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/LockBackend.java`
- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/BackendLockHandle.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultLockManager.java`
- Create: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/backend/LockBackendSurfaceTest.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryLockBackend.java`
- Modify: `distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java`
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`

- [ ] **Step 1: Write failing surface tests for the new exception and lease contracts**

Update `distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/ApiSurfaceTest.java` to:

```java
package com.mycorp.distributedlock.api;

import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ApiSurfaceTest {

    @Test
    void lockManagerAndMutexLockShouldMatchTheApproved2xShape() throws Exception {
        assertThat(LockManager.class.getInterfaces()).isEmpty();
        assertThat(MutexLock.class.getInterfaces()).containsExactly(AutoCloseable.class);
        assertThat(LockManager.class.getMethod("mutex", String.class).getReturnType()).isEqualTo(MutexLock.class);
        assertThat(LockManager.class.getMethod("readWrite", String.class).getReturnType()).isEqualTo(ReadWriteLock.class);
        assertThat(MutexLock.class.getMethod("lock").getExceptionTypes()).containsExactly(InterruptedException.class);
        assertThat(MutexLock.class.getMethod("tryLock", Duration.class).getReturnType()).isEqualTo(boolean.class);
    }

    @Test
    void apiShouldExposeExplicitTimeoutAndOwnershipLossExceptions() {
        assertThat(LockOwnershipLostException.class.getSuperclass()).isEqualTo(RuntimeException.class);
        assertThat(LockAcquisitionTimeoutException.class.getSuperclass()).isEqualTo(RuntimeException.class);
    }
}
```

Create `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/backend/LockBackendSurfaceTest.java`:

```java
package com.mycorp.distributedlock.core.backend;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LockBackendSurfaceTest {

    @Test
    void backendAcquireShouldReturnBackendLockLease() throws Exception {
        assertThat(LockBackend.class
            .getMethod("acquire", LockResource.class, LockMode.class, WaitPolicy.class)
            .getReturnType())
            .isEqualTo(BackendLockLease.class);
    }
}
```

- [ ] **Step 2: Run the targeted API/core surface tests to verify they fail**

Run:

```bash
mvn -q -pl distributed-lock-api,distributed-lock-core test -Dtest=ApiSurfaceTest,LockBackendSurfaceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `LockOwnershipLostException`, `LockAcquisitionTimeoutException`, and `BackendLockLease` do not exist yet, and `LockBackend.acquire(...)` still returns `BackendLockHandle`.

- [ ] **Step 3: Introduce the new exception classes, lease contract, and minimal repo-wide compile migration**

Create `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/LockOwnershipLostException.java`:

```java
package com.mycorp.distributedlock.api.exception;

public class LockOwnershipLostException extends RuntimeException {

    public LockOwnershipLostException(String message) {
        super(message);
    }

    public LockOwnershipLostException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

Create `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/LockAcquisitionTimeoutException.java`:

```java
package com.mycorp.distributedlock.api.exception;

public class LockAcquisitionTimeoutException extends RuntimeException {

    public LockAcquisitionTimeoutException(String message) {
        super(message);
    }
}
```

Create `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/BackendLockLease.java`:

```java
package com.mycorp.distributedlock.core.backend;

public interface BackendLockLease extends AutoCloseable {

    String key();

    LockMode mode();

    boolean isValidForCurrentExecution();

    void release();

    @Override
    default void close() {
        release();
    }
}
```

Replace `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/LockBackend.java` with:

```java
package com.mycorp.distributedlock.core.backend;

public interface LockBackend {

    BackendLockLease acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) throws InterruptedException;
}
```

Update the lease usage in `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultLockManager.java` by replacing every `BackendLockHandle` field and method call with `BackendLockLease`:

```java
private BackendLockLease mutexLease;
private BackendLockLease writeLease;
```

```java
case MUTEX -> current == mutexOwner && mutexLease != null && mutexLease.isValidForCurrentExecution();
case READ -> {
    ReadHold hold = readHolds.get(current);
    yield hold != null && hold.lease != null && hold.lease.isValidForCurrentExecution();
}
case WRITE -> current == writeOwner && writeLease != null && writeLease.isValidForCurrentExecution();
```

Rename the `ReadHold` field from `handle` to `lease` in the same file so the updated ownership checks compile:

```java
private static final class ReadHold {
    private final BackendLockLease lease;
    private int count;

    private ReadHold(BackendLockLease lease) {
        this.lease = lease;
        this.count = 1;
    }
}
```

```java
BackendLockLease acquiredLease = backend.acquire(resource, LockMode.MUTEX, waitPolicy);
if (acquiredLease == null) {
    return false;
}
mutexLease = acquiredLease;
```

```java
BackendLockLease lease = mutexLease;
mutexLease = null;
mutexOwner = null;
lease.release();
```

Update the in-memory backend in `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryLockBackend.java` to return a lease object:

```java
private record InMemoryLease(String key, LockMode mode, InMemoryLockState state) implements BackendLockLease {

    @Override
    public boolean isValidForCurrentExecution() {
        return switch (mode) {
            case MUTEX -> state.mutex.isHeldByCurrentThread();
            case READ -> state.readWrite.getReadHoldCount() > 0;
            case WRITE -> state.readWrite.isWriteLockedByCurrentThread();
        };
    }

    @Override
    public void release() {
        switch (mode) {
            case MUTEX -> state.mutex.unlock();
            case READ -> state.readWrite.readLock().unlock();
            case WRITE -> state.readWrite.writeLock().unlock();
        }
    }
}
```

Update the runtime test stub in `distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java`:

```java
@Override
public LockBackend createBackend(BackendContext context) {
    return new LockBackend() {
        @Override
        public BackendLockLease acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) {
            return new BackendLockLease() {
                @Override
                public String key() {
                    return resource.key();
                }

                @Override
                public LockMode mode() {
                    return mode;
                }

                @Override
                public boolean isValidForCurrentExecution() {
                    return true;
                }

                @Override
                public void release() {
                }
            };
        }
    };
}
```

Update `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java` to return a minimal lease wrapper:

```java
private record RedisLease(RedisLockBackend owner, String key, LockMode mode, String token, long threadId)
    implements BackendLockLease {

    @Override
    public boolean isValidForCurrentExecution() {
        return owner.isLeaseValid(this);
    }

    @Override
    public void release() {
        owner.releaseLease(this);
    }
}
```

Add the helper methods in the same file:

```java
private boolean isLeaseValid(RedisLease lease) {
    if (lease.threadId() != Thread.currentThread().getId()) {
        return false;
    }
    return switch (lease.mode()) {
        case MUTEX -> lease.token().equals(commands.get(lease.key()));
        case READ -> commands.hexists(readersKey(lease.key()), lease.token());
        case WRITE -> lease.token().equals(commands.get(writerKey(lease.key())));
    };
}

private void releaseLease(RedisLease lease) {
    Long result = switch (lease.mode()) {
        case MUTEX -> commands.eval(MUTEX_RELEASE_SCRIPT, ScriptOutputType.INTEGER, new String[]{lease.key()}, lease.token());
        case READ -> commands.eval(READ_RELEASE_SCRIPT, ScriptOutputType.INTEGER, new String[]{readersKey(lease.key())}, lease.token());
        case WRITE -> commands.eval(WRITE_RELEASE_SCRIPT, ScriptOutputType.INTEGER, new String[]{writerKey(lease.key())}, lease.token());
    };
    if (result == null || result == 0L) {
        throw new LockBackendException("Failed to release Redis lock for key " + lease.key());
    }
}
```

Update `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java` to return a minimal lease wrapper:

```java
private record ZooKeeperLease(ZooKeeperLockBackend owner, String key, LockMode mode, long threadId)
    implements BackendLockLease {

    @Override
    public boolean isValidForCurrentExecution() {
        return owner.isLeaseValid(this);
    }

    @Override
    public void release() {
        owner.releaseLease(this);
    }
}
```

Add the helper methods in the same file:

```java
private boolean isLeaseValid(ZooKeeperLease lease) {
    if (lease.threadId() != Thread.currentThread().getId()) {
        return false;
    }
    return switch (lease.mode()) {
        case MUTEX -> mutex(resourcePath("mutex", lease.key())).isOwnedByCurrentThread();
        case READ -> readWrite(resourcePath("rw", lease.key())).readLock().isOwnedByCurrentThread();
        case WRITE -> readWrite(resourcePath("rw", lease.key())).writeLock().isOwnedByCurrentThread();
    };
}

private void releaseLease(ZooKeeperLease lease) {
    try {
        switch (lease.mode()) {
            case MUTEX -> mutex(resourcePath("mutex", lease.key())).release();
            case READ -> readWrite(resourcePath("rw", lease.key())).readLock().release();
            case WRITE -> readWrite(resourcePath("rw", lease.key())).writeLock().release();
        }
    } catch (Exception exception) {
        throw new LockBackendException("Failed to release ZooKeeper lock for key " + lease.key(), exception);
    }
}
```

Delete `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/BackendLockHandle.java` once the repo compiles on `BackendLockLease`.

- [ ] **Step 4: Run the targeted migration build to verify the lease contract compiles repo-wide**

Run:

```bash
mvn -q -pl distributed-lock-api,distributed-lock-core,distributed-lock-testkit,distributed-lock-runtime,distributed-lock-redis,distributed-lock-zookeeper -am test -Dtest=ApiSurfaceTest,LockBackendSurfaceTest,DefaultLockManagerTest,InMemoryLockManagerContractTest,LockRuntimeBuilderTest,RedisBackendModuleTest,ZooKeeperBackendModuleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. This is not the final behavior, but the entire repo now compiles and tests against active leases instead of passive backend handles.

- [ ] **Step 5: Commit the lease-contract migration**

```bash
git add distributed-lock-api distributed-lock-core distributed-lock-testkit distributed-lock-runtime distributed-lock-redis distributed-lock-zookeeper
git commit -m "refactor: migrate lock backends to lease contract"
```

## Task 2: Rewrite `DefaultLockManager` Around Non-Blocking Coordinators

**Files:**
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/SupportedLockModes.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultLockManager.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultMutexLock.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultReadWriteLock.java`
- Modify: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/manager/DefaultLockManagerTest.java`
- Create: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/manager/DefaultLockManagerBlockingTest.java`
- Create: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/manager/DefaultLockManagerOwnershipLossTest.java`
- Create: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/manager/DefaultLockManagerCapabilitiesTest.java`
- Create: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/manager/DefaultLockManagerReleaseFailureTest.java`

- [ ] **Step 1: Write the failing coordinator, ownership-loss, and capabilities tests**

Create `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/manager/DefaultLockManagerBlockingTest.java`:

```java
package com.mycorp.distributedlock.core.manager;

import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.testkit.support.BlockingLeaseBackend;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultLockManagerBlockingTest {

    @Test
    void blockedContenderMustNotPreventOwnerRelease() throws Exception {
        BlockingLeaseBackend backend = new BlockingLeaseBackend();
        DefaultLockManager manager = new DefaultLockManager(backend);
        MutexLock owner = manager.mutex("orders:42");
        owner.lock();

        Thread contender = new Thread(() -> {
            try {
                manager.mutex("orders:42").tryLock(Duration.ofSeconds(5));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        contender.start();

        backend.awaitAcquireAttempt();
        owner.unlock();

        assertThat(backend.releaseObservedWithin(Duration.ofSeconds(1))).isTrue();
        contender.interrupt();
        contender.join();
    }
}
```

Create `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/manager/DefaultLockManagerOwnershipLossTest.java`:

```java
package com.mycorp.distributedlock.core.manager;

import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.testkit.support.OwnershipLossLeaseBackend;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultLockManagerOwnershipLossTest {

    @Test
    void staleLocalReentryMustFailAfterBackendOwnershipLoss() throws Exception {
        OwnershipLossLeaseBackend backend = new OwnershipLossLeaseBackend();
        DefaultLockManager manager = new DefaultLockManager(backend);
        MutexLock lock = manager.mutex("orders:77");
        lock.lock();
        backend.invalidateCurrentLease();

        assertThatThrownBy(() -> manager.mutex("orders:77").tryLock(Duration.ZERO))
            .isInstanceOf(LockOwnershipLostException.class);
    }
}
```

Create `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/manager/DefaultLockManagerCapabilitiesTest.java`:

```java
package com.mycorp.distributedlock.core.manager;

import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.core.backend.SupportedLockModes;
import com.mycorp.distributedlock.testkit.support.InMemoryLockBackend;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultLockManagerCapabilitiesTest {

    @Test
    void readWriteShouldFailFastWhenBackendDoesNotSupportIt() {
        DefaultLockManager manager = new DefaultLockManager(new InMemoryLockBackend(), new SupportedLockModes(true, false));

        assertThatThrownBy(() -> manager.readWrite("catalog:1"))
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("read/write");
    }
}
```

Create `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/manager/DefaultLockManagerReleaseFailureTest.java`:

```java
package com.mycorp.distributedlock.core.manager;

import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.testkit.support.ReleaseFailureLeaseBackend;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultLockManagerReleaseFailureTest {

    @Test
    void releaseFailureMustSurfaceWithoutLeavingActiveOwnershipBehind() throws Exception {
        ReleaseFailureLeaseBackend backend = new ReleaseFailureLeaseBackend();
        DefaultLockManager manager = new DefaultLockManager(backend);
        MutexLock lock = manager.mutex("orders:88");
        lock.lock();

        assertThatThrownBy(lock::unlock)
            .isInstanceOf(LockBackendException.class);

        lock.lock();
        lock.unlock();
    }
}
```

- [ ] **Step 2: Run the core manager tests to verify they fail with the current synchronized state machine**

Run:

```bash
mvn -q -pl distributed-lock-core,distributed-lock-testkit -am test -Dtest=DefaultLockManagerTest,DefaultLockManagerBlockingTest,DefaultLockManagerOwnershipLossTest,DefaultLockManagerCapabilitiesTest,DefaultLockManagerReleaseFailureTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the current manager still blocks inside synchronized sections, does not clear stale holds, and does not enforce capabilities.

- [ ] **Step 3: Implement `SupportedLockModes` and rewrite `DefaultLockManager` around per-key coordinators**

Create `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/SupportedLockModes.java`:

```java
package com.mycorp.distributedlock.core.backend;

public record SupportedLockModes(boolean mutexSupported, boolean readWriteSupported) {

    public static SupportedLockModes standard() {
        return new SupportedLockModes(true, true);
    }
}
```

Replace the `DefaultLockManager` constructors with:

```java
private final LockBackend backend;
private final SupportedLockModes supportedLockModes;
private final ConcurrentMap<String, LockCoordinator> coordinators = new ConcurrentHashMap<>();

public DefaultLockManager(LockBackend backend) {
    this(backend, SupportedLockModes.standard());
}

public DefaultLockManager(LockBackend backend, SupportedLockModes supportedLockModes) {
    this.backend = Objects.requireNonNull(backend, "backend");
    this.supportedLockModes = Objects.requireNonNull(supportedLockModes, "supportedLockModes");
}
```

Implement the new acquisition path in `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultLockManager.java`:

```java
boolean acquire(String key, LockMode mode, WaitPolicy waitPolicy) throws InterruptedException {
    ensureModeSupported(mode);
    String normalizedKey = normalizeKey(key);
    String coordinatorStateKey = stateKey(normalizedKey, mode);
    LockCoordinator coordinator = coordinators.computeIfAbsent(coordinatorStateKey, ignored ->
        new LockCoordinator(coordinatorStateKey));
    return coordinator.acquire(mode, waitPolicy);
}
```

Model the coordinator like this:

```java
private final class LockCoordinator {
    private final String coordinatorStateKey;
    private final ReentrantLock stateLock = new ReentrantLock();
    private final Condition stateChanged = stateLock.newCondition();
    private final LockResource resource;
    private HeldLease mutexLease;
    private HeldLease writeLease;
    private final Map<Thread, HeldLease> readLeases = new HashMap<>();

    private LockCoordinator(String coordinatorStateKey) {
        this.coordinatorStateKey = coordinatorStateKey;
        this.resource = new LockResource(coordinatorStateKey);
    }

    private boolean acquire(LockMode mode, WaitPolicy waitPolicy) throws InterruptedException {
        Thread current = Thread.currentThread();
        while (true) {
            stateLock.lockInterruptibly();
            try {
                HeldLease existing = existingLease(current, mode);
                if (existing != null) {
                    existing.assertValid();
                    existing.increment();
                    return true;
                }
            } finally {
                stateLock.unlock();
            }

            BackendLockLease acquiredLease = backend.acquire(resource, mode, waitPolicy);
            if (acquiredLease == null) {
                return false;
            }

            stateLock.lockInterruptibly();
            try {
                if (canInstallFreshLease(current, mode)) {
                    installFreshLease(current, mode, acquiredLease);
                    stateChanged.signalAll();
                    return true;
                }
            } finally {
                stateLock.unlock();
            }

            acquiredLease.release();
        }
    }

    private HeldLease existingLease(Thread current, LockMode mode) {
        return switch (mode) {
            case MUTEX -> mutexLease != null && mutexLease.owner() == current ? mutexLease : null;
            case READ -> readLeases.get(current);
            case WRITE -> writeLease != null && writeLease.owner() == current ? writeLease : null;
        };
    }

    private boolean canInstallFreshLease(Thread current, LockMode mode) {
        return switch (mode) {
            case MUTEX -> mutexLease == null;
            case READ -> writeLease == null || writeLease.owner() == current;
            case WRITE -> writeLease == null && readLeases.isEmpty();
        };
    }

    private void installFreshLease(Thread current, LockMode mode, BackendLockLease acquiredLease) {
        HeldLease heldLease = new HeldLease(current, acquiredLease);
        switch (mode) {
            case MUTEX -> mutexLease = heldLease;
            case READ -> readLeases.put(current, heldLease);
            case WRITE -> writeLease = heldLease;
        }
    }

    private HeldLease detachForRelease(Thread current, LockMode mode) {
        HeldLease heldLease = existingLease(current, mode);
        if (heldLease == null) {
            throw new IllegalMonitorStateException("Current thread does not hold " + mode + " lock");
        }
        heldLease.assertValid();
        if (!heldLease.decrementToZero()) {
            return null;
        }
        switch (mode) {
            case MUTEX -> mutexLease = null;
            case READ -> readLeases.remove(current);
            case WRITE -> writeLease = null;
        }
        return heldLease;
    }

    private boolean hasLocalHold(Thread current, LockMode mode) {
        return existingLease(current, mode) != null;
    }

    private boolean isEmpty() {
        return mutexLease == null && writeLease == null && readLeases.isEmpty();
    }
}
```

Implement the release path so backend release happens outside the local lock:

```java
void release(String key, LockMode mode) {
    LockCoordinator coordinator = requiredCoordinator(key, mode);
    coordinator.release(mode);
}
```

```java
private void release(LockMode mode) {
    HeldLease detached;
    stateLock.lock();
    try {
        detached = detachForRelease(Thread.currentThread(), mode);
    } finally {
        stateLock.unlock();
    }

    if (detached == null) {
        return;
    }

    detached.releaseBackendLease();

    stateLock.lock();
    try {
        stateChanged.signalAll();
        if (isEmpty()) {
            coordinators.remove(coordinatorStateKey, this);
        }
    } finally {
        stateLock.unlock();
    }
}
```

Define the supporting helpers in `DefaultLockManager`:

```java
private void ensureModeSupported(LockMode mode) {
    if (mode == LockMode.MUTEX && !supportedLockModes.mutexSupported()) {
        throw new LockConfigurationException("Configured backend does not support mutex locks");
    }
    if ((mode == LockMode.READ || mode == LockMode.WRITE) && !supportedLockModes.readWriteSupported()) {
        throw new LockConfigurationException("Configured backend does not support read/write locks");
    }
}

void ensureReadWriteSupported() {
    ensureModeSupported(LockMode.READ);
}

private LockCoordinator requiredCoordinator(String key, LockMode mode) {
    String normalizedKey = normalizeKey(key);
    LockCoordinator coordinator = coordinators.get(stateKey(normalizedKey, mode));
    if (coordinator == null) {
        throw new IllegalMonitorStateException("Current thread does not hold lock " + normalizedKey);
    }
    return coordinator;
}

private boolean hasLocalHold(String key, LockMode mode) {
    LockCoordinator coordinator = coordinators.get(stateKey(normalizeKey(key), mode));
    return coordinator != null && coordinator.hasLocalHold(Thread.currentThread(), mode);
}
```

Add the held-lease state object in the same file:

```java
private static final class HeldLease {
    private final Thread owner;
    private final BackendLockLease lease;
    private int holdCount = 1;

    private HeldLease(Thread owner, BackendLockLease lease) {
        this.owner = owner;
        this.lease = lease;
    }

    private Thread owner() {
        return owner;
    }

    private void assertValid() {
        if (!lease.isValidForCurrentExecution()) {
            throw new LockOwnershipLostException("Backend ownership lost for key " + lease.key());
        }
    }

    private void increment() {
        holdCount++;
    }

    private boolean decrementToZero() {
        holdCount--;
        return holdCount == 0;
    }

    private void releaseBackendLease() {
        lease.release();
    }
}
```

Add a manager-backed `close()` path in `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultMutexLock.java`:

```java
@Override
public void close() {
    manager.close(key, mode);
}
```

Add helper logic in `DefaultLockManager`:

```java
void close(String key, LockMode mode) {
    if (!hasLocalHold(key, mode)) {
        return;
    }
    release(key, mode);
}
```

Keep `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultReadWriteLock.java` thin:

```java
@Override
public MutexLock readLock() {
    manager.ensureReadWriteSupported();
    return new DefaultMutexLock(manager, key, LockMode.READ);
}

@Override
public MutexLock writeLock() {
    manager.ensureReadWriteSupported();
    return new DefaultMutexLock(manager, key, LockMode.WRITE);
}
```

- [ ] **Step 4: Run the focused core manager suite to verify the coordinator rewrite works**

Run:

```bash
mvn -q -pl distributed-lock-core,distributed-lock-testkit -am test -Dtest=DefaultLockManagerTest,DefaultLockManagerBlockingTest,DefaultLockManagerOwnershipLossTest,DefaultLockManagerCapabilitiesTest,DefaultLockManagerReleaseFailureTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. The manager now releases outside local synchronization, clears stale holds, and enforces unsupported modes before exposing read/write handles.

- [ ] **Step 5: Commit the coordinator rewrite**

```bash
git add distributed-lock-core
git commit -m "refactor: rewrite lock manager around coordinators"
```

## Task 3: Expand the Shared Testkit Contracts and Synthetic Backends

**Files:**
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockManagerContract.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryLockBackend.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryBackendModule.java`
- Create: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/BlockingLeaseBackend.java`
- Create: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/OwnershipLossLeaseBackend.java`
- Create: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/ReleaseFailureLeaseBackend.java`
- Modify: `distributed-lock-testkit/src/test/java/com/mycorp/distributedlock/testkit/InMemoryLockManagerContractTest.java`

- [ ] **Step 1: Extend the contract suite with reentry, owner-only unlock, close behavior, and read/write coverage**

Replace `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockManagerContract.java` with:

```java
package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.api.ReadWriteLock;
import com.mycorp.distributedlock.runtime.LockRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public abstract class LockManagerContract {

    protected final ExecutorService executor = Executors.newSingleThreadExecutor();
    protected LockRuntime runtime;

    protected abstract LockRuntime createRuntime() throws Exception;

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void mutexShouldExcludeConcurrentHolders() throws Exception {
        runtime = createRuntime();
        LockManager manager = runtime.lockManager();
        MutexLock lock = manager.mutex("inventory:1");

        lock.lock();
        try {
            assertThat(executor.submit(() -> manager.mutex("inventory:1").tryLock(Duration.ofMillis(100))).get()).isFalse();
        } finally {
            lock.unlock();
        }
    }

    @Test
    void sameThreadReentryShouldSucceed() throws Exception {
        runtime = createRuntime();
        LockManager manager = runtime.lockManager();
        MutexLock first = manager.mutex("inventory:2");
        MutexLock second = manager.mutex("inventory:2");

        first.lock();
        try {
            assertThat(second.tryLock(Duration.ZERO)).isTrue();
        } finally {
            second.unlock();
            first.unlock();
        }
    }

    @Test
    void unlockFromDifferentThreadShouldFail() throws Exception {
        runtime = createRuntime();
        LockManager manager = runtime.lockManager();
        MutexLock lock = manager.mutex("inventory:3");
        lock.lock();
        try {
            assertThatThrownBy(() -> executor.submit(lock::unlock).get())
                .isInstanceOf(ExecutionException.class);
        } finally {
            lock.unlock();
        }
    }

    @Test
    void closeShouldReleaseOneReentryLevel() throws Exception {
        runtime = createRuntime();
        LockManager manager = runtime.lockManager();
        MutexLock first = manager.mutex("inventory:4");
        MutexLock second = manager.mutex("inventory:4");

        first.lock();
        second.lock();
        second.close();
        assertThat(first.isHeldByCurrentThread()).isTrue();
        first.unlock();
    }

    @Test
    void readLocksShouldShareButWriteLocksShouldExclude() throws Exception {
        runtime = createRuntime();
        LockManager manager = runtime.lockManager();
        ReadWriteLock readWrite = manager.readWrite("inventory:5");
        MutexLock readLock = readWrite.readLock();
        MutexLock writeLock = readWrite.writeLock();

        readLock.lock();
        try {
            assertThat(executor.submit(() -> manager.readWrite("inventory:5").readLock().tryLock(Duration.ofMillis(100))).get())
                .isTrue();
            assertThat(executor.submit(() -> manager.readWrite("inventory:5").writeLock().tryLock(Duration.ofMillis(100))).get())
                .isFalse();
        } finally {
            readLock.unlock();
        }

        writeLock.lock();
        try {
            assertThat(executor.submit(() -> manager.readWrite("inventory:5").readLock().tryLock(Duration.ofMillis(100))).get())
                .isFalse();
        } finally {
            writeLock.unlock();
        }
    }
}
```

- [ ] **Step 2: Run the in-memory contract suite to verify the new coverage fails before support backends are added**

Run:

```bash
mvn -q -pl distributed-lock-testkit -am test -Dtest=InMemoryLockManagerContractTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the shared contract now asserts behaviors the current helper backends do not fully expose or support.

- [ ] **Step 3: Add the synthetic lease backends and adapt the in-memory backend/module to the simplified contract**

Create `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/BlockingLeaseBackend.java`:

```java
package com.mycorp.distributedlock.testkit.support;

import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BlockingLeaseBackend implements LockBackend {

    private final CountDownLatch acquireAttempted = new CountDownLatch(1);
    private final CountDownLatch releaseObserved = new CountDownLatch(1);
    private final AtomicBoolean firstLeaseHeld = new AtomicBoolean();

    @Override
    public BackendLockLease acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) throws InterruptedException {
        acquireAttempted.countDown();
        if (firstLeaseHeld.compareAndSet(false, true)) {
            return new TestLease(resource.key(), mode, true);
        }
        Thread.sleep(waitPolicy.unbounded() ? 5_000L : waitPolicy.waitTime().toMillis());
        return null;
    }

    public void awaitAcquireAttempt() throws InterruptedException {
        acquireAttempted.await(1, TimeUnit.SECONDS);
    }

    public boolean releaseObservedWithin(Duration duration) throws InterruptedException {
        return releaseObserved.await(duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    private final class TestLease implements BackendLockLease {
        private final String key;
        private final LockMode mode;
        private final boolean valid;

        private TestLease(String key, LockMode mode, boolean valid) {
            this.key = key;
            this.mode = mode;
            this.valid = valid;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public LockMode mode() {
            return mode;
        }

        @Override
        public boolean isValidForCurrentExecution() {
            return valid;
        }

        @Override
        public void release() {
            firstLeaseHeld.set(false);
            releaseObserved.countDown();
        }
    }
}
```

Create `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/OwnershipLossLeaseBackend.java`:

```java
package com.mycorp.distributedlock.testkit.support;

import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;

import java.util.concurrent.atomic.AtomicBoolean;

public final class OwnershipLossLeaseBackend implements LockBackend {

    private final AtomicBoolean valid = new AtomicBoolean(true);

    @Override
    public BackendLockLease acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) {
        valid.set(true);
        return new BackendLockLease() {
            @Override
            public String key() {
                return resource.key();
            }

            @Override
            public LockMode mode() {
                return mode;
            }

            @Override
            public boolean isValidForCurrentExecution() {
                return valid.get();
            }

            @Override
            public void release() {
                if (!valid.get()) {
                    throw new LockOwnershipLostException("Synthetic ownership loss for " + resource.key());
                }
                valid.set(false);
            }
        };
    }

    public void invalidateCurrentLease() {
        valid.set(false);
    }
}
```

Create `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/ReleaseFailureLeaseBackend.java`:

```java
package com.mycorp.distributedlock.testkit.support;

import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ReleaseFailureLeaseBackend implements LockBackend {

    private final AtomicBoolean firstRelease = new AtomicBoolean(true);

    @Override
    public BackendLockLease acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) {
        return new BackendLockLease() {
            @Override
            public String key() {
                return resource.key();
            }

            @Override
            public LockMode mode() {
                return mode;
            }

            @Override
            public boolean isValidForCurrentExecution() {
                return true;
            }

            @Override
            public void release() {
                if (firstRelease.compareAndSet(true, false)) {
                    throw new LockBackendException("Synthetic release failure");
                }
            }
        };
    }
}
```

Update `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryBackendModule.java`:

```java
@Override
public LockBackend createBackend() {
    return new InMemoryLockBackend();
}
```

- [ ] **Step 4: Run the shared contract suite again**

Run:

```bash
mvn -q -pl distributed-lock-testkit -am test -Dtest=InMemoryLockManagerContractTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. The in-memory backend now satisfies the expanded shared contract and the synthetic backends exist for the focused core tests already added in Task 2.

- [ ] **Step 5: Commit the testkit expansion**

```bash
git add distributed-lock-testkit
git commit -m "test: expand shared lock manager contracts"
```

## Task 4: Simplify Runtime SPI and Split Generic vs Backend-Specific Spring Wiring

**Files:**
- Modify: `pom.xml`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendModule.java`
- Delete: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendContext.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java`
- Modify: `distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryBackendModule.java`
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendModule.java`
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModule.java`
- Modify: `distributed-lock-spring-boot-starter/pom.xml`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockProperties.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockAutoConfiguration.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockAspect.java`
- Modify: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAutoConfigurationIntegrationTest.java`
- Modify: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAspectIntegrationTest.java`
- Create: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAsyncGuardTest.java`
- Delete: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/RedisStarterIntegrationTest.java`
- Delete: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/ZooKeeperStarterIntegrationTest.java`
- Create: `distributed-lock-redis-spring-boot-autoconfigure/pom.xml`
- Create: `distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockProperties.java`
- Create: `distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockAutoConfiguration.java`
- Create: `distributed-lock-redis-spring-boot-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `distributed-lock-redis-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/redis/springboot/integration/RedisBackendModuleAutoConfigurationTest.java`
- Create: `distributed-lock-redis-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/redis/springboot/integration/RedisStarterIntegrationTest.java`
- Create: `distributed-lock-zookeeper-spring-boot-autoconfigure/pom.xml`
- Create: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/zookeeper/springboot/config/ZooKeeperDistributedLockProperties.java`
- Create: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/zookeeper/springboot/config/ZooKeeperDistributedLockAutoConfiguration.java`
- Create: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/zookeeper/springboot/integration/ZooKeeperBackendModuleAutoConfigurationTest.java`
- Create: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/zookeeper/springboot/integration/ZooKeeperStarterIntegrationTest.java`

- [ ] **Step 1: Write failing runtime and Spring integration tests for the simplified SPI**

Update `distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java` to use the new no-context SPI:

```java
@Override
public LockBackend createBackend() {
    return new LockBackend() {
        @Override
        public BackendLockLease acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) {
            return new BackendLockLease() {
                @Override
                public String key() {
                    return resource.key();
                }

                @Override
                public LockMode mode() {
                    return mode;
                }

                @Override
                public boolean isValidForCurrentExecution() {
                    return true;
                }

                @Override
                public void release() {
                }
            };
        }
    };
}
```

Create `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAsyncGuardTest.java`:

```java
package com.mycorp.distributedlock.springboot.integration;

import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import com.mycorp.distributedlock.springboot.annotation.DistributedLock;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import com.mycorp.distributedlock.testkit.support.InMemoryBackendModule;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DistributedLockAsyncGuardTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, DistributedLockAutoConfiguration.class))
        .withUserConfiguration(TestApplication.class)
        .withPropertyValues("distributed.lock.enabled=true", "distributed.lock.backend=in-memory");

    @Test
    void annotatedAsyncMethodsShouldBeRejected() {
        contextRunner.run(context -> {
            AsyncService service = context.getBean(AsyncService.class);
            assertThatThrownBy(() -> service.async("42"))
                .isInstanceOf(LockConfigurationException.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestApplication {
        @Bean
        BackendModule inMemoryBackendModule() {
            return new InMemoryBackendModule("in-memory");
        }

        @Bean
        AsyncService asyncService() {
            return new AsyncService();
        }
    }

    static class AsyncService {
        @DistributedLock(key = "job:#{#p0}")
        CompletableFuture<String> async(String id) {
            return CompletableFuture.completedFuture(id);
        }
    }
}
```

Create `distributed-lock-redis-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/redis/springboot/integration/RedisBackendModuleAutoConfigurationTest.java`:

```java
package com.mycorp.distributedlock.redis.springboot.integration;

import com.mycorp.distributedlock.redis.RedisBackendModule;
import com.mycorp.distributedlock.redis.springboot.config.RedisDistributedLockAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RedisBackendModuleAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RedisDistributedLockAutoConfiguration.class));

    @Test
    void shouldExposeRedisBackendModuleWhenRedisBackendIsSelected() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis",
                "distributed.lock.redis.uri=redis://127.0.0.1:6379"
            )
            .run(context -> assertThat(context).hasSingleBean(RedisBackendModule.class));
    }
}
```

Create `distributed-lock-zookeeper-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/zookeeper/springboot/integration/ZooKeeperBackendModuleAutoConfigurationTest.java`:

```java
package com.mycorp.distributedlock.zookeeper.springboot.integration;

import com.mycorp.distributedlock.zookeeper.ZooKeeperBackendModule;
import com.mycorp.distributedlock.zookeeper.springboot.config.ZooKeeperDistributedLockAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ZooKeeperBackendModuleAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ZooKeeperDistributedLockAutoConfiguration.class));

    @Test
    void shouldExposeZooKeeperBackendModuleWhenZooKeeperBackendIsSelected() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=zookeeper",
                "distributed.lock.zookeeper.connect-string=127.0.0.1:2181"
            )
            .run(context -> assertThat(context).hasSingleBean(ZooKeeperBackendModule.class));
    }
}
```

- [ ] **Step 2: Run the runtime and Spring tests to verify they fail before the SPI split is implemented**

Run:

```bash
mvn -q -pl distributed-lock-runtime,distributed-lock-spring-boot-starter,distributed-lock-redis-spring-boot-autoconfigure,distributed-lock-zookeeper-spring-boot-autoconfigure -am test -Dtest=LockRuntimeBuilderTest,DistributedLockAutoConfigurationIntegrationTest,DistributedLockAspectIntegrationTest,DistributedLockAsyncGuardTest,RedisBackendModuleAutoConfigurationTest,ZooKeeperBackendModuleAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `BackendModule.createBackend()` still expects `BackendContext`, `LockRuntimeBuilder` still exposes `configuration(Object)`, and the generic starter still owns backend-specific property binding.

- [ ] **Step 3: Implement the no-context runtime SPI and the generic/backend-specific Spring split**

Replace `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendModule.java` with:

```java
package com.mycorp.distributedlock.runtime.spi;

import com.mycorp.distributedlock.core.backend.LockBackend;

public interface BackendModule {

    String id();

    BackendCapabilities capabilities();

    LockBackend createBackend();
}
```

Delete `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendContext.java`.

Replace `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java` with:

```java
package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.backend.SupportedLockModes;
import com.mycorp.distributedlock.core.manager.DefaultLockManager;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import com.mycorp.distributedlock.runtime.spi.ServiceLoaderBackendRegistry;

import java.util.ArrayList;
import java.util.List;

public final class LockRuntimeBuilder {

    private final List<BackendModule> explicitBackendModules = new ArrayList<>();
    private String backendId;

    private LockRuntimeBuilder() {
    }

    public static LockRuntimeBuilder create() {
        return new LockRuntimeBuilder();
    }

    public LockRuntimeBuilder backend(String backendId) {
        this.backendId = backendId;
        return this;
    }

    public LockRuntimeBuilder backendModules(List<BackendModule> backendModules) {
        this.explicitBackendModules.clear();
        if (backendModules != null) {
            this.explicitBackendModules.addAll(backendModules);
        }
        return this;
    }

    public LockRuntime build() {
        List<BackendModule> availableModules = explicitBackendModules.isEmpty()
            ? new ServiceLoaderBackendRegistry().discover()
            : List.copyOf(explicitBackendModules);

        BackendModule selectedModule = selectBackendModule(availableModules);
        LockBackend backend = selectedModule.createBackend();
        LockManager lockManager = new DefaultLockManager(
            backend,
            new SupportedLockModes(
                selectedModule.capabilities().mutexSupported(),
                selectedModule.capabilities().readWriteSupported()
            )
        );
        AutoCloseable backendResource = backend instanceof AutoCloseable closeable ? closeable : null;
        return new DefaultLockRuntime(lockManager, backendResource);
    }

    private BackendModule selectBackendModule(List<BackendModule> availableModules) {
        if (backendId != null && !backendId.isBlank()) {
            return availableModules.stream()
                .filter(module -> backendId.equals(module.id()))
                .findFirst()
                .orElseThrow(() -> new LockConfigurationException("Requested backend not found: " + backendId));
        }
        if (availableModules.isEmpty()) {
            throw new LockConfigurationException("No backend modules available");
        }
        if (availableModules.size() > 1) {
            throw new LockConfigurationException("Cannot select backend automatically: multiple backends available");
        }
        return availableModules.get(0);
    }
}
```

Update `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendModule.java`:

```java
@Override
public LockBackend createBackend() {
    RedisBackendConfiguration configuration = explicitConfiguration != null
        ? explicitConfiguration
        : RedisBackendConfiguration.defaultLocal();
    return new RedisLockBackend(configuration);
}
```

Update `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModule.java`:

```java
@Override
public LockBackend createBackend() {
    ZooKeeperBackendConfiguration configuration = explicitConfiguration != null
        ? explicitConfiguration
        : ZooKeeperBackendConfiguration.defaultLocal();
    return new ZooKeeperLockBackend(configuration);
}
```

Update `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryBackendModule.java`:

```java
@Override
public LockBackend createBackend() {
    return new InMemoryLockBackend();
}
```

Replace `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockProperties.java` with:

```java
package com.mycorp.distributedlock.springboot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "distributed.lock")
public class DistributedLockProperties {

    private boolean enabled = true;
    private String backend;
    private final Spring spring = new Spring();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }

    public Spring getSpring() {
        return spring;
    }

    public static final class Spring {
        private final Annotation annotation = new Annotation();

        public Annotation getAnnotation() {
            return annotation;
        }
    }

    public static final class Annotation {
        private boolean enabled = true;
        private Duration defaultTimeout;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getDefaultTimeout() {
            return defaultTimeout;
        }

        public void setDefaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = defaultTimeout;
        }
    }
}
```

Replace `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockAutoConfiguration.java` with:

```java
package com.mycorp.distributedlock.springboot.config;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import com.mycorp.distributedlock.springboot.aop.DistributedLockAspect;
import com.mycorp.distributedlock.springboot.key.LockKeyResolver;
import com.mycorp.distributedlock.springboot.key.SpelLockKeyResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
@EnableConfigurationProperties(DistributedLockProperties.class)
@ConditionalOnProperty(prefix = "distributed.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DistributedLockAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public LockRuntime lockRuntime(
        DistributedLockProperties properties,
        ObjectProvider<BackendModule> backendModules
    ) {
        LockRuntimeBuilder builder = LockRuntimeBuilder.create();
        if (properties.getBackend() != null && !properties.getBackend().isBlank()) {
            builder.backend(properties.getBackend());
        }
        List<BackendModule> modules = backendModules.orderedStream().toList();
        if (!modules.isEmpty()) {
            builder.backendModules(modules);
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public LockManager lockManager(LockRuntime runtime) {
        return runtime.lockManager();
    }

    @Bean
    @ConditionalOnMissingBean
    public LockKeyResolver lockKeyResolver() {
        return new SpelLockKeyResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "distributed.lock.spring.annotation",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
    )
    public DistributedLockAspect distributedLockAspect(
        LockManager lockManager,
        LockKeyResolver lockKeyResolver,
        DistributedLockProperties properties
    ) {
        return new DistributedLockAspect(lockManager, lockKeyResolver, properties);
    }
}
```

Update `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockAspect.java` so timeout and async failures are explicit:

```java
Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
Class<?> returnType = method.getReturnType();
if (CompletionStage.class.isAssignableFrom(returnType)) {
    throw new LockConfigurationException("`@DistributedLock` only supports synchronous return types");
}
```

```java
if (waitTimeout == null) {
    lock.lock();
} else if (!lock.tryLock(waitTimeout)) {
    throw new LockAcquisitionTimeoutException("Failed to acquire distributed lock for key " + key);
}
```

Add the two new backend Spring module poms following the existing starter pattern. `distributed-lock-redis-spring-boot-autoconfigure/pom.xml` should contain:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.mycorp</groupId>
        <artifactId>distributed-lock</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>distributed-lock-redis-spring-boot-autoconfigure</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>com.mycorp</groupId>
            <artifactId>distributed-lock-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.mycorp</groupId>
            <artifactId>distributed-lock-spring-boot-starter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

Create `distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockProperties.java`:

```java
package com.mycorp.distributedlock.redis.springboot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "distributed.lock.redis")
public class RedisDistributedLockProperties {

    private String uri = "redis://localhost:6379";
    private Duration leaseTime = Duration.ofSeconds(30);

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public Duration getLeaseTime() {
        return leaseTime;
    }

    public void setLeaseTime(Duration leaseTime) {
        this.leaseTime = leaseTime;
    }
}
```

Create `distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockAutoConfiguration.java`:

```java
package com.mycorp.distributedlock.redis.springboot.config;

import com.mycorp.distributedlock.redis.RedisBackendConfiguration;
import com.mycorp.distributedlock.redis.RedisBackendModule;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(RedisDistributedLockProperties.class)
@ConditionalOnProperty(prefix = "distributed.lock", name = "backend", havingValue = "redis")
public class RedisDistributedLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "redisBackendModule")
    public BackendModule redisBackendModule(RedisDistributedLockProperties properties) {
        return new RedisBackendModule(new RedisBackendConfiguration(
            properties.getUri(),
            properties.getLeaseTime().toSeconds()
        ));
    }
}
```

Set `distributed-lock-redis-spring-boot-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` to:

```text
com.mycorp.distributedlock.redis.springboot.config.RedisDistributedLockAutoConfiguration
```

Create `distributed-lock-zookeeper-spring-boot-autoconfigure/pom.xml`:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.mycorp</groupId>
        <artifactId>distributed-lock</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>distributed-lock-zookeeper-spring-boot-autoconfigure</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>com.mycorp</groupId>
            <artifactId>distributed-lock-zookeeper</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.mycorp</groupId>
            <artifactId>distributed-lock-spring-boot-starter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.curator</groupId>
            <artifactId>curator-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

Create `distributed-lock-zookeeper-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/zookeeper/springboot/config/ZooKeeperDistributedLockProperties.java`:

```java
package com.mycorp.distributedlock.zookeeper.springboot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "distributed.lock.zookeeper")
public class ZooKeeperDistributedLockProperties {

    private String connectString = "127.0.0.1:2181";
    private String basePath = "/distributed-locks";

    public String getConnectString() {
        return connectString;
    }

    public void setConnectString(String connectString) {
        this.connectString = connectString;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }
}
```

Create `distributed-lock-zookeeper-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/zookeeper/springboot/config/ZooKeeperDistributedLockAutoConfiguration.java`:

```java
package com.mycorp.distributedlock.zookeeper.springboot.config;

import com.mycorp.distributedlock.runtime.spi.BackendModule;
import com.mycorp.distributedlock.zookeeper.ZooKeeperBackendConfiguration;
import com.mycorp.distributedlock.zookeeper.ZooKeeperBackendModule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(ZooKeeperDistributedLockProperties.class)
@ConditionalOnProperty(prefix = "distributed.lock", name = "backend", havingValue = "zookeeper")
public class ZooKeeperDistributedLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "zooKeeperBackendModule")
    public BackendModule zooKeeperBackendModule(ZooKeeperDistributedLockProperties properties) {
        return new ZooKeeperBackendModule(new ZooKeeperBackendConfiguration(
            properties.getConnectString(),
            properties.getBasePath()
        ));
    }
}
```

Set `distributed-lock-zookeeper-spring-boot-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` to:

```text
com.mycorp.distributedlock.zookeeper.springboot.config.ZooKeeperDistributedLockAutoConfiguration
```

Update `distributed-lock-spring-boot-starter/pom.xml` so the generic starter depends only on:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<dependency>
    <groupId>com.mycorp</groupId>
    <artifactId>distributed-lock-api</artifactId>
</dependency>
<dependency>
    <groupId>com.mycorp</groupId>
    <artifactId>distributed-lock-runtime</artifactId>
</dependency>
```

Update the root `pom.xml` module list to:

```xml
<modules>
    <module>distributed-lock-api</module>
    <module>distributed-lock-core</module>
    <module>distributed-lock-runtime</module>
    <module>distributed-lock-testkit</module>
    <module>distributed-lock-redis</module>
    <module>distributed-lock-zookeeper</module>
    <module>distributed-lock-spring-boot-starter</module>
    <module>distributed-lock-redis-spring-boot-autoconfigure</module>
    <module>distributed-lock-zookeeper-spring-boot-autoconfigure</module>
    <module>distributed-lock-examples</module>
</modules>
```

- [ ] **Step 4: Run the runtime, generic starter, and backend Spring module tests**

Run:

```bash
mvn -q -pl distributed-lock-runtime,distributed-lock-spring-boot-starter,distributed-lock-redis-spring-boot-autoconfigure,distributed-lock-zookeeper-spring-boot-autoconfigure -am test -Dtest=LockRuntimeBuilderTest,DistributedLockAutoConfigurationIntegrationTest,DistributedLockAspectIntegrationTest,DistributedLockAsyncGuardTest,RedisBackendModuleAutoConfigurationTest,ZooKeeperBackendModuleAutoConfigurationTest,RedisStarterIntegrationTest,ZooKeeperStarterIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. Runtime no longer transports backend-specific configuration, the generic starter remains backend-agnostic, and the new backend Spring modules register typed `BackendModule` beans plus working integration paths.

- [ ] **Step 5: Commit the runtime/Spring split**

```bash
git add pom.xml distributed-lock-runtime distributed-lock-spring-boot-starter distributed-lock-redis-spring-boot-autoconfigure distributed-lock-zookeeper-spring-boot-autoconfigure distributed-lock-redis distributed-lock-zookeeper distributed-lock-testkit
git commit -m "refactor: split backend spring auto configuration"
```

## Task 5: Harden the Redis Adapter with Renewable Leases

**Files:**
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendModule.java`
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`
- Create: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisTestSupport.java`
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisBackendModuleTest.java`
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisLockBackendContractTest.java`
- Create: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisLeaseRenewalTest.java`
- Create: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisOwnershipLossTest.java`

- [ ] **Step 1: Write failing Redis renewal and ownership-loss tests**

Create `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisLeaseRenewalTest.java`:

```java
package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RedisLeaseRenewalTest {

    private static String containerId;
    private static int redisPort;

    @BeforeAll
    static void startRedis() throws Exception {
        containerId = RedisTestSupport.run("docker", "run", "-d", "-P", "redis:7-alpine").trim();
        String portOutput = RedisTestSupport.run("docker", "port", containerId, "6379/tcp").trim();
        redisPort = Integer.parseInt(portOutput.substring(portOutput.lastIndexOf(':') + 1));
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (containerId != null && !containerId.isBlank()) {
            RedisTestSupport.run("docker", "rm", "-f", containerId);
        }
    }

    @Test
    void leaseShouldRemainValidPastBaseTtlWhenHeld() throws Exception {
        try (RedisLockBackend backend = new RedisLockBackend(new RedisBackendConfiguration("redis://127.0.0.1:" + redisPort, 1L))) {
            BackendLockLease lease = backend.acquire(new LockResource("renew:1"), LockMode.MUTEX, WaitPolicy.indefinite());
            Thread.sleep(Duration.ofSeconds(3).toMillis());
            assertThat(lease.isValidForCurrentExecution()).isTrue();
            lease.release();
        }
    }
}
```

Create `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisTestSupport.java`:

```java
package com.mycorp.distributedlock.redis;

final class RedisTestSupport {

    private static String containerId;
    private static int redisPort;

    private RedisTestSupport() {
    }

    static void startRedis() throws Exception {
        if (containerId != null && !containerId.isBlank()) {
            return;
        }
        containerId = run("docker", "run", "-d", "-P", "redis:7-alpine").trim();
        String portOutput = run("docker", "port", containerId, "6379/tcp").trim();
        redisPort = Integer.parseInt(portOutput.substring(portOutput.lastIndexOf(':') + 1));
    }

    static void stopRedis() throws Exception {
        if (containerId != null && !containerId.isBlank()) {
            run("docker", "rm", "-f", containerId);
            containerId = null;
            redisPort = 0;
        }
    }

    static String redisUri() {
        return "redis://127.0.0.1:" + redisPort;
    }

    static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Command failed: " + String.join(" ", command) + "\n" + output);
        }
        return output;
    }
}
```

Create `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisOwnershipLossTest.java`:

```java
package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisOwnershipLossTest {

    @BeforeAll
    static void startRedis() throws Exception {
        RedisTestSupport.startRedis();
    }

    @AfterAll
    static void stopRedis() throws Exception {
        RedisTestSupport.stopRedis();
    }

    @Test
    void releaseShouldFailExplicitlyAfterTokenRemoval() throws Exception {
        String redisUri = RedisTestSupport.redisUri();
        try (RedisLockBackend backend = new RedisLockBackend(new RedisBackendConfiguration(redisUri, 30L));
             RedisClient redisClient = RedisClient.create(redisUri);
             StatefulRedisConnection<String, String> connection = redisClient.connect()) {
            BackendLockLease lease = backend.acquire(new LockResource("lost:1"), LockMode.MUTEX, WaitPolicy.indefinite());
            connection.sync().del("lost:1");

            assertThatThrownBy(lease::release)
                .isInstanceOf(LockOwnershipLostException.class);
        }
    }
}
```

- [ ] **Step 2: Run the Redis adapter tests to verify they fail before renewal is implemented**

Run:

```bash
mvn -q -pl distributed-lock-redis -am test -Dtest=RedisBackendModuleTest,RedisLockBackendContractTest,RedisLeaseRenewalTest,RedisOwnershipLossTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the current Redis lease expires after the fixed TTL, does not renew, and still wraps ownership loss as a generic backend failure.

- [ ] **Step 3: Implement typed module construction, renewable Redis leases, and explicit ownership-loss signaling**

Keep `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendModule.java` typed-only:

```java
package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;

public final class RedisBackendModule implements BackendModule {

    private final RedisBackendConfiguration explicitConfiguration;

    public RedisBackendModule() {
        this(RedisBackendConfiguration.defaultLocal());
    }

    public RedisBackendModule(RedisBackendConfiguration configuration) {
        this.explicitConfiguration = configuration;
    }

    @Override
    public String id() {
        return "redis";
    }

    @Override
    public BackendCapabilities capabilities() {
        return BackendCapabilities.standard();
    }

    @Override
    public LockBackend createBackend() {
        return new RedisLockBackend(explicitConfiguration);
    }
}
```

Update `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java` to own a shared renewal scheduler:

```java
private final ScheduledExecutorService renewalExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
    Thread thread = new Thread(runnable, "redis-lock-renewal");
    thread.setDaemon(true);
    return thread;
});
```

Return a renewable lease from `acquire(...)`:

```java
if (acquired) {
    return new RedisLease(resource.key(), mode, token, Thread.currentThread().getId(), scheduleRenewal(resource.key(), mode, token, leaseSeconds));
}
```

Add the refresh scripts near the other script constants:

```java
private static final String MUTEX_REFRESH_SCRIPT =
    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('expire', KEYS[1], tonumber(ARGV[2])) else return 0 end";

private static final String READ_REFRESH_SCRIPT =
    "if redis.call('hexists', KEYS[1], ARGV[1]) == 1 then return redis.call('expire', KEYS[1], tonumber(ARGV[2])) else return 0 end";

private static final String WRITE_REFRESH_SCRIPT = MUTEX_REFRESH_SCRIPT;
```

Implement the lease record:

```java
private final class RedisLease implements BackendLockLease {
    private final String key;
    private final LockMode mode;
    private final String token;
    private final long threadId;
    private final ScheduledFuture<?> renewalTask;

    private RedisLease(String key, LockMode mode, String token, long threadId, ScheduledFuture<?> renewalTask) {
        this.key = key;
        this.mode = mode;
        this.token = token;
        this.threadId = threadId;
        this.renewalTask = renewalTask;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public LockMode mode() {
        return mode;
    }

    @Override
    public boolean isValidForCurrentExecution() {
        if (threadId != Thread.currentThread().getId()) {
            return false;
        }
        return switch (mode) {
            case MUTEX -> token.equals(commands.get(key));
            case READ -> commands.hexists(readersKey(key), token);
            case WRITE -> token.equals(commands.get(writerKey(key)));
        };
    }

    @Override
    public void release() {
        renewalTask.cancel(false);
        Long result = switch (mode) {
            case MUTEX -> commands.eval(MUTEX_RELEASE_SCRIPT, ScriptOutputType.INTEGER, new String[]{key}, token);
            case READ -> commands.eval(READ_RELEASE_SCRIPT, ScriptOutputType.INTEGER, new String[]{readersKey(key)}, token);
            case WRITE -> commands.eval(WRITE_RELEASE_SCRIPT, ScriptOutputType.INTEGER, new String[]{writerKey(key)}, token);
        };
        if (result == null || result == 0L) {
            throw new LockOwnershipLostException("Redis lock ownership lost for key " + key);
        }
    }
}
```

Add the renewal task:

```java
private ScheduledFuture<?> scheduleRenewal(String key, LockMode mode, String token, long leaseSeconds) {
    long periodMillis = Math.max(250L, TimeUnit.SECONDS.toMillis(leaseSeconds) / 3L);
    return renewalExecutor.scheduleAtFixedRate(() -> renewLease(key, mode, token, leaseSeconds), periodMillis, periodMillis, TimeUnit.MILLISECONDS);
}
```

Implement `renewLease(...)` with refresh scripts for all three modes and wrap failures in `LockBackendException`:

```java
private void renewLease(String key, LockMode mode, String token, long leaseSeconds) {
    try {
        Long result = switch (mode) {
            case MUTEX -> commands.eval(MUTEX_REFRESH_SCRIPT, ScriptOutputType.INTEGER, new String[]{key}, token, String.valueOf(leaseSeconds));
            case READ -> commands.eval(READ_REFRESH_SCRIPT, ScriptOutputType.INTEGER, new String[]{readersKey(key)}, token, String.valueOf(leaseSeconds));
            case WRITE -> commands.eval(WRITE_REFRESH_SCRIPT, ScriptOutputType.INTEGER, new String[]{writerKey(key)}, token, String.valueOf(leaseSeconds));
        };
        if (result == null || result == 0L) {
            throw new LockOwnershipLostException("Redis lock ownership lost for key " + key);
        }
    } catch (RuntimeException exception) {
        if (exception instanceof LockOwnershipLostException) {
            throw exception;
        }
        throw new LockBackendException("Failed to renew Redis lock for key " + key, exception);
    }
}
```

Shut down the renewal scheduler in `close()`:

```java
renewalExecutor.shutdownNow();
connection.close();
redisClient.shutdown();
```

- [ ] **Step 4: Run the Redis adapter suite again**

Run:

```bash
mvn -q -pl distributed-lock-redis -am test -Dtest=RedisBackendModuleTest,RedisLockBackendContractTest,RedisLeaseRenewalTest,RedisOwnershipLossTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. Long-held Redis locks renew in place, typed module construction is the only configuration path, and token loss surfaces `LockOwnershipLostException`.

- [ ] **Step 5: Commit the Redis hardening**

```bash
git add distributed-lock-redis
git commit -m "feat: harden redis lease lifecycle"
```

## Task 6: Harden the ZooKeeper Adapter with Session-Aware Leases

**Files:**
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModule.java`
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`
- Modify: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModuleTest.java`
- Modify: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackendContractTest.java`
- Create: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperSessionLossTest.java`

- [ ] **Step 1: Write the failing ZooKeeper session-loss test**

Create `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperSessionLossTest.java`:

```java
package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZooKeeperSessionLossTest {

    @Test
    void leaseShouldReportOwnershipLossAfterSessionInvalidation() throws Exception {
        AtomicBoolean sessionValid = new AtomicBoolean(true);
        try (TestingServer server = new TestingServer();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(
                 new ZooKeeperBackendConfiguration(server.getConnectString(), "/distributed-locks"),
                 sessionValid::get
             )) {
            BackendLockLease lease = backend.acquire(new LockResource("zk:lost:1"), LockMode.MUTEX, WaitPolicy.indefinite());
            sessionValid.set(false);

            assertThat(lease.isValidForCurrentExecution()).isFalse();
            assertThatThrownBy(lease::release)
                .isInstanceOf(LockOwnershipLostException.class);
        }
    }
}
```

- [ ] **Step 2: Run the ZooKeeper adapter tests to verify they fail before session-aware leases are added**

Run:

```bash
mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperBackendModuleTest,ZooKeeperLockBackendContractTest,ZooKeeperSessionLossTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the current ZooKeeper backend returns passive key-only leases and has no session validity seam.

- [ ] **Step 3: Implement typed module construction and session-aware `ZooKeeperLease` handling**

Keep `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModule.java` typed-only:

```java
package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;

public final class ZooKeeperBackendModule implements BackendModule {

    private final ZooKeeperBackendConfiguration explicitConfiguration;

    public ZooKeeperBackendModule() {
        this(ZooKeeperBackendConfiguration.defaultLocal());
    }

    public ZooKeeperBackendModule(ZooKeeperBackendConfiguration configuration) {
        this.explicitConfiguration = configuration;
    }

    @Override
    public String id() {
        return "zookeeper";
    }

    @Override
    public BackendCapabilities capabilities() {
        return BackendCapabilities.standard();
    }

    @Override
    public LockBackend createBackend() {
        return new ZooKeeperLockBackend(explicitConfiguration);
    }
}
```

Add a testable constructor to `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`:

```java
ZooKeeperLockBackend(ZooKeeperBackendConfiguration configuration, BooleanSupplier sessionValidSupplier) {
    this.configuration = configuration;
    this.curatorFramework = CuratorFrameworkFactory.newClient(
        configuration.connectString(),
        new ExponentialBackoffRetry(1_000, 3)
    );
    this.curatorFramework.start();
    this.sessionValidSupplier = sessionValidSupplier != null
        ? sessionValidSupplier
        : () -> curatorFramework.getZookeeperClient().isConnected();
    try {
        this.curatorFramework.blockUntilConnected(10, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new LockBackendException("Interrupted while connecting to ZooKeeper", exception);
    }
}
```

Return concrete lease objects from `acquire(...)`:

```java
return switch (mode) {
    case MUTEX -> acquireMutex(resource, waitPolicy);
    case READ -> acquireRead(resource, waitPolicy);
    case WRITE -> acquireWrite(resource, waitPolicy);
};
```

Implement the lease types so they hold the actual acquired lock object:

```java
private final class ZooKeeperLease implements BackendLockLease {
    private final String key;
    private final LockMode mode;
    private final long threadId;
    private final InterProcessMutex acquiredLock;

    private ZooKeeperLease(String key, LockMode mode, long threadId, InterProcessMutex acquiredLock) {
        this.key = key;
        this.mode = mode;
        this.threadId = threadId;
        this.acquiredLock = acquiredLock;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public LockMode mode() {
        return mode;
    }

    @Override
    public boolean isValidForCurrentExecution() {
        return threadId == Thread.currentThread().getId()
            && sessionValidSupplier.getAsBoolean()
            && acquiredLock.isOwnedByCurrentThread();
    }

    @Override
    public void release() {
        if (!sessionValidSupplier.getAsBoolean()) {
            throw new LockOwnershipLostException("ZooKeeper session lost for key " + key);
        }
        try {
            acquiredLock.release();
        } catch (Exception exception) {
            throw new LockBackendException("Failed to release ZooKeeper lock for key " + key, exception);
        }
    }
}
```

Implement `acquireMutex(...)`, `acquireRead(...)`, and `acquireWrite(...)` to return `ZooKeeperLease` directly rather than booleans.

- [ ] **Step 4: Run the ZooKeeper adapter suite again**

Run:

```bash
mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperBackendModuleTest,ZooKeeperLockBackendContractTest,ZooKeeperSessionLossTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. ZooKeeper leases now keep the acquired lock object, can report session invalidation, and still satisfy the shared contract suite.

- [ ] **Step 5: Commit the ZooKeeper hardening**

```bash
git add distributed-lock-zookeeper
git commit -m "feat: harden zookeeper lease lifecycle"
```

## Task 7: Update Examples, Benchmarks, and Documentation to the Typed Module Model

**Files:**
- Modify: `distributed-lock-examples/pom.xml`
- Modify: `distributed-lock-examples/README.md`
- Modify: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/ProgrammaticRedisExample.java`
- Modify: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/ProgrammaticZooKeeperExample.java`
- Modify: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/spring/SpringBootRedisExampleApplication.java`
- Modify: `distributed-lock-examples/src/main/resources/application.yml`
- Modify: `distributed-lock-benchmarks/pom.xml`
- Modify: `distributed-lock-benchmarks/README.md`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/RedisBenchmarkEnvironment.java`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/ZooKeeperBenchmarkEnvironment.java`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/SpringBenchmarkEnvironment.java`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/SpringBenchmarkApplication.java`
- Modify: `distributed-lock-benchmarks/src/test/java/com/mycorp/distributedlock/benchmarks/BenchmarkEnvironmentSmokeTest.java`
- Modify: `distributed-lock-spring-boot-starter/README.md`
- Modify: `distributed-lock-test-suite/README.md`
- Modify: `distributed-lock-test-suite/TEST-CONFIGURATION.md`

- [ ] **Step 1: Run the examples compile and benchmark smoke tests to confirm the old runtime/starter configuration path is broken**

Run:

```bash
mvn -q -pl distributed-lock-examples -am -DskipTests compile
mvn -q -f distributed-lock-benchmarks/pom.xml test -Dtest=BenchmarkEnvironmentSmokeTest
```

Expected: FAIL because the examples and benchmark helpers still call `LockRuntimeBuilder.configuration(...)` and the benchmark Spring application still hand-wires a Redis `BackendModule` bean instead of relying on the new backend Spring auto-config module.

- [ ] **Step 2: Rewrite the examples and benchmark support code to use typed modules and backend Spring auto-config artifacts**

Update `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/ProgrammaticRedisExample.java`:

```java
package com.mycorp.distributedlock.examples;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.redis.RedisBackendConfiguration;
import com.mycorp.distributedlock.redis.RedisBackendModule;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;

import java.time.Duration;
import java.util.List;

public final class ProgrammaticRedisExample {

    private ProgrammaticRedisExample() {
    }

    public static void main(String[] args) throws Exception {
        try (LockRuntime runtime = LockRuntimeBuilder.create()
            .backend("redis")
            .backendModules(List.of(new RedisBackendModule(
                new RedisBackendConfiguration("redis://127.0.0.1:6379", 30L)
            )))
            .build()) {
            LockManager lockManager = runtime.lockManager();
            MutexLock lock = lockManager.mutex("example:redis:order-42");
            if (!lock.tryLock(Duration.ofSeconds(2))) {
                throw new IllegalStateException("Could not acquire Redis lock");
            }

            try (lock) {
                System.out.println("Redis lock acquired for " + lock.key());
            }
        }
    }
}
```

Update `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/ProgrammaticZooKeeperExample.java`:

```java
package com.mycorp.distributedlock.examples;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.zookeeper.ZooKeeperBackendConfiguration;
import com.mycorp.distributedlock.zookeeper.ZooKeeperBackendModule;

import java.time.Duration;
import java.util.List;

public final class ProgrammaticZooKeeperExample {

    private ProgrammaticZooKeeperExample() {
    }

    public static void main(String[] args) throws Exception {
        try (LockRuntime runtime = LockRuntimeBuilder.create()
            .backend("zookeeper")
            .backendModules(List.of(new ZooKeeperBackendModule(
                new ZooKeeperBackendConfiguration("127.0.0.1:2181", "/distributed-locks")
            )))
            .build()) {
            LockManager lockManager = runtime.lockManager();
            MutexLock lock = lockManager.mutex("example:zk:inventory-7");
            if (!lock.tryLock(Duration.ofSeconds(2))) {
                throw new IllegalStateException("Could not acquire ZooKeeper lock");
            }

            try (lock) {
                System.out.println("ZooKeeper lock acquired for " + lock.key());
            }
        }
    }
}
```

Update `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/RedisBenchmarkEnvironment.java`:

```java
LockRuntime runtime = LockRuntimeBuilder.create()
    .backend("redis")
    .backendModules(java.util.List.of(new RedisBackendModule(
        new RedisBackendConfiguration(redisUri, 30L)
    )))
    .build();
```

Update `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/ZooKeeperBenchmarkEnvironment.java`:

```java
LockRuntime runtime = LockRuntimeBuilder.create()
    .backend("zookeeper")
    .backendModules(java.util.List.of(new ZooKeeperBackendModule(
        new ZooKeeperBackendConfiguration(connectString, basePath)
    )))
    .build();
```

Remove the explicit backend bean from `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/SpringBenchmarkApplication.java` by deleting:

```java
@Bean
BackendModule redisBackendModule(@Value("${distributed.lock.redis.uri}") String redisUri) {
    return new RedisBackendModule(redisUri);
}
```

Update `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/SpringBenchmarkEnvironment.java` to rely on the new Redis Spring auto-config module by keeping only property values and classpath wiring:

```java
ConfigurableApplicationContext applicationContext = new SpringApplicationBuilder(SpringBenchmarkApplication.class)
    .properties(
        "spring.main.web-application-type=none",
        "distributed.lock.enabled=true",
        "distributed.lock.backend=redis",
        "distributed.lock.redis.uri=" + redisEnvironment.redisUri(),
        "distributed.lock.redis.lease-time=30s",
        "distributed.lock.spring.annotation.enabled=true",
        "distributed.lock.spring.annotation.default-timeout=250ms"
    )
    .run();
```

Add backend Spring module dependencies to `distributed-lock-examples/pom.xml`:

```xml
<dependency>
    <groupId>com.mycorp</groupId>
    <artifactId>distributed-lock-redis-spring-boot-autoconfigure</artifactId>
</dependency>
<dependency>
    <groupId>com.mycorp</groupId>
    <artifactId>distributed-lock-zookeeper-spring-boot-autoconfigure</artifactId>
</dependency>
```

Add the Redis Spring module dependency to `distributed-lock-benchmarks/pom.xml`:

```xml
<dependency>
    <groupId>com.mycorp</groupId>
    <artifactId>distributed-lock-redis-spring-boot-autoconfigure</artifactId>
</dependency>
```

Update `distributed-lock-examples/src/main/resources/application.yml` to keep Redis properties but add a clarifying comment that the backend Spring auto-config module must be on the classpath:

```yaml
distributed:
  lock:
    enabled: true
    backend: redis
    redis:
      uri: redis://127.0.0.1:6379
      lease-time: 30s
```

Update `distributed-lock-spring-boot-starter/README.md` so dependency setup is explicit with this XML snippet:

```xml
<dependency>
  <groupId>com.mycorp</groupId>
  <artifactId>distributed-lock-spring-boot-starter</artifactId>
</dependency>
<dependency>
  <groupId>com.mycorp</groupId>
  <artifactId>distributed-lock-redis-spring-boot-autoconfigure</artifactId>
</dependency>
```

Document in the same README that `@DistributedLock` only supports synchronous method bodies.

Update `distributed-lock-test-suite/README.md` and `distributed-lock-test-suite/TEST-CONFIGURATION.md` to list the new tests as plain bullets:

```text
- `DefaultLockManagerBlockingTest`
- `DefaultLockManagerOwnershipLossTest`
- `DefaultLockManagerCapabilitiesTest`
- `DefaultLockManagerReleaseFailureTest`
- `RedisLeaseRenewalTest`
- `RedisOwnershipLossTest`
- `ZooKeeperSessionLossTest`
- `DistributedLockAsyncGuardTest`
- `RedisBackendModuleAutoConfigurationTest`
- `ZooKeeperBackendModuleAutoConfigurationTest`
```

- [ ] **Step 3: Run the examples compile, benchmark smoke tests, and a full mainline verification pass**

Run:

```bash
mvn -q test
mvn -q -pl distributed-lock-examples -am -DskipTests compile
mvn -q -f distributed-lock-benchmarks/pom.xml test -Dtest=BenchmarkEnvironmentSmokeTest
```

Expected: PASS. The default reactor passes, the examples compile with typed runtime assembly and backend Spring modules, and the benchmark helper environments still start successfully.

- [ ] **Step 4: Commit the examples, benchmarks, and docs update**

```bash
git add distributed-lock-examples distributed-lock-benchmarks distributed-lock-spring-boot-starter/README.md distributed-lock-test-suite
git commit -m "docs: update lock usage for typed backend modules"
```
