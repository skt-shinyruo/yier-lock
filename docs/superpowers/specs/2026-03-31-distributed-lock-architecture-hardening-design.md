# Distributed Lock Architecture Hardening Design

## Summary

This design hardens the current 2.0 distributed lock implementation without replacing the public usage model.
`LockManager`, `MutexLock`, `ReadWriteLock`, programmatic runtime assembly, and Spring annotation entrypoints remain, but the internal contract between `core`, `runtime`, backend adapters, and Spring integration is rebuilt.

The current implementation has four structural faults:

- `core` blocks inside synchronized sections while calling remote backends
- local thread state is treated as the authority for lock ownership after acquisition
- `runtime` and `starter` pass backend configuration through `Object` and stringly-typed maps
- backend capabilities exist in SPI shape but are not enforced anywhere

This redesign makes backend leases first-class, removes string-based configuration coupling from generic runtime/starter code, and expands the test matrix around ownership loss, release progress, and capability enforcement.

## Non-Goals

- No 3.0 API redesign
- No async lock API
- No health, metrics, tracing, or actuator work
- No compatibility layer for the old `LockRuntimeBuilder.configuration(Object)` path

## Goals

- Preserve the public 2.0 usage style for synchronous callers
- Ensure a waiting contender cannot block the owning thread from releasing
- Treat backend lease validity as the truth source for continued ownership
- Eliminate `Object` and `Map<String, Object>` configuration flow from generic runtime/starter layers
- Make backend capability checks effective instead of decorative
- Keep Redis and ZooKeeper adapters independent and strongly typed
- Make Spring annotation behavior explicit and fail fast for unsupported async usage

## Design Decisions

### 1. Backend leases replace passive handles

`core` currently receives passive `BackendLockHandle` values and must route release and ownership checks back through `LockBackend`.
That split forces `DefaultLockManager` to keep too much lifecycle state and makes stale local ownership easy to preserve after the backend has already invalidated the lock.

The new backend contract is:

```java
public interface BackendLockLease extends AutoCloseable {
    String key();
    LockMode mode();
    boolean isValidForCurrentExecution();
    void release();

    @Override
    default void close() {
        release();
    }
}
```

```java
public interface LockBackend {
    BackendLockLease acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) throws InterruptedException;
}
```

Consequences:

- backend-specific ownership checks stay with the backend-specific lease
- release logic moves with the lease instead of being re-dispatched through a central backend
- `core` only tracks local reentrancy and mode rules
- ownership loss can be surfaced precisely as a dedicated exception

### 2. Local state becomes a coordinator, not the truth source

`DefaultLockManager` will be rewritten around per-key coordinators that only protect local bookkeeping.
They will not hold an intrinsic monitor while waiting on backend acquisition or release.

Each key gets a `LockCoordinator` that owns:

- a `ReentrantLock stateLock`
- condition signaling for local state changes
- local hold records for current execution
- mode conflict checks
- install/remove lifecycle for backend leases

Each held lock path is represented by a `HeldLease`:

```java
final class HeldLease {
    private final BackendLockLease lease;
    private int holdCount;
    private HoldStatus status;
}
```

`HoldStatus` values:

- `ACTIVE`
- `RELEASING`
- `LOST`

Acquisition flow:

1. Lock local `stateLock`
2. Check for local reentry and local mode conflicts
3. If reentering, verify `lease.isValidForCurrentExecution()` before incrementing hold count
4. If a fresh backend acquire is required, release `stateLock`
5. Call `backend.acquire(...)`
6. Re-lock `stateLock`
7. Re-check local state and install the returned lease
8. If state changed incompatibly while backend acquire was in flight, immediately release the newly acquired lease outside the local lock and retry or fail

Release flow:

1. Lock local `stateLock`
2. Verify a local hold exists
3. Decrement hold count
4. If hold count remains positive, return immediately
5. Move the hold into `RELEASING` and detach it from the active local map
6. Unlock local `stateLock`
7. Call `lease.release()`
8. Re-lock local `stateLock`
9. Finalize cleanup and signal waiters

This guarantees the owner can progress to release even while another thread is blocked in backend acquisition.

### 3. Backend lease validity is checked on every local reentry boundary

The current implementation increments local reentry counts without revalidating the backend lease.
That is incorrect for Redis lease expiry and ZooKeeper session loss.

New rules:

- reentry requires a local hold and a valid backend lease
- if a local hold exists but `lease.isValidForCurrentExecution()` is false, local state is marked `LOST`, removed from active ownership, and `LockOwnershipLostException` is thrown
- `unlock()` against a lost lease throws `LockOwnershipLostException`
- `close()` follows the same rule as `unlock()` and is no longer allowed to silently no-op after ownership loss

