# Distributed Lock Bootstrap and Capability Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make backend bootstrap explicit, preserve `ServiceLoader` discovery without allowing implicit backend creation, and enforce safety-critical backend capabilities before runtime or Spring startup succeeds.

**Architecture:** Tighten `distributed-lock-runtime` so `LockRuntimeBuilder` always requires an explicit backend id and validates expanded backend capabilities before calling `createBackend()`. Keep Redis and ZooKeeper typed modules configuration-only, introduce dedicated `ServiceLoader` shim modules that advertise ids/capabilities but throw on backend creation, and make the Spring starter consume only Spring `BackendModule` beans instead of falling back to classpath discovery. Finish by aligning backend-specific auto-configuration, testkit helpers, docs, examples, and benchmark bootstrap verification with the same explicit contract.

**Tech Stack:** Java 17, Maven, JUnit 5, AssertJ, Spring Boot 3.2, Lettuce, Curator/ZooKeeper

---

## File Map

- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendCapabilities.java`
  Expand capability metadata to include fencing and renewable-session guarantees.
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java`
  Require explicit backend selection and reject backends that lack kernel-required capabilities before `createBackend()`.
- Modify: `distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java`
  Cover missing backend id, duplicate ids, required-capability failures, and the guarantee that invalid modules never reach `createBackend()`.
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendModule.java`
  Keep only the typed Redis constructor.
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisServiceLoaderBackendModule.java`
  Provide discovery-only Redis `ServiceLoader` registration that refuses implicit backend creation.
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendConfiguration.java`
  Remove `defaultLocal()` so Redis bootstrap always flows through typed caller input.
- Modify: `distributed-lock-redis/src/main/resources/META-INF/services/com.mycorp.distributedlock.runtime.spi.BackendModule`
  Point runtime discovery at the Redis `ServiceLoader` shim.
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisBackendModuleTest.java`
  Verify typed constructor shape, `ServiceLoader` discovery, and explicit-configuration failure guidance.
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModule.java`
  Keep only the typed ZooKeeper constructor.
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperServiceLoaderBackendModule.java`
  Provide discovery-only ZooKeeper `ServiceLoader` registration that refuses implicit backend creation.
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendConfiguration.java`
  Remove `defaultLocal()` so ZooKeeper bootstrap always flows through typed caller input.
- Modify: `distributed-lock-zookeeper/src/main/resources/META-INF/services/com.mycorp.distributedlock.runtime.spi.BackendModule`
  Point runtime discovery at the ZooKeeper `ServiceLoader` shim.
- Modify: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModuleTest.java`
  Verify typed constructor shape, `ServiceLoader` discovery, and explicit-configuration failure guidance.
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockAutoConfiguration.java`
  Make the generic starter resolve only Spring-managed `BackendModule` beans and fail fast when none match.
- Modify: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAutoConfigurationIntegrationTest.java`
  Verify missing backend property, blank backend property, missing bean-backed module, ignored `ServiceLoader` providers, and unsafe capabilities.
- Modify: `distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockAutoConfiguration.java`
  Only publish a Redis backend bean when `distributed.lock.backend=redis`, and back off for user-supplied backend modules or runtimes.
- Modify: `distributed-lock-redis-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/redis/springboot/integration/RedisBackendModuleAutoConfigurationTest.java`
  Verify explicit backend selection and back-off rules.
- Modify: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/zookeeper/springboot/config/ZooKeeperDistributedLockAutoConfiguration.java`
  Only publish a ZooKeeper backend bean when `distributed.lock.backend=zookeeper`, and back off for user-supplied backend modules or runtimes.
- Modify: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/zookeeper/springboot/integration/ZooKeeperBackendModuleAutoConfigurationTest.java`
  Verify explicit backend selection and back-off rules.
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryBackendModule.java`
  Keep testkit backend capabilities aligned with the expanded runtime SPI.
- Modify: `distributed-lock-spring-boot-starter/README.md`
  Document that `distributed.lock.backend` is mandatory and that the generic starter does not auto-select from discovered modules.
- Modify: `distributed-lock-examples/README.md`
  State explicitly that programmatic runtime construction must call `.backend("...")`.
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/SpringBenchmarkEnvironment.java`
  Keep benchmark starter bootstrap aligned with the explicit backend property contract.

### Task 1: Require Explicit Backend Selection and Full Runtime Capabilities

**Files:**
- Modify: `distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendCapabilities.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java`

