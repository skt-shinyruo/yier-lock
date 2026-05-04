# Distributed Lock v3 Architecture Design

## Status

Draft for review.

## Context

This project is a Java 17 multi-module distributed lock framework with public API, core client behavior, runtime assembly, Redis and ZooKeeper backends, Spring Boot integration, observability, examples, benchmarks, and a backend testkit.

A code review found that the current 2.x architecture has structural issues that are difficult to solve with narrow fixes:

- `distributed-lock-spi` depends on `distributed-lock-core`, so backend plugin authors must depend on core internals instead of a standalone SPI.
- `LockRuntime` exposes `spi.BackendCapabilities`, leaking extension metadata into the user-facing runtime API.
- `LockRuntimeBuilder` has `ServiceLoader` discovery, but official Redis and ZooKeeper modules intentionally do not register providers because they require typed configuration.
- `BackendCapabilities` is a small boolean record that cannot describe fairness, ownership, lease, timeout, renewal, or lifecycle semantics.
- `SynchronousLockExecutor` opens a new `LockSession` for each scoped call, but Redis and ZooKeeper have very different session cost models.
- Spring's generic starter has hard-coded knowledge of Redis and ZooKeeper backend module bean names and configuration classes.
- Observability reconstructs `DefaultSynchronousLockExecutor` instead of transparently decorating the selected runtime.
- `LockContext` exposes a ThreadLocal synchronization model from the public API even though async/reactive usage is intentionally unsupported.
- The regression matrix module is only a smoke test and does not enforce cross-backend behavior.

This spec assumes a breaking v3 release. It does not preserve source compatibility with 2.x where compatibility would keep the current architectural problems.

## Goals

- Create clean module boundaries where public API, backend SPI, runtime assembly, backend implementations, Spring integration, and extensions have distinct responsibilities.
- Make backend extension possible without depending on core implementation classes.
- Remove SPI types from user-facing runtime APIs.
- Replace boolean capabilities with a behavior contract that describes backend semantics explicitly enough for runtime validation, Spring diagnostics, tests, and documentation.
- Split backend connection lifecycle from lock session lifecycle so Redis and ZooKeeper can expose realistic costs and ownership semantics.
- Replace implicit backend discovery with explicit assembly.
- Make Spring generic auto-configuration backend-agnostic.
- Make observability a transparent runtime/client/session/executor decorator.
- Move ThreadLocal lock context out of the core public API and into an optional synchronous-context extension.
- Turn the test suite into a real conformance matrix for every backend.

## Non-Goals

- Do not add an async or reactive lock API in v3.
- Do not replace Lettuce or Curator.
- Do not implement strict FIFO fairness for Redis.
- Do not make ZooKeeper support fixed-duration leases; ZooKeeper remains session-bound.
- Do not preserve binary or source compatibility for 2.x APIs.
- Do not build multi-backend failover or quorum locking in this release.

## Recommended Design

Use a clean plugin-kernel architecture:

- `distributed-lock-api`: user-facing request, lease, session, client, executor, exceptions, and runtime metadata types.
- `distributed-lock-spi`: backend provider, backend client, backend session, backend lease, backend behavior contract, backend configuration descriptor, and testkit integration hooks. This module depends only on `distributed-lock-api`.
- `distributed-lock-core`: backend-neutral client/session/executor implementation. It consumes SPI interfaces and does not define backend contracts.
- `distributed-lock-runtime`: explicit runtime assembly. It selects a supplied backend provider by id, validates requested features against the backend behavior contract, creates the backend client once, and exposes only public API types.
- Backend modules: Redis and ZooKeeper implement SPI and own their typed configuration, lifecycle, and behavior metadata.
- Spring modules: the generic starter consumes `BackendProvider` beans by type. Backend-specific autoconfigure modules create those beans. No generic starter code knows backend-specific bean names or configuration class names.
- Extension modules: observability and synchronous context decorate public API interfaces without depending on `DefaultSynchronousLockExecutor` internals.
- Test modules: testkit owns backend conformance contracts; test-suite instantiates all configured backends and runs the matrix.

Alternatives considered:

- Keep the current module graph and move only `LockBackend`/`BackendSession` into SPI. This reduces breakage, but leaves runtime metadata leakage, ServiceLoader ambiguity, Spring hard-coding, and observability coupling.
- Split Redis and ZooKeeper into separate products behind a thin facade. This makes backend behavior clear, but duplicates client/session/executor code and makes future backend support heavier.
- Keep ServiceLoader and add provider configuration callbacks. This still hides configuration selection and keeps failures late; explicit assembly is clearer for a library with required typed backend configuration.

