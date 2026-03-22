# Distributed Lock Benchmarks 2.0 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild `distributed-lock-benchmarks` as a 2.0-only benchmark module that stays outside the default reactor, measures only real Redis/ZooKeeper/Spring starter paths, and can be compiled, smoke-tested, packaged, and run manually.

**Architecture:** Replace every 1.x benchmark and smoke test with a scenario-oriented suite built on top of `LockRuntime`, `LockManager`, `MutexLock`, and `ReadWriteLock`. Centralize Redis, ZooKeeper, and Spring Boot startup in small benchmark environment helpers so JMH classes only describe measured scenarios, while setup and teardown remain outside measured paths.

**Tech Stack:** Java 17, Maven standalone module build, JMH, Testcontainers Redis, Curator `TestingServer`, Lettuce, Spring Boot 3.x, JUnit 5, AssertJ

---

## Scope Boundary

This plan covers only the standalone benchmark module:

- `distributed-lock-benchmarks/pom.xml`
- benchmark README
- benchmark support environments
- JMH benchmark classes
- benchmark smoke tests
- removal of 1.x benchmark code

This plan does not cover:

- root `pom.xml` module list changes
- automatic CI benchmark execution
- benchmark result dashboards
- extension benchmarks for async, batch, health, observability, fairness, or HA

## File Structure

### Benchmark Module Build

- Modify: `distributed-lock-benchmarks/pom.xml`
  Responsibility: depend only on 2.0 modules and benchmark libraries, keep packaging runnable JMH jar, stay standalone outside the root reactor.
- Create: `distributed-lock-benchmarks/README.md`
  Responsibility: document prerequisites, compile/package commands, smoke test command, and focused JMH run commands.

### Shared Benchmark Support

- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/RedisBenchmarkEnvironment.java`
  Responsibility: start Redis with Testcontainers, create a Redis-backed `LockRuntime`, expose `LockManager`, and release all resources.
- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/ZooKeeperBenchmarkEnvironment.java`
  Responsibility: start Curator `TestingServer`, create a ZooKeeper-backed `LockRuntime`, expose `LockManager`, and release all resources.
- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/SpringBenchmarkApplication.java`
  Responsibility: provide a minimal Spring Boot 3 benchmark application with one programmatic path and one annotation-driven path.
- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/SpringBenchmarkEnvironment.java`
  Responsibility: start and stop a Spring Boot benchmark context backed by real Redis infrastructure and expose the benchmark beans.
- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/BenchmarkKeys.java`
  Responsibility: generate consistent shared and unique benchmark keys.
- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/BenchmarkWorkloads.java`
  Responsibility: hold small reusable critical-section helpers so JMH classes stay focused on scenarios.

### Benchmarks

- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/MutexLifecycleBenchmark.java`
  Responsibility: measure unique-key mutex lifecycle and successful `tryLock(Duration)` paths for Redis and ZooKeeper.
- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/MutexContentionBenchmark.java`
  Responsibility: measure shared-key contention under parameterized thread counts for Redis and ZooKeeper.
- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/ReadWriteLockBenchmark.java`
  Responsibility: measure read-heavy, write-exclusive, and mixed read/write paths for Redis and ZooKeeper.
- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/RuntimeLifecycleBenchmark.java`
  Responsibility: measure runtime creation, manager access, handle creation, and runtime close on already-running real backends.
- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/SpringStarterBenchmark.java`
  Responsibility: measure annotation-driven and programmatic Spring starter paths against a real Redis-backed Spring context.

### Tests

- Create: `distributed-lock-benchmarks/src/test/java/com/mycorp/distributedlock/benchmarks/BenchmarkEnvironmentSmokeTest.java`
  Responsibility: verify the Redis, ZooKeeper, and Spring benchmark environments start successfully and expose usable 2.0 runtime paths.

### Legacy Cleanup

Delete these 1.x benchmark files once the new 2.0 benchmark surface compiles:

- `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/BatchLockOperationsBenchmark.java`
- `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/ConcurrentLockBenchmark.java`
- `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/DistributedLockBenchmark.java`
- `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/EnhancedDistributedLockBenchmark.java`
- `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/FairLockBenchmark.java`
- `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/ReadWriteLockBenchmark.java`
- `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/SpringDistributedLockBenchmark.java`
- `distributed-lock-benchmarks/src/test/java/com/mycorp/distributedlock/stress/StressAndPerformanceTest.java`

## Task 1: Rebuild the Benchmark Module Skeleton

**Files:**
- Modify: `distributed-lock-benchmarks/pom.xml`
- Create: `distributed-lock-benchmarks/README.md`
- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/MutexLifecycleBenchmark.java`
- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/MutexContentionBenchmark.java`
- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/ReadWriteLockBenchmark.java`
- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/RuntimeLifecycleBenchmark.java`
- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/SpringStarterBenchmark.java`
- Delete: all legacy benchmark classes listed above
- Delete: `distributed-lock-benchmarks/src/test/java/com/mycorp/distributedlock/stress/StressAndPerformanceTest.java`

- [ ] **Step 1: Install the current 2.0 mainline artifacts locally**

Run:

```bash
mvn -q install -DskipTests
```

Expected: PASS. This publishes the current 2.0 modules to the local Maven repository so the standalone benchmark module can resolve them.

- [ ] **Step 2: Run the standalone benchmark compile to confirm the current 1.x benchmark module is broken**

Run:

```bash
mvn -q -f distributed-lock-benchmarks/pom.xml -DskipTests compile
```

Expected: FAIL with unresolved 1.x symbols such as `DistributedLockFactory`, `DistributedLock`, `DistributedReadWriteLock`, `LockProvider`, or `SpringDistributedLockFactory`.

- [ ] **Step 3: Rewrite the benchmark module `pom.xml` for the 2.0 dependency model**

Key dependency shape:

```xml
<dependencies>
    <dependency>
        <groupId>com.mycorp</groupId>
        <artifactId>distributed-lock-api</artifactId>
    </dependency>
    <dependency>
        <groupId>com.mycorp</groupId>
        <artifactId>distributed-lock-runtime</artifactId>
    </dependency>
    <dependency>
        <groupId>com.mycorp</groupId>
        <artifactId>distributed-lock-redis</artifactId>
    </dependency>
    <dependency>
        <groupId>com.mycorp</groupId>
        <artifactId>distributed-lock-zookeeper</artifactId>
    </dependency>
    <dependency>
        <groupId>com.mycorp</groupId>
        <artifactId>distributed-lock-spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>org.openjdk.jmh</groupId>
        <artifactId>jmh-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.openjdk.jmh</groupId>
        <artifactId>jmh-generator-annprocess</artifactId>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers</artifactId>
    </dependency>
    <dependency>
        <groupId>org.apache.curator</groupId>
        <artifactId>curator-test</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

Rules:

- do not reintroduce any 1.x API dependencies
- keep the JMH shade plugin
- do not modify the root reactor

- [ ] **Step 4: Replace the legacy benchmark inventory with compile-safe 2.0 stubs**

Use minimal JMH class shells first so the module can compile while later tasks fill in behavior:

```java
@State(Scope.Benchmark)
public class MutexLifecycleBenchmark {
}
```

Do this for:

- `MutexLifecycleBenchmark`
- `MutexContentionBenchmark`
- `ReadWriteLockBenchmark`
- `RuntimeLifecycleBenchmark`
- `SpringStarterBenchmark`

Delete every legacy benchmark class at the same time so compile failures are not masked by removed imports.

- [ ] **Step 5: Write the benchmark README skeleton**

Start with these sections:

```md
# Distributed Lock Benchmarks

## Prerequisites
- Docker
- local `mvn -q install -DskipTests`

