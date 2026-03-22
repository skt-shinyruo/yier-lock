# Distributed Lock 2.0 Core Rebuild Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Rebuild this repository as a 2.0 distributed lock library with a small public API, a backend-agnostic core, a single runtime assembly model, Redis and ZooKeeper adapters, a thin Spring Boot 3 starter, and no 1.x compatibility layer.

**Architecture:** Repurpose the existing `distributed-lock-api`, `distributed-lock-core`, `distributed-lock-redis`, `distributed-lock-zookeeper`, and `distributed-lock-spring-boot-starter` modules around the approved 2.0 design, and add `distributed-lock-runtime`, `distributed-lock-testkit`, and `distributed-lock-examples`. Drive the rewrite from shared contract tests, let runtime own backend discovery and lifecycle, and delete 1.x abstractions instead of wrapping them.

**Tech Stack:** Java 17, Maven multi-module build, JUnit 5, AssertJ, Testcontainers, Curator `TestingServer`, Lettuce, Spring Boot 3.x, Java `ServiceLoader`

## Execution Status

- Completed on 2026-03-22.
- All mainline 2.0 tasks in this plan were implemented and verified with `mvn -q test`.
- `distributed-lock-benchmarks` remains outside the default reactor exactly as scoped here; benchmark modernization is still deferred.
- Two implementation details differ from the original filenames in this plan:
  - `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockManagerContract.java` was placed under `src/main/java` so Redis and ZooKeeper contract tests can extend it directly.
  - Example classes landed as `ProgrammaticRedisExample`, `ProgrammaticZooKeeperExample`, and `SpringBootRedisExampleApplication` instead of the earlier `Basic*` placeholders.

---

## Scope Boundary

This plan covers the mainline 2.0 rebuild only:

- public API
- core domain and backend port
- runtime assembly
- Redis adapter
- ZooKeeper adapter
- Spring Boot starter
- examples
- contract and integration tests
- deletion of 1.x abstractions

This plan explicitly excludes:

- optional extension modules (`async`, `batch`, `observability`, `health`)
- benchmark modernization

Write those as follow-up plans only after this plan lands and passes.

## File Structure

### Reactor and Build

- Modify: `pom.xml`
  Responsibility: define the 2.0 reactor, dependency management, Java 17 baseline, and correct module graph.
- Create: `distributed-lock-runtime/pom.xml`
  Responsibility: build definition for runtime assembly and SPI loading.
- Create: `distributed-lock-testkit/pom.xml`
  Responsibility: build definition for shared lock contract tests.
- Create: `distributed-lock-examples/pom.xml`
  Responsibility: build definition for real 2.0 examples that compile in the reactor.
- Modify: `distributed-lock-api/pom.xml`
- Modify: `distributed-lock-core/pom.xml`
- Modify: `distributed-lock-redis/pom.xml`
- Modify: `distributed-lock-zookeeper/pom.xml`
- Modify: `distributed-lock-spring-boot-starter/pom.xml`
  Responsibility: remove 1.x dependency pollution and align module dependencies with the 2.0 design.

### Public API

- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockManager.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/MutexLock.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/ReadWriteLock.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/LockBackendException.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/LockConfigurationException.java`
- Create: `distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/ApiSurfaceTest.java`
  Responsibility: define and protect the minimal 2.0 public surface.

Delete these 1.x API files once the new API compiles:

- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/DistributedLock.java`
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/DistributedLockFactory.java`
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/DistributedReadWriteLock.java`
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/AsyncLockOperations.java`
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/BatchLockOperations.java`
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/ServiceLoaderDistributedLockFactory.java`
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockProvider.java`
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockConfigurationBuilder.java`
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/HighAvailabilityStrategy.java`
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/PerformanceMetrics.java`
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/HealthCheck.java`
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockEvent.java`
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockEventListener.java`
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/GracefulShutdown.java`
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/annotation/DistributedReadLock.java`

### Core Domain

- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/LockBackend.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/BackendLockHandle.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/LockMode.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/LockResource.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/WaitPolicy.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultLockManager.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultMutexLock.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultReadWriteLock.java`
- Create: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/manager/DefaultLockManagerTest.java`
  Responsibility: hold backend-neutral semantics and orchestration only.