- [ ] **Step 1: Write the failing runtime tests**

Add or replace the coverage in `distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java` with:

```java
@Test
void builderShouldFailWhenNoBackendIsConfiguredEvenIfOneModuleExists() {
    LockRuntimeBuilder builder = LockRuntimeBuilder.create()
        .backendModules(List.of(new StubBackendModule("redis")));

    assertThatThrownBy(builder::build)
        .isInstanceOf(LockConfigurationException.class)
        .hasMessageContaining("backend id must be configured");
}

@Test
void builderShouldFailWhenBackendIsBlank() {
    LockRuntimeBuilder builder = LockRuntimeBuilder.create()
        .backend("   ")
        .backendModules(List.of(new StubBackendModule("redis")));

    assertThatThrownBy(builder::build)
        .isInstanceOf(LockConfigurationException.class)
        .hasMessageContaining("backend id must be configured");
}

@Test
void builderShouldRejectBackendWithoutMutexSupport() {
    LockRuntimeBuilder builder = LockRuntimeBuilder.create()
        .backend("redis")
        .backendModules(List.of(new StubBackendModule(
            "redis",
            new BackendCapabilities(false, true, true, true)
        )));

    assertThatThrownBy(builder::build)
        .isInstanceOf(LockConfigurationException.class)
        .hasMessageContaining("redis")
        .hasMessageContaining("mutexSupported");
}

@Test
void builderShouldRejectBackendWithoutFencingSupport() {
    LockRuntimeBuilder builder = LockRuntimeBuilder.create()
        .backend("redis")
        .backendModules(List.of(new StubBackendModule(
            "redis",
            new BackendCapabilities(true, true, false, true)
        )));

    assertThatThrownBy(builder::build)
        .isInstanceOf(LockConfigurationException.class)
        .hasMessageContaining("redis")
        .hasMessageContaining("fencingSupported");
}

@Test
void builderShouldRejectBackendWithoutRenewableSessionsSupport() {
    LockRuntimeBuilder builder = LockRuntimeBuilder.create()
        .backend("redis")
        .backendModules(List.of(new StubBackendModule(
            "redis",
            new BackendCapabilities(true, true, true, false)
        )));

    assertThatThrownBy(builder::build)
        .isInstanceOf(LockConfigurationException.class)
        .hasMessageContaining("redis")
        .hasMessageContaining("renewableSessionsSupported");
}

@Test
void builderShouldRejectInvalidCapabilitiesBeforeCreatingBackend() {
    TrackingBackendModule module = new TrackingBackendModule(
        "redis",
        new BackendCapabilities(false, true, true, true)
    );

    assertThatThrownBy(() -> LockRuntimeBuilder.create()
        .backend("redis")
        .backendModules(List.of(module))
        .build())
        .isInstanceOf(LockConfigurationException.class)
        .hasMessageContaining("mutexSupported");

    assertThat(module.createBackendAttempted()).isFalse();
}

@Test
void builderShouldExposeLockClientAndExecutorWithExplicitMutexOnlyBackend() throws Exception {
    try (LockRuntime runtime = LockRuntimeBuilder.create()
        .backend("mutex-only")
        .backendModules(List.of(new StubBackendModule(
            "mutex-only",
            new BackendCapabilities(true, false, true, true)
        )))
        .build()) {
        try (LockSession session = runtime.lockClient().openSession()) {
            try (LockLease lease = session.acquire(sampleRequest(LockMode.MUTEX))) {
                assertThat(lease.mode()).isEqualTo(LockMode.MUTEX);
            }

            assertThatThrownBy(() -> session.acquire(sampleRequest(LockMode.READ)))
                .isInstanceOf(UnsupportedLockCapabilityException.class)
                .hasMessageContaining("READ");
        }

        assertThat(runtime.lockExecutor().withLock(sampleRequest(LockMode.MUTEX), () -> "ok"))
            .isEqualTo("ok");
    }
}

@Test
void builderShouldUseExplicitlySelectedBackendWhenMultipleUniqueModulesExist() throws Exception {
    try (LockRuntime runtime = LockRuntimeBuilder.create()
        .backend("zookeeper")
        .backendModules(List.of(
            new StubBackendModule("redis", new BackendCapabilities(true, false, true, true)),
            new StubBackendModule("zookeeper", BackendCapabilities.standard())
        ))
        .build()) {
        try (LockSession session = runtime.lockClient().openSession()) {
            try (LockLease lease = session.acquire(sampleRequest(LockMode.READ))) {
                assertThat(lease.mode()).isEqualTo(LockMode.READ);
            }
        }
    }
}

@Test
void builderShouldRejectDuplicateBackendIdsExplicitly() {
    LockRuntimeBuilder builder = LockRuntimeBuilder.create()
        .backend("redis")
        .backendModules(List.of(new StubBackendModule("redis"), new StubBackendModule("redis")));

    assertThatThrownBy(builder::build)
        .isInstanceOf(LockConfigurationException.class)
        .hasMessageContaining("Duplicate backend modules")
        .hasMessageContaining("redis");
}
```

