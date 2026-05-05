# Distributed Lock v3 Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the breaking v3 clean plugin-kernel architecture described in `docs/superpowers/specs/2026-05-04-distributed-lock-v3-architecture-design.md`.

**Architecture:** Move `LockRuntime` and backend behavior metadata into the public API, make `distributed-lock-spi` depend only on API, refactor core to consume SPI backend clients, and make runtime assemble explicit provider/configuration pairs. Then migrate Redis, ZooKeeper, Spring Boot integration, observability, context access, testkit, examples, benchmarks, and docs onto the new API/SPI boundary.

**Tech Stack:** Java 17, Maven, JUnit 5, AssertJ, Spring Boot 3.2 test runner, Lettuce Redis, Apache Curator/ZooKeeper, Testcontainers, Micrometer.

---

## Scope Check

This is one intentionally breaking v3 architecture plan. Although it touches many modules, the work is not safely separable into independent plans because every backend, Spring integration, extension, example, and conformance test depends on the same API/SPI/runtime contract change. Execute tasks in order and commit after each task.

---

## File Structure

Create or modify these files:

- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockRuntime.java` - new public runtime interface.
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/RuntimeInfo.java` - immutable runtime metadata.
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/BackendBehavior.java` - immutable backend behavior snapshot.
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/FencingSemantics.java` - behavior enum.
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LeaseSemantics.java` - behavior enum.
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/SessionSemantics.java` - behavior enum.
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/WaitSemantics.java` - behavior enum.
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/FairnessSemantics.java` - behavior enum.
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/OwnershipLossSemantics.java` - behavior enum.
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/BackendCostModel.java` - behavior enum.
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockContext.java` - delete after context extension is introduced.
- `distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/ApiSurfaceTest.java` - update API expectations.
- `distributed-lock-spi/src/main/java/com/mycorp/distributedlock/spi/BackendConfiguration.java` - new marker interface.
- `distributed-lock-spi/src/main/java/com/mycorp/distributedlock/spi/BackendDescriptor.java` - new backend descriptor.
- `distributed-lock-spi/src/main/java/com/mycorp/distributedlock/spi/BackendProvider.java` - replacement for `BackendModule`.
- `distributed-lock-spi/src/main/java/com/mycorp/distributedlock/spi/BackendClient.java` - moved backend client contract.
- `distributed-lock-spi/src/main/java/com/mycorp/distributedlock/spi/BackendSession.java` - moved backend session contract.
- `distributed-lock-spi/src/main/java/com/mycorp/distributedlock/spi/BackendLease.java` - moved backend lease contract.
- `distributed-lock-spi/src/main/java/com/mycorp/distributedlock/spi/BackendCapabilities.java` - delete.
- `distributed-lock-spi/src/main/java/com/mycorp/distributedlock/spi/BackendModule.java` - delete.
- `distributed-lock-spi/pom.xml` - remove `distributed-lock-core` dependency.
- `distributed-lock-spi/src/test/java/com/mycorp/distributedlock/spi/*Test.java` - replace capabilities/module tests.
- `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/*.java` - delete or replace imports with SPI contracts.
- `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockClient.java` - accept `BackendClient` and `BackendBehavior`.
- `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockSession.java` - consume SPI `BackendSession`/`BackendLease`.
- `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/LockRequestValidator.java` - validate against `BackendBehavior`.
- `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/SupportedLockModes.java` - delete.
- `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultSynchronousLockExecutor.java` - replace public `LockContext` with core-private synchronous reentry tracking.
- `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/SynchronousLockScope.java` - new core-private ThreadLocal reentry guard.
- `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/*Test.java` - update test doubles and assertions.
- `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntime.java` - delete after API replacement.
- `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/DefaultLockRuntime.java` - return API `RuntimeInfo`.
- `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java` - explicit provider/configuration assembly.
- `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/ServiceLoaderBackendRegistry.java` - delete.
- `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeDecorator.java` - new decorator hook.
- `distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java` - rewrite runtime assembly tests.
- `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendProvider.java` - create replacement for `RedisBackendModule`.
- `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendModule.java` - delete.
- `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendConfiguration.java` - implement `BackendConfiguration`.
- `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java` - implement or delegate to `BackendClient`.
- `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendSession.java` - implement SPI `BackendSession`.
- `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLease.java` - implement SPI `BackendLease`.
- `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/*Test.java` - update provider and runtime usage.
- `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendProvider.java` - create replacement for `ZooKeeperBackendModule`.
- `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModule.java` - delete.
- `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendConfiguration.java` - implement `BackendConfiguration`.
- `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java` - implement or delegate to `BackendClient`.
- `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendSession.java` - implement SPI `BackendSession`.
- `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLease.java` - implement SPI `BackendLease`.
- `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/*Test.java` - update provider and runtime usage.
- `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockAutoConfiguration.java` - backend-agnostic provider/configuration assembly.
- `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/LockRuntimeCustomizer.java` - replace or adapt to decorators.
- `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockAspect.java` - use runtime behavior for validation and remove direct `LockContext` dependency.
- `distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockAutoConfiguration.java` - expose provider plus configuration beans.
- `distributed-lock-zookeeper-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/zookeeper/springboot/config/ZooKeeperDistributedLockAutoConfiguration.java` - expose provider plus configuration beans.
- `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/*.java` - replace runtime reconstruction with transparent decorators.
- `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/config/DistributedLockObservabilityAutoConfiguration.java` - register decorator bean.
- `distributed-lock-extension-context/pom.xml` - new optional context extension module.
- `distributed-lock-extension-context/src/main/java/com/mycorp/distributedlock/context/LockScopeContext.java` - optional ThreadLocal access.
- `distributed-lock-extension-context/src/main/java/com/mycorp/distributedlock/context/ContextBindingSynchronousLockExecutor.java` - executor decorator.
- `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/*` - convert contracts to behavior-driven conformance.
- `distributed-lock-test-suite/src/test/java/com/mycorp/distributedlock/testsuite/RegressionMatrixSmokeTest.java` - replace smoke test with matrix tests.
- `distributed-lock-examples/src/main/java/**/*.java` - migrate examples to providers/configurations.
- `distributed-lock-benchmarks/src/main/java/**/*.java` - migrate benchmarks to providers/configurations.
- `distributed-lock-spring-boot-starter/README.md` - update v3 architecture and Spring assembly docs.
- `distributed-lock-examples/README.md` - update v3 examples.
- `distributed-lock-test-suite/README.md` - update conformance matrix docs.
- `docs/migration-2x-to-3x.md` - new migration guide.
- `pom.xml` - add `distributed-lock-extension-context` module and adjust dependency management.

---

### Task 1: Public API Runtime Metadata and Behavior Contract

**Files:**
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockRuntime.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/RuntimeInfo.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/BackendBehavior.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/FencingSemantics.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LeaseSemantics.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/SessionSemantics.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/WaitSemantics.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/FairnessSemantics.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/OwnershipLossSemantics.java`
- Create: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/BackendCostModel.java`
- Modify: `distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/ApiSurfaceTest.java`

- [ ] **Step 1: Write failing API metadata tests**

Add these tests to `ApiSurfaceTest`:

```java
@Test
void lockRuntimeShouldExposeOnlyApiTypes() throws Exception {
    Method info = LockRuntime.class.getMethod("info");

    assertThat(info.getReturnType()).isEqualTo(RuntimeInfo.class);
    assertThat(Arrays.stream(LockRuntime.class.getMethods())
        .filter(method -> method.getDeclaringClass().equals(LockRuntime.class))
        .flatMap(method -> Stream.concat(Stream.of(method.getReturnType()), Arrays.stream(method.getParameterTypes())))
        .map(Class::getName))
        .noneMatch(name -> name.startsWith("com.mycorp.distributedlock.spi."));
}

@Test
void backendBehaviorShouldDescribeRedisAndZooKeeperVisibleSemantics() {
    BackendBehavior redis = BackendBehavior.builder()
        .lockModes(Set.of(LockMode.MUTEX, LockMode.READ, LockMode.WRITE))
        .fencing(FencingSemantics.MONOTONIC_PER_KEY)
        .leaseSemantics(Set.of(LeaseSemantics.RENEWABLE_WATCHDOG, LeaseSemantics.FIXED_TTL))
        .session(SessionSemantics.CLIENT_LOCAL_TTL)
        .wait(WaitSemantics.POLLING)
        .fairness(FairnessSemantics.EXCLUSIVE_PREFERRED)
        .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
        .costModel(BackendCostModel.CHEAP_SESSION)
        .build();

    BackendBehavior zookeeper = BackendBehavior.builder()
        .lockModes(Set.of(LockMode.MUTEX, LockMode.READ, LockMode.WRITE))
        .fencing(FencingSemantics.MONOTONIC_PER_KEY)
        .leaseSemantics(Set.of(LeaseSemantics.SESSION_BOUND))
        .session(SessionSemantics.BACKEND_EPHEMERAL_SESSION)
        .wait(WaitSemantics.WATCHED_QUEUE)
        .fairness(FairnessSemantics.FIFO_QUEUE)
        .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
        .costModel(BackendCostModel.NETWORK_CLIENT_PER_SESSION)
        .build();

    assertThat(redis.supportsLockMode(LockMode.READ)).isTrue();
    assertThat(redis.supportsLeaseSemantics(LeaseSemantics.FIXED_TTL)).isTrue();
    assertThat(zookeeper.supportsLeaseSemantics(LeaseSemantics.FIXED_TTL)).isFalse();
}
```

Add imports:

```java
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
```

- [ ] **Step 2: Run the API test to verify failure**

Run:

```bash
mvn -pl distributed-lock-api -Dtest=ApiSurfaceTest test
```

Expected: compilation fails because `LockRuntime`, `RuntimeInfo`, `BackendBehavior`, and the semantic enums do not exist in `distributed-lock-api`.

- [ ] **Step 3: Add semantic enum files**

Create `FencingSemantics.java`:

```java
package com.mycorp.distributedlock.api;