Delete the current 1.x `core` packages after the new core passes tests:

- `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/config`
- `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/lock`
- `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/observability`
- `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/optimization`
- `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/util`
- `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/ConfiguredDistributedLockFactory.java`
- `distributed-lock-core/src/main/resources/reference.conf`

### Runtime

- Create: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntime.java`
- Create: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java`
- Create: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/DefaultLockRuntime.java`
- Create: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendModule.java`
- Create: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendCapabilities.java`
- Create: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendContext.java`
- Create: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/ServiceLoaderBackendRegistry.java`
- Create: `distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java`
  Responsibility: own backend discovery, backend selection, and lifecycle.

### Testkit

- Create: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryBackendModule.java`
- Create: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryLockBackend.java`
- Create: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockManagerContract.java`
- Create: `distributed-lock-testkit/src/test/java/com/mycorp/distributedlock/testkit/InMemoryLockManagerContractTest.java`
  Responsibility: define one shared contract suite that every backend must satisfy.

### Redis

- Create: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendModule.java`
- Create: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`
- Create: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendConfiguration.java`
- Create: `distributed-lock-redis/src/main/resources/META-INF/services/com.mycorp.distributedlock.runtime.spi.BackendModule`
- Create: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisLockBackendContractTest.java`
- Create: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisBackendModuleTest.java`
  Responsibility: provide the Redis adapter only.

Delete the current 1.x Redis implementation once the contract test passes:

- `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/SimpleRedisLock.java`
- `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/SimpleRedisLockProvider.java`
- `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/EnhancedRedisReadWriteLock.java`
- `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBatchLockOperations.java`
- `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisClusterFactory.java`
- `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisDistributedLockFactory.java`
- `distributed-lock-redis/src/main/resources/META-INF/services/com.mycorp.distributedlock.api.LockProvider`

### ZooKeeper

- Create: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModule.java`
- Create: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`
- Create: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendConfiguration.java`
- Create: `distributed-lock-zookeeper/src/main/resources/META-INF/services/com.mycorp.distributedlock.runtime.spi.BackendModule`
- Create: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackendContractTest.java`
- Create: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModuleTest.java`
  Responsibility: provide the ZooKeeper adapter only.

Delete the current 1.x ZooKeeper implementation once the contract test passes:

- `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperDistributedLock.java`
- `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperDistributedReadWriteLock.java`
- `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperDistributedLockFactory.java`
- `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockProvider.java`
- `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/connection`
- `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/operation`
- `distributed-lock-zookeeper/src/main/resources/META-INF/services/com.mycorp.distributedlock.api.LockProvider`

### Spring Boot Starter

- Create: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockProperties.java`
- Create: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockAutoConfiguration.java`
- Create: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/annotation/DistributedLock.java`
- Create: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/annotation/DistributedLockMode.java`
- Create: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockAspect.java`
- Create: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/key/LockKeyResolver.java`
- Create: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/key/SpelLockKeyResolver.java`
- Create: `distributed-lock-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAspectIntegrationTest.java`
- Create: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAutoConfigurationIntegrationTest.java`
- Create: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/RedisStarterIntegrationTest.java`
- Create: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/ZooKeeperStarterIntegrationTest.java`
  Responsibility: make the starter a thin adapter over `LockRuntime`.

Delete the current 1.x starter classes when the new tests pass:

- `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/SpringDistributedLockFactory.java`
- `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/actuator`
- `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/health`
- `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/EnableDistributedLock.java`
- `distributed-lock-spring-boot-starter/src/main/resources/META-INF/spring.factories`
- `distributed-lock-spring-boot-starter/src/main/resources/META-INF/spring-configuration-metadata.json`

### Examples and Cleanup

