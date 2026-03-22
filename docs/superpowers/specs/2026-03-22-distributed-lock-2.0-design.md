# Distributed Lock 2.0 Design

## Summary

This document defines the target architecture for `distributed-lock` 2.0.

The 2.0 direction is:

- a small and stable distributed lock kernel
- explicit backend adapters for Redis and ZooKeeper
- a thin Spring Boot integration layer
- optional capabilities delivered as external extensions

2.0 is a breaking redesign. It does **not** preserve 1.x public APIs, factories, providers, compatibility facades, or configuration aliases. Old 1.x architecture elements may be removed directly once their 2.0 replacements exist.

## Why 2.0 Exists

The current 1.x codebase has several structural failures:

- oversized public interfaces that mix lock semantics with async, batch, health, metrics, HA, events, and lifecycle concerns
- a `core` module that depends on Redis, ZooKeeper, Spring, Micrometer, and OpenTelemetry
- multiple overlapping factory/provider abstractions with no single runtime truth
- backend-specific semantics forced into a fake shared API surface
- starter, examples, metadata, and configuration models that drift independently
- tests that validate mocked plumbing instead of real end-to-end module interaction

2.0 exists to replace that with a narrow, enforceable architecture.

## Product Positioning

2.0 serves two goals at the same time, but at different layers:

- product goal: a small, reliable distributed lock library
- architecture goal: an extensible platform around that stable kernel

This is resolved by making the kernel intentionally small and pushing non-essential capabilities into extension modules.

## Core Decisions

### Decision 1: The Kernel Is Small

The default product only guarantees:

- mutex locks
- read/write locks
- Redis backend
- ZooKeeper backend
- Spring Boot integration

The kernel does not guarantee:

- batch lock operations
- async lock operations
- metrics
- health endpoints
- tracing
- HA strategies
- event listeners
- fair locks
- deadlock detection

Those capabilities may exist as extensions, but they do not shape kernel APIs.

### Decision 2: No 1.x Compatibility Layer

2.0 does not carry forward 1.x compatibility shims.

The following are explicitly allowed:

- deleting legacy factories
- deleting legacy providers
- deleting oversized result DTO interfaces
- deleting deprecated configuration aliases
- deleting benchmark-only or example-only compatibility constructors
- deleting modules that exist only to preserve 1.x API drift

If limited migration help is needed later, it must live in a separate `legacy-adapter` artifact and must not affect the 2.0 kernel design.

### Decision 3: Ports and Adapters

2.0 uses a ports-and-adapters architecture:

- `api` exposes the smallest public contract
- `core` defines domain behavior and backend ports
- backend modules implement those ports
- `runtime` assembles the system
- `spring-boot-starter` integrates runtime with Spring
- extensions decorate runtime-managed services

## Target Module Topology

### Mandatory Modules

#### `distributed-lock-api`

Purpose:

- hold only minimal public contracts and public exceptions

Allowed dependencies:

- JDK
- minimal logging only if truly necessary

Forbidden dependencies:

- Redis
- ZooKeeper
- Spring
- Micrometer
- OpenTelemetry

#### `distributed-lock-core`

Purpose:

- define lock domain behavior
- define backend ports
- implement common orchestration
- hold shared value objects and domain rules

Allowed dependencies:

- `distributed-lock-api`
- JDK

Forbidden dependencies:

- backend clients
- Spring
- metrics/tracing libraries

#### `distributed-lock-runtime`

Purpose:

- load backend modules
- select a backend
- construct `LockManager`
- own resource lifecycle

Allowed dependencies:

- `distributed-lock-api`
- `distributed-lock-core`
- Java `ServiceLoader`

#### `distributed-lock-redis`

Purpose:

- implement Redis backend adapter

Allowed dependencies:

- `distributed-lock-api`
- `distributed-lock-core`
- `distributed-lock-runtime`
- Lettuce

#### `distributed-lock-zookeeper`

Purpose:

- implement ZooKeeper backend adapter

Allowed dependencies:

- `distributed-lock-api`
- `distributed-lock-core`
- `distributed-lock-runtime`
- Curator/ZooKeeper client

#### `distributed-lock-spring-boot-starter`

Purpose:

- bind Spring configuration
- build `LockRuntime`
- expose `LockManager`
- provide annotation-based locking integration

Allowed dependencies:

- `distributed-lock-api`
- `distributed-lock-runtime`
- Spring Boot

Forbidden responsibilities:

- backend implementation
- runtime selection logic beyond property mapping
- custom lock factory stacks

#### `distributed-lock-testkit`

Purpose:

- define shared contract tests
- provide backend-agnostic test fixtures