## Commands
- compile
- smoke test
- package
- run one benchmark
```

- [ ] **Step 6: Re-run the standalone compile to verify the module skeleton is 2.0-clean**

Run:

```bash
mvn -q -f distributed-lock-benchmarks/pom.xml -DskipTests compile
```

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add distributed-lock-benchmarks/pom.xml \
  distributed-lock-benchmarks/README.md \
  distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks \
  distributed-lock-benchmarks/src/test/java
git commit -m "build: rebuild benchmark module for lock 2.0"
```

## Task 2: Add Real Backend Environments and Smoke Tests

**Files:**
- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/RedisBenchmarkEnvironment.java`
- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/ZooKeeperBenchmarkEnvironment.java`
- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/SpringBenchmarkApplication.java`
- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/SpringBenchmarkEnvironment.java`
- Create: `distributed-lock-benchmarks/src/test/java/com/mycorp/distributedlock/benchmarks/BenchmarkEnvironmentSmokeTest.java`
- Reference: `distributed-lock-runtime/src/main/java/com/mycorp/distributedlock/runtime/LockRuntimeBuilder.java`
- Reference: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockAutoConfiguration.java`
- Reference: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/annotation/DistributedLock.java`

- [ ] **Step 1: Write the failing benchmark environment smoke test**

Use three focused smoke tests:

```java
@Test
void redisEnvironmentShouldCreateWorkingRuntime() throws Exception {
    try (RedisBenchmarkEnvironment environment = RedisBenchmarkEnvironment.start()) {
        MutexLock lock = environment.lockManager().mutex("bench:smoke:redis");
        assertThat(lock.tryLock(Duration.ofMillis(100))).isTrue();
    }
}
```

```java
@Test
void zooKeeperEnvironmentShouldCreateWorkingRuntime() throws Exception {
    try (ZooKeeperBenchmarkEnvironment environment = ZooKeeperBenchmarkEnvironment.start()) {
        MutexLock lock = environment.lockManager().mutex("bench:smoke:zk");
        assertThat(lock.tryLock(Duration.ofMillis(100))).isTrue();
    }
}
```

```java
@Test
void springEnvironmentShouldExposeBenchmarkService() throws Exception {
    try (SpringBenchmarkEnvironment environment = SpringBenchmarkEnvironment.start()) {
        assertThat(environment.programmaticService()).isNotNull();
        assertThat(environment.annotatedService()).isNotNull();
    }
}
```

- [ ] **Step 2: Run the smoke test to verify it fails**

Run:

```bash
mvn -q -f distributed-lock-benchmarks/pom.xml -Dtest=BenchmarkEnvironmentSmokeTest test
```

Expected: FAIL because the benchmark environment classes do not exist yet.

- [ ] **Step 3: Implement `RedisBenchmarkEnvironment`**

Use this shape:

```java
public final class RedisBenchmarkEnvironment implements AutoCloseable {
    public static RedisBenchmarkEnvironment start() { ... }
    public LockRuntime runtime() { ... }
    public LockManager lockManager() { ... }
    @Override
    public void close() throws Exception { ... }
}
```

Rules:

- start Redis with Testcontainers in `start()`
- create the runtime with `LockRuntimeBuilder.create().backend("redis").configuration(Map.of(...)).build()`
- close runtime before stopping the container

- [ ] **Step 4: Implement `ZooKeeperBenchmarkEnvironment`**

Use this shape:

```java
public final class ZooKeeperBenchmarkEnvironment implements AutoCloseable {
    public static ZooKeeperBenchmarkEnvironment start() { ... }
    public LockRuntime runtime() { ... }
    public LockManager lockManager() { ... }
    @Override
    public void close() throws Exception { ... }
}
```

Rules:

- start `TestingServer` in `start()`
- create runtime with `backend("zookeeper")`
- close runtime before closing `TestingServer`

- [ ] **Step 5: Implement the Spring benchmark application and environment**

`SpringBenchmarkApplication` should expose:

```java
@SpringBootApplication
class SpringBenchmarkApplication {
    @Service
    static class BenchmarkService {
        @DistributedLock(key = "bench:spring:annotated:#{#p0}", waitFor = "250ms")
        public void annotated(String id) { ... }

        public void programmatic(String id, LockManager lockManager) throws InterruptedException { ... }
    }
}
```

`SpringBenchmarkEnvironment` should:

- start a Spring context in `@Setup(Level.Trial)`-equivalent lifecycle for later benchmark classes
- wire the starter against a real Redis container
- expose the benchmark service bean and `LockManager`

- [ ] **Step 6: Re-run the smoke test to verify the environments work**

Run:

```bash
mvn -q -f distributed-lock-benchmarks/pom.xml -Dtest=BenchmarkEnvironmentSmokeTest test
```

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support \
  distributed-lock-benchmarks/src/test/java/com/mycorp/distributedlock/benchmarks/BenchmarkEnvironmentSmokeTest.java
git commit -m "feat: add real benchmark environments"
```

## Task 3: Implement Shared Helpers and Mutex Benchmarks

**Files:**
- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/BenchmarkKeys.java`
- Create: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/BenchmarkWorkloads.java`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/MutexLifecycleBenchmark.java`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/MutexContentionBenchmark.java`

- [ ] **Step 1: Write the mutex benchmark classes against helper APIs that do not exist yet**

Target usage:

```java
String key = BenchmarkKeys.unique("mutex-lifecycle", "redis", Thread.currentThread().getId());
BenchmarkWorkloads.mutexLifecycle(environment.lockManager(), key, blackhole);
```

```java
String key = BenchmarkKeys.shared("mutex-contention", "zookeeper");
BenchmarkWorkloads.contendedTryLock(environment.lockManager(), key, Duration.ofMillis(waitMillis), blackhole);
```

- [ ] **Step 2: Run compile to verify the missing helper APIs fail**

Run:

```bash
mvn -q -f distributed-lock-benchmarks/pom.xml -DskipTests compile
```

Expected: FAIL because `BenchmarkKeys` and `BenchmarkWorkloads` do not exist yet.

- [ ] **Step 3: Implement `BenchmarkKeys`**

Use a tiny static utility:

```java
public final class BenchmarkKeys {
    public static String unique(String suite, String backend, long id) { ... }
    public static String shared(String suite, String backend) { ... }
}
```

Rules:

- key format must be deterministic
- shared and unique keys must be visually distinguishable in backend inspection

- [ ] **Step 4: Implement `BenchmarkWorkloads`**

Include small helpers such as:

```java
public static void mutexLifecycle(LockManager manager, String key, Blackhole bh) throws InterruptedException { ... }
public static void contendedTryLock(LockManager manager, String key, Duration wait, Blackhole bh) throws InterruptedException { ... }
```

Rules:

- always release locks in `finally`
- keep the critical section tiny and deterministic
- do not sleep in the critical section unless the scenario explicitly requires it

- [ ] **Step 5: Fill in `MutexLifecycleBenchmark`**

Required scenarios:

- Redis unique-key lifecycle
- ZooKeeper unique-key lifecycle
- Redis successful `tryLock(Duration)` path
- ZooKeeper successful `tryLock(Duration)` path

Use one real environment per backend with `@Setup(Level.Trial)` and `@TearDown(Level.Trial)`.

- [ ] **Step 6: Fill in `MutexContentionBenchmark`**

Required structure:

```java
@Param({"1", "4", "8"})
int threadCount;
```

Use shared keys and JMH groups or parameterized thread scenarios, but keep the measured path limited to lock attempts and small critical-section work.

- [ ] **Step 7: Re-run compile and smoke test**

Run:

```bash
mvn -q -f distributed-lock-benchmarks/pom.xml -DskipTests compile
mvn -q -f distributed-lock-benchmarks/pom.xml -Dtest=BenchmarkEnvironmentSmokeTest test
```

Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/BenchmarkKeys.java \
  distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/BenchmarkWorkloads.java \
  distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/MutexLifecycleBenchmark.java \
  distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/MutexContentionBenchmark.java
git commit -m "feat: add mutex benchmarks for lock 2.0"
```