Keep the `StubBackendModule` helper convenience constructor delegating to `BackendCapabilities.standard()`, and add a `TrackingBackendModule` helper if the class does not already contain one.

- [ ] **Step 2: Run the focused runtime tests to verify they fail**

Run:

```bash
mvn -q -pl distributed-lock-runtime -am test -Dtest=LockRuntimeBuilderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `LockRuntimeBuilder` still auto-selects a single backend, the capability record is still incomplete, and invalid modules can still slip past bootstrap validation.

- [ ] **Step 3: Implement explicit backend selection and expanded capabilities**

Replace `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendCapabilities.java` with:

```java
package com.mycorp.distributedlock.runtime.spi;

public record BackendCapabilities(
    boolean mutexSupported,
    boolean readWriteSupported,
    boolean fencingSupported,
    boolean renewableSessionsSupported
) {

    public static BackendCapabilities standard() {
        return new BackendCapabilities(true, true, true, true);
    }
}
```

Update `selectBackendModule(...)` and `validateCapabilities(...)` in `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java` to:

```java
private BackendModule selectBackendModule(List<BackendModule> availableModules) {
    validateUniqueBackendIds(availableModules);

    if (backendId == null || backendId.isBlank()) {
        throw new LockConfigurationException("A backend id must be configured before building the lock runtime");
    }

    return availableModules.stream()
        .filter(module -> backendId.equals(module.id()))
        .findFirst()
        .orElseThrow(() -> new LockConfigurationException("Requested backend not found: " + backendId));
}

private void validateCapabilities(BackendModule module) {
    BackendCapabilities capabilities = module.capabilities();
    if (capabilities == null) {
        throw new LockConfigurationException("Backend module capabilities must not be null: " + module.id());
    }

    List<String> missingRequirements = new ArrayList<>();
    if (!capabilities.mutexSupported()) {
        missingRequirements.add("mutexSupported");
    }
    if (!capabilities.fencingSupported()) {
        missingRequirements.add("fencingSupported");
    }
    if (!capabilities.renewableSessionsSupported()) {
        missingRequirements.add("renewableSessionsSupported");
    }

    if (!missingRequirements.isEmpty()) {
        throw new LockConfigurationException(
            "Backend module does not satisfy runtime requirements: " + module.id() + " missing " + missingRequirements
        );
    }
}
```

Leave `build()` wired as: discover modules, resolve one module, validate capabilities, derive `SupportedLockModes`, create the backend, and return `DefaultLockRuntime`.

- [ ] **Step 4: Run the focused runtime tests to verify they pass**

Run:

```bash
mvn -q -pl distributed-lock-runtime -am test -Dtest=LockRuntimeBuilderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 5: Commit the runtime bootstrap hardening**

Run:

```bash
git add distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/BackendCapabilities.java \
        distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java \
        distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java
git commit -m "fix: require explicit backend runtime selection"
```

### Task 2: Split Redis Typed Bootstrap from ServiceLoader Discovery

**Files:**
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisBackendModuleTest.java`
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendModule.java`
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisServiceLoaderBackendModule.java`
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendConfiguration.java`
- Modify: `distributed-lock-redis/src/main/resources/META-INF/services/com.mycorp.distributedlock.runtime.spi.BackendModule`

- [ ] **Step 1: Write the failing Redis module tests**

Replace the module tests in `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisBackendModuleTest.java` with:

```java
@Test
void shouldExposeRedisBackendIdentityAndCapabilities() {
    RedisBackendModule module = new RedisBackendModule(new RedisBackendConfiguration("redis://localhost:6379", 30L));

    assertThat(module.id()).isEqualTo("redis");
    assertThat(module.capabilities()).isEqualTo(new BackendCapabilities(true, true, true, true));
}

@Test
void shouldExposeOnlyTypedConfigurationConstructor() {
    assertThat(Arrays.stream(RedisBackendModule.class.getConstructors())
        .map(constructor -> List.of(constructor.getParameterTypes())))
        .containsExactly(List.of(RedisBackendConfiguration.class));
}

@Test
void shouldAllowServiceLoaderToDiscoverRedisProvider() {
    BackendModule module = discoverRedisProvider();

    assertThat(module.id()).isEqualTo("redis");
    assertThat(module.capabilities()).isEqualTo(BackendCapabilities.standard());
}

@Test
void serviceLoadedRedisProviderShouldRequireExplicitTypedConfiguration() {
    BackendModule module = discoverRedisProvider();

    assertThatThrownBy(module::createBackend)
        .isInstanceOf(LockConfigurationException.class)
        .hasMessageContaining("Redis requires explicit typed configuration")
        .hasMessageContaining("new RedisBackendModule(new RedisBackendConfiguration(");
}

private BackendModule discoverRedisProvider() {
    return ServiceLoader.load(BackendModule.class).stream()
        .map(ServiceLoader.Provider::get)
        .filter(module -> module.id().equals("redis"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Redis BackendModule was not discovered"));
}
```

- [ ] **Step 2: Run the focused Redis tests to verify they fail**

Run:

```bash
mvn -q -pl distributed-lock-redis -am test -Dtest=RedisBackendModuleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the Redis typed module still exposes an implicit bootstrap path or the service registration still points at a backend-creating implementation.

- [ ] **Step 3: Implement typed Redis bootstrap plus a discovery-only ServiceLoader shim**

Replace `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendModule.java` with:

```java
package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;

import java.util.Objects;

public final class RedisBackendModule implements BackendModule {

    private final RedisBackendConfiguration configuration;

    public RedisBackendModule(RedisBackendConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
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
        return new RedisLockBackend(configuration);
    }
}
```

Create or replace `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisServiceLoaderBackendModule.java` with:

```java
package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;

public final class RedisServiceLoaderBackendModule implements BackendModule {

    public RedisServiceLoaderBackendModule() {
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
        throw new LockConfigurationException(
            "Redis requires explicit typed configuration. "
                + "Instantiate it with new RedisBackendModule(new RedisBackendConfiguration(\"redis://127.0.0.1:6379\", 30L))."
        );
    }
}
```

Trim `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendConfiguration.java` down to the validating canonical constructor:

```java
package com.mycorp.distributedlock.redis;

import java.util.Objects;

public record RedisBackendConfiguration(String redisUri, long leaseSeconds) {

    public RedisBackendConfiguration {
        Objects.requireNonNull(redisUri, "redisUri");
        if (redisUri.isBlank()) {
            throw new IllegalArgumentException("redisUri cannot be blank");
        }
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("leaseSeconds must be positive");
        }
    }
}
```

Set `distributed-lock-redis/src/main/resources/META-INF/services/com.mycorp.distributedlock.runtime.spi.BackendModule` to:

```text
com.mycorp.distributedlock.redis.RedisServiceLoaderBackendModule
```

- [ ] **Step 4: Run the focused Redis tests to verify they pass**

Run:

```bash
mvn -q -pl distributed-lock-redis -am test -Dtest=RedisBackendModuleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 5: Commit the Redis bootstrap split**

Run:

```bash
git add distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendModule.java \
        distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisServiceLoaderBackendModule.java \
        distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendConfiguration.java \
        distributed-lock-redis/src/main/resources/META-INF/services/com.mycorp.distributedlock.runtime.spi.BackendModule \
        distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisBackendModuleTest.java
git commit -m "refactor: harden redis backend bootstrap"
```

### Task 3: Split ZooKeeper Typed Bootstrap from ServiceLoader Discovery

**Files:**
- Modify: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModuleTest.java`
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModule.java`
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperServiceLoaderBackendModule.java`
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendConfiguration.java`
- Modify: `distributed-lock-zookeeper/src/main/resources/META-INF/services/com.mycorp.distributedlock.runtime.spi.BackendModule`

- [ ] **Step 1: Write the failing ZooKeeper module tests**

Replace the module tests in `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModuleTest.java` with:

```java
@Test
void shouldExposeZooKeeperBackendIdentityAndCapabilities() {
    ZooKeeperBackendModule module = new ZooKeeperBackendModule(
        new ZooKeeperBackendConfiguration("127.0.0.1:2181", "/distributed-locks")
    );

    assertThat(module.id()).isEqualTo("zookeeper");
    assertThat(module.capabilities()).isEqualTo(new BackendCapabilities(true, true, true, true));
}

@Test
void shouldExposeOnlyTypedConfigurationConstructor() {
    assertThat(Arrays.stream(ZooKeeperBackendModule.class.getConstructors())
        .map(constructor -> List.of(constructor.getParameterTypes())))
        .containsExactly(List.of(ZooKeeperBackendConfiguration.class));
}

@Test
void shouldAllowServiceLoaderToDiscoverZooKeeperProvider() {
    BackendModule module = discoverZooKeeperProvider();

    assertThat(module.id()).isEqualTo("zookeeper");
    assertThat(module.capabilities()).isEqualTo(BackendCapabilities.standard());
}

@Test
void serviceLoadedZooKeeperProviderShouldRequireExplicitTypedConfiguration() {
    BackendModule module = discoverZooKeeperProvider();

    assertThatThrownBy(module::createBackend)
        .isInstanceOf(LockConfigurationException.class)
        .hasMessageContaining("ZooKeeper requires explicit typed configuration")
        .hasMessageContaining("new ZooKeeperBackendModule(new ZooKeeperBackendConfiguration(");
}

private BackendModule discoverZooKeeperProvider() {
    return ServiceLoader.load(BackendModule.class).stream()
        .map(ServiceLoader.Provider::get)
        .filter(module -> module.id().equals("zookeeper"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("ZooKeeper BackendModule was not discovered"));
}
```

- [ ] **Step 2: Run the focused ZooKeeper tests to verify they fail**

Run:

```bash
mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperBackendModuleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the ZooKeeper typed module still exposes an implicit bootstrap path or the service registration still points at a backend-creating implementation.

- [ ] **Step 3: Implement typed ZooKeeper bootstrap plus a discovery-only ServiceLoader shim**

Replace `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModule.java` with:

```java
package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;

import java.util.Objects;

public final class ZooKeeperBackendModule implements BackendModule {

    private final ZooKeeperBackendConfiguration configuration;

    public ZooKeeperBackendModule(ZooKeeperBackendConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
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
        return new ZooKeeperLockBackend(configuration);
    }
}
```

Create or replace `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperServiceLoaderBackendModule.java` with:

```java
package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;

public final class ZooKeeperServiceLoaderBackendModule implements BackendModule {

    public ZooKeeperServiceLoaderBackendModule() {
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
        throw new LockConfigurationException(
            "ZooKeeper requires explicit typed configuration. "
                + "Instantiate it with new ZooKeeperBackendModule(new ZooKeeperBackendConfiguration(\"127.0.0.1:2181\", \"/distributed-locks\"))."
        );
    }
}
```

Trim `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendConfiguration.java` down to:

```java
package com.mycorp.distributedlock.zookeeper;

import java.util.Objects;

public record ZooKeeperBackendConfiguration(String connectString, String basePath) {

    public ZooKeeperBackendConfiguration {
        Objects.requireNonNull(connectString, "connectString");
        Objects.requireNonNull(basePath, "basePath");
        if (connectString.isBlank()) {
            throw new IllegalArgumentException("connectString cannot be blank");
        }
        if (basePath.isBlank() || !basePath.startsWith("/")) {
            throw new IllegalArgumentException("basePath must start with '/'");
        }
    }
}
```

Set `distributed-lock-zookeeper/src/main/resources/META-INF/services/com.mycorp.distributedlock.runtime.spi.BackendModule` to:

```text
com.mycorp.distributedlock.zookeeper.ZooKeeperServiceLoaderBackendModule
```

- [ ] **Step 4: Run the focused ZooKeeper tests to verify they pass**

Run:

```bash
mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperBackendModuleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 5: Commit the ZooKeeper bootstrap split**

Run:

```bash
git add distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModule.java \
        distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperServiceLoaderBackendModule.java \
        distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendConfiguration.java \
        distributed-lock-zookeeper/src/main/resources/META-INF/services/com.mycorp.distributedlock.runtime.spi.BackendModule \
        distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModuleTest.java
git commit -m "refactor: harden zookeeper backend bootstrap"
```

### Task 4: Make the Generic Spring Starter Resolve Only Bean-Backed Modules