### Optional Extension Modules

Potential extension artifacts:

- `distributed-lock-extension-async`
- `distributed-lock-extension-batch`
- `distributed-lock-extension-observability`
- `distributed-lock-extension-health`
- `distributed-lock-extension-observability-spring`
- `distributed-lock-extension-health-spring`

Rules for all extensions:

- may depend on `api`, `core`, and `runtime`
- may decorate `LockManager` or runtime-managed services
- may not add requirements to the kernel API
- may not force backend modules to implement unrelated features

### Support Modules

#### `distributed-lock-examples`

Purpose:

- compile and run against real 2.0 APIs
- demonstrate only supported public usage

#### `distributed-lock-benchmarks`

Purpose:

- performance measurement only

Constraint:

- benchmark needs must not alter public API design

## Dependency Direction

The allowed dependency flow is:

```text
api
  <- core
  <- runtime
  <- spring-boot-starter
  <- extensions

core
  <- runtime
  <- redis
  <- zookeeper
  <- extensions

runtime
  <- spring-boot-starter
  <- extensions

redis / zookeeper
  <- examples
  <- tests
```

No module may depend back into Spring, Redis, or ZooKeeper from `api` or `core`.

## Public 2.0 API

### Public Interfaces

Recommended public API shape:

```java
public interface LockManager {
    MutexLock mutex(String key);
    ReadWriteLock readWrite(String key);
}

public interface MutexLock extends AutoCloseable {
    void lock() throws InterruptedException;
    boolean tryLock(Duration waitTime) throws InterruptedException;
    void unlock();
    boolean isHeldByCurrentThread();
    String key();
}

public interface ReadWriteLock {
    MutexLock readLock();
    MutexLock writeLock();
}
```

### Public API Rules

Public API must not include:

- backend-specific lease models
- renewal APIs
- health inspection APIs
- metrics APIs
- batch execution APIs
- async APIs
- factory statistics/state/configuration APIs
- backend selection by priority
- listener registration APIs

The public API is intentionally small so every supported backend can implement it honestly.

### Public Error Model

2.0 public error handling is intentionally narrow:

- invalid lock key input uses `IllegalArgumentException`
- unlocking without ownership uses `IllegalMonitorStateException`
- interruption during acquisition uses `InterruptedException`
- backend/client/protocol failures during lock operations use a single public unchecked exception: `LockBackendException`
- invalid runtime/backend selection or invalid configuration at startup uses a single public unchecked exception: `LockConfigurationException`

Kernel and runtime code should prefer these standard or explicitly defined exceptions instead of introducing feature-specific exception trees.

### Public API Timing Rules

Public acquisition semantics are fixed as follows:

- `lock()` waits indefinitely until the lock is acquired or the thread is interrupted
- `tryLock(Duration waitTime)` is the only public timed acquisition API
- backend configuration may tune client/network/operation timeouts, but may not redefine the meaning of `lock()` or `tryLock(Duration)`
- Spring annotation defaults may supply a wait duration only for annotation-driven acquisition when the annotation omits one

This prevents backend configuration from silently changing public locking semantics.

### Kernel Lock Semantics

2.0 kernel semantics are fixed as follows:

- mutex locks are reentrant for the same thread within the same `LockManager`
- read locks are reentrant for the same thread within the same `LockManager`
- write locks are reentrant for the same thread within the same `LockManager`
- reentrancy and ownership are defined by logical lock key within a `LockManager`, not by Java object identity of the returned handle
- a lock acquired `N` times by the same thread must be unlocked `N` times before it is fully released
- unlock by a non-owning thread must fail
- ownership is thread-affine in the kernel contract
- read/write upgrade is not part of the kernel API
- read/write downgrade is not part of the kernel API
- if a thread already holds a read lock for key `K`, an attempt to acquire the write lock for key `K` through the kernel API must fail fast rather than block or deadlock
- if a thread already holds a write lock for key `K`, an attempt to acquire the read lock for key `K` through the kernel API must fail fast rather than implicitly downgrade
- fairness is not part of the kernel contract
- public API does not expose lease or renewal semantics

This keeps the external model close to Java lock expectations while still allowing backend-specific internal implementations.

### AutoCloseable Semantics

2.0 close semantics are fixed as follows:

- `MutexLock.close()` releases one hold if and only if the current thread owns the lock
- `MutexLock.close()` is a no-op when the current thread does not own the lock
- runtime lifecycle is owned by `LockRuntime`, not by `LockManager`

This allows safe try-with-resources usage without creating a second lifecycle authority for the runtime.

## Core Domain and Ports

### Core Port

`core` owns backend-neutral ports.

