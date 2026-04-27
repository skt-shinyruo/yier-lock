# Build And Test Hygiene Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clean up runtime API ergonomics, builder validation, dependency boundaries, benchmark drift, and documented test commands without changing lock behavior.

**Architecture:** Make small build-system and validation changes in isolated tasks. Runtime builder validation is covered by unit tests, dependency cleanup is verified by module compile/test, and benchmark/Spring Boot matrix support is documented with explicit Maven commands.

**Tech Stack:** Java 17, Maven multi-module reactor, JUnit 5, AssertJ, Spring Boot configuration properties, Jakarta Validation, Micrometer optional dependency, JMH benchmarks.

---

## File Map

- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntime.java`
  Redeclares `close()` without checked exceptions.
- Modify: `distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/ApiSurfaceTest.java`
  Adds API surface assertion for `LockRuntime.close()`.
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java`
  Adds deterministic validation for module entries, ids, capabilities, and backend creation.
- Modify: `distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java`
  Adds invalid metadata tests.
- Modify: `distributed-lock-core/pom.xml`, `distributed-lock-runtime/pom.xml`, `distributed-lock-redis/pom.xml`, `distributed-lock-zookeeper/pom.xml`
  Removes unused dependencies.
- Modify: Spring property classes under `distributed-lock-spring-boot-starter`, `distributed-lock-redis-spring-boot-autoconfigure`, and `distributed-lock-zookeeper-spring-boot-autoconfigure`
  Adds declarative validation annotations.
- Modify: `distributed-lock-extension-observability-spring/pom.xml`
  Marks Micrometer optional after conditional metrics configuration exists.
- Modify: root `pom.xml`
  Adds explicit `benchmarks` profile.
- Modify: `distributed-lock-benchmarks/README.md`, `distributed-lock-test-suite/README.md`, `distributed-lock-spring-boot-starter/README.md`
  Documents benchmark profile, test commands, and Spring Boot support matrix.

## Task 1: Narrow Runtime Close And Harden Builder Validation

**Files:**
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntime.java`
- Modify: `distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/ApiSurfaceTest.java`
- Modify: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java`
- Modify: `distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java`

- [ ] **Step 1: Add API close surface test**

In `ApiSurfaceTest`, import runtime type:

```java
import com.mycorp.distributedlock.runtime.LockRuntime;
```

Add this assertion to `apiShouldExposeTheApprovedLeaseSessionFencingTypes()`:

```java
assertThat(LockRuntime.class.getMethod("close").getExceptionTypes()).isEmpty();
```

- [ ] **Step 2: Add builder validation tests**

Add these tests to `LockRuntimeBuilderTest`:

```java
@Test
void builderShouldRejectNullBackendModuleEntries() {
    LockRuntimeBuilder builder = LockRuntimeBuilder.create()
        .backend("redis")
        .backendModules(java.util.Arrays.asList(new StubBackendModule("redis"), null));

    assertThatThrownBy(builder::build)
        .isInstanceOf(LockConfigurationException.class)
        .hasMessageContaining("Backend module must not be null");
}

@Test
void builderShouldRejectNullBackendModuleId() {
    LockRuntimeBuilder builder = LockRuntimeBuilder.create()
        .backend("redis")
        .backendModules(List.of(new StubBackendModule(null)));

    assertThatThrownBy(builder::build)
        .isInstanceOf(LockConfigurationException.class)
        .hasMessageContaining("Backend module id must not be blank");
}

@Test
void builderShouldRejectBlankBackendModuleId() {
    LockRuntimeBuilder builder = LockRuntimeBuilder.create()
        .backend("redis")
        .backendModules(List.of(new StubBackendModule("   ")));

    assertThatThrownBy(builder::build)
        .isInstanceOf(LockConfigurationException.class)
        .hasMessageContaining("Backend module id must not be blank");
}

@Test
void builderShouldRejectNullBackendFromSelectedModule() {
    BackendModule module = new StubBackendModule("redis") {
        @Override
        public LockBackend createBackend() {
            return null;
        }
    };
    LockRuntimeBuilder builder = LockRuntimeBuilder.create().backend("redis").backendModules(List.of(module));

    assertThatThrownBy(builder::build)
        .isInstanceOf(LockConfigurationException.class)
        .hasMessageContaining("Backend module returned null backend");
}
```

