# Lock Correctness Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden Redis, ZooKeeper, Spring AOP, and test execution behavior according to `docs/superpowers/specs/2026-04-29-lock-correctness-hardening-design.md`.

**Architecture:** Keep public APIs source-compatible, add explicit Redis configuration for fixed-lease renewal, key strategy, and renewal pool size, reject unsafe Spring async boundaries before invocation, and move Redis/Testcontainers checks behind an explicit Maven profile. Backend behavior changes stay local to each backend module; Spring auto-configuration only maps properties into the existing typed backend modules.

**Tech Stack:** Java 17, Maven, JUnit 5, AssertJ, Spring Boot 3.2 test runner, Lettuce Redis, Apache Curator/ZooKeeper, Testcontainers.

---

## File Structure

Modify these files:

- `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendConfiguration.java` - Redis backend typed configuration and validation.
- `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java` - renewal scheduler construction.
- `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendSession.java` - fixed-vs-renewable lease decision.
- `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLease.java` - renewable lease flag and renewal guard.
- `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisBackendConfigurationTest.java` - new unit tests for configuration defaults and validation.
- `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisFixedLeasePolicyTest.java` - update integration expectations for fixed lease expiry and compatibility renewal mode.
- `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/*.java` - add `@Tag("redis-integration")` to Testcontainers-backed classes only.
- `distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockProperties.java` - new Redis Spring properties.
- `distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockAutoConfiguration.java` - map new properties into typed configuration.
- `distributed-lock-redis-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/redis/springboot/integration/RedisBackendModuleAutoConfigurationTest.java` - property binding and pass-through tests.
- `distributed-lock-redis-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/redis/springboot/integration/RedisStarterIntegrationTest.java` - tag Testcontainers-backed starter integration test.
- `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendSession.java` - watcher wakeup behavior.
- `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperAcquireWaitLifecycleTest.java` - prompt wakeup regression test.
- `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockProperties.java` - `allowDynamicReturnType` property.
- `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockAspect.java` - early return-type guard updates.
- `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAsyncGuardTest.java` - default rejection and compatibility-mode tests.
- `distributed-lock-spring-boot-starter/src/test/java/kotlin/coroutines/Continuation.java` - test-only marker interface for coroutine signature detection.
- `pom.xml` - global Surefire tag exclusion and `redis-integration` profile.
- `distributed-lock-spring-boot-starter/README.md` - Redis property, fixed lease, cluster, and async-boundary docs.
- `distributed-lock-test-suite/README.md` - update verification commands and Testcontainers wording.

Do not modify public API records in `distributed-lock-api` for this plan.

---

### Task 1: Redis Configuration Model and Renewal Pool

