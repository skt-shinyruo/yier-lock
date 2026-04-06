# Lease/Session/Fencing Lock Redesign

## Summary

This redesign replaces the current thread-bound distributed lock model with a lease-based execution model.
The new architecture treats a backend-backed `LockLease` as the source of truth, groups leases under a renewable `LockSession`, and uses a monotonic `FencingToken` to protect external side effects after ownership loss.

The current 2.0 code is still shaped like a distributed version of `java.util.concurrent.locks.Lock`.
That works for synchronous same-thread usage, but it leaks thread affinity into `core`, forces Spring integration to reject async boundaries, and makes ownership recovery depend on local thread bookkeeping instead of backend-backed lease truth.

The new direction is:

- `api` exposes lease/session-oriented primitives
- `core` coordinates leases and capability rules without binding ownership to JVM threads
- backend adapters implement session, lease, and fencing semantics
- `runtime` assembles a `LockClient`
- convenience execution APIs live above the kernel
- Spring integration builds on scoped execution instead of thread-bound lock handles

## Goals

- Remove JVM thread identity from the core ownership model
- Make backend lease validity the only ownership truth source
- Introduce fencing tokens so stale owners cannot safely write to guarded resources
- Support synchronous, async, and cross-thread execution scopes from the same core model
- Keep backend-specific configuration and semantics out of generic runtime code
- Preserve a small kernel while allowing richer integration layers

## Non-Goals

- No source-level compatibility with `LockManager`, `MutexLock`, or `ReadWriteLock`
- No attempt to emulate `java.util.concurrent.locks.Lock`
- No requirement that every integration layer support every async abstraction in `core`
- No observability, metrics, tracing, or actuator work in this redesign
- No fairness, deadlock detection, or complex HA coordination policies

## Design Decisions

### 1. Lease and Session replace thread ownership

Ownership moves from "current thread holds lock X" to "live session owns lease X".

The core API becomes:

```java
public interface LockClient extends AutoCloseable {
    LockSession openSession(SessionRequest request);
}
```

```java
public interface LockSession extends AutoCloseable {
    LockLease acquire(LockRequest request) throws InterruptedException;
}
```

```java
public interface LockLease extends AutoCloseable {
    LockKey key();
    LockMode mode();
    FencingToken fencingToken();
    LeaseState state();
    boolean isValid();
    void release();
}
```

Consequences:

- ownership survives thread hops as long as the session and lease remain valid
- reentrancy is no longer "same thread reacquires"; it must be defined explicitly in the session/lease model
- backend adapters carry release and validity logic with the lease object
- `close()` and `release()` semantics map directly to backend-backed ownership

### 2. Fencing tokens are required in the kernel

Every successful acquisition returns a monotonic `FencingToken`.
The token is part of the public contract, not an optional backend detail.

Rules:

- a new lease receives a token greater than any token previously issued for the same lock key and mode domain
- downstream systems that can compare tokens must reject stale tokens
- if a backend cannot provide a safe fencing implementation, that backend cannot advertise fenced-write safety

This solves the failure mode where an old owner resumes after a lease/session loss and still performs writes.
Lease validity alone is not enough once an external system has already accepted work from a stale execution path.

### 3. Execution helpers are layered on top of leases

The kernel exposes explicit lease acquisition.
Convenience execution lives in a separate abstraction:

```java
public interface LockExecutor {
    <T> T withLock(LockRequest request, LockedSupplier<T> action) throws Exception;
}
```

Design rules:

- `api/core` define lease/session primitives and may include a synchronous scoped executor
- async scoped execution belongs in a dedicated runtime or integration module, not in the minimal kernel contract
- reactive/coroutine adapters live in extension modules

This keeps the kernel small while still allowing ergonomic integrations.

### 4. Read/write semantics become capabilities, not assumptions

The current code assumes every backend supports mutex and read/write modes unless configured otherwise.
The redesign keeps capabilities explicit and makes them shape the public surface:

```java
public record LockCapabilities(
    boolean mutexSupported,
    boolean readWriteSupported,
    boolean fencingSupported,
    boolean renewableSessionsSupported
) {}
```

Rules:

- unsupported modes fail fast before backend acquisition
- integrations must surface capability mismatches as configuration errors
- a backend may support mutex without read/write or sessions without fencing, but those capabilities must be explicit

### 5. Requests and keys become typed value objects

Stringly-typed public methods are replaced with request objects:

```java
public record LockKey(String value) {}
```

```java
public record LockRequest(
    LockKey key,
    LockMode mode,
    WaitPolicy waitPolicy,
    LeasePolicy leasePolicy
) {}
```

```java
public record SessionRequest(SessionPolicy sessionPolicy) {}
```

This gives the public API one stable place to evolve timeout, renewal, and scoping semantics without growing long method lists.

### 6. Core no longer owns reentrancy by thread

The current `DefaultLockManager` keeps a per-thread state machine and tries to reconcile backend loss with local thread maps.
That entire model is removed.

New core responsibilities:

- validate requests against capabilities
- delegate session/lease creation to the backend port
- maintain only lease/session lifecycle coordination that is independent of thread identity
- expose convenience sync execution by wrapping `acquire -> try/finally release`

If reentrancy is supported, it must be defined as a backend session rule and represented explicitly, for example by reacquiring through the same session.
It must not depend on `Thread.currentThread()`.

### 7. Backend SPI becomes session-oriented

The runtime/backend boundary changes from "give me a backend that can acquire a lock" to "give me a backend client that can open sessions":

```java
public interface LockBackend {
    LockCapabilities capabilities();
    BackendSession openSession(SessionRequest request);
}
```