Remove `final` from `StubBackendModule` so the null-backend test can override it.

- [ ] **Step 3: Run API/runtime tests to verify failures**

Run:

```bash
mvn -q -pl distributed-lock-api,distributed-lock-runtime -am test -Dtest=ApiSurfaceTest,LockRuntimeBuilderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `LockRuntime.close()` is inherited from `AutoCloseable` and builder validation is not deterministic.

- [ ] **Step 4: Redeclare runtime close**

Update `LockRuntime.java`:

```java
public interface LockRuntime extends AutoCloseable {

    LockClient lockClient();

    LockExecutor lockExecutor();

    @Override
    void close();
}
```

- [ ] **Step 5: Add deterministic builder validation**

In `LockRuntimeBuilder`, introduce a small metadata record and validate before selection:

```java
private record ModuleMetadata(BackendModule module, String id, BackendCapabilities capabilities) {
}
```

Replace build module preparation with:

```java
List<ModuleMetadata> availableModules = validateModules(explicitBackendModules.isEmpty()
    ? new ServiceLoaderBackendRegistry().discover()
    : List.copyOf(explicitBackendModules));

ModuleMetadata selectedModule = selectBackendModule(availableModules);
validateCapabilities(selectedModule);
LockBackend backend = selectedModule.module().createBackend();
if (backend == null) {
    throw new LockConfigurationException("Backend module returned null backend: " + selectedModule.id());
}
SupportedLockModes supportedLockModes = new SupportedLockModes(
    selectedModule.capabilities().mutexSupported(),
    selectedModule.capabilities().readWriteSupported()
);
```

Add:

```java
private List<ModuleMetadata> validateModules(List<BackendModule> modules) {
    List<ModuleMetadata> metadata = new ArrayList<>();
    for (BackendModule module : modules) {
        if (module == null) {
            throw new LockConfigurationException("Backend module must not be null");
        }
        String id = module.id();
        if (id == null || id.isBlank()) {
            throw new LockConfigurationException("Backend module id must not be blank");
        }
        BackendCapabilities capabilities = module.capabilities();
        if (capabilities == null) {
            throw new LockConfigurationException("Backend module capabilities must not be null: " + id);
        }
        metadata.add(new ModuleMetadata(module, id, capabilities));
    }
    validateUniqueBackendIds(metadata);
    return metadata;
}
```

Update `selectBackendModule`, `validateCapabilities`, and `validateUniqueBackendIds` to use `ModuleMetadata` and `ModuleMetadata::id`.

- [ ] **Step 6: Run API/runtime focused tests**

Run:

```bash
mvn -q -pl distributed-lock-api,distributed-lock-runtime -am test -Dtest=ApiSurfaceTest,LockRuntimeBuilderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 7: Commit runtime hygiene**

```bash
git add distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntime.java distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/ApiSurfaceTest.java distributed-lock-runtime/src/test/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilderTest.java
git commit -m "fix: harden runtime builder validation"
```

## Task 2: Remove Unused Direct Dependencies

**Files:**
- Modify: `distributed-lock-core/pom.xml`
- Modify: `distributed-lock-runtime/pom.xml`
- Modify: `distributed-lock-redis/pom.xml`
- Modify: `distributed-lock-zookeeper/pom.xml`

- [ ] **Step 1: Remove unused dependencies from POMs**

Remove these dependency blocks:

```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
</dependency>
```

from:

```text
distributed-lock-core/pom.xml
distributed-lock-runtime/pom.xml
distributed-lock-redis/pom.xml
distributed-lock-zookeeper/pom.xml
```