**Files:**
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendConfiguration.java`
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`
- Create: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisBackendConfigurationTest.java`

- [ ] **Step 1: Write failing configuration tests**

Create `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisBackendConfigurationTest.java`:

```java
package com.mycorp.distributedlock.redis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisBackendConfigurationTest {

    @Test
    void defaultConstructorsShouldPreserveLegacyBehaviorWithSafeNewDefaults() {
        RedisBackendConfiguration configuration = new RedisBackendConfiguration("redis://127.0.0.1:6379", 30L);

        assertThat(configuration.redisUri()).isEqualTo("redis://127.0.0.1:6379");
        assertThat(configuration.leaseSeconds()).isEqualTo(30L);
        assertThat(configuration.keyStrategy()).isEqualTo(RedisKeyStrategy.LEGACY);
        assertThat(configuration.fixedLeaseRenewalEnabled()).isFalse();
        assertThat(configuration.renewalPoolSize()).isZero();
        assertThat(configuration.effectiveRenewalPoolSize()).isBetween(2, 8);
    }

    @Test
    void explicitConfigurationShouldExposeAllAdvancedOptions() {
        RedisBackendConfiguration configuration = new RedisBackendConfiguration(
            "redis://127.0.0.1:6379",
            45L,
            RedisKeyStrategy.HASH_TAGGED,
            true,
            4
        );

        assertThat(configuration.keyStrategy()).isEqualTo(RedisKeyStrategy.HASH_TAGGED);
        assertThat(configuration.fixedLeaseRenewalEnabled()).isTrue();
        assertThat(configuration.renewalPoolSize()).isEqualTo(4);
        assertThat(configuration.effectiveRenewalPoolSize()).isEqualTo(4);
    }

    @Test
    void renewalPoolSizeZeroShouldMeanComputedDefault() {
        RedisBackendConfiguration configuration = new RedisBackendConfiguration(
            "redis://127.0.0.1:6379",
            30L,
            RedisKeyStrategy.LEGACY,
            false,
            0
        );

        assertThat(configuration.effectiveRenewalPoolSize()).isBetween(2, 8);
    }

    @Test
    void shouldRejectNegativeRenewalPoolSize() {
        assertThatThrownBy(() -> new RedisBackendConfiguration(
            "redis://127.0.0.1:6379",
            30L,
            RedisKeyStrategy.LEGACY,
            false,
            -1
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("renewalPoolSize");
    }
}
```

- [ ] **Step 2: Run the new test to verify it fails**

Run:

```bash
mvn -pl distributed-lock-redis -Dtest=RedisBackendConfigurationTest test
```

Expected: compilation fails because `RedisBackendConfiguration` does not have `fixedLeaseRenewalEnabled()`, `renewalPoolSize()`, or `effectiveRenewalPoolSize()`.

- [ ] **Step 3: Extend `RedisBackendConfiguration`**

Replace `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendConfiguration.java` with:

```java
package com.mycorp.distributedlock.redis;

import java.util.Objects;

public record RedisBackendConfiguration(
    String redisUri,
    long leaseSeconds,
    RedisKeyStrategy keyStrategy,
    boolean fixedLeaseRenewalEnabled,
    int renewalPoolSize
) {

    static final int COMPUTED_RENEWAL_POOL_SIZE = 0;

    public RedisBackendConfiguration(String redisUri, long leaseSeconds) {
        this(redisUri, leaseSeconds, RedisKeyStrategy.LEGACY);
    }

    public RedisBackendConfiguration(String redisUri, long leaseSeconds, RedisKeyStrategy keyStrategy) {
        this(redisUri, leaseSeconds, keyStrategy, false, COMPUTED_RENEWAL_POOL_SIZE);
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
        if (renewalPoolSize < 0) {
            throw new IllegalArgumentException("renewalPoolSize must be zero or positive");
        }
    }

    int effectiveRenewalPoolSize() {
        if (renewalPoolSize > 0) {
            return renewalPoolSize;
        }
        int halfProcessors = Runtime.getRuntime().availableProcessors() / 2;
        return Math.max(2, Math.min(8, halfProcessors));
    }
}
```

- [ ] **Step 4: Use the configured renewal pool in `RedisLockBackend`**

In `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`, add:

```java
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
```

Replace the existing field:

```java
private final ScheduledExecutorService renewalExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
    Thread thread = new Thread(runnable, "redis-lock-renewal");
    thread.setDaemon(true);
    return thread;
});
```

with:

```java
private final ScheduledExecutorService renewalExecutor;
```

In the constructor, after `this.configuration = ...`, add:

```java
this.renewalExecutor = Executors.newScheduledThreadPool(
    configuration.effectiveRenewalPoolSize(),
    renewalThreadFactory()
);
```

Add this private helper before the static key helpers:

```java
private ThreadFactory renewalThreadFactory() {
    AtomicInteger sequence = new AtomicInteger();
    return runnable -> {
        Thread thread = new Thread(runnable, "redis-lock-renewal-" + sequence.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };
}
```

- [ ] **Step 5: Run Redis configuration tests**

Run:

```bash
mvn -pl distributed-lock-redis -Dtest=RedisBackendConfigurationTest,RedisBackendModuleTest,RedisKeyStrategyTest test
```

Expected: all listed tests pass without Docker.

- [ ] **Step 6: Commit Task 1**

```bash
git add distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendConfiguration.java \
  distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java \
  distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisBackendConfigurationTest.java
git commit -m "feat(redis): add explicit renewal configuration"
```

---

### Task 2: Redis Fixed-Lease Renewal Semantics

**Files:**
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendSession.java`
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLease.java`
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisFixedLeasePolicyTest.java`

- [ ] **Step 1: Replace fixed-lease renewal tests with expiry tests**

In `RedisFixedLeasePolicyTest`, replace these tests:

- `renewalShouldUseFixedLeaseDurationInsteadOfBackendDefault`
- `renewalShouldRefreshBeforeShortFixedLeaseCanExpire`
- `writeRenewalShouldUseFixedLeaseDurationInsteadOfBackendDefault`

with:

```java
@Test
void fixedMutexLeaseShouldExpireWithoutRenewalByDefault() throws Exception {
    try (RedisLockBackend backend = redis.newBackend(30L);
         BackendSession holderSession = backend.openSession();
         BackendLockLease lease = holderSession.acquire(mutexRequest(
             "redis:fixed:mutex-expiry",
             LeasePolicy.fixed(Duration.ofMillis(240))
         ));
         BackendSession contenderSession = backend.openSession()) {

        Thread.sleep(500L);

        assertThat(lease.isValid()).isFalse();
        try (BackendLockLease contenderLease = contenderSession.acquire(new LockRequest(
            new LockKey("redis:fixed:mutex-expiry"),
            LockMode.MUTEX,
            WaitPolicy.tryOnce(),
            LeasePolicy.backendDefault()
        ))) {
            assertThat(contenderLease.isValid()).isTrue();
        }
    }
}

@Test
void fixedWriteLeaseShouldExpireWithoutRenewalByDefault() throws Exception {
    try (RedisLockBackend backend = redis.newBackend(30L);
         BackendSession holderSession = backend.openSession();
         BackendLockLease lease = holderSession.acquire(writeRequest(
             "redis:fixed:write-expiry",
             LeasePolicy.fixed(Duration.ofMillis(240))
         ));
         BackendSession contenderSession = backend.openSession()) {

        Thread.sleep(500L);

        assertThat(lease.isValid()).isFalse();
        try (BackendLockLease contenderLease = contenderSession.acquire(new LockRequest(
            new LockKey("redis:fixed:write-expiry"),
            LockMode.WRITE,
            WaitPolicy.tryOnce(),
            LeasePolicy.backendDefault()
        ))) {
            assertThat(contenderLease.isValid()).isTrue();
        }
    }
}

@Test
void fixedLeaseRenewalCompatibilityModeShouldKeepMutexLeaseAlive() throws Exception {
    RedisBackendConfiguration configuration = new RedisBackendConfiguration(
        redis.redisUri(),
        30L,
        RedisKeyStrategy.LEGACY,
        true,
        0
    );
    try (RedisLockBackend backend = new RedisLockBackend(configuration);
         BackendSession session = backend.openSession();
         BackendLockLease lease = session.acquire(mutexRequest(
             "redis:fixed:compat-renewal",
             LeasePolicy.fixed(Duration.ofMillis(240))
         ))) {

        Thread.sleep(500L);

        assertThat(lease.isValid()).isTrue();
        assertThat(redis.commands().pttl(ownerKey("redis:fixed:compat-renewal", LockMode.MUTEX)))
            .isBetween(1L, 240L);
    }
}
```

- [ ] **Step 2: Run the modified Redis fixed-lease tests to verify failure**

Run:

```bash
mvn -pl distributed-lock-redis -Dtest=RedisFixedLeasePolicyTest test
```

Expected with Docker available: expiry tests fail because fixed leases are still renewed by default. Expected without Docker: environmental Testcontainers failure; continue implementation and run this command later with Docker.

- [ ] **Step 3: Add renewable flag to `RedisLease`**

In `RedisLease`, add a field:

```java
private final boolean renewable;
```

Change the constructor signature to include the flag:

```java
RedisLease(
    RedisLockBackend backend,
    LockKey key,
    LockMode mode,
    FencingToken fencingToken,
    String ownerValue,
    RedisBackendSession session,
    long leaseMillis,
    boolean renewable
) {
    this.backend = backend;
    this.key = key;
    this.mode = mode;
    this.fencingToken = fencingToken;
    this.ownerValue = ownerValue;
    this.session = session;
    this.leaseMillis = leaseMillis;
    this.renewable = renewable;
}
```

Add package-private accessor near `ownerValue()`:

```java
boolean renewable() {
    return renewable;
}
```

- [ ] **Step 4: Decide renewability in `RedisBackendSession`**

In `RedisBackendSession`, add:

```java
import com.mycorp.distributedlock.api.LeaseMode;
```

In `acquire(...)`, replace:

```java
lease.startRenewal();
```

with:

```java
if (lease.renewable()) {
    lease.startRenewal();
}
```

In `tryAcquire(...)`, pass the renewable flag when constructing `RedisLease`:

```java
return new RedisLease(
    backend,
    request.key(),
    request.mode(),
    new FencingToken(fence),
    RedisLockBackend.ownerValue(sessionId, fence),
    this,
    leaseMillis,
    renewable(request)
);
```

Add this helper near `effectiveLeaseMillis(...)`:

```java
private boolean renewable(LockRequest request) {
    return request.leasePolicy().mode() == LeaseMode.BACKEND_DEFAULT
        || backend.configuration().fixedLeaseRenewalEnabled();
}
```

- [ ] **Step 5: Run Redis fixed-lease tests**

Run with Docker/Testcontainers available:

```bash
mvn -pl distributed-lock-redis -Dtest=RedisFixedLeasePolicyTest test
```

Expected: all tests in `RedisFixedLeasePolicyTest` pass.

- [ ] **Step 6: Run non-Docker Redis unit tests**

Run:

```bash
mvn -pl distributed-lock-redis -Dtest=RedisBackendConfigurationTest,RedisBackendModuleTest,RedisKeyStrategyTest test
```

Expected: all listed tests pass without Docker.

- [ ] **Step 7: Commit Task 2**

```bash
git add distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendSession.java \
  distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLease.java \
  distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisFixedLeasePolicyTest.java
git commit -m "fix(redis): make fixed leases expire by default"
```

---

### Task 3: Redis Spring Boot Properties

**Files:**
- Modify: `distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockProperties.java`
- Modify: `distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockAutoConfiguration.java`
- Modify: `distributed-lock-redis-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/redis/springboot/integration/RedisBackendModuleAutoConfigurationTest.java`

- [ ] **Step 1: Write failing Spring property pass-through test**

In `RedisBackendModuleAutoConfigurationTest`, add imports:

```java
import com.mycorp.distributedlock.redis.RedisBackendConfiguration;
import com.mycorp.distributedlock.redis.RedisKeyStrategy;
import java.lang.reflect.Field;
```

Add this test after `shouldBindRedisPropertiesAndExposeBackendModule()`:

```java
@Test
void shouldBindAdvancedRedisPropertiesAndPassThemToBackendConfiguration() {
    contextRunner
        .withPropertyValues(
            "distributed.lock.enabled=true",
            "distributed.lock.backend=redis",
            "distributed.lock.redis.uri=redis://127.0.0.1:6380",
            "distributed.lock.redis.lease-time=45s",
            "distributed.lock.redis.key-strategy=hash-tagged",
            "distributed.lock.redis.fixed-lease-renewal-enabled=true",
            "distributed.lock.redis.renewal-pool-size=4"
        )
        .run(context -> {
            RedisDistributedLockProperties properties = context.getBean(RedisDistributedLockProperties.class);
            assertThat(properties.getKeyStrategy()).isEqualTo(RedisKeyStrategy.HASH_TAGGED);
            assertThat(properties.isFixedLeaseRenewalEnabled()).isTrue();
            assertThat(properties.getRenewalPoolSize()).isEqualTo(4);

            RedisBackendConfiguration configuration = redisConfiguration(context.getBean(BackendModule.class));
            assertThat(configuration.keyStrategy()).isEqualTo(RedisKeyStrategy.HASH_TAGGED);
            assertThat(configuration.fixedLeaseRenewalEnabled()).isTrue();
            assertThat(configuration.renewalPoolSize()).isEqualTo(4);
        });
}
```

Add this helper near the bottom of the test class:

```java
private static RedisBackendConfiguration redisConfiguration(BackendModule module) {
    try {
        Field field = RedisBackendModule.class.getDeclaredField("configuration");
        field.setAccessible(true);
        return (RedisBackendConfiguration) field.get(module);
    } catch (ReflectiveOperationException exception) {
        throw new AssertionError("Failed to inspect Redis backend configuration", exception);
    }
}
```

- [ ] **Step 2: Run the new Spring auto-configuration test to verify failure**

Run:

```bash
mvn -pl distributed-lock-redis-spring-boot-autoconfigure -Dtest=RedisBackendModuleAutoConfigurationTest test
```

Expected: compilation fails because `RedisDistributedLockProperties` lacks the new accessors.

- [ ] **Step 3: Add Redis Spring properties**

In `RedisDistributedLockProperties`, add import:

```java
import com.mycorp.distributedlock.redis.RedisKeyStrategy;
```

Add fields:

```java
private RedisKeyStrategy keyStrategy = RedisKeyStrategy.LEGACY;
private boolean fixedLeaseRenewalEnabled;
private int renewalPoolSize;
```

Add accessors:

```java
public RedisKeyStrategy getKeyStrategy() {
    return keyStrategy;
}

public void setKeyStrategy(RedisKeyStrategy keyStrategy) {
    this.keyStrategy = keyStrategy;
}

public boolean isFixedLeaseRenewalEnabled() {
    return fixedLeaseRenewalEnabled;
}

public void setFixedLeaseRenewalEnabled(boolean fixedLeaseRenewalEnabled) {
    this.fixedLeaseRenewalEnabled = fixedLeaseRenewalEnabled;
}

public int getRenewalPoolSize() {
    return renewalPoolSize;
}

public void setRenewalPoolSize(int renewalPoolSize) {
    this.renewalPoolSize = renewalPoolSize;
}
```

- [ ] **Step 4: Map properties in auto-configuration**

In `RedisDistributedLockAutoConfiguration.redisBackendModule(...)`, replace:

```java
RedisBackendConfiguration configuration = new RedisBackendConfiguration(
    requireUri(properties.getUri()),
    toLeaseSeconds(properties.getLeaseTime())
);
```

with:

```java
RedisBackendConfiguration configuration = new RedisBackendConfiguration(
    requireUri(properties.getUri()),
    toLeaseSeconds(properties.getLeaseTime()),
    properties.getKeyStrategy(),
    properties.isFixedLeaseRenewalEnabled(),
    properties.getRenewalPoolSize()
);
```

Add validation in `redisBackendModule(...)` before building the configuration:

```java
if (properties.getRenewalPoolSize() < 0) {
    throw new IllegalArgumentException("distributed.lock.redis.renewal-pool-size must be zero or positive");
}
```

- [ ] **Step 5: Add a negative renewal pool property test**

In `RedisBackendModuleAutoConfigurationTest`, add:

```java
@Test
void shouldRejectNegativeRenewalPoolSize() {
    contextRunner
        .withPropertyValues(
            "distributed.lock.enabled=true",
            "distributed.lock.backend=redis",
            "distributed.lock.redis.uri=redis://127.0.0.1:6380",
            "distributed.lock.redis.lease-time=45s",
            "distributed.lock.redis.renewal-pool-size=-1"
        )
        .run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasMessageContaining("renewal-pool-size");
        });
}
```

- [ ] **Step 6: Run Redis Spring auto-configuration tests**

Run:

```bash
mvn -pl distributed-lock-redis-spring-boot-autoconfigure -Dtest=RedisBackendModuleAutoConfigurationTest test
```

Expected: `RedisBackendModuleAutoConfigurationTest` passes without Docker.

- [ ] **Step 7: Commit Task 3**

```bash
git add distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockProperties.java \
  distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockAutoConfiguration.java \
  distributed-lock-redis-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/redis/springboot/integration/RedisBackendModuleAutoConfigurationTest.java
git commit -m "feat(redis-spring): expose advanced redis lock settings"
```

---

### Task 4: Spring Async Boundary Guard

**Files:**
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockProperties.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockAspect.java`
- Modify: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAsyncGuardTest.java`
- Create: `distributed-lock-spring-boot-starter/src/test/java/kotlin/coroutines/Continuation.java`

- [ ] **Step 1: Write failing async guard tests**

In `DistributedLockAsyncGuardTest`, replace `objectReturningAsyncValueShouldBeRejectedByExecutorDefenseInDepth()` with:

```java
@Test
void objectReturnTypeShouldBeRejectedBeforeInvocationByDefault() {
    contextRunner.run(context -> {
        AsyncService service = context.getBean(AsyncService.class);

        assertThatThrownBy(() -> service.processObjectAsync("42"))
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("Object");
        assertThat(service.wasInvoked()).isFalse();
    });
}

@Test
void objectReturnTypeCompatibilityModeShouldKeepExecutorDefenseInDepth() {
    contextRunner
        .withPropertyValues("distributed.lock.spring.annotation.allow-dynamic-return-type=true")
        .run(context -> {
            AsyncService service = context.getBean(AsyncService.class);

            assertThatThrownBy(() -> service.processObjectAsync("42"))
                .isInstanceOf(LockConfigurationException.class)
                .hasMessageContaining("CompletionStage");
            assertThat(service.wasInvoked()).isTrue();
        });
}
```

Add a test:

```java
@Test
void kotlinCoroutineSignatureShouldBeRejectedBeforeInvocation() {
    contextRunner.run(context -> {
        AsyncService service = context.getBean(AsyncService.class);

        assertThatThrownBy(() -> service.processCoroutine("42", null))
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("Kotlin coroutine");
        assertThat(service.wasInvoked()).isFalse();
    });
}
```

In `AsyncService`, add:

```java
@DistributedLock(key = "job:#{#p0}")
public Object processCoroutine(String jobId, kotlin.coroutines.Continuation<String> continuation) {
    invoked.set(true);
    return "processed-" + jobId;
}
```

Create `distributed-lock-spring-boot-starter/src/test/java/kotlin/coroutines/Continuation.java`:

```java
package kotlin.coroutines;

public interface Continuation<T> {
}
```

- [ ] **Step 2: Run async guard tests to verify failure**

Run:

```bash
mvn -pl distributed-lock-spring-boot-starter -Dtest=DistributedLockAsyncGuardTest test
```

Expected: tests fail because `Object` return types and coroutine signatures are not rejected before invocation.

- [ ] **Step 3: Add Spring compatibility property**

In `DistributedLockProperties.Annotation`, add:

```java
private boolean allowDynamicReturnType;
```

Add accessors:

```java
public boolean isAllowDynamicReturnType() {
    return allowDynamicReturnType;
}

public void setAllowDynamicReturnType(boolean allowDynamicReturnType) {
    this.allowDynamicReturnType = allowDynamicReturnType;
}
```

- [ ] **Step 4: Update `DistributedLockAspect` return-type guard**

In `ensureSynchronousReturnType(Method method)`, replace the method body with:

```java
Class<?> returnType = method.getReturnType();
if (isKotlinCoroutineMethod(method)) {
    throw new LockConfigurationException(
        "@DistributedLock does not support Kotlin coroutine methods: " + method
    );
}
if (Object.class.equals(returnType) && !properties.getSpring().getAnnotation().isAllowDynamicReturnType()) {
    throw new LockConfigurationException(
        "@DistributedLock does not support Object return type by default because async results cannot be rejected before invocation: "
            + method
    );
}
if (CompletionStage.class.isAssignableFrom(returnType)
    || Future.class.isAssignableFrom(returnType)
    || isReactivePublisherType(returnType)) {
    throw new LockConfigurationException(
        "@DistributedLock does not support async return types such as " + returnType.getSimpleName() + ": " + method
    );
}
```

Add this helper below `ensureSynchronousReturnType(...)`:

```java
private boolean isKotlinCoroutineMethod(Method method) {
    Class<?>[] parameterTypes = method.getParameterTypes();
    if (parameterTypes.length == 0) {
        return false;
    }
    return "kotlin.coroutines.Continuation".equals(parameterTypes[parameterTypes.length - 1].getName());
}
```

- [ ] **Step 5: Run Spring starter tests**

Run:

```bash
mvn -pl distributed-lock-spring-boot-starter -am test
```

Expected: Spring starter module and dependencies pass. If other tests fail due to the new `Object` guard, inspect the failing annotated method and either make its return type precise or set compatibility mode in that specific test when it intentionally covers dynamic-return behavior.

- [ ] **Step 6: Commit Task 4**

```bash
git add distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockProperties.java \
  distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockAspect.java \
  distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAsyncGuardTest.java \
  distributed-lock-spring-boot-starter/src/test/java/kotlin/coroutines/Continuation.java
git commit -m "fix(spring): reject ambiguous async return boundaries"
```

---

### Task 5: ZooKeeper Prompt Watcher Wakeup

**Files:**
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendSession.java`
- Modify: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperAcquireWaitLifecycleTest.java`

- [ ] **Step 1: Write failing prompt wakeup test**

In `ZooKeeperAcquireWaitLifecycleTest`, add imports:

```java
import java.util.concurrent.atomic.AtomicLong;
```

Add this test after `timedAcquireShouldContinueWaitingAcrossBoundedSlices()`:

```java
@Test
void predecessorDeletionShouldWakeWaitingAcquirePromptly() throws Exception {
    try (ZooKeeperTestSupport support = new ZooKeeperTestSupport();
         ZooKeeperLockBackend backend = new ZooKeeperLockBackend(support.configuration());
         BackendSession holder = backend.openSession();
         BackendLockLease holderLease = holder.acquire(request("zk:wait:prompt"));
         BackendSession waiter = backend.openSession()) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicLong releasedNanos = new AtomicLong();
        try {
            Future<Duration> result = executor.submit(() -> {
                try (BackendLockLease waiterLease = waiter.acquire(timedRequest("zk:wait:prompt"))) {
                    assertThat(waiterLease.isValid()).isTrue();
                    return Duration.ofNanos(System.nanoTime() - releasedNanos.get());
                }
            });

            Thread.sleep(150L);
            releasedNanos.set(System.nanoTime());
            holderLease.release();

            Duration elapsedAfterRelease = result.get(2, TimeUnit.SECONDS);
            assertThat(elapsedAfterRelease).isLessThan(Duration.ofMillis(80));
        } finally {
            executor.shutdownNow();
        }
    }
}
```

- [ ] **Step 2: Run the new ZooKeeper test to verify failure**

Run:

```bash
mvn -pl distributed-lock-zookeeper -Dtest=ZooKeeperAcquireWaitLifecycleTest#predecessorDeletionShouldWakeWaitingAcquirePromptly test
```

Expected: test fails or is flaky because waiter wakeup depends on the 250 ms polling slice.

- [ ] **Step 3: Make watcher notify the session wait monitor**

In `ZooKeeperBackendSession`, add:

```java
import java.util.concurrent.atomic.AtomicBoolean;
```

Replace `awaitNodeDeletion(...)` with:

```java
private boolean awaitNodeDeletion(String path, long remainingNanos, LockRequest request) throws Exception {
    AtomicBoolean predecessorDeleted = new AtomicBoolean(false);
    CuratorWatcher watcher = event -> {
        predecessorDeleted.set(true);
        signalTerminalWaiters();
    };
    Stat stat = curatorFramework.checkExists().usingWatcher(watcher).forPath(path);
    if (stat == null) {
        return true;
    }
    long waitNanos = remainingNanos == Long.MAX_VALUE
        ? TimeUnit.MILLISECONDS.toNanos(250L)
        : Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(250L));
    synchronized (terminalMonitor) {
        if (state.get() != SessionState.ACTIVE) {
            ensureActive(request);
        }
        if (!predecessorDeleted.get()) {
            terminalMonitor.wait(TimeUnit.NANOSECONDS.toMillis(waitNanos), (int) (waitNanos % 1_000_000L));
        }
    }
    ensureActive(request);
    return predecessorDeleted.get() || curatorFramework.checkExists().forPath(path) == null;
}
```

- [ ] **Step 4: Run ZooKeeper tests**

Run:

```bash
mvn -pl distributed-lock-zookeeper -am test
```

Expected: ZooKeeper module and dependencies pass.

- [ ] **Step 5: Commit Task 5**

```bash
git add distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendSession.java \
  distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperAcquireWaitLifecycleTest.java
git commit -m "fix(zookeeper): wake waiters from predecessor watcher"
```

---

### Task 6: Redis Integration Test Profile

**Files:**
- Modify: `pom.xml`
- Modify: Redis Testcontainers-backed test classes under `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/`
- Modify: `distributed-lock-redis-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/redis/springboot/integration/RedisStarterIntegrationTest.java`

- [ ] **Step 1: Add global Surefire tag exclusion in root POM**

In root `pom.xml`, add property:

```xml
<surefire.excludedGroups>redis-integration</surefire.excludedGroups>
```

inside `<properties>`.

Inside `maven-surefire-plugin` configuration under `<pluginManagement>`, add:

```xml
<configuration>
    <excludedGroups>${surefire.excludedGroups}</excludedGroups>
</configuration>
```

If the plugin already has a `<configuration>`, merge this element into it instead of creating a second `<configuration>`.

Add this profile after the existing `benchmarks` profile:

```xml
<profile>
    <id>redis-integration</id>
    <properties>
        <surefire.excludedGroups></surefire.excludedGroups>
    </properties>
</profile>
```

- [ ] **Step 2: Tag Redis Testcontainers-backed tests**

For each class that directly or indirectly calls `RedisTestSupport.startRedis()` or creates `GenericContainer`, add:

```java
import org.junit.jupiter.api.Tag;
```

and annotate the class:

```java
@Tag("redis-integration")
```

Apply this to:

- `RedisReadLockExpirationTest`
- `RedisLeaseRenewalTest`
- `RedisReadWriteWriterPreferenceTest`
- `RedisOwnershipLossTest`
- `RedisFixedLeasePolicyTest`
- `RedisExecutorOwnershipLossTest`
- `RedisSessionLossTest`
- every package-private contract class in `RedisLockBackendContractTest`: `RedisMutexLockContractTest`, `RedisWaitPolicyContractTest`, `RedisFencingContractTest`, `RedisReadWriteLockContractTest`, `RedisLeasePolicyContractTest`, `RedisFixedLeasePolicyContractTest`, `RedisReentryContractTest`, `RedisSessionLifecycleContractTest`
- `RedisStarterIntegrationTest`

Do not tag these non-Docker tests:

- `RedisBackendConfigurationTest`
- `RedisBackendModuleTest`
- `RedisKeyStrategyTest`
- `RedisBackendModuleAutoConfigurationTest`

- [ ] **Step 3: Run default Redis tests without Docker**

Run:

```bash
mvn -pl distributed-lock-redis,distributed-lock-redis-spring-boot-autoconfigure -am test
```

Expected: no Testcontainers startup is attempted. Redis unit/configuration tests and Redis Spring auto-configuration tests pass.

- [ ] **Step 4: Run Redis integration profile where Docker is available**

Run:

```bash
mvn -Predis-integration -pl distributed-lock-redis,distributed-lock-redis-spring-boot-autoconfigure -am test
```

Expected with Docker available: Redis integration tests run and pass. Expected without Docker: Testcontainers reports Docker environment failure; record it as environment-limited, not as a code failure.

- [ ] **Step 5: Commit Task 6**

```bash
git add pom.xml \
  distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis \
  distributed-lock-redis-spring-boot-autoconfigure/src/test/java/com/mycorp/distributedlock/redis/springboot/integration/RedisStarterIntegrationTest.java
git commit -m "test(redis): gate redis integration tests behind profile"
```

---

### Task 7: Documentation and Final Verification

**Files:**
- Modify: `distributed-lock-spring-boot-starter/README.md`
- Modify: `distributed-lock-test-suite/README.md`

- [ ] **Step 1: Update Spring starter README Redis properties**

In `distributed-lock-spring-boot-starter/README.md`, update the Redis YAML block to:

```yaml
distributed:
  lock:
    redis:
      uri: redis://127.0.0.1:6379
      lease-time: 30s
      key-strategy: legacy
      fixed-lease-renewal-enabled: false
      renewal-pool-size: 0
```

Add this paragraph immediately after the Redis YAML block:

```markdown
`key-strategy` defaults to `legacy`. Redis Cluster deployments should set `key-strategy: hash-tagged` so the owner, reader, pending-writer, and fencing keys used by a single Lua script share one cluster slot. `renewal-pool-size: 0` uses the backend computed default. `lease-time` is the backend-default watchdog lease duration.
```

- [ ] **Step 2: Update fixed lease and AOP boundary docs**

Replace the sentence:

```markdown
Blank `leaseFor` uses the backend default lease duration, while a positive value requests a fixed lease duration.
```

with:

```markdown
Blank `leaseFor` uses the backend default watchdog lease duration. On Redis, a positive `leaseFor` requests a fixed Redis TTL that does not auto-renew by default; set `distributed.lock.redis.fixed-lease-renewal-enabled=true` only when migrating code that intentionally relied on renewable fixed leases. ZooKeeper does not support fixed-duration leases because ownership is session-bound.
```

In the AOP boundaries section, append:

```markdown
Methods declared with return type `Object` are rejected by default because dynamic async return values cannot be detected until after user code has run. During migration, `distributed.lock.spring.annotation.allow-dynamic-return-type=true` restores the older defense-in-depth behavior, where the executor rejects returned `CompletionStage`, `Future`, or `Publisher` instances after invocation.
```

- [ ] **Step 3: Update test suite README commands**

In `distributed-lock-test-suite/README.md`, replace the backend prerequisites section with:

```markdown
## Backend Prerequisites

- Core, runtime, testkit, Spring starter, observability, and ZooKeeper adapter checks do not require Docker.
- Redis integration checks use Testcontainers and require a working local Docker environment.
- Benchmark compilation is part of the matrix; full JMH execution is opt-in because it may require local backend services and dedicated runtime settings.
```

Replace the command block with:

```bash
mvn test
mvn -pl distributed-lock-core,distributed-lock-testkit -am test
mvn -pl distributed-lock-runtime,distributed-lock-spring-boot-starter -am test
mvn -pl distributed-lock-zookeeper -am test
mvn -pl distributed-lock-redis,distributed-lock-redis-spring-boot-autoconfigure -am test
mvn -Predis-integration -pl distributed-lock-redis,distributed-lock-redis-spring-boot-autoconfigure -am test
mvn -pl distributed-lock-test-suite -am test
mvn -Pbenchmarks -DskipTests compile
```

- [ ] **Step 4: Run documentation diff check**

Run:

```bash
git diff --check
```

Expected: no whitespace errors.

- [ ] **Step 5: Run default full test suite**

Run:

```bash
mvn test
```

Expected: build passes without Docker. Redis integration tests tagged `redis-integration` do not run.

- [ ] **Step 6: Run focused module checks**

Run:

```bash
mvn -pl distributed-lock-zookeeper -am test
mvn -pl distributed-lock-spring-boot-starter,distributed-lock-extension-observability,distributed-lock-extension-observability-spring -am test
mvn -pl distributed-lock-redis,distributed-lock-redis-spring-boot-autoconfigure -am test
```

Expected: all commands pass without Docker.

- [ ] **Step 7: Run Redis integration profile if Docker is available**

Run:

```bash
mvn -Predis-integration -pl distributed-lock-redis,distributed-lock-redis-spring-boot-autoconfigure -am test
```

Expected with Docker available: command passes. If Docker is unavailable, capture the Testcontainers failure in the final report and do not claim Redis integration passed.

- [ ] **Step 8: Commit Task 7**

```bash
git add distributed-lock-spring-boot-starter/README.md distributed-lock-test-suite/README.md
git commit -m "docs: document lock hardening behavior"
```

---

## Final Acceptance Checklist

- [ ] Redis fixed leases expire without auto-renewal by default.
- [ ] Redis fixed lease compatibility mode renews fixed leases only when explicitly enabled.
- [ ] Redis Spring Boot exposes `key-strategy`, `fixed-lease-renewal-enabled`, and `renewal-pool-size`.
- [ ] Redis Cluster docs tell users to choose `hash-tagged`.
- [ ] Redis renewal scheduler uses configurable multi-thread scheduling.
- [ ] ZooKeeper predecessor deletion wakes waiters promptly.
- [ ] Spring rejects `Object` return type before invocation by default and has a compatibility flag.
- [ ] Root `mvn test` no longer requires Docker.
- [ ] Redis Testcontainers tests run through `-Predis-integration`.
- [ ] Work is committed in the seven task commits listed above.