- Create: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/ProgrammaticRedisExample.java`
- Create: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/ProgrammaticZooKeeperExample.java`
- Create: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/spring/SpringBootRedisExampleApplication.java`
- Modify: `distributed-lock-spring-boot-starter/README.md`
- Modify: `distributed-lock-test-suite/README.md`
- Modify: `distributed-lock-test-suite/TEST-CONFIGURATION.md`
  Responsibility: align docs and examples with the real 2.0 API and remove 1.x guidance.

Delete old example assets once new examples compile:

- `examples/`
- stale design/optimization reports that describe removed 1.x architecture and would mislead maintainers

## Task 1: Rewire the Reactor for 2.0

**Files:**
- Modify: `pom.xml`
- Create: `distributed-lock-runtime/pom.xml`
- Create: `distributed-lock-testkit/pom.xml`
- Create: `distributed-lock-examples/pom.xml`
- Modify: `distributed-lock-api/pom.xml`
- Modify: `distributed-lock-core/pom.xml`
- Modify: `distributed-lock-redis/pom.xml`
- Modify: `distributed-lock-zookeeper/pom.xml`
- Modify: `distributed-lock-spring-boot-starter/pom.xml`

- [x] **Step 1: Update the root reactor to the 2.0 module graph**

Set the root module list to:

```xml
<modules>
    <module>distributed-lock-api</module>
    <module>distributed-lock-core</module>
    <module>distributed-lock-runtime</module>
    <module>distributed-lock-testkit</module>
    <module>distributed-lock-redis</module>
    <module>distributed-lock-zookeeper</module>
    <module>distributed-lock-spring-boot-starter</module>
    <module>distributed-lock-examples</module>
</modules>
```

- [x] **Step 2: Create minimal `pom.xml` files for the new runtime, testkit, and examples modules**

Each new module should inherit from the root parent and declare only the dependencies needed by the 2.0 spec.

- [x] **Step 3: Rewrite existing module `pom.xml` files to match the approved dependency direction**

Critical outcomes:

- `distributed-lock-core` depends only on `distributed-lock-api`
- `distributed-lock-runtime` depends on `distributed-lock-api` and `distributed-lock-core`
- `distributed-lock-spring-boot-starter` depends on `distributed-lock-api`, `distributed-lock-runtime`, and Spring Boot 3
- Redis and ZooKeeper depend on `api`, `core`, `runtime`, and their own client libraries only

- [x] **Step 4: Remove Boot 2 metadata and set the starter baseline to Java 17 and Spring Boot 3.x**

Run: `mvn -q -DskipTests validate`

Expected: Maven reactor validation passes and recognizes the new modules.

- [x] **Step 5: Commit**

```bash
git add pom.xml \
  distributed-lock-api/pom.xml \
  distributed-lock-core/pom.xml \
  distributed-lock-runtime/pom.xml \
  distributed-lock-testkit/pom.xml \
  distributed-lock-redis/pom.xml \
  distributed-lock-zookeeper/pom.xml \
  distributed-lock-spring-boot-starter/pom.xml \
  distributed-lock-examples/pom.xml
git commit -m "build: rewire reactor for distributed lock 2.0"
```

## Task 2: Replace the Public API with the 2.0 Surface

**Files:**
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockManager.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/MutexLock.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/ReadWriteLock.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/LockBackendException.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/exception/LockConfigurationException.java`
- Test: `distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/ApiSurfaceTest.java`
- Delete: 1.x public API files listed in the file structure section

- [x] **Step 1: Write a failing API surface test**

```java
@Test
void lockManagerAndMutexLockShouldMatchTheApproved2xShape() throws Exception {
    assertThat(LockManager.class.getInterfaces()).isEmpty();
    assertThat(MutexLock.class.getInterfaces()).containsExactly(AutoCloseable.class);
    assertThat(LockManager.class.getMethod("mutex", String.class).getReturnType()).isEqualTo(MutexLock.class);
    assertThat(LockManager.class.getMethod("readWrite", String.class).getReturnType()).isEqualTo(ReadWriteLock.class);
    assertThat(MutexLock.class.getMethod("lock").getExceptionTypes()).containsExactly(InterruptedException.class);
    assertThat(MutexLock.class.getMethod("tryLock", Duration.class).getReturnType()).isEqualTo(boolean.class);
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl distributed-lock-api test -Dtest=ApiSurfaceTest`

Expected: FAIL because `LockManager`, `MutexLock`, `ReadWriteLock`, and the 2.0 exception classes do not exist yet.