## v3 Module Boundaries

### Public API

`distributed-lock-api` contains only types that application code should import:

- `LockRuntime`
- `LockClient`
- `LockSession`
- `LockLease`
- `SynchronousLockExecutor`
- `LockedAction`
- `LockRequest`
- `LockKey`
- `LockMode`
- `WaitPolicy`
- `LeasePolicy`
- `FencingToken`
- public exception and failure-context types
- public metadata snapshots such as `RuntimeInfo` and `BackendBehavior`

In v3, `LockRuntime` moves into `distributed-lock-api`. `distributed-lock-runtime` owns `LockRuntimeBuilder` and the default runtime implementation, but the runtime interface itself is public API.

`LockRuntime` must not import `com.mycorp.distributedlock.spi.*`. Replace:

```java
BackendCapabilities capabilities();
```

with:

```java
RuntimeInfo info();
```

`RuntimeInfo` is an API record containing:

- selected backend id
- selected backend display name
- backend behavior snapshot
- runtime version string

`BackendBehavior` lives in API because users, Spring diagnostics, docs, and tests need to inspect it without importing SPI. It must be immutable and descriptive, not a backend implementation hook.

### SPI

`distributed-lock-spi` depends on `distributed-lock-api` only.

It defines:

```java
public interface BackendProvider<C extends BackendConfiguration> {
    BackendDescriptor<C> descriptor();

    BackendClient createClient(C configuration);
}
```

```java
public interface BackendClient extends AutoCloseable {
    BackendSession openSession();

    @Override
    void close();
}
```

```java
public interface BackendSession extends AutoCloseable {
    BackendLease acquire(LockRequest request) throws InterruptedException;

    SessionState state();

    @Override
    void close();
}
```

```java
public interface BackendLease extends LockLease {
}
```

`BackendDescriptor<C>` includes:

- backend id
- display name
- typed configuration class
- `BackendBehavior`
- optional configuration hints for documentation and diagnostics

`BackendConfiguration` is a marker interface for typed backend configuration records/classes. Redis and ZooKeeper configuration types implement it.

SPI must not expose `DefaultLockClient`, `DefaultLockSession`, `DefaultSynchronousLockExecutor`, `SupportedLockModes`, or any class from `distributed-lock-core`.

### Allowed Dependencies

- `distributed-lock-api` depends on nothing in this reactor.
- `distributed-lock-spi` depends on `distributed-lock-api`.
- `distributed-lock-core` depends on `distributed-lock-api` and `distributed-lock-spi`.
- `distributed-lock-runtime` depends on `distributed-lock-api`, `distributed-lock-core`, and `distributed-lock-spi`.
- backend modules depend on `distributed-lock-api` and `distributed-lock-spi`; they must not depend on `distributed-lock-core`.
- Spring backend autoconfigure modules depend on backend modules, `distributed-lock-api`, `distributed-lock-spi`, and Spring Boot auto-config infrastructure. They do not depend on the generic starter.
- extensions depend on `distributed-lock-api`, `distributed-lock-runtime`, and `distributed-lock-spi` only when they need metadata or decoration hooks.

### Core

`distributed-lock-core` contains:

- `DefaultLockClient`
- `DefaultLockSession`
- `DefaultSynchronousLockExecutor`
- request validation against `BackendBehavior`
- session-bound lease tracking
- reentry checks
- close/release failure aggregation

`DefaultLockClient` accepts a `BackendClient` and a `BackendBehavior`, not `LockBackend` and `SupportedLockModes`.

`SupportedLockModes` becomes an internal adapter or disappears. Validation uses the richer behavior model directly.

### Runtime

`distributed-lock-runtime` assembles one backend explicitly:

```java
LockRuntime runtime = LockRuntimeBuilder.create()
    .backend("redis")
    .backendProvider(new RedisBackendProvider())
    .backendConfiguration(new RedisBackendConfiguration(...))
    .build();
```

For multiple providers:

```java
LockRuntime runtime = LockRuntimeBuilder.create()
    .backend("zookeeper")
    .backendProviders(List.of(redisProvider, zooKeeperProvider))
    .backendConfiguration(ZooKeeperBackendConfiguration.class, zooKeeperConfig)
    .build();
```