**Files:**
- Modify: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAutoConfigurationIntegrationTest.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockAutoConfiguration.java`

- [ ] **Step 1: Write the failing generic starter tests**

Update `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAutoConfigurationIntegrationTest.java` so the runner setup and failure coverage include:

```java
private final ApplicationContextRunner emptyContextRunner = new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, DistributedLockAutoConfiguration.class));

private final ApplicationContextRunner contextRunner = emptyContextRunner
    .withUserConfiguration(TestBackendConfiguration.class);

@Test
void shouldFailWhenEnabledWithoutBackendProperty() {
    emptyContextRunner
        .withPropertyValues("distributed.lock.enabled=true")
        .run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasMessageContaining("backend id must be configured");
        });
}

@Test
void shouldFailWhenEnabledWithBlankBackendPropertyAndNoSpringBackendBeans() {
    emptyContextRunner
        .withPropertyValues(
            "distributed.lock.enabled=true",
            "distributed.lock.backend=   "
        )
        .run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasMessageContaining("backend id must be configured");
        });
}

@Test
void shouldFailWhenConfiguredBackendModuleIsMissing() {
    contextRunner
        .withPropertyValues(
            "distributed.lock.enabled=true",
            "distributed.lock.backend=redis"
        )
        .run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasMessageContaining("Requested backend not found: redis");
        });
}

@Test
void shouldIgnoreServiceLoaderBackendsWhenSpringHasNoBackendBeans() {
    emptyContextRunner
        .withPropertyValues(
            "distributed.lock.enabled=true",
            "distributed.lock.backend=service-loader-only"
        )
        .run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasMessageContaining("Requested backend not found: service-loader-only");
        });
}

@Test
void shouldFailWhenResolvedBackendLacksRequiredCapabilities() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, DistributedLockAutoConfiguration.class))
        .withUserConfiguration(UnsafeBackendConfiguration.class)
        .withPropertyValues(
            "distributed.lock.enabled=true",
            "distributed.lock.backend=unsafe"
        )
        .run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasMessageContaining("unsafe")
                .hasMessageContaining("fencingSupported")
                .hasMessageContaining("renewableSessionsSupported");
        });
}
```

Keep the existing happy-path test with `distributed.lock.backend=in-memory`, and keep the `UnsafeBackendConfiguration` helper returning `new BackendCapabilities(true, true, false, false)`.

- [ ] **Step 2: Run the focused starter tests to verify they fail**

Run:

```bash
mvn -q -pl distributed-lock-spring-boot-starter -am test -Dtest=DistributedLockAutoConfigurationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the starter still treats backend resolution too permissively or still consults classpath discovery when Spring provides no matching `BackendModule` bean.

- [ ] **Step 3: Make bean-backed backend resolution explicit in the generic starter**

Replace the beginning of `lockRuntime(...)` in `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockAutoConfiguration.java` with:

```java
@Bean(destroyMethod = "close")
@ConditionalOnMissingBean
public LockRuntime lockRuntime(
    DistributedLockProperties properties,
    ObjectProvider<BackendModule> backendModules
) {
    String backendId = properties.getBackend();
    LockRuntimeBuilder builder = LockRuntimeBuilder.create()
        .backend(backendId);

    List<BackendModule> modules = backendModules.orderedStream().toList();
    if (modules.isEmpty()) {
        if (backendId == null || backendId.isBlank()) {
            throw new LockConfigurationException("A backend id must be configured before building the lock runtime");
        }
        throw new LockConfigurationException("Requested backend not found: " + backendId);
    }

    builder.backendModules(modules);
    return builder.build();
}
```

Do not change `DistributedLockProperties`; keep `backend` optional at binding time so the starter can still back off cleanly when `distributed.lock.enabled=false`.

- [ ] **Step 4: Run the generic and end-to-end Spring tests to verify they pass**

Run:

```bash
mvn -q -pl distributed-lock-spring-boot-starter,distributed-lock-redis-spring-boot-autoconfigure,distributed-lock-zookeeper-spring-boot-autoconfigure -am test \
  -Dtest=DistributedLockAutoConfigurationIntegrationTest,RedisStarterIntegrationTest,ZooKeeperStarterIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 5: Commit the generic Spring bootstrap hardening**

Run:

```bash
git add distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockAutoConfiguration.java \
        distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAutoConfigurationIntegrationTest.java
git commit -m "fix: harden spring backend bootstrap selection"
```

### Task 5: Align Backend-Specific Spring Modules and Testkit Helpers

**Files:**
- Modify: `distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockAutoConfiguration.java`
- Modify: `distributed-lock-redis-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/redis/springboot/integration/RedisBackendModuleAutoConfigurationTest.java`
- Modify: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/zookeeper/springboot/config/ZooKeeperDistributedLockAutoConfiguration.java`
- Modify: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/zookeeper/springboot/integration/ZooKeeperBackendModuleAutoConfigurationTest.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryBackendModule.java`

- [ ] **Step 1: Write the failing backend-specific and testkit coverage**

Add or update the backend-specific auto-configuration tests with the following assertions:

```java
@Test
void shouldBackOffWhenBackendSelectionDoesNotMatch() {
    contextRunner
        .withPropertyValues(
            "distributed.lock.enabled=true",
            "distributed.lock.backend=zookeeper"
        )
        .run(context -> {
            assertThat(context).doesNotHaveBean(BackendModule.class);
            assertThat(context).doesNotHaveBean(RedisDistributedLockProperties.class);
        });
}

@Test
void shouldBackOffForUserSuppliedBackendModuleRegardlessOfBeanName() {
    contextRunner
        .withUserConfiguration(UserRedisBackendOverrideConfiguration.class)
        .withPropertyValues(
            "distributed.lock.enabled=true",
            "distributed.lock.backend=redis"
        )
        .run(context -> {
            assertThat(context).hasSingleBean(BackendModule.class);
            assertThat(context).doesNotHaveBean("redisBackendModule");
        });
}
```

```java
@Test
void shouldBackOffWhenBackendSelectionDoesNotMatch() {
    contextRunner
        .withPropertyValues(
            "distributed.lock.enabled=true",
            "distributed.lock.backend=redis"
        )
        .run(context -> {
            assertThat(context).doesNotHaveBean(BackendModule.class);
            assertThat(context).doesNotHaveBean(ZooKeeperDistributedLockProperties.class);
        });
}

@Test
void shouldBackOffForUserSuppliedBackendModuleRegardlessOfBeanName() {
    contextRunner
        .withUserConfiguration(UserZooKeeperBackendOverrideConfiguration.class)
        .withPropertyValues(
            "distributed.lock.enabled=true",
            "distributed.lock.backend=zookeeper"
        )
        .run(context -> {
            assertThat(context).hasSingleBean(BackendModule.class);
            assertThat(context).doesNotHaveBean("zooKeeperBackendModule");
        });
}
```

Keep the existing property-binding assertions for the matching backend path, and add a simple testkit assertion by using `InMemoryBackendModule("in-memory").capabilities()` wherever you already validate the in-memory backend helper.

- [ ] **Step 2: Run the focused backend-specific and testkit tests to verify they fail**

Run:

```bash
mvn -q -pl distributed-lock-redis-spring-boot-autoconfigure,distributed-lock-zookeeper-spring-boot-autoconfigure,distributed-lock-testkit -am test \
  -Dtest=RedisBackendModuleAutoConfigurationTest,ZooKeeperBackendModuleAutoConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the backend-specific modules still activate too broadly, do not back off for user-owned beans cleanly, or the testkit backend still uses the old capability shape.

- [ ] **Step 3: Restrict backend-specific bean publication and align testkit capabilities**

Ensure `distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockAutoConfiguration.java` looks like:

```java
@AutoConfiguration
@AutoConfigureBefore(name = "com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration")
@EnableConfigurationProperties(RedisDistributedLockProperties.class)
@ConditionalOnProperty(prefix = "distributed.lock", name = "backend", havingValue = "redis")
public class RedisDistributedLockAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "distributed.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean({ BackendModule.class, LockRuntime.class })
    public BackendModule redisBackendModule(RedisDistributedLockProperties properties) {
        RedisBackendConfiguration configuration = new RedisBackendConfiguration(
            requireUri(properties.getUri()),
            toLeaseSeconds(properties.getLeaseTime())
        );
        return new RedisBackendModule(configuration);
    }
}
```

Ensure `distributed-lock-zookeeper-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/zookeeper/springboot/config/ZooKeeperDistributedLockAutoConfiguration.java` looks like:

```java
@AutoConfiguration
@AutoConfigureBefore(name = "com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration")
@EnableConfigurationProperties(ZooKeeperDistributedLockProperties.class)
@ConditionalOnProperty(prefix = "distributed.lock", name = "backend", havingValue = "zookeeper")
public class ZooKeeperDistributedLockAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "distributed.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean({ BackendModule.class, LockRuntime.class })
    public BackendModule zooKeeperBackendModule(ZooKeeperDistributedLockProperties properties) {
        ZooKeeperBackendConfiguration configuration = new ZooKeeperBackendConfiguration(
            requireConnectString(properties.getConnectString()),
            requireBasePath(properties.getBasePath())
        );
        return new ZooKeeperBackendModule(configuration);
    }
}
```

Keep `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryBackendModule.java` aligned with the new SPI:

```java
@Override
public BackendCapabilities capabilities() {
    return BackendCapabilities.standard();
}
```

- [ ] **Step 4: Run the focused backend-specific and testkit tests to verify they pass**

Run:

```bash
mvn -q -pl distributed-lock-redis-spring-boot-autoconfigure,distributed-lock-zookeeper-spring-boot-autoconfigure,distributed-lock-testkit -am test \
  -Dtest=RedisBackendModuleAutoConfigurationTest,ZooKeeperBackendModuleAutoConfigurationTest,RedisStarterIntegrationTest,ZooKeeperStarterIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 5: Commit the backend-specific Spring and testkit alignment**

Run:

```bash
git add distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockAutoConfiguration.java \
        distributed-lock-redis-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/redis/springboot/integration/RedisBackendModuleAutoConfigurationTest.java \
        distributed-lock-zookeeper-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/zookeeper/springboot/config/ZooKeeperDistributedLockAutoConfiguration.java \
        distributed-lock-zookeeper-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/zookeeper/springboot/integration/ZooKeeperBackendModuleAutoConfigurationTest.java \
        distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryBackendModule.java
git commit -m "test: align backend modules with explicit spring bootstrap"
```

### Task 6: Update Docs, Examples, and Benchmark Verification

**Files:**
- Modify: `distributed-lock-spring-boot-starter/README.md`
- Modify: `distributed-lock-examples/README.md`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/SpringBenchmarkEnvironment.java`

- [ ] **Step 1: Update the README content to reflect the explicit bootstrap contract**

Update `distributed-lock-spring-boot-starter/README.md` so `Requirements` and `Configuration` include:

```md
## Requirements

- Java 17+
- Spring Boot 3.x
- one backend Spring auto-config module on the classpath, or an explicit backend module bean
- explicit `distributed.lock.backend` selection whenever `distributed.lock.enabled=true`
```

```md
The `backend` property is required. The generic starter will not auto-select a backend from discovered modules.
If the application defines any explicit `BackendModule` bean, backend-specific backend-module registration backs off.
At that point the application owns the full backend module registry and must supply a module whose `id()` matches `distributed.lock.backend`.
```

Add this sentence under `## Configuration model` in `distributed-lock-examples/README.md`:

```md
Programmatic runtime construction must always declare `.backend("...")`; backend discovery can supply candidate modules, but it is not allowed to auto-select one.
```

- [ ] **Step 2: Keep the benchmark Spring bootstrap on the explicit backend path**

Make sure `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/SpringBenchmarkEnvironment.java` passes the backend property explicitly:

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

- [ ] **Step 3: Re-run example and benchmark compile verification**

Run:

```bash
mvn -q -pl distributed-lock-examples -am -DskipTests compile
mvn -q install -DskipTests
mvn -q -f distributed-lock-benchmarks/pom.xml -DskipTests compile
```

Expected: PASS.

- [ ] **Step 4: Run the full stage verification**

Run:

```bash
mvn -q -pl distributed-lock-runtime,distributed-lock-redis,distributed-lock-zookeeper,distributed-lock-spring-boot-starter,distributed-lock-redis-spring-boot-autoconfigure,distributed-lock-zookeeper-spring-boot-autoconfigure,distributed-lock-testkit,distributed-lock-examples -am test \
  -Dtest=LockRuntimeBuilderTest,RedisBackendModuleTest,ZooKeeperBackendModuleTest,DistributedLockAutoConfigurationIntegrationTest,RedisBackendModuleAutoConfigurationTest,ZooKeeperBackendModuleAutoConfigurationTest,RedisStarterIntegrationTest,ZooKeeperStarterIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 5: Commit the documentation and verification sweep**

Run:

```bash
git add distributed-lock-spring-boot-starter/README.md \
        distributed-lock-examples/README.md \
        distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/SpringBenchmarkEnvironment.java
git commit -m "docs: clarify explicit backend bootstrap contract"
```