public enum FencingSemantics {
    MONOTONIC_PER_KEY
}
```

Create `LeaseSemantics.java`:

```java
package com.mycorp.distributedlock.api;

public enum LeaseSemantics {
    RENEWABLE_WATCHDOG,
    FIXED_TTL,
    SESSION_BOUND
}
```

Create `SessionSemantics.java`:

```java
package com.mycorp.distributedlock.api;

public enum SessionSemantics {
    CLIENT_LOCAL_TTL,
    BACKEND_EPHEMERAL_SESSION
}
```

Create `WaitSemantics.java`:

```java
package com.mycorp.distributedlock.api;

public enum WaitSemantics {
    POLLING,
    WATCHED_QUEUE
}
```

Create `FairnessSemantics.java`:

```java
package com.mycorp.distributedlock.api;

public enum FairnessSemantics {
    NONE,
    EXCLUSIVE_PREFERRED,
    FIFO_QUEUE
}
```

Create `OwnershipLossSemantics.java`:

```java
package com.mycorp.distributedlock.api;

public enum OwnershipLossSemantics {
    EXPLICIT_LOST_STATE
}
```

Create `BackendCostModel.java`:

```java
package com.mycorp.distributedlock.api;

public enum BackendCostModel {
    CHEAP_SESSION,
    NETWORK_CLIENT_PER_SESSION,
    POOLED_NETWORK_CLIENT
}
```

- [ ] **Step 4: Add `BackendBehavior`**

Create `BackendBehavior.java`:

```java
package com.mycorp.distributedlock.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public record BackendBehavior(
    Set<LockMode> lockModes,
    FencingSemantics fencing,
    Set<LeaseSemantics> leaseSemantics,
    SessionSemantics session,
    WaitSemantics wait,
    FairnessSemantics fairness,
    OwnershipLossSemantics ownershipLoss,
    BackendCostModel costModel
) {

    public BackendBehavior {
        lockModes = immutableEnumSet(lockModes, LockMode.class, "lockModes");
        fencing = Objects.requireNonNull(fencing, "fencing");
        leaseSemantics = immutableEnumSet(leaseSemantics, LeaseSemantics.class, "leaseSemantics");
        session = Objects.requireNonNull(session, "session");
        wait = Objects.requireNonNull(wait, "wait");
        fairness = Objects.requireNonNull(fairness, "fairness");
        ownershipLoss = Objects.requireNonNull(ownershipLoss, "ownershipLoss");
        costModel = Objects.requireNonNull(costModel, "costModel");
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean supportsLockMode(LockMode mode) {
        return lockModes.contains(Objects.requireNonNull(mode, "mode"));
    }

    public boolean supportsLeaseSemantics(LeaseSemantics semantics) {
        return leaseSemantics.contains(Objects.requireNonNull(semantics, "semantics"));
    }

    private static <E extends Enum<E>> Set<E> immutableEnumSet(Set<E> values, Class<E> enumType, String name) {
        Objects.requireNonNull(values, name);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(values));
    }

    public static final class Builder {
        private Set<LockMode> lockModes;
        private FencingSemantics fencing;
        private Set<LeaseSemantics> leaseSemantics;
        private SessionSemantics session;
        private WaitSemantics wait;
        private FairnessSemantics fairness;
        private OwnershipLossSemantics ownershipLoss;
        private BackendCostModel costModel;

        public Builder lockModes(Set<LockMode> lockModes) {
            this.lockModes = lockModes;
            return this;
        }

        public Builder fencing(FencingSemantics fencing) {
            this.fencing = fencing;
            return this;
        }

        public Builder leaseSemantics(Set<LeaseSemantics> leaseSemantics) {
            this.leaseSemantics = leaseSemantics;
            return this;
        }

        public Builder session(SessionSemantics session) {
            this.session = session;
            return this;
        }

        public Builder wait(WaitSemantics wait) {
            this.wait = wait;
            return this;
        }

        public Builder fairness(FairnessSemantics fairness) {
            this.fairness = fairness;
            return this;
        }

        public Builder ownershipLoss(OwnershipLossSemantics ownershipLoss) {
            this.ownershipLoss = ownershipLoss;
            return this;
        }

        public Builder costModel(BackendCostModel costModel) {
            this.costModel = costModel;
            return this;
        }

        public BackendBehavior build() {
            return new BackendBehavior(lockModes, fencing, leaseSemantics, session, wait, fairness, ownershipLoss, costModel);
        }
    }
}
```

- [ ] **Step 5: Add `RuntimeInfo` and API `LockRuntime`**

Create `RuntimeInfo.java`:

```java
package com.mycorp.distributedlock.api;

import java.util.Objects;

public record RuntimeInfo(
    String backendId,
    String backendDisplayName,
    BackendBehavior behavior,
    String runtimeVersion
) {
    public RuntimeInfo {
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("backendId must not be blank");
        }
        if (backendDisplayName == null || backendDisplayName.isBlank()) {
            throw new IllegalArgumentException("backendDisplayName must not be blank");
        }
        behavior = Objects.requireNonNull(behavior, "behavior");
        if (runtimeVersion == null || runtimeVersion.isBlank()) {
            throw new IllegalArgumentException("runtimeVersion must not be blank");
        }
    }
}
```

Create `LockRuntime.java`:

```java
package com.mycorp.distributedlock.api;

public interface LockRuntime extends AutoCloseable {

    LockClient lockClient();

    SynchronousLockExecutor synchronousLockExecutor();

    RuntimeInfo info();

    @Override
    void close();
}
```

- [ ] **Step 6: Run API tests**

Run:

```bash
mvn -pl distributed-lock-api test
```

Expected: API module tests pass. Reactor still fails outside API until subsequent tasks migrate imports.

- [ ] **Step 7: Commit Task 1**

```bash
git add distributed-lock-api/src/main/java/com/mycorp/distributedlock/api \
  distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/ApiSurfaceTest.java
git commit -m "feat(api): add v3 runtime behavior metadata"
```

---

### Task 2: Standalone Backend SPI

**Files:**
- Modify: `distributed-lock-spi/pom.xml`
- Create: `distributed-lock-spi/src/main/java/com/mycorp/distributedlock/spi/BackendConfiguration.java`
- Create: `distributed-lock-spi/src/main/java/com/mycorp/distributedlock/spi/BackendDescriptor.java`
- Create: `distributed-lock-spi/src/main/java/com/mycorp/distributedlock/spi/BackendProvider.java`
- Create: `distributed-lock-spi/src/main/java/com/mycorp/distributedlock/spi/BackendClient.java`
- Create: `distributed-lock-spi/src/main/java/com/mycorp/distributedlock/spi/BackendSession.java`
- Create: `distributed-lock-spi/src/main/java/com/mycorp/distributedlock/spi/BackendLease.java`
- Delete: `distributed-lock-spi/src/main/java/com/mycorp/distributedlock/spi/BackendCapabilities.java`
- Delete: `distributed-lock-spi/src/main/java/com/mycorp/distributedlock/spi/BackendModule.java`
- Modify: `distributed-lock-spi/src/test/java/com/mycorp/distributedlock/spi/BackendCapabilitiesTest.java`

- [ ] **Step 1: Write failing SPI contract tests**

Replace `BackendCapabilitiesTest.java` with `BackendDescriptorTest.java`:

```java
package com.mycorp.distributedlock.spi;