Rules:

- A backend id is always required.
- Runtime builder never auto-selects from classpath contents.
- Runtime builder does not call `ServiceLoader`.
- Backend ids must be unique within the supplied provider set.
- The selected provider's configuration class must have a matching configuration instance.
- Runtime validates the selected `BackendBehavior` before creating the backend client.
- Runtime exposes only `LockRuntime`, `LockClient`, `SynchronousLockExecutor`, and `RuntimeInfo`.

If a user wants classpath discovery, that must be a separate optional module, not the default runtime path. The optional module can return providers, but explicit backend id and typed configuration remain required.

## Backend Behavior Contract

Replace `BackendCapabilities` with `BackendBehavior`.

`BackendBehavior` describes:

- `Set<LockMode> lockModes`
- `FencingSemantics fencing`
- `LeaseSemantics lease`
- `SessionSemantics session`
- `WaitSemantics wait`
- `FairnessSemantics fairness`
- `OwnershipLossSemantics ownershipLoss`
- `BackendCostModel costModel`

Recommended enum shapes:

```java
enum FencingSemantics {
    MONOTONIC_PER_KEY
}
```

```java
enum LeaseSemantics {
    RENEWABLE_WATCHDOG,
    FIXED_TTL,
    SESSION_BOUND
}
```

```java
enum SessionSemantics {
    CLIENT_LOCAL_TTL,
    BACKEND_EPHEMERAL_SESSION
}
```

```java
enum WaitSemantics {
    POLLING,
    WATCHED_QUEUE
}
```

```java
enum FairnessSemantics {
    NONE,
    EXCLUSIVE_PREFERRED,
    FIFO_QUEUE
}
```

```java
enum BackendCostModel {
    CHEAP_SESSION,
    NETWORK_CLIENT_PER_SESSION,
    POOLED_NETWORK_CLIENT
}
```

The exact enum names can change during implementation, but the behavior contract must satisfy these requirements:

- Runtime can reject unsupported lock modes before backend acquisition.
- Runtime can reject unsupported lease policies before backend acquisition.
- Spring can fail fast when annotation configuration requests behavior the selected backend cannot provide.
- Testkit can select required conformance tests from declared behavior.
- Documentation can describe backend behavior without hard-coded backend-specific text in the generic starter.

Redis v3 behavior:

- lock modes: `MUTEX`, `READ`, `WRITE`
- fencing: monotonic per key
- lease: renewable watchdog plus fixed TTL
- session: client-local TTL
- wait: polling
- fairness: exclusive-preferred, not FIFO
- cost model: cheap session over shared backend client

ZooKeeper v3 behavior:

- lock modes: `MUTEX`, `READ`, `WRITE`
- fencing: monotonic per key
- lease: session-bound
- session: backend ephemeral session
- wait: watched queue
- fairness: FIFO queue for exclusive contenders; readers can share before earlier exclusive contenders only according to the queue rules documented by the backend
- cost model: network client per session unless implementation changes to a pool

## Lifecycle Model

v3 separates three lifecycles:

- Runtime lifecycle: owns one selected backend client and shared runtime decorators.
- Backend client lifecycle: owns backend resources such as Redis connections, renewal executors, Curator client pools, or backend-specific connection factories.
- Lock session lifecycle: owns acquired leases and the backend's ownership proof for that session.

`SynchronousLockExecutor` continues to open a session per scoped call. The behavior contract must reveal whether that session is cheap or expensive. Redis declares `CHEAP_SESSION`; ZooKeeper declares `NETWORK_CLIENT_PER_SESSION`.

The generic executor does not hide this. Documentation and benchmarks must show the cost difference.

## Context Model

Remove `LockContext` from `distributed-lock-api`.

The primary way to access lock data inside a critical section is the `LockedAction` parameter:

```java
lockExecutor.withLock(request, lease -> {
    FencingToken token = lease.fencingToken();
    return doWork(token);
});
```

For Spring annotation users, add an optional synchronous-context extension:

- module: `distributed-lock-extension-context`
- API: `LockScopeContext`
- implementation: ThreadLocal binding around `SynchronousLockExecutor`
- Spring integration: optional auto-configuration that binds context for `@DistributedLock`

The extension must document that it works only for synchronous execution on the invoking thread.

This makes the synchronous ThreadLocal behavior opt-in instead of a core API promise.

## Spring Boot v3 Design