```java
public interface BackendSession extends AutoCloseable {
    BackendLockLease acquire(LockRequest request) throws InterruptedException;
    SessionState state();
}
```

```java
public interface BackendLockLease extends AutoCloseable {
    LockKey key();
    LockMode mode();
    FencingToken fencingToken();
    boolean isValid();
    void release();
}
```

Runtime modules still discover typed `BackendModule` instances, select one backend, and expose a session-based client for that backend.

### 8. Spring integration becomes scoped-execution integration

Spring annotation support will no longer operate on thread-bound lock handles.
Instead, it will delegate to `LockExecutor` or a Spring-specific async extension.

Rules:

- synchronous methods use scoped execution directly
- async methods are no longer globally rejected by the kernel
- if Spring async support is enabled, the integration must hold the lease for the full async lifecycle and release it on terminal completion
- if a selected integration path cannot safely keep lease ownership across the async boundary, startup must fail for that mode

This moves "which async model is supported" out of the core lock semantics and into the integration layer where it belongs.

## Public API Shape

### Core API

- `LockClient`
- `LockSession`
- `LockLease`
- `LockExecutor`
- `LockKey`
- `LockRequest`
- `SessionRequest`
- `WaitPolicy`
- `LeasePolicy`
- `SessionPolicy`
- `FencingToken`
- `LockCapabilities`
- `LockMode`
- `LeaseState`
- `SessionState`

### Exceptions

- `LockConfigurationException`
- `LockAcquisitionTimeoutException`
- `LockOwnershipLostException`
- `LockSessionLostException`
- `UnsupportedLockCapabilityException`

### Compatibility

`LockManager`, `MutexLock`, and `ReadWriteLock` move out of the mainline API.
If compatibility is needed, it should exist in a dedicated `legacy-lock-api` adapter that clearly advertises its synchronous thread-bound semantics.

## Module Changes

### `distributed-lock-api`

Replace the current public surface with lease/session/fencing primitives and exceptions.

### `distributed-lock-core`

Replace `DefaultLockManager` with focused components:

- `DefaultLockClient`
- `DefaultLockSession`
- `DefaultLockExecutor`
- request/capability validators
- session/lease lifecycle coordination

`core` must not store ownership by `Thread`.

### `distributed-lock-runtime`

Runtime remains responsible for:

- backend discovery
- backend selection
- backend lifecycle ownership
- constructing `LockClient`

It must not interpret backend-specific configuration or async semantics.

### `distributed-lock-redis`

Redis must provide:

- renewable session semantics
- lease validity checks
- monotonic fencing token issuance per lock key
- explicit loss signaling when renewal or release fails

### `distributed-lock-zookeeper`

ZooKeeper must provide:

- session-backed ownership
- lease validity tied to Curator/ZooKeeper session state
- fencing token semantics backed by a monotonic sequence per lock key

### `distributed-lock-spring-boot-starter`

The generic starter exposes:

- `LockClient`
- sync `LockExecutor`
- generic properties

It does not hard-code backend properties or async model assumptions.

### Extension modules

Introduce extension-style modules instead of inflating the kernel:

- keep `distributed-lock-spring-boot-starter` as the generic sync starter
- keep backend-specific Spring modules for Redis and ZooKeeper
- add optional async-scoped integration modules only when they can be tested end-to-end
- optional async/reactive integrations if needed later

## Migration Strategy

### Phase 1: Introduce the new lease/session API alongside current 2.x internals

- add the new public API and backend SPI
- implement new backend lease/session primitives in Redis and ZooKeeper
- add fencing token support
- add runtime assembly for `LockClient`

### Phase 2: Move convenience execution to the new model

- add `LockExecutor`
- migrate Spring synchronous interception to scoped execution
- add async-aware integration only where the full lifecycle can be held safely

### Phase 3: Remove thread-bound mainline APIs

- deprecate old lock-handle APIs
- move compatibility shims to a separate adapter module if still needed
- update examples, docs, and benchmarks to use `LockClient`/`LockExecutor`

## Testing Strategy

The redesign needs focused tests at four layers.

### API and core tests

- request/capability validation
- session lifecycle and close semantics
- lease validity and ownership-loss propagation
- scoped execution release on success and failure
- zero dependency on calling thread identity

### Backend contract tests

- lease acquisition and release
- fencing token monotonicity
- ownership loss after TTL/session expiration
- session invalidation propagation
- read/write capability behavior where supported

### Integration tests

- runtime backend selection
- Spring sync scoped execution
- backend-specific auto-configuration
- async integration only for explicitly supported execution models

### Regression tests

- stale owner with lower fencing token is rejected by guarded resource simulations
- cross-thread completion still releases lease correctly
- lease/session loss surfaces deterministic exceptions instead of local phantom ownership

## Risks

### Risk: token semantics differ across backends

Mitigation:

- make fencing support a capability
- refuse to advertise fenced safety without a real monotonic implementation

### Risk: API break is large

Mitigation:

- keep a compatibility adapter separate from the kernel
- migrate examples and starter integrations first so the new path is well documented

### Risk: async support reintroduces complexity

Mitigation:

- keep async abstractions out of the minimal kernel
- add only one integration path at a time with end-to-end lifecycle tests

## Acceptance Criteria

The redesign is complete when all of the following are true:

- no core path treats `Thread` identity as lock ownership
- all successful acquisitions return a `FencingToken`
- stale executions can be rejected by token-aware guarded resource tests
- backend selection and configuration remain typed and backend-local
- sync convenience execution works without exposing thread-bound lock handles
- Spring integration no longer relies on same-thread unlock semantics
- Redis and ZooKeeper pass the shared lease/session/fencing contract suite
- examples and benchmarks use the new API surface