This is a deliberate behavior tightening. Silent success after ownership loss hides corruption.

### 4. Generic runtime no longer transports backend configuration

The current runtime SPI uses:

- `LockRuntimeBuilder.configuration(Object)`
- `BackendContext(String backendId, Object configuration)`
- adapter-specific parsing of `Map<?, ?>`

This is removed.

New runtime SPI:

```java
public interface BackendModule {
    String id();
    BackendCapabilities capabilities();
    LockBackend createBackend();
}
```

`LockRuntimeBuilder` keeps only:

- `backend(String backendId)`
- `backendModules(List<BackendModule> backendModules)`
- `build()`

Configuration becomes the responsibility of the code that instantiates a `BackendModule`, not generic runtime.

Programmatic usage becomes:

```java
LockRuntime runtime = LockRuntimeBuilder.create()
    .backend("redis")
    .backendModules(List.of(new RedisBackendModule(new RedisBackendConfiguration(uri, leaseSeconds))))
    .build();
```

This restores a clean boundary:

- runtime selects modules
- modules own typed backend construction
- generic runtime never interprets backend-specific fields

### 5. Spring generic starter stops owning backend-specific properties

The generic Spring Boot starter will no longer bind Redis or ZooKeeper settings.
It will only bind generic lock and annotation settings:

- `distributed.lock.enabled`
- `distributed.lock.backend`
- `distributed.lock.spring.annotation.enabled`
- `distributed.lock.spring.annotation.default-timeout`

Backend-specific Spring binding moves into two new modules:

- `distributed-lock-redis-spring-boot-autoconfigure`
- `distributed-lock-zookeeper-spring-boot-autoconfigure`

Each backend Spring module will:

- bind typed backend properties
- construct typed backend configuration
- expose a `BackendModule` bean

The generic starter will only consume `BackendModule` beans and the generic properties class.

This removes the current hidden coupling where generic starter code knows keys like `lease-seconds` and `connect-string`.

### 6. Backend capabilities become enforced behavior

`BackendCapabilities` remains part of SPI.
It is now consumed by `DefaultLockManager`.

Rules:

- `mutex(String key)` requires `mutexSupported`
- `readWrite(String key)` requires `readWriteSupported`
- if unsupported, fail fast with `LockConfigurationException`
- Spring annotation mode resolution uses the same manager path, so capability checks are automatically enforced there too

No backend is allowed to “advertise everything and fail later” unless that is truly its supported contract.

### 7. Spring annotation locking is explicitly synchronous-only

`DistributedLockAspect` will keep method interception but will stop pretending it can safely wrap async boundaries.

Rules:

- if the intercepted method returns `CompletionStage`, reactive publisher types, or another explicitly configured async type, fail fast with `LockConfigurationException`
- timeout acquisition failure throws `LockAcquisitionTimeoutException`
- successful acquisition wraps only the synchronous method body

This makes the contract explicit and avoids the current silent mismatch between thread-bound ownership and async execution.

## Module-by-Module Changes

### `distributed-lock-api`

Keep:

- `LockManager`
- `ReadWriteLock`
- `MutexLock`
- existing exception types

Add:

- `LockOwnershipLostException`
- `LockAcquisitionTimeoutException`

Behavioral change:

- `MutexLock.close()` no longer silently ignores lost ownership
- `unlock()` and `close()` surface ownership loss consistently

### `distributed-lock-core`

Delete or replace:

- `BackendLockHandle`

Add:

- `BackendLockLease`
- reworked `LockBackend`
- internal coordinator classes used by `DefaultLockManager`

Rewrite:

- `DefaultLockManager`
- `DefaultMutexLock`
- `DefaultReadWriteLock`

Preserve:

- `LockMode`
- `LockResource`
- `WaitPolicy`

### `distributed-lock-runtime`

Delete:

- `BackendContext`
- `LockRuntimeBuilder.configuration(Object)`

Modify:

- `BackendModule`
- `LockRuntimeBuilder`

Preserve:

- `LockRuntime`
- `DefaultLockRuntime`
- `ServiceLoaderBackendRegistry`
- `BackendCapabilities`

### `distributed-lock-redis`

Preserve:

- typed `RedisBackendConfiguration`

Modify:

- `RedisBackendModule` to remove all `Object` and `Map` parsing
- `RedisLockBackend` to return `RedisLease`

Add:

- watchdog-based lease renewal
- refresh scripts for mutex/read/write modes

### `distributed-lock-zookeeper`

Preserve:

- typed `ZooKeeperBackendConfiguration`

Modify:

- `ZooKeeperBackendModule` to remove all `Object` and `Map` parsing
- `ZooKeeperLockBackend` to return `ZooKeeperLease`

Add:

- session validity checks surfaced through lease validity
- a small test seam for simulating or observing connection/session loss

### `distributed-lock-spring-boot-starter`

Modify:

- `DistributedLockProperties` to remove backend-specific nested properties
- `DistributedLockAutoConfiguration` to consume only generic settings and available `BackendModule` beans
- `DistributedLockAspect` to throw explicit timeout/configuration exceptions and reject async-returning methods

Keep:

- `DistributedLock`
- `DistributedLockMode`
- `LockKeyResolver`
- `SpelLockKeyResolver`

### New modules

Add:

- `distributed-lock-redis-spring-boot-autoconfigure`
- `distributed-lock-zookeeper-spring-boot-autoconfigure`

Each module owns:

- its Spring `@ConfigurationProperties`
- adapter-specific `BackendModule` bean creation
- minimal auto-configuration

## Testing Strategy

The current test suite mostly proves happy-path acquisition.
That is insufficient for this redesign.

### API tests

Extend `ApiSurfaceTest` to verify:

- new exception types exist
- `MutexLock` still extends `AutoCloseable`
- public method surface remains stable

### Core tests

Keep the existing reentry test and add:

- `DefaultLockManagerBlockingTest`
  - prove a contender blocked in backend acquisition does not prevent the owner from unlocking
- `DefaultLockManagerOwnershipLossTest`
  - prove stale local holds are detected and cleared
- `DefaultLockManagerCapabilitiesTest`
  - prove unsupported modes fail fast
- `DefaultLockManagerReleaseFailureTest`
  - prove failed release transitions local state out of active ownership and surfaces the backend failure correctly

### Testkit expansion

Expand `LockManagerContract` and support fakes so contract coverage includes:

- mutual exclusion
- reentry
- owner-only unlock
- ownership loss detection
- release progress while another thread is contending
- runtime close behavior
- read/write capability enforcement

Add controllable fake backends for:

- blocking acquire
- synthetic ownership loss
- synthetic release failure

### Redis tests

Extend adapter tests with:

- `RedisLeaseRenewalTest`
  - hold a lock longer than the base lease and verify it remains valid
- `RedisOwnershipLossTest`
  - force token loss and verify `LockOwnershipLostException`
- contract-suite coverage against the expanded testkit

### ZooKeeper tests

Extend adapter tests with:

- `ZooKeeperSessionLossTest`
  - simulate lost session and verify ownership loss handling
- contract-suite coverage against the expanded testkit

### Spring tests

Modify and add tests for:

- generic starter bean registration without backend-specific property parsing
- timeout acquisition surfacing `LockAcquisitionTimeoutException`
- async-returning annotated methods failing fast with `LockConfigurationException`
- backend-specific auto-config modules registering `BackendModule` beans correctly

## Migration Plan

Implementation should proceed in this order:

1. Introduce new API exceptions and backend lease contract
2. Rewrite `core` state machine and core tests
3. Remove runtime configuration transport and update runtime tests
4. Migrate Redis adapter to typed module construction and lease renewal
5. Migrate ZooKeeper adapter to typed module construction and lease validity
6. Split backend-specific Spring auto-configuration out of the generic starter
7. Update examples, benchmarks, and READMEs

This order minimizes half-migrated states where runtime and adapters expect different contracts.

## Risks and Mitigations

### Risk: stricter ownership-loss behavior breaks existing callers

Mitigation:

- keep the public API shape stable
- document the behavioral change clearly
- convert hidden corruption into explicit failure

### Risk: Redis renewal introduces background lifecycle complexity

Mitigation:

- keep the renewal scope inside the lease object
- ensure `release()` and backend `close()` cancel any scheduled work
- add focused renewal tests rather than relying on integration timing alone

### Risk: Spring module split increases reactor complexity

Mitigation:

- keep the new modules thin
- do not duplicate generic starter code
- limit them to properties binding plus `BackendModule` bean exposure

## Acceptance Criteria

The redesign is complete when all of the following are true:

- no generic module passes backend configuration through `Object` or `Map`
- no `DefaultLockManager` path performs backend acquisition or release while holding local synchronization that blocks release progress
- ownership loss is detected and surfaced explicitly
- backend capability checks fail fast
- Spring generic starter no longer binds Redis or ZooKeeper properties
- backend-specific Spring auto-config modules supply typed `BackendModule` beans
- examples and benchmarks use typed backend module construction
- the expanded contract and integration test suites pass for in-memory, Redis, ZooKeeper, and Spring entrypoints