The generic starter must be backend-agnostic.

Generic starter responsibilities:

- bind generic properties under `distributed.lock.*`
- create `LockRuntime` from available `BackendProvider<?>` beans
- expose `LockClient` and `SynchronousLockExecutor`
- register `@DistributedLock` interceptor
- validate annotation settings against `runtime.info().behavior()`

Backend-specific autoconfigure responsibilities:

- bind typed backend properties
- create exactly one `BackendProvider` bean for that backend
- create the matching backend configuration bean

The generic starter must not:

- hard-code Redis or ZooKeeper bean names
- inspect backend-specific auto-configuration class names
- contain backend-specific property names
- decide how to override backend-specific default provider beans

Spring assembly rules:

- `distributed.lock.backend` is required when distributed lock is enabled.
- All `BackendProvider<?>` beans are collected by type.
- All backend configuration beans are collected by type or by provider descriptor.
- The selected provider id determines the required configuration class.
- If no matching provider exists, startup fails with a clear backend id error.
- If the provider exists but configuration is absent or invalid, startup fails with backend-specific configuration details.
- If multiple provider beans have the same id, startup fails.

## Observability v3 Design

Observability must decorate public API interfaces transparently.

Replace runtime reconstruction with decorator factories:

- `LockRuntimeDecorator`
- `LockClientDecorator`
- `LockSessionDecorator`
- `LockLeaseDecorator`
- `SynchronousLockExecutorDecorator`

The default runtime builder accepts ordered decorators:

```java
LockRuntime runtime = LockRuntimeBuilder.create()
    .backend("redis")
    .backendProvider(provider)
    .backendConfiguration(config)
    .decorators(List.of(observabilityDecorator))
    .build();
```

Spring observability registers a decorator bean. The generic runtime auto-configuration applies decorators in order.

Observability must not instantiate `DefaultSynchronousLockExecutor`. It decorates the executor produced by runtime assembly.

## Backend Migration

### Redis

Rename `RedisBackendModule` to `RedisBackendProvider`.

`RedisBackendProvider` returns a descriptor with:

- id: `redis`
- configuration class: `RedisBackendConfiguration`
- behavior: Redis v3 behavior contract

`RedisLockBackend` becomes either:

- `RedisBackendClient`, if it directly implements `BackendClient`; or
- an internal implementation hidden behind `RedisBackendProvider`.

Redis session and lease types implement SPI interfaces.

The Redis provider keeps typed configuration; no ServiceLoader provider is required in the base Redis module.

### ZooKeeper

Rename `ZooKeeperBackendModule` to `ZooKeeperBackendProvider`.

`ZooKeeperBackendProvider` returns a descriptor with:

- id: `zookeeper`
- configuration class: `ZooKeeperBackendConfiguration`
- behavior: ZooKeeper v3 behavior contract

The ZooKeeper implementation must make its client-per-session cost explicit. If the implementation changes to a shared Curator client or pool, update the behavior contract and tests accordingly.

## Testing Strategy

### Unit Tests

- API tests assert that `LockRuntime` no longer references SPI types.
- SPI module tests assert it has no dependency on core.
- Runtime builder tests cover explicit provider/configuration selection, duplicate backend ids, missing backend id, missing configuration, invalid behavior, and decorator ordering.
- Core tests validate requests against `BackendBehavior`.
- Spring tests assert the generic starter contains no Redis/ZooKeeper hard-coded backend names or configuration class names.
- Observability tests assert decorators wrap existing runtime components without reconstructing default core classes.

### Conformance Tests

Move testkit contracts to behavior-driven conformance:

- mutex contract
- read/write contract
- fencing contract
- wait policy contract
- fixed TTL lease contract
- renewable watchdog contract
- session-bound lease contract
- ownership loss contract
- reentry contract
- lifecycle close/release contract
- fairness/ordering contract where the backend declares enough semantics

Each backend contributes a test fixture:

```java
BackendConformanceFixture fixture();
```

The fixture supplies:

- provider
- typed configuration
- startup/teardown hooks
- backend behavior
- optional external dependency tags such as `redis-integration`

`distributed-lock-test-suite` becomes the canonical matrix runner and must run every non-external conformance contract for every fixture available in the reactor. Redis Testcontainers tests remain behind the explicit integration profile.

### Verification Commands

After implementation:

```bash
mvn test
mvn -pl distributed-lock-api,distributed-lock-spi,distributed-lock-core,distributed-lock-runtime -am test
mvn -pl distributed-lock-spring-boot-starter,distributed-lock-redis-spring-boot-autoconfigure,distributed-lock-zookeeper-spring-boot-autoconfigure -am test
mvn -pl distributed-lock-zookeeper -am test
mvn -Predis-integration -pl distributed-lock-redis,distributed-lock-redis-spring-boot-autoconfigure -am test
mvn -pl distributed-lock-test-suite -am test
mvn -Pbenchmarks -DskipTests compile
```

## Documentation and Examples

Update:

- backend README files to describe provider/configuration usage
- Spring starter README to remove backend-specific defaults and describe provider-based assembly
- examples to use `RedisBackendProvider` and `ZooKeeperBackendProvider`
- observability README to describe decorator-based integration
- test-suite README to describe the conformance matrix
- migration guide from 2.x to 3.x

The migration guide must include:

- `BackendModule` to `BackendProvider`
- `BackendCapabilities` to `BackendBehavior`
- `LockRuntime.capabilities()` to `LockRuntime.info().behavior()`
- removal of `LockContext` from core API
- Spring backend provider/configuration bean changes
- removal of default `ServiceLoader` backend discovery

## Migration and Compatibility

This is a breaking v3 release.

Required migration actions for users:

- Replace programmatic `RedisBackendModule`/`ZooKeeperBackendModule` construction with provider plus typed configuration.
- Replace `runtime.capabilities()` calls with `runtime.info().behavior()`.
- Replace `LockContext.requireCurrentFencingToken()` with the `lease` callback parameter, or add the optional context extension.
- Ensure Spring apps include one backend-specific autoconfigure module and set `distributed.lock.backend`.
- Remove assumptions that backend discovery happens from classpath alone.

Required migration actions for backend authors:

- Depend on `distributed-lock-api` and `distributed-lock-spi`, not `distributed-lock-core`.
- Implement `BackendProvider<C>`, `BackendClient`, `BackendSession`, and `BackendLease`.
- Provide an accurate `BackendBehavior`.
- Add a conformance fixture.

## Implementation Slices

1. Introduce v3 API metadata and SPI contracts.
2. Refactor core to consume SPI backend client/session/lease interfaces.
3. Rewrite runtime builder around explicit provider and typed configuration assembly.
4. Migrate Redis to `RedisBackendProvider`.
5. Migrate ZooKeeper to `ZooKeeperBackendProvider`.
6. Refactor Spring generic starter and backend-specific autoconfigure modules.
7. Refactor observability into transparent decorators.
8. Move ThreadLocal context into an optional extension.
9. Rebuild testkit/test-suite as behavior-driven conformance.
10. Update examples, benchmarks, README files, and migration guide.

Each slice should compile and test before moving to the next slice. Because this is a breaking v3 line, implementation should happen on a dedicated branch and should not be mixed with 2.x hardening commits.

## Acceptance Criteria

- `distributed-lock-spi` has no dependency on `distributed-lock-core`.
- `distributed-lock-api` has no dependency on `distributed-lock-spi`.
- `LockRuntime` exposes `RuntimeInfo`, not `BackendCapabilities` or any SPI type.
- Runtime builder does not use `ServiceLoader` in the default assembly path.
- Redis and ZooKeeper expose providers with typed configuration descriptors.
- Spring generic starter contains no hard-coded Redis/ZooKeeper backend bean names or configuration class names.
- Observability does not instantiate `DefaultSynchronousLockExecutor`.
- `LockContext` is absent from `distributed-lock-api`; equivalent ThreadLocal behavior exists only in an optional context extension.
- Test-suite runs real conformance contracts rather than only type-resolution smoke tests.
- Documentation includes a 2.x to 3.x migration guide.

## Residual Risks

- Breaking API migration is large and will require coordinated updates across examples, benchmarks, tests, and docs.
- A richer behavior contract can become too detailed if it tries to model every backend nuance. Keep it focused on validation, diagnostics, tests, and user-visible semantics.
- ZooKeeper's current client-per-session model may look inefficient once exposed. The spec intentionally makes the cost visible rather than hiding it.
- Removing default ServiceLoader discovery may surprise users who expected classpath-based assembly. The explicit builder and Spring provider model should make failures earlier and clearer.
- Moving ThreadLocal context out of the core API can require user code changes where annotations relied on `LockContext.requireCurrentFencingToken()`.