## Task 4: Implement Read/Write and Runtime Lifecycle Benchmarks

**Files:**
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/ReadWriteLockBenchmark.java`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/RuntimeLifecycleBenchmark.java`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/BenchmarkWorkloads.java`

- [ ] **Step 1: Extend the benchmark classes to reference not-yet-implemented read/write and runtime helpers**

Target helper usage:

```java
BenchmarkWorkloads.readSection(environment.lockManager(), key, blackhole);
BenchmarkWorkloads.writeSection(environment.lockManager(), key, blackhole);
BenchmarkWorkloads.runtimeLifecycle(() -> environmentFactory.startRuntime(), blackhole);
```

- [ ] **Step 2: Run compile to verify the new helper methods are missing**

Run:

```bash
mvn -q -f distributed-lock-benchmarks/pom.xml -DskipTests compile
```

Expected: FAIL because read/write and runtime helper methods are not implemented yet.

- [ ] **Step 3: Extend `BenchmarkWorkloads` with read/write helpers**

Add helpers shaped like:

```java
public static void readSection(LockManager manager, String key, Blackhole bh) throws InterruptedException { ... }
public static void writeSection(LockManager manager, String key, Blackhole bh) throws InterruptedException { ... }
```

Rules:

- use `manager.readWrite(key).readLock()` and `writeLock()`
- never attempt unsupported upgrade/downgrade flows

- [ ] **Step 4: Implement `ReadWriteLockBenchmark`**

Required scenarios:

- Redis read-heavy path
- ZooKeeper read-heavy path
- Redis write-exclusive path
- ZooKeeper write-exclusive path
- Redis mixed read/write path
- ZooKeeper mixed read/write path

Use already-started backend environments and keep setup out of the measured path.

- [ ] **Step 5: Implement `RuntimeLifecycleBenchmark`**

Required scenarios:

- Redis runtime build and close
- ZooKeeper runtime build and close
- lock-manager acquisition on already-running backend infrastructure
- handle creation for mutex and read/write entry points

Rules:

- do not measure container or `TestingServer` startup
- do not reuse fake backends

- [ ] **Step 6: Re-run compile and smoke test**

Run:

```bash
mvn -q -f distributed-lock-benchmarks/pom.xml -DskipTests compile
mvn -q -f distributed-lock-benchmarks/pom.xml -Dtest=BenchmarkEnvironmentSmokeTest test
```

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/ReadWriteLockBenchmark.java \
  distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/RuntimeLifecycleBenchmark.java \
  distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/BenchmarkWorkloads.java
git commit -m "feat: add read write and runtime benchmarks"
```

## Task 5: Implement the Spring Starter Benchmark