Recommended backend port shape:

```java
public interface LockBackend {
    BackendLockHandle acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) throws InterruptedException;
    void release(BackendLockHandle handle);
    boolean isHeldByCurrentExecution(BackendLockHandle handle);
}
```

Supporting value objects:

- `LockResource`
- `LockMode` with `MUTEX`, `READ`, `WRITE`
- `WaitPolicy`
- `BackendLockHandle`

### Core Behavioral Rules

`core` owns:

- input validation
- lock acquisition orchestration
- read/write lock composition
- lifecycle semantics from the point of view of public API

`core` does not own:

- Redis key layout
- ZooKeeper path layout
- Spring expression parsing
- metrics emission
- tracing spans
- Actuator health payloads

## Runtime Architecture

### Single Runtime Truth

2.0 has exactly one runtime assembly model.

Recommended public runtime entry point:

```java
public interface LockRuntime extends AutoCloseable {
    LockManager lockManager();
}
```

Recommended construction:

```java
LockRuntime runtime = LockRuntimeBuilder.create()
    .backend("redis")
    .configuration(configSource)
    .build();
```

### Backend Module SPI

Backend discovery remains internal to runtime only.

Recommended SPI:

```java
public interface BackendModule {
    String id();
    BackendCapabilities capabilities();
    LockBackend createBackend(BackendContext context);
}
```

### Backend Selection Rules

Backend selection must be explicit and deterministic:

1. If backend id is configured, runtime must use that backend or fail startup.
2. If backend id is not configured and exactly one backend is present, runtime may use it.
3. If backend id is not configured and multiple backends are present, runtime must fail startup.
4. If no backend is present, runtime must fail startup.

Runtime must not use provider priorities to decide a backend.

### Lifecycle Ownership

`LockRuntime.close()` owns:

- lock manager shutdown
- backend client shutdown
- scheduler shutdown
- resources created by runtime

No other public layer should manually own backend clients in normal usage.

## Redis and ZooKeeper Adapter Responsibilities

### Redis Adapter

The Redis module owns:

- Redis key naming
- token ownership model
- Redis-specific timeouts and lease behavior
- Lua or atomic command usage

### ZooKeeper Adapter

The ZooKeeper module owns:

- path layout
- Curator/ZooKeeper session handling
- lock recipe usage
- connection lifecycle

### Shared Constraint

Redis and ZooKeeper must implement the same kernel semantics, but they may use different internal ownership and duration models. The kernel API must not fake a single backend timing model where one does not exist.

## Configuration Model

### Single Configuration Namespace

2.0 uses one namespace only:

- `distributed.lock.*`

The following must be removed:

- `distributed-lock.*`
- `spring.distributed-lock.*`
- environment/system-property alias sprawl used as equivalent configuration languages

### Configuration Layers

#### Runtime configuration

Examples:

```yaml
distributed:
  lock:
    enabled: true
    backend: redis
```

Runtime configuration may contain only cross-backend concerns.

#### Backend configuration

Examples:

```yaml
distributed:
  lock:
    redis:
      uri: redis://localhost:6379
      key-prefix: dlock:
      operation-timeout: 5s
      lease-time: 30s
    zookeeper:
      connect-string: localhost:2181
      base-path: /distributed-locks
      session-timeout: 30s
      operation-timeout: 5s
```

Backend-specific configuration is interpreted only by the owning backend module.

Backend configuration may define adapter-internal settings such as:

- network/client timeout
- command/operation timeout
- Redis lease duration
- ZooKeeper session timeout

Backend configuration may not override the public meaning of `lock()` or `tryLock(Duration)`.

#### Spring integration configuration

Examples:

```yaml
distributed:
  lock:
    spring:
      annotation:
        enabled: true
        default-timeout: 5s
```

This configuration is only for Spring integration behavior.

Precedence rules for Spring-driven locking:

1. annotation `waitFor` value, if present
2. starter annotation default timeout
3. no further fallback at the Spring layer; if neither is set, acquisition is unbounded and uses `lock()`

## Spring Boot Integration

### Platform Baseline

The 2.0 starter targets:

- Java 17 minimum
- Spring Boot 3.x
- Jakarta-based Spring APIs
- Boot 3 auto-configuration registration conventions

2.0 must not support both Boot 2 and Boot 3 in the same starter artifact.

### Starter Responsibilities

The starter should:

- bind Spring properties
- build `LockRuntime`
- expose `LockRuntime` and `LockManager` as beans
- provide annotation-based locking

The starter should not:

- implement backend lock logic
- maintain a separate lock factory abstraction
- define backend health contracts
- define metrics/tracing contracts
- create its own provider stack