import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.api.BackendCostModel;
import com.mycorp.distributedlock.api.FairnessSemantics;
import com.mycorp.distributedlock.api.FencingSemantics;
import com.mycorp.distributedlock.api.LeaseSemantics;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.OwnershipLossSemantics;
import com.mycorp.distributedlock.api.SessionSemantics;
import com.mycorp.distributedlock.api.WaitSemantics;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackendDescriptorTest {

    @Test
    void descriptorShouldExposeProviderMetadataWithoutCoreTypes() {
        BackendBehavior behavior = BackendBehavior.builder()
            .lockModes(Set.of(LockMode.MUTEX))
            .fencing(FencingSemantics.MONOTONIC_PER_KEY)
            .leaseSemantics(Set.of(LeaseSemantics.RENEWABLE_WATCHDOG))
            .session(SessionSemantics.CLIENT_LOCAL_TTL)
            .wait(WaitSemantics.POLLING)
            .fairness(FairnessSemantics.NONE)
            .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
            .costModel(BackendCostModel.CHEAP_SESSION)
            .build();

        BackendDescriptor<TestConfiguration> descriptor = new BackendDescriptor<>(
            "test",
            "Test Backend",
            TestConfiguration.class,
            behavior
        );

        assertThat(descriptor.id()).isEqualTo("test");
        assertThat(descriptor.displayName()).isEqualTo("Test Backend");
        assertThat(descriptor.configurationType()).isEqualTo(TestConfiguration.class);
        assertThat(descriptor.behavior()).isEqualTo(behavior);
    }

    @Test
    void descriptorShouldRejectBlankIds() {
        BackendBehavior behavior = BackendBehavior.builder()
            .lockModes(Set.of(LockMode.MUTEX))
            .fencing(FencingSemantics.MONOTONIC_PER_KEY)
            .leaseSemantics(Set.of(LeaseSemantics.RENEWABLE_WATCHDOG))
            .session(SessionSemantics.CLIENT_LOCAL_TTL)
            .wait(WaitSemantics.POLLING)
            .fairness(FairnessSemantics.NONE)
            .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
            .costModel(BackendCostModel.CHEAP_SESSION)
            .build();

        assertThatThrownBy(() -> new BackendDescriptor<>("", "Test Backend", TestConfiguration.class, behavior))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("id");
    }

    private record TestConfiguration() implements BackendConfiguration {
    }
}
```

- [ ] **Step 2: Run SPI tests to verify failure**

Run:

```bash
mvn -pl distributed-lock-spi test
```

Expected: compilation fails because the new SPI types do not exist and `BackendCapabilitiesTest` filename no longer matches the replacement class.

- [ ] **Step 3: Remove core dependency from SPI POM**

In `distributed-lock-spi/pom.xml`, delete this dependency:

```xml
<dependency>
    <groupId>com.mycorp</groupId>
    <artifactId>distributed-lock-core</artifactId>
</dependency>
```

Keep `distributed-lock-api` as the only reactor dependency in this module.

- [ ] **Step 4: Add SPI contract files**

Create `BackendConfiguration.java`:

```java
package com.mycorp.distributedlock.spi;

public interface BackendConfiguration {
}
```

Create `BackendDescriptor.java`:

```java
package com.mycorp.distributedlock.spi;

import com.mycorp.distributedlock.api.BackendBehavior;

import java.util.Objects;

public record BackendDescriptor<C extends BackendConfiguration>(
    String id,
    String displayName,
    Class<C> configurationType,
    BackendBehavior behavior
) {
    public BackendDescriptor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Backend descriptor id must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Backend descriptor displayName must not be blank");
        }
        configurationType = Objects.requireNonNull(configurationType, "configurationType");
        behavior = Objects.requireNonNull(behavior, "behavior");
    }
}
```

Create `BackendProvider.java`:

```java
package com.mycorp.distributedlock.spi;

public interface BackendProvider<C extends BackendConfiguration> {

    BackendDescriptor<C> descriptor();

    BackendClient createClient(C configuration);
}
```

Create `BackendClient.java`:

```java
package com.mycorp.distributedlock.spi;

public interface BackendClient extends AutoCloseable {

    BackendSession openSession();

    @Override
    void close();
}
```

Create `BackendSession.java`:

```java
package com.mycorp.distributedlock.spi;

import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;

public interface BackendSession extends AutoCloseable {

    BackendLease acquire(LockRequest request) throws InterruptedException;

    SessionState state();

    @Override
    void close();
}
```

Create `BackendLease.java`:

```java
package com.mycorp.distributedlock.spi;

import com.mycorp.distributedlock.api.LockLease;

public interface BackendLease extends LockLease {
}
```

- [ ] **Step 5: Delete old SPI types**

Delete:

```bash
distributed-lock-spi/src/main/java/com/mycorp/distributedlock/spi/BackendCapabilities.java
distributed-lock-spi/src/main/java/com/mycorp/distributedlock/spi/BackendModule.java
```

Rename `BackendCapabilitiesTest.java` to `BackendDescriptorTest.java`.

- [ ] **Step 6: Run API and SPI tests**

Run:

```bash
mvn -pl distributed-lock-api,distributed-lock-spi -am test
```

Expected: API and SPI tests pass. Other modules still fail until migrated.

- [ ] **Step 7: Commit Task 2**

```bash
git add distributed-lock-spi/pom.xml distributed-lock-spi/src/main/java/com/mycorp/distributedlock/spi \
  distributed-lock-spi/src/test/java/com/mycorp/distributedlock/spi
git commit -m "feat(spi): define standalone backend provider contract"
```

---

### Task 3: Core Consumes SPI and Drops Core Backend Contracts

**Files:**
- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/LockBackend.java`
- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/BackendSession.java`
- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/BackendLockLease.java`
- Delete: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/SupportedLockModes.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockClient.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockSession.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/LockRequestValidator.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/SessionBoundLockLease.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultSynchronousLockExecutor.java`
- Modify: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/*.java`
- Delete: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/backend/LockBackendSurfaceTest.java`

- [ ] **Step 1: Update core tests to use behavior and SPI test doubles**

In `DefaultLockClientTest`, replace client construction with:

```java
DefaultLockClient client = new DefaultLockClient(backend, standardBehavior());
```

Add this helper to the test class:

```java
private static BackendBehavior standardBehavior() {
    return BackendBehavior.builder()
        .lockModes(Set.of(LockMode.MUTEX, LockMode.READ, LockMode.WRITE))
        .fencing(FencingSemantics.MONOTONIC_PER_KEY)
        .leaseSemantics(Set.of(LeaseSemantics.RENEWABLE_WATCHDOG, LeaseSemantics.FIXED_TTL))
        .session(SessionSemantics.CLIENT_LOCAL_TTL)
        .wait(WaitSemantics.POLLING)
        .fairness(FairnessSemantics.EXCLUSIVE_PREFERRED)
        .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
        .costModel(BackendCostModel.CHEAP_SESSION)
        .build();
}
```

For unsupported modes, use:

```java
private static BackendBehavior mutexOnlyBehavior() {
    return BackendBehavior.builder()
        .lockModes(Set.of(LockMode.MUTEX))
        .fencing(FencingSemantics.MONOTONIC_PER_KEY)
        .leaseSemantics(Set.of(LeaseSemantics.RENEWABLE_WATCHDOG))
        .session(SessionSemantics.CLIENT_LOCAL_TTL)
        .wait(WaitSemantics.POLLING)
        .fairness(FairnessSemantics.NONE)
        .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
        .costModel(BackendCostModel.CHEAP_SESSION)
        .build();
}
```

Change test double imports from `com.mycorp.distributedlock.core.backend.*` to:

```java
import com.mycorp.distributedlock.spi.BackendClient;
import com.mycorp.distributedlock.spi.BackendLease;
import com.mycorp.distributedlock.spi.BackendSession;
```

- [ ] **Step 2: Run core tests to verify failure**

Run:

```bash
mvn -pl distributed-lock-core -Dtest=DefaultLockClientTest,DefaultLockSessionTest,DefaultSynchronousLockExecutorTest test
```

Expected: compilation fails because `DefaultLockClient` still expects `SupportedLockModes` and core backend types.

- [ ] **Step 3: Update `DefaultLockClient`**

Replace `DefaultLockClient.java` with:

```java
package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.spi.BackendClient;

import java.util.Objects;

public final class DefaultLockClient implements LockClient {

    private final BackendClient backendClient;
    private final BackendBehavior behavior;
    private final LockRequestValidator validator;

    public DefaultLockClient(BackendClient backendClient, BackendBehavior behavior) {
        this(backendClient, behavior, new LockRequestValidator());
    }

    DefaultLockClient(BackendClient backendClient, BackendBehavior behavior, LockRequestValidator validator) {
        this.backendClient = Objects.requireNonNull(backendClient, "backendClient");
        this.behavior = Objects.requireNonNull(behavior, "behavior");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    @Override
    public LockSession openSession() {
        return new DefaultLockSession(behavior, backendClient.openSession(), validator);
    }

    @Override
    public void close() {
        try {
            backendClient.close();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new LockBackendException("Failed to close lock backend client", exception);
        }
    }
}
```

- [ ] **Step 4: Update core session and validator**

In `DefaultLockSession.java`, replace fields:

```java
private final SupportedLockModes supportedLockModes;
private final BackendSession backendSession;
```

with:

```java
private final BackendBehavior behavior;
private final BackendSession backendSession;
```

Change the constructor to:

```java
public DefaultLockSession(
    BackendBehavior behavior,
    BackendSession backendSession,
    LockRequestValidator validator
) {
    this.behavior = Objects.requireNonNull(behavior, "behavior");
    this.backendSession = Objects.requireNonNull(backendSession, "backendSession");
    this.validator = Objects.requireNonNull(validator, "validator");
}
```

Replace validation call:

```java
validator.validate(supportedLockModes, request);
```

with:

```java
validator.validate(behavior, request);
```

Change imports to API `BackendBehavior` and SPI `BackendLease`/`BackendSession`.

Replace `LockRequestValidator.java` with:

```java
package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.api.LeaseMode;
import com.mycorp.distributedlock.api.LeaseSemantics;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.exception.LockFailureContext;
import com.mycorp.distributedlock.api.exception.UnsupportedLockCapabilityException;

import java.util.Objects;

final class LockRequestValidator {