**Files:**
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/SpringStarterBenchmark.java`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/SpringBenchmarkApplication.java`
- Modify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/SpringBenchmarkEnvironment.java`

- [ ] **Step 1: Write the benchmark class against explicit Spring benchmark entry points**

Target usage:

```java
environment.annotatedService().annotated("spring-path");
environment.benchmarkService().programmatic("spring-path");
```

- [ ] **Step 2: Run compile to verify the Spring benchmark entry points are incomplete**

Run:

```bash
mvn -q -f distributed-lock-benchmarks/pom.xml -DskipTests compile
```

Expected: FAIL because the Spring benchmark application or environment does not yet expose the required benchmark methods cleanly.

- [ ] **Step 3: Implement the programmatic Spring path**

In `SpringBenchmarkApplication`, add a service method shaped like:

```java
public void programmatic(String id) throws InterruptedException {
    MutexLock lock = lockManager.mutex("bench:spring:programmatic:" + id);
    if (!lock.tryLock(Duration.ofMillis(250))) {
        throw new IllegalStateException("lock busy");
    }
    try (lock) {
        Blackhole.consumeCPU(64);
    }
}
```

Use a benchmark-friendly abstraction if direct `Blackhole` access inside the service is awkward; the point is to keep the path real but small.

- [ ] **Step 4: Implement the annotation-driven Spring path**

In the same benchmark service:

```java
@DistributedLock(key = "bench:spring:annotated:#{#p0}", waitFor = "250ms")
public void annotated(String id) {
    // small critical section
}
```

Rules:

- benchmark this against an already-started Spring context
- do not measure context startup in these methods

- [ ] **Step 5: Implement `SpringStarterBenchmark`**

Required scenarios:

- annotated path benchmark
- programmatic path benchmark

Use a real Redis-backed Spring environment started in `@Setup(Level.Trial)`.

- [ ] **Step 6: Re-run compile and smoke test**

Run:

```bash
mvn -q -f distributed-lock-benchmarks/pom.xml -DskipTests compile
mvn -q -f distributed-lock-benchmarks/pom.xml -Dtest=BenchmarkEnvironmentSmokeTest test
```

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/SpringStarterBenchmark.java \
  distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/SpringBenchmarkApplication.java \
  distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/support/SpringBenchmarkEnvironment.java
git commit -m "feat: add spring starter benchmark paths"
```

## Task 6: Final Documentation and Manual Run Verification

**Files:**
- Modify: `distributed-lock-benchmarks/README.md`
- Verify: `distributed-lock-benchmarks/pom.xml`
- Verify: `distributed-lock-benchmarks/src/main/java/com/mycorp/distributedlock/benchmarks/**`
- Verify: `distributed-lock-benchmarks/src/test/java/com/mycorp/distributedlock/benchmarks/BenchmarkEnvironmentSmokeTest.java`

- [ ] **Step 1: Expand the benchmark README into the final manual operator guide**

Required sections:

- prerequisites
- standalone compile command
- smoke test command
- package command
- example JMH invocations
- note that the module stays outside the default reactor

Include at least these commands verbatim:

```bash
mvn -q install -DskipTests
mvn -q -f distributed-lock-benchmarks/pom.xml test
mvn -q -f distributed-lock-benchmarks/pom.xml package
java -jar distributed-lock-benchmarks/target/benchmarks.jar MutexLifecycleBenchmark
```

- [ ] **Step 2: Run the final standalone compile**

Run:

```bash
mvn -q install -DskipTests
mvn -q -f distributed-lock-benchmarks/pom.xml -DskipTests compile
```

Expected: PASS

- [ ] **Step 3: Run the final smoke test**

Run:

```bash
mvn -q -f distributed-lock-benchmarks/pom.xml -Dtest=BenchmarkEnvironmentSmokeTest test
```

Expected: PASS

- [ ] **Step 4: Package the runnable benchmark jar**

Run:

```bash
mvn -q -f distributed-lock-benchmarks/pom.xml package
```

Expected: PASS and produces `distributed-lock-benchmarks/target/benchmarks.jar`

- [ ] **Step 5: Run one focused JMH benchmark manually**

Run:

```bash
java -jar distributed-lock-benchmarks/target/benchmarks.jar MutexLifecycleBenchmark -wi 1 -i 1 -f 1
```

Expected: JMH prints a result table and exits successfully after running against real backend infrastructure.

- [ ] **Step 6: Sweep the benchmark module for deleted 1.x compile-time references**

Run:

```bash
rg -n "DistributedLockFactory|LockProvider|SpringDistributedLockFactory|BatchLockOperations|DistributedReadWriteLock" distributed-lock-benchmarks/src
```

Expected: no matches

- [ ] **Step 7: Confirm the root reactor is still unchanged**

Run:

```bash
rg -n "<module>distributed-lock-benchmarks</module>" pom.xml
```

Expected: no matches

- [ ] **Step 8: Commit**

```bash
git add distributed-lock-benchmarks
git commit -m "refactor: modernize benchmarks for lock 2.0"
```