- [x] **Step 3: Implement the minimal 2.0 API and exception classes**

Use this skeleton:

```java
public interface LockManager {
    MutexLock mutex(String key);
    ReadWriteLock readWrite(String key);
}
```

```java
public interface MutexLock extends AutoCloseable {
    void lock() throws InterruptedException;
    boolean tryLock(Duration waitTime) throws InterruptedException;
    void unlock();
    boolean isHeldByCurrentThread();
    String key();

    @Override
    default void close() {
        if (isHeldByCurrentThread()) {
            unlock();
        }
    }
}
```

- [x] **Step 4: Delete the 1.x API files instead of deprecating them**

Do not add compatibility wrappers.

- [x] **Step 5: Run test to verify it passes**

Run: `mvn -q -pl distributed-lock-api test -Dtest=ApiSurfaceTest`

Expected: PASS

- [x] **Step 6: Commit**

```bash
git add distributed-lock-api
git commit -m "feat: replace public api with 2.0 lock surface"
```

## Task 3: Build the Backend-Neutral Core

**Files:**
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/LockBackend.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/BackendLockHandle.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/LockMode.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/LockResource.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/WaitPolicy.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultLockManager.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultMutexLock.java`
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/manager/DefaultReadWriteLock.java`
- Test: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/manager/DefaultLockManagerTest.java`
- Delete: current 1.x core packages after new tests pass

- [x] **Step 1: Write the failing core orchestration test**

```java
@Test
void sameThreadReentryShouldBeTrackedByLogicalKeyNotHandleIdentity() throws Exception {
    FakeLockBackend backend = new FakeLockBackend();
    DefaultLockManager manager = new DefaultLockManager(backend);

    MutexLock first = manager.mutex("orders:1");
    MutexLock second = manager.mutex("orders:1");

    first.lock();
    assertThat(second.tryLock(Duration.ZERO)).isTrue();

    second.unlock();
    assertThat(first.isHeldByCurrentThread()).isTrue();
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl distributed-lock-core test -Dtest=DefaultLockManagerTest`

Expected: FAIL because the core port and manager classes do not exist yet.

- [x] **Step 3: Implement the backend port and value objects**

Use this starting point:

```java
public interface LockBackend {
    BackendLockHandle acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) throws InterruptedException;
    void release(BackendLockHandle handle);
    boolean isHeldByCurrentExecution(BackendLockHandle handle);
}
```

- [x] **Step 4: Implement `DefaultLockManager`, `DefaultMutexLock`, and `DefaultReadWriteLock`**

Rules to enforce in code:

- key validation uses `IllegalArgumentException`
- same-thread reentry is tracked by logical key
- non-owner `unlock()` throws `IllegalMonitorStateException`
- read-to-write and write-to-read on the same key fail fast

- [x] **Step 5: Delete the old `core` code instead of carrying it forward**

Do not preserve `config`, `observability`, `optimization`, `util`, or old factory helpers.

- [x] **Step 6: Run test to verify it passes**

Run: `mvn -q -pl distributed-lock-core test -Dtest=DefaultLockManagerTest`

Expected: PASS

- [x] **Step 7: Commit**

```bash
git add distributed-lock-core
git commit -m "feat: add backend-neutral 2.0 core"
```

## Task 4: Add Runtime Assembly and Shared Contract Tests

**Files:**
- Create: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntime.java`
- Create: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java`
- Create: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/DefaultLockRuntime.java`
- Create: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendModule.java`
- Create: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendCapabilities.java`
- Create: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendContext.java`
- Create: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/ServiceLoaderBackendRegistry.java`
- Create: `distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java`
- Create: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryBackendModule.java`
- Create: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryLockBackend.java`
- Create: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockManagerContract.java`
- Create: `distributed-lock-testkit/src/test/java/com/mycorp/distributedlock/testkit/InMemoryLockManagerContractTest.java`

- [x] **Step 1: Write the failing runtime selection test**

```java
@Test
void builderShouldFailWhenMultipleBackendsExistAndNoBackendIsConfigured() {
    LockRuntimeBuilder builder = LockRuntimeBuilder.create()
        .backendModules(List.of(new InMemoryBackendModule("redis"), new InMemoryBackendModule("zookeeper")));

    assertThatThrownBy(builder::build)
        .isInstanceOf(LockConfigurationException.class)
        .hasMessageContaining("multiple backends");
}
```

- [x] **Step 2: Write the failing contract test**

```java
@Test
void mutexShouldExcludeConcurrentHolders() throws Exception {
    LockManager manager = runtime.lockManager();
    MutexLock lock = manager.mutex("inventory:1");

    lock.lock();
    try {
        assertThat(executor.submit(() -> manager.mutex("inventory:1").tryLock(Duration.ofMillis(100))).get()).isFalse();
    } finally {
        lock.unlock();
    }
}
```

- [x] **Step 3: Run tests to verify they fail**

Run: `mvn -q -pl distributed-lock-runtime,distributed-lock-testkit -am test -Dtest=LockRuntimeBuilderTest,InMemoryLockManagerContractTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because runtime and in-memory test backend do not exist yet.