### Annotation Model

The starter exposes a small annotation surface.

Recommended shape:

```java
@DistributedLock(
    key = "order:#{#orderId}",
    mode = DistributedLockMode.MUTEX,
    waitFor = "5s"
)
```

The annotation mode enum is part of the starter public API and must not reuse the internal core backend `LockMode` enum.

The annotation must not include:

- fairness controls
- retry policy controls
- renewal controls
- HA controls
- deadlock detection controls

### Spring-Specific Key Resolution

Spring expression support belongs only in the starter layer through a Spring-specific `LockKeyResolver`.

It must not be implemented in `core`.

## Extension Strategy

### Official Extensions

Keep as optional official extensions:

- async operations
- batch operations
- observability
- health reporting

These extensions must live outside kernel API and integrate through decoration or runtime hooks.

### Experimental Only

Do not include in the 2.0 core or official default extension set:

- HA strategy abstraction
- deadlock detection
- event listener architecture
- fair lock abstraction

If revisited later, they must be explicitly marked experimental and backend-specific where needed.

### Direct Deletions

Delete from 2.0 rather than preserving compatibility:

- factory statistics APIs
- factory state/configuration APIs
- oversized async result object hierarchies
- benchmark/example compatibility facades
- factory/provider duplication layers

## Testing Strategy

### Layer 1: Contract Tests

`distributed-lock-testkit` defines shared behavioral contracts for any `LockManager` implementation.

Required kernel contract cases:

- mutex excludes concurrent holders
- `tryLock(waitTime)` obeys waiting semantics
- same-thread reentry succeeds and requires balanced unlocks
- unlocking without ownership fails
- read locks can coexist
- write lock excludes read and write lock holders
- runtime shutdown prevents future acquisition
- read-to-write acquisition on the same key fails fast for the same thread
- write-to-read acquisition on the same key fails fast for the same thread
- `MutexLock.close()` behaves as specified by the kernel close contract

### Layer 2: Backend Contract Integration

Redis and ZooKeeper modules must run the same shared contract suite against real backend instances.

Recommended tooling:

- Redis via Testcontainers
- ZooKeeper via Curator `TestingServer` or Testcontainers

### Layer 3: Starter Integration

Starter integration tests must use real runtime assembly with real backend modules, not only mocks.

At minimum:

- starter + runtime + redis
- starter + runtime + zookeeper

### Examples and Benchmarks

- examples must compile in the root reactor and demonstrate only valid 2.0 APIs
- benchmarks are non-regression assets and may not shape the public design

## Migration Strategy

### Phase 1: Freeze 1.x

- stop adding new features to 1.x APIs
- only fix blocking bugs if necessary

### Phase 2: Create 2.0 Skeleton

- introduce new `api`, `core`, `runtime`, and `testkit`
- implement the minimal lock semantics first
- an in-memory backend is acceptable as a bootstrap target for contract tests

### Phase 3: Migrate Backends

- implement Redis adapter on the new backend SPI
- implement ZooKeeper adapter on the new backend SPI
- make both pass the same contract suite

### Phase 4: Integrate Spring and Extensions

- build the new starter on top of runtime
- migrate examples
- then add optional extensions

The migration order is intentional: do not let Spring, observability, or extension needs shape the kernel before backend contracts are stable.

## Deletion Plan for 1.x Concepts

The following 1.x concepts are not part of 2.0 and should be deleted during migration:

- `ServiceLoaderDistributedLockFactory`
- `RedisDistributedLockFactory`
- `ZooKeeperDistributedLockFactory`
- `SpringDistributedLockFactory`
- `LockProvider`
- oversized public `DistributedLockFactory`
- oversized public `DistributedLock`
- oversized public `DistributedReadWriteLock`
- oversized public `AsyncLockOperations`
- compatibility constructors added only for examples or benchmarks
- configuration aliases that imply multiple equivalent configuration languages

## Out of Scope for 2.0

The following are explicitly out of scope for the initial 2.0 kernel:

- preserving binary/source compatibility with 1.x
- implementing cross-backend fair locking
- promising cross-backend lease/renew semantics in public API
- building a generalized distributed coordination platform beyond locking

## Success Criteria

2.0 is successful when all of the following are true:

- public API can be explained in a few minutes
- `core` has no Redis/ZooKeeper/Spring dependencies
- there is one runtime assembly model
- Redis and ZooKeeper both pass the same contract tests
- Spring starter is a thin adapter over runtime
- examples compile and run against the real 2.0 API
- optional features no longer force changes into kernel interfaces
- 1.x architecture layers are removed instead of hidden behind compatibility facades
