# Distributed Lock Benchmarks 2.0 Design

## Summary

This document defines the benchmark modernization for `distributed-lock-benchmarks` after the 2.0 core rebuild.

The benchmark module will be rebuilt as a 2.0-only performance asset with these fixed constraints:

- it remains outside the default reactor
- it uses only 2.0 APIs and runtime paths
- all measured scenarios use real backends
- it must compile and run manually
- it does not become part of the default `mvn test` workflow

This work is a redesign, not a compatibility patch. Old 1.x benchmark classes that depend on removed factories, providers, batch operations, async operations, or health APIs may be deleted directly.

## Why This Exists

The current benchmark module still reflects the deleted 1.x model:

- it depends on removed types such as `DistributedLockFactory`, `DistributedLock`, `DistributedReadWriteLock`, and `LockProvider`
- it includes benchmark scenarios for removed 1.x public capabilities such as batch operations and factory health checks
- it compares against `SpringDistributedLockFactory`, which no longer exists in 2.0
- it uses a smoke test built around mocked 1.x provider abstractions instead of real benchmark environment validation

After the 2.0 core rebuild, this module is no longer aligned with the product it is supposed to measure. The benchmark suite must therefore be rebuilt around the 2.0 architecture instead of patched to keep old names alive.

## Product Boundary

### Included

This benchmark redesign covers:

- benchmark module dependency modernization
- real Redis benchmark environment
- real ZooKeeper benchmark environment
- real Spring Boot starter benchmark environment
- JMH benchmarks for 2.0 mutex, read/write, runtime, and starter paths
- a lightweight smoke-style test suite for benchmark environment startup
- benchmark README and manual run instructions

### Excluded

This redesign does not cover:

- putting benchmarks back into the default root reactor
- automatic benchmark execution in normal CI or `mvn test`
- extension benchmarks for async, batch, observability, health, HA, fairness, or deadlock detection
- result dashboards, historical result storage, or automated performance regression thresholds
- a standalone benchmark platform or orchestration framework

## Core Decisions

### Decision 1: The Module Stays Outside the Default Reactor

`distributed-lock-benchmarks` will remain in the repository but stay out of the root `<modules>` list.

Rationale:

- benchmark execution is slower and more fragile than mainline verification
- real backend startup should not become part of the default developer workflow
- the 2.0 kernel should not carry extra build complexity for performance assets

Required outcome:

- the module inherits the root parent and shares dependency management
- the module is built explicitly with `-f distributed-lock-benchmarks/pom.xml`

### Decision 2: Rebuild Around 2.0 Scenarios, Not 1.x Names

The module will be organized by 2.0 scenarios rather than by legacy abstraction names.

Examples of acceptable scenario naming:

- `MutexLifecycleBenchmark`
- `MutexContentionBenchmark`
- `ReadWriteLockBenchmark`
- `RuntimeLifecycleBenchmark`
- `SpringStarterBenchmark`

Examples of legacy-oriented names that should be removed:

- `BatchLockOperationsBenchmark`
- `EnhancedDistributedLockBenchmark`
- `DistributedLockBenchmark`
- `SpringDistributedLockBenchmark`
- any benchmark whose meaning depends on `DistributedLockFactory` or `LockProvider`

### Decision 3: All Benchmarks Use Real Backends

Every measured benchmark path uses real backend infrastructure:

- Redis benchmarks use a real Redis instance
- ZooKeeper benchmarks use a real ZooKeeper server
- Spring starter benchmarks are backed by a real Redis runtime path

This means:

- no in-memory benchmark substitutes
- no mocked backend implementations for JMH measurements
- no fake runtime-only microbenchmarks detached from backend behavior

The only non-JMH test layer allowed is a smoke-style correctness check that validates benchmark environments can start and basic 2.0 runtime creation works.

### Decision 4: Manual Benchmarking, Minimal Day-to-Day Guarantees

The module guarantee is intentionally limited:

- it must compile
- it must package into a runnable JMH jar
- it must provide manual benchmark commands
- it may provide lightweight smoke tests

It does not guarantee:

- automatic execution on every mainline build
- automatic performance gating
- stable benchmark numbers across machines

## Target Module Structure

The module should converge to this shape:

```text
distributed-lock-benchmarks
  pom.xml
  README.md
  src/main/java/com/mycorp/distributedlock/benchmarks/support/*
  src/main/java/com/mycorp/distributedlock/benchmarks/MutexLifecycleBenchmark.java
  src/main/java/com/mycorp/distributedlock/benchmarks/MutexContentionBenchmark.java
  src/main/java/com/mycorp/distributedlock/benchmarks/ReadWriteLockBenchmark.java
  src/main/java/com/mycorp/distributedlock/benchmarks/RuntimeLifecycleBenchmark.java
  src/main/java/com/mycorp/distributedlock/benchmarks/SpringStarterBenchmark.java
  src/test/java/com/mycorp/distributedlock/benchmarks/BenchmarkEnvironmentSmokeTest.java
```

## Dependency Model

### Allowed Project Dependencies

- `distributed-lock-api`
- `distributed-lock-runtime`
- `distributed-lock-redis`
- `distributed-lock-zookeeper`
- `distributed-lock-spring-boot-starter`

### Allowed Library Dependencies

- JMH
- Lettuce
- Curator framework and recipes
- Curator `TestingServer`
- Testcontainers for Redis
- Spring Boot test support, only where required for starter-path benchmarking

### Forbidden Dependencies and Coupling

- any removed 1.x API or compatibility layer
- any dependency that exists solely to preserve deleted benchmark scenarios
- any benchmark-only pressure on public API design

The benchmark module must adapt to 2.0. The 2.0 codebase must not adapt to old benchmark code.