    void validate(BackendBehavior behavior, LockRequest request) {
        Objects.requireNonNull(behavior, "behavior");
        Objects.requireNonNull(request, "request");

        LockMode mode = request.mode();
        if (!behavior.supportsLockMode(mode)) {
            throw unsupportedCapability("Backend does not support " + mode + " mode", request);
        }
        if (request.leasePolicy().mode() == LeaseMode.FIXED
            && !behavior.supportsLeaseSemantics(LeaseSemantics.FIXED_TTL)) {
            throw unsupportedCapability("Backend does not support fixed lease duration", request);
        }
    }

    private static UnsupportedLockCapabilityException unsupportedCapability(String message, LockRequest request) {
        return new UnsupportedLockCapabilityException(
            message,
            null,
            LockFailureContext.fromRequest(request, null, null)
        );
    }
}
```

- [ ] **Step 5: Replace public `LockContext` usage with a core-private reentry guard**

In `DefaultSynchronousLockExecutor.java`, delete:

```java
import com.mycorp.distributedlock.api.LockContext;
```

Create `SynchronousLockScope.java`:

```java
package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;

import java.util.Objects;

final class SynchronousLockScope {

    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

    private SynchronousLockScope() {
    }

    static boolean contains(LockKey key) {
        Objects.requireNonNull(key, "key");
        Frame frame = CURRENT.get();
        while (frame != null) {
            if (frame.lease().key().equals(key)) {
                return true;
            }
            frame = frame.previous();
        }
        return false;
    }

    static Binding bind(LockLease lease) {
        Objects.requireNonNull(lease, "lease");
        Frame frame = new Frame(lease, CURRENT.get());
        CURRENT.set(frame);
        return new Binding(frame);
    }

    static final class Binding implements AutoCloseable {
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
            if (CURRENT.get() != frame) {
                throw new IllegalStateException("Lock scope bindings must be closed in LIFO order");
            }
            closed = true;
            if (frame.previous() == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(frame.previous());
            }
        }
    }