- [x] **Step 4: Implement runtime assembly and SPI loading**

Key rules:

- explicit backend selection wins
- zero backends fails
- multiple backends without configuration fails
- runtime owns backend close lifecycle

- [x] **Step 5: Implement the in-memory backend module and contract suite**

Contract suite must cover:

- mutex exclusion
- reentry semantics
- owner-only unlock
- read/write sharing and exclusion
- runtime shutdown behavior
- `MutexLock.close()` behavior

- [x] **Step 6: Run tests to verify they pass**

Run: `mvn -q -pl distributed-lock-runtime,distributed-lock-testkit -am test -Dtest=LockRuntimeBuilderTest,InMemoryLockManagerContractTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS

- [x] **Step 7: Commit**

```bash
git add distributed-lock-runtime distributed-lock-testkit
git commit -m "feat: add runtime assembly and lock contract tests"
```

## Task 5: Implement the Redis Backend Adapter

**Files:**
- Create: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendModule.java`
- Create: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`
- Create: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendConfiguration.java`
- Create: `distributed-lock-redis/src/main/resources/META-INF/services/com.mycorp.distributedlock.runtime.spi.BackendModule`
- Create: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisLockBackendContractTest.java`
- Create: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisBackendModuleTest.java`
- Delete: 1.x Redis classes listed above

- [x] **Step 1: Write the failing Redis contract test by reusing the shared contract suite**

```java
class RedisLockBackendContractTest extends LockManagerContract {
    @Override
    protected LockRuntime createRuntime() {
        return LockRuntimeBuilder.create()
            .backend("redis")
            .backendModules(List.of(new RedisBackendModule(redisUri)))
            .build();
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl distributed-lock-redis -am test -Dtest=RedisLockBackendContractTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because the Redis backend module and backend implementation do not exist yet.

- [x] **Step 3: Implement the Redis backend module and backend configuration**

Use this service declaration:

```text
com.mycorp.distributedlock.redis.RedisBackendModule
```

- [x] **Step 4: Implement `RedisLockBackend`**

Implementation requirements:

- token-based ownership
- reentry tracked by logical key within the manager/runtime
- Redis lease handling remains adapter-internal
- backend failures wrap into `LockBackendException`

- [x] **Step 5: Delete the old Redis factory/provider/lock classes**

Do not keep the old SPI service file for `com.mycorp.distributedlock.api.LockProvider`.

- [x] **Step 6: Run the Redis contract and module tests**

Run: `mvn -q -pl distributed-lock-redis -am test -Dtest=RedisLockBackendContractTest,RedisBackendModuleTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS

- [x] **Step 7: Commit**

```bash
git add distributed-lock-redis
git commit -m "feat: implement redis backend for lock runtime"
```

## Task 6: Implement the ZooKeeper Backend Adapter