Remove this block from `distributed-lock-zookeeper/pom.xml`:

```xml
<dependency>
    <groupId>org.apache.curator</groupId>
    <artifactId>curator-recipes</artifactId>
</dependency>
```

- [ ] **Step 2: Compile affected modules**

Run:

```bash
mvn -q -pl distributed-lock-core,distributed-lock-runtime,distributed-lock-redis,distributed-lock-zookeeper -am test -DskipTests=false
```

Expected: PASS. Any compilation error means a removed dependency is still used and must be restored only for that module.

- [ ] **Step 3: Commit dependency cleanup**

```bash
git add distributed-lock-core/pom.xml distributed-lock-runtime/pom.xml distributed-lock-redis/pom.xml distributed-lock-zookeeper/pom.xml
git commit -m "chore: remove unused backend dependencies"
```

## Task 3: Add Declarative Spring Configuration Validation

**Files:**
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockProperties.java`
- Modify: `distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockProperties.java`
- Modify: `distributed-lock-zookeeper-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/zookeeper/springboot/config/ZooKeeperDistributedLockProperties.java`
- Modify: existing auto-configuration integration tests for invalid properties

- [ ] **Step 1: Add validation annotations**

In `DistributedLockProperties`, annotate backend:

```java
@jakarta.validation.constraints.NotBlank
private String backend;
```

In `RedisDistributedLockProperties`, annotate fields:

```java
@jakarta.validation.constraints.NotBlank
private String uri;

@jakarta.validation.constraints.NotNull
private Duration leaseTime;
```

In `ZooKeeperDistributedLockProperties`, annotate fields:

```java
@jakarta.validation.constraints.NotBlank
private String connectString;

@jakarta.validation.constraints.NotBlank
private String basePath;
```

- [ ] **Step 2: Run Spring auto-configuration tests**

Run:

```bash
mvn -q -pl distributed-lock-spring-boot-starter,distributed-lock-redis-spring-boot-autoconfigure,distributed-lock-zookeeper-spring-boot-autoconfigure -am test -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. If an existing assertion expects the old manual exception text, change that assertion to match a Spring Boot validation failure containing the same property name, then rerun this command until it passes.

- [ ] **Step 3: Commit validation hardening**

```bash
git add distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockProperties.java distributed-lock-redis-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/redis/springboot/config/RedisDistributedLockProperties.java distributed-lock-zookeeper-spring-boot-autoconfigure/src/main/java/com/mycorp/distributedlock/zookeeper/springboot/config/ZooKeeperDistributedLockProperties.java distributed-lock-spring-boot-starter/src/test/java distributed-lock-redis-spring-boot-autoconfigure/src/test/java distributed-lock-zookeeper-spring-boot-autoconfigure/src/test/java
git commit -m "fix: validate distributed lock configuration properties"
```

## Task 4: Make Micrometer Optional After Observability Toggle Work

**Files:**
- Modify: `distributed-lock-extension-observability-spring/pom.xml`
- Modify: `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/config/DistributedLockObservabilityAutoConfiguration.java`
- Modify: `distributed-lock-extension-observability-spring/src/test/java/com/mycorp/distributedlock/observability/springboot/integration/DistributedLockObservabilityAutoConfigurationTest.java`

- [ ] **Step 1: Mark Micrometer optional**

In `distributed-lock-extension-observability-spring/pom.xml`, change Micrometer dependency to:

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-core</artifactId>
    <optional>true</optional>
</dependency>
```

- [ ] **Step 2: Guard metrics configuration by class**

Add `@ConditionalOnClass(MeterRegistry.class)` to the bean or code path that creates `MicrometerLockObservationSink`. If the current implementation keeps a single `lockObservationSink` bean, ensure it only references `MeterRegistry` through an `ObjectProvider<MeterRegistry>` and never requires a registry when metrics are disabled.

- [ ] **Step 3: Run observability Spring tests**

Run:

```bash
mvn -q -pl distributed-lock-extension-observability-spring -am test -Dtest=DistributedLockObservabilityAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 4: Commit optional Micrometer dependency**