    private record Frame(LockLease lease, Frame previous) {
    }
}
```

Replace the try-with-resources:

```java
try (LockSession session = client.openSession();
     LockLease lease = session.acquire(request);
     LockContext.Binding ignored = LockContext.bind(lease)) {
```

with:

```java
try (LockSession session = client.openSession();
     LockLease lease = session.acquire(request);
     SynchronousLockScope.Binding ignored = SynchronousLockScope.bind(lease)) {
```

Replace `rejectReentry(request)` implementation with:

```java
private static void rejectReentry(LockRequest request) {
    if (SynchronousLockScope.contains(request.key())) {
        throw new LockReentryException(
            "Lock key is already held in the current synchronous scope: " + request.key().value(),
            null,
            LockFailureContext.fromRequest(request, null, null)
        );
    }
}
```

- [ ] **Step 6: Delete core backend contracts and `SupportedLockModes`**

Delete:

```bash
distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/LockBackend.java
distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/BackendSession.java
distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/backend/BackendLockLease.java
distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/SupportedLockModes.java
distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/backend/LockBackendSurfaceTest.java
```

- [ ] **Step 7: Run core tests**

Run:

```bash
mvn -pl distributed-lock-api,distributed-lock-spi,distributed-lock-core -am test
```

Expected: API, SPI, and core tests pass. Runtime/backend modules still fail until migrated.

- [ ] **Step 8: Commit Task 3**

```bash
git add distributed-lock-core distributed-lock-api distributed-lock-spi
git commit -m "refactor(core): consume v3 backend spi"
```

---

### Task 4: Explicit Runtime Assembly and Decorator Hook

**Files:**
- Delete: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntime.java`
- Delete: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/ServiceLoaderBackendRegistry.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/DefaultLockRuntime.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java`
- Create: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeDecorator.java`
- Modify: `distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java`

- [ ] **Step 1: Rewrite runtime builder tests for explicit assembly**

In `LockRuntimeBuilderTest`, replace `BackendModule` test doubles with `BackendProvider<TestConfiguration>` test doubles. Ensure the test imports include these API and SPI types:

```java
import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.api.BackendCostModel;
import com.mycorp.distributedlock.api.FairnessSemantics;
import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.FencingSemantics;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LeaseSemantics;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.api.OwnershipLossSemantics;
import com.mycorp.distributedlock.api.SessionSemantics;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitSemantics;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.spi.BackendClient;
import com.mycorp.distributedlock.spi.BackendConfiguration;
import com.mycorp.distributedlock.spi.BackendDescriptor;
import com.mycorp.distributedlock.spi.BackendLease;
import com.mycorp.distributedlock.spi.BackendProvider;
import com.mycorp.distributedlock.spi.BackendSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
```

Add these concrete tests:

```java
@Test
void builderShouldCreateRuntimeFromExplicitProviderAndConfiguration() throws Exception {
    TrackingBackendClient client = new TrackingBackendClient();
    LockRuntime runtime = LockRuntimeBuilder.create()
        .backend("test")
        .backendProvider(new TestBackendProvider("test", standardBehavior(), client))
        .backendConfiguration(new TestConfiguration("primary"))
        .build();

    assertThat(runtime.info().backendId()).isEqualTo("test");
    assertThat(runtime.info().backendDisplayName()).isEqualTo("Test test");
    assertThat(runtime.info().behavior()).isEqualTo(standardBehavior());
    assertThat(runtime.lockClient().openSession()).isNotNull();
    runtime.close();
    assertThat(client.closed()).isTrue();
}

@Test
void builderShouldRejectMissingBackendId() {
    LockRuntimeBuilder builder = LockRuntimeBuilder.create()
        .backendProvider(new TestBackendProvider("test", standardBehavior(), new TrackingBackendClient()))
        .backendConfiguration(new TestConfiguration("primary"));

    assertThatThrownBy(builder::build)
        .isInstanceOf(LockConfigurationException.class)
        .hasMessageContaining("backend id");
}

@Test
void builderShouldRejectMissingConfigurationForSelectedProvider() {
    LockRuntimeBuilder builder = LockRuntimeBuilder.create()
        .backend("test")
        .backendProvider(new TestBackendProvider("test", standardBehavior(), new TrackingBackendClient()));

    assertThatThrownBy(builder::build)
        .isInstanceOf(LockConfigurationException.class)
        .hasMessageContaining("configuration")
        .hasMessageContaining("test");
}

@Test
void builderShouldRejectDuplicateProviderIds() {
    LockRuntimeBuilder builder = LockRuntimeBuilder.create()
        .backend("test")
        .backendProviders(List.of(
            new TestBackendProvider("test", standardBehavior(), new TrackingBackendClient()),
            new TestBackendProvider("test", standardBehavior(), new TrackingBackendClient())
        ))
        .backendConfiguration(new TestConfiguration("primary"));

    assertThatThrownBy(builder::build)
        .isInstanceOf(LockConfigurationException.class)
        .hasMessageContaining("Duplicate backend providers")
        .hasMessageContaining("test");
}

@Test
void builderShouldApplyDecoratorsInOrder() throws Exception {
    List<String> calls = new ArrayList<>();
    LockRuntimeDecorator first = runtime -> {
        calls.add("first");
        return runtime;
    };
    LockRuntimeDecorator second = runtime -> {
        calls.add("second");
        return runtime;
    };

    try (LockRuntime ignored = LockRuntimeBuilder.create()
        .backend("test")
        .backendProvider(new TestBackendProvider("test", standardBehavior(), new TrackingBackendClient()))
        .backendConfiguration(new TestConfiguration("primary"))
        .decorators(List.of(first, second))
        .build()) {
        assertThat(calls).containsExactly("first", "second");
    }
}
```

Use this test configuration and provider test double:

```java
private static BackendBehavior standardBehavior() {
    return BackendBehavior.builder()
        .lockModes(Set.of(LockMode.MUTEX, LockMode.READ, LockMode.WRITE))
        .fencing(FencingSemantics.MONOTONIC_PER_KEY)
        .leaseSemantics(Set.of(LeaseSemantics.RENEWABLE_WATCHDOG, LeaseSemantics.FIXED_TTL))
        .session(SessionSemantics.CLIENT_LOCAL_TTL)
        .wait(WaitSemantics.POLLING)
        .fairness(FairnessSemantics.EXCLUSIVE_PREFERRED)
        .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
        .costModel(BackendCostModel.CHEAP_SESSION)
        .build();
}

private record TestConfiguration(String name) implements BackendConfiguration {
}

private static final class TestBackendProvider implements BackendProvider<TestConfiguration> {
    private final BackendDescriptor<TestConfiguration> descriptor;
    private final BackendClient client;

    TestBackendProvider(String id, BackendBehavior behavior, TrackingBackendClient client) {
        this.descriptor = new BackendDescriptor<>(id, "Test " + id, TestConfiguration.class, behavior);
        this.client = client;
    }

    @Override
    public BackendDescriptor<TestConfiguration> descriptor() {
        return descriptor;
    }

    @Override
    public BackendClient createClient(TestConfiguration configuration) {
        return client;
    }
}
```

Add this tracking backend client:

```java
private static final class TrackingBackendClient implements BackendClient {
    private boolean closed;

    @Override
    public BackendSession openSession() {
        return new TestBackendSession();
    }

    @Override
    public void close() {
        closed = true;
    }

    boolean closed() {
        return closed;
    }
}

private static final class TestBackendSession implements BackendSession {
    private SessionState state = SessionState.ACTIVE;

    @Override
    public BackendLease acquire(LockRequest request) {
        return new TestBackendLease(request.key(), request.mode());
    }

    @Override
    public SessionState state() {
        return state;
    }

    @Override
    public void close() {
        state = SessionState.CLOSED;
    }
}

private static final class TestBackendLease implements BackendLease {
    private final LockKey key;
    private final LockMode mode;
    private LeaseState state = LeaseState.ACTIVE;

    TestBackendLease(LockKey key, LockMode mode) {
        this.key = key;
        this.mode = mode;
    }

    @Override
    public LockKey key() {
        return key;
    }

    @Override
    public LockMode mode() {
        return mode;
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
        state = LeaseState.RELEASED;
    }
}
```

- [ ] **Step 2: Run runtime tests to verify failure**

Run:

```bash
mvn -pl distributed-lock-runtime -Dtest=LockRuntimeBuilderTest test
```

Expected: compilation fails because runtime still imports old SPI and runtime `LockRuntime` is in the runtime package.

- [ ] **Step 3: Add decorator hook**

Create `LockRuntimeDecorator.java`:

```java
package com.mycorp.distributedlock.runtime;

import com.mycorp.distributedlock.api.LockRuntime;

@FunctionalInterface
public interface LockRuntimeDecorator {
    LockRuntime decorate(LockRuntime runtime);
}
```

- [ ] **Step 4: Update `DefaultLockRuntime`**

Replace imports to use API `LockRuntime` and `RuntimeInfo`. Store `RuntimeInfo` instead of backend id and capabilities:

```java
private final RuntimeInfo info;
```

Constructor:

```java
public DefaultLockRuntime(
    RuntimeInfo info,
    LockClient lockClient,
    SynchronousLockExecutor synchronousLockExecutor
) {
    this.info = Objects.requireNonNull(info, "info");
    this.lockClient = Objects.requireNonNull(lockClient, "lockClient");
    this.synchronousLockExecutor = Objects.requireNonNull(synchronousLockExecutor, "synchronousLockExecutor");
}
```

Implement:

```java
@Override
public RuntimeInfo info() {
    return info;
}
```

Remove `backendId()` and `capabilities()`.

- [ ] **Step 5: Rewrite `LockRuntimeBuilder`**

Replace `LockRuntimeBuilder` with an explicit provider/configuration builder. Required fields:

```java
private final List<BackendProvider<?>> backendProviders = new ArrayList<>();
private final Map<Class<? extends BackendConfiguration>, BackendConfiguration> configurations = new LinkedHashMap<>();
private final List<LockRuntimeDecorator> decorators = new ArrayList<>();
private String backendId;
```

Add methods:

```java
public LockRuntimeBuilder backend(String backendId) {
    this.backendId = backendId;
    return this;
}

public LockRuntimeBuilder backendProvider(BackendProvider<?> provider) {
    this.backendProviders.add(Objects.requireNonNull(provider, "provider"));
    return this;
}

public LockRuntimeBuilder backendProviders(List<? extends BackendProvider<?>> providers) {
    this.backendProviders.clear();
    if (providers != null) {
        providers.forEach(this::backendProvider);
    }
    return this;
}

public <C extends BackendConfiguration> LockRuntimeBuilder backendConfiguration(C configuration) {
    Objects.requireNonNull(configuration, "configuration");
    return backendConfiguration(configuration.getClass().asSubclass(BackendConfiguration.class), configuration);
}

public <C extends BackendConfiguration> LockRuntimeBuilder backendConfiguration(Class<C> type, C configuration) {
    configurations.put(Objects.requireNonNull(type, "type"), Objects.requireNonNull(configuration, "configuration"));
    return this;
}

public LockRuntimeBuilder decorators(List<LockRuntimeDecorator> decorators) {
    this.decorators.clear();
    if (decorators != null) {
        decorators.forEach(decorator -> this.decorators.add(Objects.requireNonNull(decorator, "decorator")));
    }
    return this;
}
```

Build flow:

```java
public LockRuntime build() {
    BackendProvider<?> provider = selectedProvider();
    BackendDescriptor<?> descriptor = provider.descriptor();
    BackendConfiguration configuration = configurationFor(descriptor);
    BackendClient backendClient = createClient(provider, configuration);
    LockClient lockClient = new DefaultLockClient(backendClient, descriptor.behavior());
    SynchronousLockExecutor executor = new DefaultSynchronousLockExecutor(lockClient);
    LockRuntime runtime = new DefaultLockRuntime(
        new RuntimeInfo(descriptor.id(), descriptor.displayName(), descriptor.behavior(), runtimeVersion()),
        lockClient,
        executor
    );
    for (LockRuntimeDecorator decorator : decorators) {
        runtime = decorator.decorate(runtime);
    }
    return runtime;
}
```

Use this helper for the generic provider call:

```java
@SuppressWarnings({"unchecked", "rawtypes"})
private static BackendClient createClient(BackendProvider provider, BackendConfiguration configuration) {
    BackendClient client = provider.createClient(configuration);
    if (client == null) {
        throw new LockConfigurationException("Backend provider returned null client: " + provider.descriptor().id());
    }
    return client;
}
```

- [ ] **Step 6: Delete runtime `LockRuntime` and ServiceLoader registry**

Delete:

```bash
distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntime.java
distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/spi/ServiceLoaderBackendRegistry.java
```

- [ ] **Step 7: Run runtime tests**

Run:

```bash
mvn -pl distributed-lock-api,distributed-lock-spi,distributed-lock-core,distributed-lock-runtime -am test
```

Expected: API/SPI/core/runtime tests pass.

- [ ] **Step 8: Commit Task 4**

```bash
git add distributed-lock-runtime distributed-lock-api distributed-lock-spi distributed-lock-core
git commit -m "refactor(runtime): require explicit backend providers"
```

---

### Task 5: Redis Provider Migration

**Files:**
- Create: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendProvider.java`
- Delete: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendModule.java`
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendConfiguration.java`
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendSession.java`
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLease.java`
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/*Test.java`

- [ ] **Step 1: Replace Redis module tests**

Rename `RedisBackendModuleTest` to `RedisBackendProviderTest`. Assert:

```java
RedisBackendProvider provider = new RedisBackendProvider();
BackendDescriptor<RedisBackendConfiguration> descriptor = provider.descriptor();

assertThat(descriptor.id()).isEqualTo("redis");
assertThat(descriptor.displayName()).isEqualTo("Redis");
assertThat(descriptor.configurationType()).isEqualTo(RedisBackendConfiguration.class);
assertThat(descriptor.behavior().supportsLockMode(LockMode.READ)).isTrue();
assertThat(descriptor.behavior().supportsLeaseSemantics(LeaseSemantics.FIXED_TTL)).isTrue();
assertThat(descriptor.behavior().costModel()).isEqualTo(BackendCostModel.CHEAP_SESSION);
```

Remove ServiceLoader assertions because default ServiceLoader discovery is not part of v3.

- [ ] **Step 2: Run Redis tests to verify failure**

Run:

```bash
mvn -pl distributed-lock-redis -Dtest=RedisBackendProviderTest test
```

Expected: compilation fails because `RedisBackendProvider` does not exist.

- [ ] **Step 3: Make Redis configuration implement SPI marker**

Change record declaration:

```java
public record RedisBackendConfiguration(
    String redisUri,
    long leaseSeconds,
    RedisKeyStrategy keyStrategy,
    boolean fixedLeaseRenewalEnabled,
    int renewalPoolSize
) implements BackendConfiguration {
```

Add import:

```java
import com.mycorp.distributedlock.spi.BackendConfiguration;
```

- [ ] **Step 4: Add `RedisBackendProvider`**

Create:

```java
package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.api.BackendCostModel;
import com.mycorp.distributedlock.api.FairnessSemantics;
import com.mycorp.distributedlock.api.FencingSemantics;
import com.mycorp.distributedlock.api.LeaseSemantics;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.OwnershipLossSemantics;
import com.mycorp.distributedlock.api.SessionSemantics;
import com.mycorp.distributedlock.api.WaitSemantics;
import com.mycorp.distributedlock.spi.BackendClient;
import com.mycorp.distributedlock.spi.BackendDescriptor;
import com.mycorp.distributedlock.spi.BackendProvider;

import java.util.Set;

public final class RedisBackendProvider implements BackendProvider<RedisBackendConfiguration> {

    private static final BackendBehavior BEHAVIOR = BackendBehavior.builder()
        .lockModes(Set.of(LockMode.MUTEX, LockMode.READ, LockMode.WRITE))
        .fencing(FencingSemantics.MONOTONIC_PER_KEY)
        .leaseSemantics(Set.of(LeaseSemantics.RENEWABLE_WATCHDOG, LeaseSemantics.FIXED_TTL))
        .session(SessionSemantics.CLIENT_LOCAL_TTL)
        .wait(WaitSemantics.POLLING)
        .fairness(FairnessSemantics.EXCLUSIVE_PREFERRED)
        .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
        .costModel(BackendCostModel.CHEAP_SESSION)
        .build();

    private static final BackendDescriptor<RedisBackendConfiguration> DESCRIPTOR =
        new BackendDescriptor<>("redis", "Redis", RedisBackendConfiguration.class, BEHAVIOR);

    @Override
    public BackendDescriptor<RedisBackendConfiguration> descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public BackendClient createClient(RedisBackendConfiguration configuration) {
        return new RedisLockBackend(configuration);
    }
}
```

- [ ] **Step 5: Make Redis implementation use SPI backend contracts**

In `RedisLockBackend`, replace:

```java
implements LockBackend
```

with:

```java
implements BackendClient
```

In `RedisBackendSession`, replace core backend imports with:

```java
import com.mycorp.distributedlock.spi.BackendLease;
import com.mycorp.distributedlock.spi.BackendSession;
```

Change `acquire` return type to `BackendLease`.

In `RedisLease`, replace:

```java
implements BackendLockLease
```

with:

```java
implements BackendLease
```

- [ ] **Step 6: Update Redis runtime construction in tests**

Replace:

```java
.backendModules(List.of(new RedisBackendModule(configuration)))
```

with:

```java
.backendProvider(new RedisBackendProvider())
.backendConfiguration(configuration)
```

Apply this replacement in all Redis tests and support classes.

- [ ] **Step 7: Delete `RedisBackendModule`**

Delete:

```bash
distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisBackendModule.java
```

- [ ] **Step 8: Run Redis non-Docker tests**

Run:

```bash
mvn -pl distributed-lock-redis -Dtest=RedisBackendConfigurationTest,RedisBackendProviderTest,RedisKeyStrategyTest test
```

Expected: Redis non-Docker provider/configuration/key tests pass.

- [ ] **Step 9: Commit Task 5**

```bash
git add distributed-lock-redis
git commit -m "refactor(redis): expose v3 backend provider"
```

---

### Task 6: ZooKeeper Provider Migration

**Files:**
- Create: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendProvider.java`
- Delete: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModule.java`
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendConfiguration.java`
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendSession.java`
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLease.java`
- Modify: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/*Test.java`

- [ ] **Step 1: Replace ZooKeeper module tests**

Rename `ZooKeeperBackendModuleTest` to `ZooKeeperBackendProviderTest`. Assert:

```java
ZooKeeperBackendProvider provider = new ZooKeeperBackendProvider();
BackendDescriptor<ZooKeeperBackendConfiguration> descriptor = provider.descriptor();

assertThat(descriptor.id()).isEqualTo("zookeeper");
assertThat(descriptor.displayName()).isEqualTo("ZooKeeper");
assertThat(descriptor.configurationType()).isEqualTo(ZooKeeperBackendConfiguration.class);
assertThat(descriptor.behavior().supportsLockMode(LockMode.READ)).isTrue();
assertThat(descriptor.behavior().supportsLeaseSemantics(LeaseSemantics.FIXED_TTL)).isFalse();
assertThat(descriptor.behavior().costModel()).isEqualTo(BackendCostModel.NETWORK_CLIENT_PER_SESSION);
```

- [ ] **Step 2: Run ZooKeeper provider test to verify failure**

Run:

```bash
mvn -pl distributed-lock-zookeeper -Dtest=ZooKeeperBackendProviderTest test
```

Expected: compilation fails because `ZooKeeperBackendProvider` does not exist.

- [ ] **Step 3: Make ZooKeeper configuration implement SPI marker**

Change record declaration:

```java
public record ZooKeeperBackendConfiguration(String connectString, String basePath) implements BackendConfiguration {
```

Add import:

```java
import com.mycorp.distributedlock.spi.BackendConfiguration;
```

- [ ] **Step 4: Add `ZooKeeperBackendProvider`**

Create:

```java
package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.api.BackendCostModel;
import com.mycorp.distributedlock.api.FairnessSemantics;
import com.mycorp.distributedlock.api.FencingSemantics;
import com.mycorp.distributedlock.api.LeaseSemantics;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.OwnershipLossSemantics;
import com.mycorp.distributedlock.api.SessionSemantics;
import com.mycorp.distributedlock.api.WaitSemantics;
import com.mycorp.distributedlock.spi.BackendClient;
import com.mycorp.distributedlock.spi.BackendDescriptor;
import com.mycorp.distributedlock.spi.BackendProvider;

import java.util.Set;

public final class ZooKeeperBackendProvider implements BackendProvider<ZooKeeperBackendConfiguration> {

    private static final BackendBehavior BEHAVIOR = BackendBehavior.builder()
        .lockModes(Set.of(LockMode.MUTEX, LockMode.READ, LockMode.WRITE))
        .fencing(FencingSemantics.MONOTONIC_PER_KEY)
        .leaseSemantics(Set.of(LeaseSemantics.SESSION_BOUND))
        .session(SessionSemantics.BACKEND_EPHEMERAL_SESSION)
        .wait(WaitSemantics.WATCHED_QUEUE)
        .fairness(FairnessSemantics.FIFO_QUEUE)
        .ownershipLoss(OwnershipLossSemantics.EXPLICIT_LOST_STATE)
        .costModel(BackendCostModel.NETWORK_CLIENT_PER_SESSION)
        .build();

    private static final BackendDescriptor<ZooKeeperBackendConfiguration> DESCRIPTOR =
        new BackendDescriptor<>("zookeeper", "ZooKeeper", ZooKeeperBackendConfiguration.class, BEHAVIOR);

    @Override
    public BackendDescriptor<ZooKeeperBackendConfiguration> descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public BackendClient createClient(ZooKeeperBackendConfiguration configuration) {
        return new ZooKeeperLockBackend(configuration);
    }
}
```

- [ ] **Step 5: Make ZooKeeper implementation use SPI backend contracts**

In `ZooKeeperLockBackend`, replace:

```java
implements LockBackend
```

with:

```java
implements BackendClient
```

In `ZooKeeperBackendSession`, replace core backend imports with:

```java
import com.mycorp.distributedlock.spi.BackendLease;
import com.mycorp.distributedlock.spi.BackendSession;
```

Change `acquire` return type to `BackendLease`.

In `ZooKeeperLease`, replace:

```java
implements BackendLockLease
```

with:

```java
implements BackendLease
```

- [ ] **Step 6: Update ZooKeeper runtime construction in tests**

Replace:

```java
.backendModules(List.of(new ZooKeeperBackendModule(configuration)))
```

with:

```java
.backendProvider(new ZooKeeperBackendProvider())
.backendConfiguration(configuration)
```

Apply this replacement in all ZooKeeper tests and support classes.

- [ ] **Step 7: Delete `ZooKeeperBackendModule`**

Delete:

```bash
distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperBackendModule.java
```

- [ ] **Step 8: Run ZooKeeper tests**

Run:

```bash
mvn -pl distributed-lock-zookeeper -am test
```

Expected: ZooKeeper module tests pass.

- [ ] **Step 9: Commit Task 6**

```bash
git add distributed-lock-zookeeper
git commit -m "refactor(zookeeper): expose v3 backend provider"
```

---

### Task 7: Spring Boot Backend-Agnostic Runtime Assembly

**Files:**
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockAutoConfiguration.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/LockRuntimeCustomizer.java`
- Modify: `distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockAutoConfiguration.java`
- Modify: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/zookeeper/springboot/config/ZooKeeperDistributedLockAutoConfiguration.java`
- Modify: Spring integration tests under all three Spring modules.

- [ ] **Step 1: Write failing generic starter tests**

In `DistributedLockAutoConfigurationIntegrationTest`, add a test that starts the context with:

```java
.withUserConfiguration(GenericProviderConfiguration.class)
.withPropertyValues(
    "distributed.lock.enabled=true",
    "distributed.lock.backend=in-memory"
)
```

Assert:

```java
assertThat(context).hasSingleBean(LockRuntime.class);
assertThat(context.getBean(LockRuntime.class).info().backendId()).isEqualTo("in-memory");
assertThat(context.getBean(LockClient.class)).isNotNull();
assertThat(context.getBean(SynchronousLockExecutor.class)).isNotNull();
```

Add test-only beans:

```java
@Configuration(proxyBeanMethods = false)
static class GenericProviderConfiguration {
    @Bean
    BackendProvider<InMemoryConfiguration> inMemoryProvider() {
        return new InMemoryBackendProvider();
    }

    @Bean
    InMemoryConfiguration inMemoryConfiguration() {
        return new InMemoryConfiguration();
    }
}
```

- [ ] **Step 2: Run Spring starter tests to verify failure**

Run:

```bash
mvn -pl distributed-lock-spring-boot-starter -Dtest=DistributedLockAutoConfigurationIntegrationTest test
```

Expected: compilation or startup fails because auto-configuration still expects `BackendModule` beans.

- [ ] **Step 3: Replace generic runtime assembly**

In `DistributedLockAutoConfiguration.DefaultLockRuntimeConfiguration.lockRuntime`, change parameters to:

```java
Map<String, BackendProvider<?>> backendProviders,
Map<String, BackendConfiguration> backendConfigurations,
ObjectProvider<LockRuntimeDecorator> decorators
```

Build runtime:

```java
LockRuntimeBuilder builder = LockRuntimeBuilder.create().backend(properties.getBackend());
backendProviders.values().forEach(builder::backendProvider);
backendConfigurations.values().forEach(builder::backendConfiguration);
builder.decorators(decorators.orderedStream().toList());
return builder.build();
```

Delete `AUTO_DEFAULT_BACKEND_MODULES`, `backendModulesForRuntime`, `isAutoDefaultOverridden`, and `isKnownAutoDefaultBean`.

- [ ] **Step 4: Convert `LockRuntimeCustomizer` into the v3 decorator type**

Replace `LockRuntimeCustomizer.java` with this compatibility adapter so existing Spring tests can migrate one module at a time while the runtime uses the new decorator contract:

```java
package com.mycorp.distributedlock.springboot.config;

import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeDecorator;

@FunctionalInterface
public interface LockRuntimeCustomizer extends LockRuntimeDecorator {
    @Override
    LockRuntime decorate(LockRuntime runtime);
}
```

After this change, generic auto-configuration consumes `ObjectProvider<LockRuntimeDecorator>`; any remaining `LockRuntimeCustomizer` beans still work because they are also decorators.

- [ ] **Step 5: Update Redis Spring auto-configuration**

Change Redis autoconfigure to expose:

```java
@Bean
@ConditionalOnMissingBean
RedisBackendProvider redisBackendProvider() {
    return new RedisBackendProvider();
}

@Bean
@ConditionalOnMissingBean
RedisBackendConfiguration redisBackendConfiguration(RedisDistributedLockProperties properties) {
    return new RedisBackendConfiguration(
        requireUri(properties.getUri()),
        toLeaseSeconds(properties.getLeaseTime()),
        properties.getKeyStrategy(),
        properties.isFixedLeaseRenewalEnabled(),
        properties.getRenewalPoolSize()
    );
}
```

Remove `BackendModule` bean creation.

- [ ] **Step 6: Update ZooKeeper Spring auto-configuration**

Change ZooKeeper autoconfigure to expose:

```java
@Bean
@ConditionalOnMissingBean
ZooKeeperBackendProvider zooKeeperBackendProvider() {
    return new ZooKeeperBackendProvider();
}

@Bean
@ConditionalOnMissingBean
ZooKeeperBackendConfiguration zooKeeperBackendConfiguration(ZooKeeperDistributedLockProperties properties) {
    return new ZooKeeperBackendConfiguration(
        requireConnectString(properties.getConnectString()),
        requireBasePath(properties.getBasePath())
    );
}
```

Remove `BackendModule` bean creation.

- [ ] **Step 7: Run Spring tests**

Run:

```bash
mvn -pl distributed-lock-spring-boot-starter,distributed-lock-redis-spring-boot-autoconfigure,distributed-lock-zookeeper-spring-boot-autoconfigure -am test
```

Expected: Spring starter and backend auto-configuration tests pass.

- [ ] **Step 8: Commit Task 7**

```bash
git add distributed-lock-spring-boot-starter distributed-lock-redis-spring-boot-autoconfigure distributed-lock-zookeeper-spring-boot-autoconfigure
git commit -m "refactor(spring): assemble runtime from backend providers"
```

---

### Task 8: Observability Decorators and Optional Context Extension

**Files:**
- Modify: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockRuntime.java`
- Modify: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockExecutor.java`
- Modify: `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/config/DistributedLockObservabilityAutoConfiguration.java`
- Create: `distributed-lock-extension-context/pom.xml`
- Create: `distributed-lock-extension-context/src/main/java/com/mycorp/distributedlock/context/LockScopeContext.java`
- Create: `distributed-lock-extension-context/src/main/java/com/mycorp/distributedlock/context/ContextBindingSynchronousLockExecutor.java`
- Create: `distributed-lock-extension-context/src/main/java/com/mycorp/distributedlock/context/ContextLockRuntimeDecorator.java`
- Delete: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockContext.java`
- Modify: `pom.xml`

- [ ] **Step 1: Add context extension module to root POM**

Add module:

```xml
<module>distributed-lock-extension-context</module>
```

Add dependency management entry:

```xml
<dependency>
    <groupId>com.mycorp</groupId>
    <artifactId>distributed-lock-extension-context</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 2: Create context extension POM**

Create `distributed-lock-extension-context/pom.xml` with dependencies on `distributed-lock-api` and `distributed-lock-runtime`.

- [ ] **Step 3: Add `LockScopeContext`**

Create:

```java
package com.mycorp.distributedlock.context;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LockLease;

import java.util.Optional;

public final class LockScopeContext {

    private static final ThreadLocal<LockLease> CURRENT = new ThreadLocal<>();

    private LockScopeContext() {
    }

    public static Optional<LockLease> currentLease() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static FencingToken requireCurrentFencingToken() {
        return currentLease()
            .map(LockLease::fencingToken)
            .orElseThrow(() -> new IllegalStateException("No lock lease is bound to the current thread"));
    }

    static Binding bind(LockLease lease) {
        LockLease previous = CURRENT.get();
        CURRENT.set(lease);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    @FunctionalInterface
    interface Binding extends AutoCloseable {
        @Override
        void close();
    }
}
```

- [ ] **Step 4: Add context executor and runtime decorator**

Create `ContextBindingSynchronousLockExecutor.java`:

```java
package com.mycorp.distributedlock.context;

import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockedAction;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;

import java.util.Objects;

public final class ContextBindingSynchronousLockExecutor implements SynchronousLockExecutor {
    private final SynchronousLockExecutor delegate;

    public ContextBindingSynchronousLockExecutor(SynchronousLockExecutor delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public <T> T withLock(LockRequest request, LockedAction<T> action) throws Exception {
        return delegate.withLock(request, lease -> {
            try (LockScopeContext.Binding ignored = LockScopeContext.bind(lease)) {
                return action.execute(lease);
            }
        });
    }
}
```

Create `ContextLockRuntimeDecorator.java`:

```java
package com.mycorp.distributedlock.context;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.api.RuntimeInfo;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.runtime.LockRuntimeDecorator;

public final class ContextLockRuntimeDecorator implements LockRuntimeDecorator {
    @Override
    public LockRuntime decorate(LockRuntime runtime) {
        SynchronousLockExecutor executor = new ContextBindingSynchronousLockExecutor(runtime.synchronousLockExecutor());
        return new LockRuntime() {
            @Override
            public LockClient lockClient() {
                return runtime.lockClient();
            }

            @Override
            public SynchronousLockExecutor synchronousLockExecutor() {
                return executor;
            }

            @Override
            public RuntimeInfo info() {
                return runtime.info();
            }

            @Override
            public void close() {
                runtime.close();
            }
        };
    }
}
```

- [ ] **Step 5: Refactor observability to runtime decorator**

Create `ObservedLockRuntimeDecorator.java` in `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability`:

```java
package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.api.RuntimeInfo;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.runtime.LockRuntimeDecorator;

import java.util.Objects;

public final class ObservedLockRuntimeDecorator implements LockRuntimeDecorator {
    private final LockObservationSink sink;
    private final boolean includeKey;

    public ObservedLockRuntimeDecorator(LockObservationSink sink, boolean includeKey) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.includeKey = includeKey;
    }

    @Override
    public LockRuntime decorate(LockRuntime runtime) {
        String backendId = runtime.info().backendId();
        LockClient client = new ObservedLockClient(runtime.lockClient(), sink, backendId, includeKey);
        SynchronousLockExecutor executor = new ObservedLockExecutor(
            runtime.synchronousLockExecutor(),
            sink,
            backendId,
            includeKey
        );
        return new LockRuntime() {
            @Override
            public LockClient lockClient() {
                return client;
            }

            @Override
            public SynchronousLockExecutor synchronousLockExecutor() {
                return executor;
            }

            @Override
            public RuntimeInfo info() {
                return runtime.info();
            }

            @Override
            public void close() {
                runtime.close();
            }
        };
    }
}
```

Delete or stop using `ObservedLockRuntime`. The decorator wraps `runtime.synchronousLockExecutor()` directly:

```java
SynchronousLockExecutor observedExecutor = new ObservedLockExecutor(
    runtime.synchronousLockExecutor(),
    sink,
    runtime.info().backendId(),
    includeKey
);
```

Delete any import of `DefaultSynchronousLockExecutor` from observability.

- [ ] **Step 6: Delete API `LockContext`**

Delete:

```bash
distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/LockContext.java
```

Task 10 updates Spring README examples to use either the `lease` callback or `LockScopeContext`.

- [ ] **Step 7: Run extension tests**

Run:

```bash
mvn -pl distributed-lock-extension-observability,distributed-lock-extension-observability-spring,distributed-lock-extension-context -am test
```

Expected: observability and context extension tests pass, and observability no longer imports core.

- [ ] **Step 8: Commit Task 8**

```bash
git add pom.xml distributed-lock-extension-observability distributed-lock-extension-observability-spring distributed-lock-extension-context distributed-lock-api
git commit -m "refactor(extensions): use runtime decorators"
```

---

### Task 9: Behavior-Driven Testkit and Real Test Suite Matrix

**Files:**
- Modify: `distributed-lock-testkit/pom.xml`
- Create: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/BackendConformanceFixture.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/*Contract.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/support/InMemoryBackendModule.java`
- Modify: `distributed-lock-test-suite/pom.xml`
- Modify: `distributed-lock-test-suite/src/test/java/com/mycorp/distributedlock/testsuite/RegressionMatrixSmokeTest.java`

- [ ] **Step 1: Add conformance fixture API**

Create:

```java
package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.BackendBehavior;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.spi.BackendConfiguration;
import com.mycorp.distributedlock.spi.BackendProvider;

public interface BackendConformanceFixture<C extends BackendConfiguration> extends AutoCloseable {

    BackendProvider<C> provider();

    C configuration();

    default BackendBehavior behavior() {
        return provider().descriptor().behavior();
    }

    default LockRuntimeBuilder runtimeBuilder() {
        return LockRuntimeBuilder.create()
            .backend(provider().descriptor().id())
            .backendProvider(provider())
            .backendConfiguration(configuration());
    }

    @Override
    default void close() {
    }
}
```

- [ ] **Step 2: Update contract base class**

Replace `createRuntime()` in `LockClientContract` with:

```java
protected abstract BackendConformanceFixture<?> createFixture() throws Exception;

protected LockRuntime createRuntime() throws Exception {
    fixture = createFixture();
    return fixture.runtimeBuilder().build();
}
```

Add fixture cleanup in `tearDown()`:

```java
if (fixture != null) {
    fixture.close();
}
```

- [ ] **Step 3: Gate behavior-specific tests**

In fixed TTL tests, add:

```java
Assumptions.assumeTrue(runtime.info().behavior().supportsLeaseSemantics(LeaseSemantics.FIXED_TTL));
```

In session-bound tests, add:

```java
Assumptions.assumeTrue(runtime.info().behavior().supportsLeaseSemantics(LeaseSemantics.SESSION_BOUND));
```

In read/write tests, add:

```java
Assumptions.assumeTrue(runtime.info().behavior().supportsLockMode(LockMode.READ));
Assumptions.assumeTrue(runtime.info().behavior().supportsLockMode(LockMode.WRITE));
```

- [ ] **Step 4: Replace in-memory backend module with provider**

Rename `InMemoryBackendModule` to `InMemoryBackendProvider`, implement `BackendProvider<InMemoryBackendConfiguration>`, and make its descriptor use `BackendBehavior` with cheap session, polling, monotonic fencing, renewable watchdog, fixed TTL, and all three lock modes.

- [ ] **Step 5: Replace test-suite smoke test**

Replace `RegressionMatrixSmokeTest` with a matrix class that creates in-memory and ZooKeeper fixtures by default. Redis fixture remains in Redis module integration tests behind `redis-integration`.

Core assertion:

```java
@Test
void suiteShouldRunAtLeastOneRealFixture() throws Exception {
    List<BackendConformanceFixture<?>> fixtures = List.of(new InMemoryConformanceFixture());

    assertThat(fixtures).isNotEmpty();
    for (BackendConformanceFixture<?> fixture : fixtures) {
        try (fixture; LockRuntime runtime = fixture.runtimeBuilder().build()) {
            assertThat(runtime.info().backendId()).isNotBlank();
            assertThat(runtime.lockClient()).isNotNull();
        }
    }
}
```

- [ ] **Step 6: Run testkit and suite**

Run:

```bash
mvn -pl distributed-lock-testkit,distributed-lock-test-suite -am test
```

Expected: testkit and test-suite pass real fixture tests.

- [ ] **Step 7: Commit Task 9**

```bash
git add distributed-lock-testkit distributed-lock-test-suite
git commit -m "test: rebuild backend conformance matrix"
```

---

### Task 10: Examples, Benchmarks, Documentation, and Full Verification

**Files:**
- Modify: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/*.java`
- Modify: `distributed-lock-examples/src/main/java/com/mycorp/distributedlock/examples/spring/*.java`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/**/*.java`
- Modify: `distributed-lock-spring-boot-starter/README.md`
- Modify: `distributed-lock-examples/README.md`
- Modify: `distributed-lock-test-suite/README.md`
- Create: `docs/migration-2x-to-3x.md`

- [ ] **Step 1: Update programmatic examples**

Replace:

```java
LockRuntime runtime = LockRuntimeBuilder.create()
    .backend("redis")
    .backendModules(List.of(new RedisBackendModule(configuration)))
    .build();
```

with:

```java
LockRuntime runtime = LockRuntimeBuilder.create()
    .backend("redis")
    .backendProvider(new RedisBackendProvider())
    .backendConfiguration(configuration)
    .build();
```

Do the same for ZooKeeper with `ZooKeeperBackendProvider`.

- [ ] **Step 2: Update benchmark runtime construction**

In `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/RedisBenchmarkEnvironment.java`, replace Redis runtime construction with:

```java
.backendProvider(new RedisBackendProvider())
.backendConfiguration(new RedisBackendConfiguration(redisUri, 30L))
```

In `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/ZooKeeperBenchmarkEnvironment.java`, replace ZooKeeper runtime construction with:

```java
.backendProvider(new ZooKeeperBackendProvider())
.backendConfiguration(new ZooKeeperBackendConfiguration(connectString, basePath))
```

In `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/RuntimeLifecycleBenchmark.java`, make the same two provider/configuration replacements for the Redis and ZooKeeper benchmark setup methods.

- [ ] **Step 3: Write migration guide**

Create `docs/migration-2x-to-3x.md` with these sections:

````markdown
# Migrating Distributed Lock 2.x to 3.x

## Runtime Assembly

2.x used `BackendModule` objects:

```java
LockRuntimeBuilder.create()
    .backend("redis")
    .backendModules(List.of(new RedisBackendModule(configuration)))
    .build();
```

3.x uses explicit providers and typed configuration:

```java
LockRuntimeBuilder.create()
    .backend("redis")
    .backendProvider(new RedisBackendProvider())
    .backendConfiguration(configuration)
    .build();
```

## Runtime Metadata

Replace `runtime.capabilities()` with `runtime.info().behavior()`.

## Lock Context

`LockContext` is no longer in the core API. Use the `lease` parameter inside `LockedAction`. Applications that require synchronous annotation-only context access should add `distributed-lock-extension-context` and use `LockScopeContext`.

## Spring Boot

The generic starter collects `BackendProvider<?>` and backend configuration beans. Backend-specific modules create those beans. `distributed.lock.backend` remains required.

## Backend Authors

Backend authors now implement `BackendProvider<C>`, `BackendClient`, `BackendSession`, and `BackendLease`, and depend on `distributed-lock-api` plus `distributed-lock-spi`.
````

- [ ] **Step 4: Update README files**

In `distributed-lock-spring-boot-starter/README.md`, remove references to `BackendModule` and add:

```markdown
Spring Boot v3 integration is provider-based. The generic starter does not know Redis or ZooKeeper bean names. Backend-specific auto-config modules contribute a `BackendProvider<?>` bean and a typed backend configuration bean; the generic starter selects the provider whose descriptor id matches `distributed.lock.backend`.
```

In `distributed-lock-examples/README.md`, update all examples to use providers.

In `distributed-lock-test-suite/README.md`, state that the suite runs behavior-driven conformance fixtures.

- [ ] **Step 5: Run full non-Docker verification**

Run:

```bash
mvn test
```

Expected: all non-Docker tests pass.

- [ ] **Step 6: Run focused module verification**

Run:

```bash
mvn -pl distributed-lock-api,distributed-lock-spi,distributed-lock-core,distributed-lock-runtime -am test
mvn -pl distributed-lock-spring-boot-starter,distributed-lock-redis-spring-boot-autoconfigure,distributed-lock-zookeeper-spring-boot-autoconfigure -am test
mvn -pl distributed-lock-zookeeper -am test
mvn -pl distributed-lock-test-suite -am test
mvn -Pbenchmarks -DskipTests compile
```

Expected: all commands pass.

- [ ] **Step 7: Run Redis integration verification when Docker is available**

Run:

```bash
mvn -Predis-integration -pl distributed-lock-redis,distributed-lock-redis-spring-boot-autoconfigure -am test
```

Expected: Redis Testcontainers-backed tests pass. If Docker is unavailable, record that this command was not run and do not claim Redis integration verification.

- [ ] **Step 8: Commit Task 10**

```bash
git add distributed-lock-examples distributed-lock-benchmarks distributed-lock-spring-boot-starter/README.md \
  distributed-lock-examples/README.md distributed-lock-test-suite/README.md docs/migration-2x-to-3x.md
git commit -m "docs: document distributed lock v3 migration"
```

---

## Final Verification

After all tasks are complete, run:

```bash
mvn test
mvn -pl distributed-lock-api,distributed-lock-spi,distributed-lock-core,distributed-lock-runtime -am test
mvn -pl distributed-lock-spring-boot-starter,distributed-lock-redis-spring-boot-autoconfigure,distributed-lock-zookeeper-spring-boot-autoconfigure -am test
mvn -pl distributed-lock-zookeeper -am test
mvn -pl distributed-lock-test-suite -am test
mvn -Pbenchmarks -DskipTests compile
```

Run Redis integration when Docker is available:

```bash
mvn -Predis-integration -pl distributed-lock-redis,distributed-lock-redis-spring-boot-autoconfigure -am test
```

Expected final state:

- `distributed-lock-spi` has no dependency on `distributed-lock-core`.
- `distributed-lock-api` has no dependency on `distributed-lock-spi`.
- `LockRuntime` lives in API and exposes `RuntimeInfo`.
- Default runtime assembly does not use `ServiceLoader`.
- Redis and ZooKeeper expose `BackendProvider` implementations.
- Spring generic starter has no backend-specific bean-name or class-name hard-coding.
- Observability decorates runtime components without constructing `DefaultSynchronousLockExecutor`.
- `LockContext` is removed from API and replaced by optional `distributed-lock-extension-context`.
- `distributed-lock-test-suite` runs real conformance checks.
- Migration documentation exists at `docs/migration-2x-to-3x.md`.