**Files:**
- Create: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModule.java`
- Create: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`
- Create: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendConfiguration.java`
- Create: `distributed-lock-zookeeper/src/main/resources/META-INF/services/com.mycorp.distributedlock.runtime.spi.BackendModule`
- Create: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackendContractTest.java`
- Create: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModuleTest.java`
- Delete: 1.x ZooKeeper classes listed above

- [x] **Step 1: Write the failing ZooKeeper contract test**

```java
class ZooKeeperLockBackendContractTest extends LockManagerContract {
    @Override
    protected LockRuntime createRuntime() {
        return LockRuntimeBuilder.create()
            .backend("zookeeper")
            .backendModules(List.of(new ZooKeeperBackendModule(connectString)))
            .build();
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperLockBackendContractTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because the ZooKeeper backend implementation does not exist yet.

- [x] **Step 3: Implement the ZooKeeper backend module and backend configuration**

Keep ZooKeeper specifics internal:

- Curator client lifecycle
- path layout
- session handling
- lock recipe use

- [x] **Step 4: Implement `ZooKeeperLockBackend`**

Requirements:

- satisfy the same contract suite as Redis
- fail fast on unsupported same-thread read/write transitions
- wrap backend failures in `LockBackendException`

- [x] **Step 5: Delete the old ZooKeeper provider/factory/operation packages**

Delete the old SPI file for `com.mycorp.distributedlock.api.LockProvider`.

- [x] **Step 6: Run the ZooKeeper contract and module tests**

Run: `mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperLockBackendContractTest,ZooKeeperBackendModuleTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS

- [x] **Step 7: Commit**

```bash
git add distributed-lock-zookeeper
git commit -m "feat: implement zookeeper backend for lock runtime"
```

## Task 7: Rebuild the Spring Boot 3 Starter

**Files:**
- Create: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockProperties.java`
- Create: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockAutoConfiguration.java`
- Create: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/annotation/DistributedLock.java`
- Create: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/annotation/DistributedLockMode.java`
- Create: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockAspect.java`
- Create: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/key/LockKeyResolver.java`
- Create: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/key/SpelLockKeyResolver.java`
- Create: `distributed-lock-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAutoConfigurationIntegrationTest.java`
- Test: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAspectIntegrationTest.java`
- Test: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/RedisStarterIntegrationTest.java`
- Test: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/ZooKeeperStarterIntegrationTest.java`
- Delete: 1.x starter classes and resources listed above

- [x] **Step 1: Write the failing auto-configuration integration test**

```java
@Test
void contextShouldExposeLockRuntimeAndLockManagerBeans() {
    contextRunner
        .withPropertyValues(
            "distributed.lock.enabled=true",
            "distributed.lock.backend=redis",
            "distributed.lock.redis.uri=redis://localhost:" + redisPort
        )
        .run(context -> {
            assertThat(context).hasSingleBean(LockRuntime.class);
            assertThat(context).hasSingleBean(LockManager.class);
        });
}
```

- [x] **Step 2: Write the failing aspect integration test against a real backend**

```java
@Test
void annotatedMethodShouldSerializeConcurrentCalls() throws Exception {
    Future<String> first = executor.submit(() -> service.process("42"));
    Future<String> second = executor.submit(() -> service.process("42"));

    assertThat(first.get()).isEqualTo("ok");
    assertThat(second.get()).isEqualTo("ok");
    assertThat(service.maxObservedConcurrency()).isEqualTo(1);
}
```

- [x] **Step 3: Run tests to verify they fail**

Run: `mvn -q -pl distributed-lock-spring-boot-starter -am test -Dtest=DistributedLockAutoConfigurationIntegrationTest,DistributedLockAspectIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because the new starter configuration, annotation, and aspect do not exist yet.

- [x] **Step 4: Implement the Boot 3 starter on top of `LockRuntime`**

Rules:

- use `distributed.lock.*` only
- no custom Spring factory layer
- no provider adapter layer
- register auto-configuration through `AutoConfiguration.imports`

- [x] **Step 5: Implement `DistributedLock` annotation, `DistributedLockMode`, and key resolution**

Key rules:

- `waitFor` resolves through annotation first, then starter default timeout
- missing timeout falls back to unbounded `lock()`
- starter public enum must not reuse core backend `LockMode`

- [x] **Step 6: Delete old starter resources and classes**

Delete `spring.factories`, manual metadata, health/actuator wrappers, and the old `SpringDistributedLockFactory`.

- [x] **Step 7: Run the starter tests with real backends**

Run: `mvn -q -pl distributed-lock-spring-boot-starter -am test -Dtest=DistributedLockAutoConfigurationIntegrationTest,DistributedLockAspectIntegrationTest,RedisStarterIntegrationTest,ZooKeeperStarterIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS

- [x] **Step 8: Commit**

```bash
git add distributed-lock-spring-boot-starter
git commit -m "feat: rebuild spring boot starter on lock runtime"
```

## Task 8: Rewrite Examples and Remove the 1.x Surface

**Files:**
- Create: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/ProgrammaticRedisExample.java`
- Create: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/ProgrammaticZooKeeperExample.java`
- Create: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/spring/SpringBootRedisExampleApplication.java`
- Modify: `distributed-lock-spring-boot-starter/README.md`
- Modify: `distributed-lock-test-suite/README.md`
- Modify: `distributed-lock-test-suite/TEST-CONFIGURATION.md`
- Delete: `examples/`
- Delete: stale 1.x architecture reports that no longer describe the code

- [x] **Step 1: Write the failing example compile check**

Run: `mvn -q -pl distributed-lock-examples -am -DskipTests compile`

Expected: FAIL because the new examples module and 2.0 example classes do not exist yet.

- [x] **Step 2: Create the new examples module and rewrite the examples to use only the 2.0 API**

Programming example target:

```java
try (LockRuntime runtime = LockRuntimeBuilder.create().backend("redis").build()) {
    LockManager manager = runtime.lockManager();
    try (MutexLock lock = manager.mutex("orders:42")) {
        lock.lock();
        // work
    }
}
```

- [x] **Step 3: Rewrite the Spring example to use the new starter annotation and Boot 3**

Do not preserve old example APIs, fake timeout fields, or old factory names.

- [x] **Step 4: Update the README and test-suite docs to describe only the 2.0 surface**

Delete references to:

- `DistributedLockFactory`
- `LockProvider`
- `ServiceLoaderDistributedLockFactory`
- old starter metadata and health/metrics defaults

- [x] **Step 5: Delete the old `examples/` module and stale design reports**

The repository should no longer contain two competing example trees.

- [x] **Step 6: Run compile to verify examples are real reactor assets**

Run: `mvn -q -pl distributed-lock-examples -am -DskipTests compile`

Expected: PASS

- [x] **Step 7: Commit**

```bash
git add distributed-lock-examples distributed-lock-spring-boot-starter/README.md distributed-lock-test-suite
git commit -m "docs: align examples and docs with distributed lock 2.0"
```

## Task 9: Final Deletion Sweep and Full Verification

**Files:**
- Modify: any module files still referencing 1.x abstractions
- Delete: all remaining 1.x classes, resources, and tests that cannot compile against the 2.0 design

- [x] **Step 1: Remove any remaining compile-time references to the 1.x API surface**

Run:

```bash
rg -n "DistributedLockFactory|ServiceLoaderDistributedLockFactory|LockProvider|AsyncLockOperations|BatchLockOperations|SpringDistributedLockFactory" .
```

Expected: matches only in historical commits, this plan doc, or intentionally preserved benchmark follow-up notes. No production code or active tests should reference them.

- [x] **Step 2: Keep benchmarks out of the default reactor until a dedicated benchmark plan exists**

Concrete rule for this plan:

- `distributed-lock-benchmarks` remains in the repository
- `distributed-lock-benchmarks` is removed from the root `<modules>` list
- no production code may keep 1.x compatibility solely for benchmark compilation
- benchmark refresh is deferred to a separate plan after the 2.0 core rebuild passes

- [x] **Step 3: Run the focused module test suites**

Run:

```bash
mvn -q -pl distributed-lock-api,distributed-lock-core,distributed-lock-runtime,distributed-lock-testkit,distributed-lock-redis,distributed-lock-zookeeper,distributed-lock-spring-boot-starter,distributed-lock-examples -am test
```

Expected: PASS

- [x] **Step 4: Run a full reactor verification**

Run:

```bash
mvn -q test
```

Expected: PASS

- [x] **Step 5: Run final repository checks**

Run:

```bash
git status --short
```

Expected: only intentional changes remain.

- [x] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: complete distributed lock 2.0 core rebuild"
```