## Benchmark Environments

### Redis Environment

`RedisBenchmarkEnvironment` is responsible for:

- starting a real Redis backend, preferably with Testcontainers
- creating a 2.0 `LockRuntime`
- exposing a `LockManager`
- shutting everything down cleanly

### ZooKeeper Environment

`ZooKeeperBenchmarkEnvironment` is responsible for:

- starting a real ZooKeeper server with `TestingServer`
- creating a 2.0 `LockRuntime`
- exposing a `LockManager`
- shutting everything down cleanly

### Spring Environment

`SpringBenchmarkEnvironment` is responsible for:

- starting a minimal Spring Boot 3 application context
- wiring the 2.0 starter with a real Redis backend
- exposing both annotation-driven and programmatic benchmark entry points
- shutting the context and backend down cleanly

### Shared Support

Common helpers should live under `benchmarks/support`, including:

- `BenchmarkKeys`
  - consistent shared-key vs unique-key generation
- `BenchmarkWorkloads`
  - shared critical-section bodies and helper operations
- any small support class that removes duplication from benchmark setup and teardown

## Benchmark Suite Design

### 1. Mutex Lifecycle Benchmark

Purpose:

- measure baseline acquire/release cost for 2.0 `MutexLock`

Required scenarios:

- Redis unique-key lifecycle
- ZooKeeper unique-key lifecycle
- `tryLock(Duration)` success path

This benchmark answers:

- what is the single-operation cost of the main 2.0 mutex path on each backend

### 2. Mutex Contention Benchmark

Purpose:

- measure contention behavior on a shared key

Required characteristics:

- shared key path
- parameterized thread counts
- Redis and ZooKeeper comparison
- failed `tryLock(Duration)` and successful acquisition both contribute to the measured path

This benchmark answers:

- how each backend degrades under lock contention

### 3. Read/Write Lock Benchmark

Purpose:

- measure 2.0 read/write lock behavior

Required scenarios:

- read-heavy shared access
- write-exclusive path
- mixed read/write traffic

Explicit exclusion:

- no upgrade or downgrade benchmarks, because 2.0 defines those transitions as unsupported fail-fast paths rather than supported operations

### 4. Runtime Lifecycle Benchmark

Purpose:

- measure the practical overhead of the 2.0 runtime assembly layer with real backends

Required scenarios:

- build `LockRuntime`
- obtain `LockManager`
- create lock handles
- close runtime

This benchmark is still backend-backed. It is not a fake in-memory microbenchmark.

### 5. Spring Starter Benchmark

Purpose:

- measure the incremental cost of the 2.0 Spring Boot integration layer

Required scenarios:

- annotation-driven lock path with SpEL key resolution
- programmatic `LockManager` path inside Spring
- Redis-backed starter path using real backend infrastructure

This benchmark answers:

- how much overhead the starter and AOP path add relative to direct runtime usage

## Deleted Legacy Benchmark Surface

The following categories should be removed entirely:

- batch-operation benchmarks
- async-operation benchmarks
- factory health and health-check benchmarks
- benchmarks built around `SpringDistributedLockFactory`
- smoke tests based on `LockProvider`
- any JMH benchmark that requires deleted 1.x factories, providers, or result DTOs

This deletion is intentional and desirable. These scenarios no longer describe the 2.0 product.

## Smoke Test Strategy

The module may keep a lightweight test suite, but only for environment sanity checks.

`BenchmarkEnvironmentSmokeTest` should verify:

- Redis benchmark environment can start and create a runtime
- ZooKeeper benchmark environment can start and create a runtime
- Spring benchmark environment can start and expose the expected benchmark entry path

It must not:

- benchmark performance through JUnit
- use mocked 1.x provider abstractions
- reintroduce fake benchmark dependencies just to make tests easy

## Manual Execution Model

### Compile

```bash
mvn -f distributed-lock-benchmarks/pom.xml -DskipTests compile
```

### Smoke Test

```bash
mvn -f distributed-lock-benchmarks/pom.xml test
```

### Package Runnable JMH Jar

```bash
mvn -f distributed-lock-benchmarks/pom.xml package
```

### Run a Specific Benchmark

```bash
java -jar distributed-lock-benchmarks/target/benchmarks.jar MutexLifecycleBenchmark
```

### Run a Benchmark Family

```bash
java -jar distributed-lock-benchmarks/target/benchmarks.jar ".*Spring.*"
java -jar distributed-lock-benchmarks/target/benchmarks.jar ".*ReadWrite.*"
```

The README must document these commands explicitly.

## Error Handling and Operational Rules

- benchmark environments must fail fast when Redis or ZooKeeper cannot be started
- setup and teardown must always release backend resources
- starter benchmark code must not hide backend startup failures behind silent fallbacks
- benchmarks must not depend on developer-local preinstalled infrastructure unless explicitly documented

## Verification Requirements

Before this redesign is considered complete:

- `distributed-lock-benchmarks` compiles against the current 2.0 main branch
- its smoke tests pass with real backend startup
- `package` produces a runnable JMH jar
- no benchmark source depends on deleted 1.x APIs
- the root reactor remains unchanged and does not include the benchmark module

## Non-Goals

This redesign is not trying to produce:

- a permanent benchmark CI pipeline
- automatic historical result comparison
- a generalized benchmarking platform for future extension modules
- compatibility preservation for deleted 1.x benchmark assets

## Acceptance Criteria

This design is satisfied when:

- the benchmark module measures only 2.0-supported behavior
- the benchmark suite is organized around explicit 2.0 scenarios
- all measured paths use real Redis or ZooKeeper infrastructure
- the module stays out of the default reactor
- the module can be compiled, smoke-tested, packaged, and run manually with documented commands