```bash
git add distributed-lock-extension-observability-spring/pom.xml distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/config/DistributedLockObservabilityAutoConfiguration.java distributed-lock-extension-observability-spring/src/test/java/com/mycorp/distributedlock/observability/springboot/integration/DistributedLockObservabilityAutoConfigurationTest.java
git commit -m "chore: make observability metrics dependency optional"
```

## Task 5: Add Benchmark Profile And Documentation

**Files:**
- Modify: `pom.xml`
- Modify: `distributed-lock-benchmarks/README.md`
- Modify: `distributed-lock-test-suite/README.md`

- [ ] **Step 1: Add root benchmark profile**

In root `pom.xml`, after the `<modules>` block, add:

```xml
<profiles>
    <profile>
        <id>benchmarks</id>
        <modules>
            <module>distributed-lock-benchmarks</module>
        </modules>
    </profile>
</profiles>
```

If the POM already has a `<profiles>` block after implementation work, add this profile inside the existing block.

- [ ] **Step 2: Update benchmark README commands**

In `distributed-lock-benchmarks/README.md`, add:

~~~markdown
### Compile through the root benchmark profile

```bash
mvn -Pbenchmarks -DskipTests compile
```

The profile keeps benchmarks outside the default reactor while giving CI a single root-level compile command.
~~~

- [ ] **Step 3: Update test-suite README**

Add this note under benchmark explanation:

```markdown
- Benchmark sources can be compile-checked from the root with `mvn -Pbenchmarks -DskipTests compile`; full JMH execution remains opt-in and requires local Redis/ZooKeeper when a benchmark uses those backends.
```

- [ ] **Step 4: Run benchmark profile compile**

Run:

```bash
mvn -q -Pbenchmarks -DskipTests compile
```

Expected: PASS.

- [ ] **Step 5: Commit benchmark profile**

```bash
git add pom.xml distributed-lock-benchmarks/README.md distributed-lock-test-suite/README.md
git commit -m "chore: add benchmark compile profile"
```

## Task 6: Document Spring Boot Support Matrix And Run Final Checks

**Files:**
- Modify: `distributed-lock-spring-boot-starter/README.md`
- Modify: `distributed-lock-test-suite/README.md`

- [ ] **Step 1: Update Spring Boot support statement**

In `distributed-lock-spring-boot-starter/README.md`, replace the generic Spring Boot requirement with:

```markdown
- Spring Boot 3.2.x is the primary tested line
- Spring Boot 3.3.x and 3.4.x are compatibility targets verified by overriding `spring-boot.version` during regression
```

- [ ] **Step 2: Add compatibility commands**

In `distributed-lock-test-suite/README.md`, add:

```markdown
# Spring Boot compatibility checks
mvn test -Dspring-boot.version=3.3.13
mvn test -Dspring-boot.version=3.4.5
```

- [ ] **Step 3: Run default reactor tests**

Run:

```bash
mvn test
```

Expected: PASS.

- [ ] **Step 4: Run Spring Boot 3.3 compatibility check**

Run:

```bash
mvn test -Dspring-boot.version=3.3.13
```

Expected: PASS. If dependency resolution fails because this exact patch is unavailable in the local configured repositories, choose the latest resolvable 3.3.x patch and update the README command to that version.

- [ ] **Step 5: Run Spring Boot 3.4 compatibility check**

Run:

```bash
mvn test -Dspring-boot.version=3.4.5
```

Expected: PASS. If dependency resolution fails because this exact patch is unavailable in the local configured repositories, choose the latest resolvable 3.4.x patch and update the README command to that version.

- [ ] **Step 6: Commit documentation matrix**

```bash
git add distributed-lock-spring-boot-starter/README.md distributed-lock-test-suite/README.md
git commit -m "docs: clarify spring boot support matrix"
```
