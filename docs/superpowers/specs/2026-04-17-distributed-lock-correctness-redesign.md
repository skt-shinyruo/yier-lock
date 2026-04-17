# Distributed Lock Correctness Redesign

## Summary

This redesign fixes the current 2.0 implementation by shrinking the public API to semantics that are actually implemented, hardening Redis lease/session loss handling, and replacing the ZooKeeper read/write lock algorithm with a correct sequential-node protocol.

The current code has three correctness failures and one API failure:

- ZooKeeper read/write acquisition uses a check-then-create race that can grant a reader and writer on the same key at the same time
- Redis renewal can stop silently after a scheduled task exception, leaving leases to expire without transitioning session state to `LOST`
- fencing tokens are monotonic per `key + mode` instead of per business key, which breaks stale-write protection across lock modes
- public policy/capability types advertise behavior that is not implemented

This redesign prefers smaller truthful contracts over preserving a larger but misleading surface.

## Goals

- Preserve the lease/session execution model introduced in 2.0
- Remove public policies and capability signals that have no real implementation
- Make Redis failure paths transition deterministically into `LOST`
- Make ZooKeeper read/write locking correct under concurrency
- Make fencing tokens monotonic per lock key across all modes
- Expand tests so correctness does not depend on mutex-only happy paths

## Non-Goals

- No async or reactive lease lifecycle support in this change
- No new fairness guarantees beyond what the chosen backend protocols already imply
- No metrics, tracing, health endpoints, or operator tooling
- No compatibility shim for removed API fields or enums

## Design Decisions

### 1. Public API shrinks to implemented semantics

The public API will keep:

- `LockClient`
- `LockSession`
- `LockLease`
- `LockExecutor`
- `LockKey`
- `LockRequest`
- `LockMode`
- `WaitPolicy`
- `FencingToken`
- `LeaseState`
- `SessionState`
- existing exception types that still describe real behavior

The public API will remove:

- `LeasePolicy`
- `SessionPolicy`
- `SessionRequest`
- `LockCapabilities`

New public shapes:

```java
public interface LockClient extends AutoCloseable {
    LockSession openSession();
}
```

```java
public record LockRequest(
    LockKey key,
    LockMode mode,
    WaitPolicy waitPolicy
) {}
```

Rationale:

- there is only one supported lease lifecycle: release on close
- there is only one supported session lifecycle: explicit close
- capability negotiation is an internal backend/runtime concern, not a useful public feature in the current product
- removing fake options is safer than keeping knobs that do nothing

### 2. Core lifecycle remains lease/session based, but only for synchronous execution

`LockExecutor` remains a synchronous convenience API:

```java
public interface LockExecutor {
    <T> T withLock(LockRequest request, LockedSupplier<T> action) throws Exception;
}
```

Rules:

- `LockExecutor` opens a session, acquires a lease, binds current fencing context for the current thread, runs the action, and closes in reverse order
- Spring annotation support continues to delegate to `LockExecutor`
- Spring continues to reject `Future`, `CompletionStage`, and reactive return types
- async lifecycle support is explicitly out of scope instead of being implied by session-oriented naming

This keeps the kernel and starter honest: they support synchronous execution scopes only.

### 3. Fencing tokens become monotonic per lock key

Every successful acquisition for a given business key must observe a token greater than any previously granted token for that same key, regardless of `LockMode`.

Rules:

- `MUTEX`, `READ`, and `WRITE` all draw from the same per-key counter
- token issuance happens when acquisition succeeds, not when a contender first appears
- downstream guarded resources may compare only the token, without needing to know the lock mode

This restores the intended stale-writer protection model:

- old `MUTEX` owner cannot safely write after a newer `WRITE` owner succeeds
- old `READ` owner cannot safely publish state after a newer `MUTEX` owner succeeds

### 4. Redis sessions and leases use explicit loss state transitions

Redis keeps the current session-oriented structure, but the renewal loop becomes a state machine instead of an exception source.

Session rules:

- session starts in `ACTIVE`
- if session renewal fails, the session transitions once to `LOST`
- if any active lease refresh fails because ownership is gone, that lease transitions to `LOST`
- if lease refresh fails because Redis I/O or script execution fails, the session also transitions to `LOST`
- once a session is `LOST`, new acquire attempts fail with `LockSessionLostException`

Implementation rules:

- the scheduled renewal task must catch all exceptions internally
- exceptions from renewal must not escape the scheduled task body
- on renewal failure, the session marks all active leases `LOST`, cancels future renewal, and stores the terminal loss cause for diagnostics
- `release()` on a lost lease throws `LockOwnershipLostException`
- `close()` should tolerate already-lost ownership as an expected terminal state and only surface real backend cleanup failures

This prevents the current failure mode where the scheduled task dies and the caller learns about it only after TTL expiry.

### 5. ZooKeeper read/write locking uses ephemeral sequential queue nodes

The current ZooKeeper algorithm must be replaced completely.

Per lock key, ZooKeeper will use:

- a queue root such as `.../rw/<encoded-key>/locks`
- ephemeral sequential contender nodes under that root
- node prefixes `read-` and `write-`
- a per-key persistent fencing counter path such as `.../fence/<encoded-key>`

Acquisition algorithm:

1. create an `EPHEMERAL_SEQUENTIAL` contender node with mode and session metadata in node data
2. list and sort all contender nodes by sequence number
3. for `WRITE`
   only acquire if there are no nodes before the current node
4. for `READ`
   acquire if there is no earlier `write-` node
5. if blocked, watch exactly one predecessor:
   - `WRITE` watches the nearest earlier node
   - `READ` watches the nearest earlier writer
6. when the watched node disappears, re-run the check
7. when acquisition succeeds, issue the next fencing token for the key

Consequences:

- reader/reader concurrency is allowed
- writer excludes all earlier and later contenders until its node is released
- no check-then-create race remains
- wakeups are targeted instead of polling every 25 ms

### 6. ZooKeeper lease validity is tied to both session and queue node ownership

`ZooKeeperLease.isValid()` becomes true only when:

- the Curator/ZooKeeper session is still valid
- the contender node still exists
- the node data still matches the lease owner metadata
- the lease has not already transitioned to `RELEASED` or `LOST`

`release()` rules:

- if the ZooKeeper session is already lost, mark the lease `LOST` and throw `LockOwnershipLostException`
- if the contender node is gone or belongs to someone else, mark `LOST` and throw `LockOwnershipLostException`
- otherwise delete the contender node and transition to `RELEASED`

This matches the Redis rule that backend loss must become an explicit lease state transition.

### 7. Backend capability metadata becomes internal and minimal

Runtime/backend selection still needs to know which modes a backend supports, but callers do not.

`BackendCapabilities` remains in SPI form and shrinks to:

```java
public record BackendCapabilities(
    boolean mutexSupported,
    boolean readWriteSupported
) {}
```

Rules:

- request validation still fails fast before backend acquisition for unsupported modes
- public API no longer exposes capability records
- both Redis and ZooKeeper continue to advertise `mutexSupported = true` and `readWriteSupported = true`

### 8. Polling-based backend waits are removed where the backend can signal progress

Redis may keep timed retry loops because Redis does not provide the same waiter primitives in the current design.
ZooKeeper must stop using fixed 25 ms polling for lock acquisition.

ZooKeeper wait semantics become:

- compute remaining timeout from `WaitPolicy`
- wait on predecessor-node deletion callbacks up to the remaining timeout
- on timeout, delete the contender node and throw `LockAcquisitionTimeoutException`
- on interruption, delete the contender node, restore interrupt status if needed, and propagate `InterruptedException`

This reduces herd behavior and makes the read/write protocol match standard ZooKeeper lock recipes.

## Module Changes

### `distributed-lock-api`

- remove `LeasePolicy`
- remove `SessionPolicy`
- remove `SessionRequest`
- remove `LockCapabilities`
- update `LockClient.openSession(SessionRequest)` to `openSession()`
- update `LockRequest` to drop `leasePolicy`
- update API surface tests to assert the reduced public contract

### `distributed-lock-core`

- update `DefaultLockClient` to open sessions without a request object
- update `DefaultLockExecutor` to use the reduced request/session API
- remove validation logic tied to removed public capability types
- keep mode validation against internal backend capabilities
- keep `CurrentLockContext` for synchronous execution helpers

### `distributed-lock-runtime`

- update `LockRuntimeBuilder` to construct `DefaultLockExecutor` without session policy configuration
- keep backend selection and duplicate-id validation
- keep `BackendModule` and `BackendCapabilities`, but with the reduced capability shape

### `distributed-lock-redis`

- unify fencing counters by key
- redesign renewal failure handling around explicit `LOST` transitions
- ensure renewal task catches and contains all exceptions
- update tests for ownership loss, renewal failure, and cross-mode token monotonicity

### `distributed-lock-zookeeper`

- replace fixed owner-path read/write protocol with ephemeral sequential contender nodes
- replace polling wait loops with predecessor watches
- unify fencing counters by key
- update lease validity and release rules around queue-node ownership
- add concurrency tests that prove correct reader/writer exclusion

### Spring starter and examples

- update all `LockRequest` call sites to the reduced constructor
- update `LockClient.openSession()` call sites
- preserve async-return rejection in the starter tests and README
- remove documentation references to removed policies and capabilities

## Testing Strategy

The existing suite is too mutex-heavy.
This redesign requires behavior-focused tests around the broken paths.

### API tests

Extend `ApiSurfaceTest` to verify:

- removed public types no longer exist
- `LockClient.openSession()` has no parameters
- `LockRequest` contains only `key`, `mode`, and `waitPolicy`

### Core tests

Update and extend:

- `DefaultLockClientTest`
  - compile against the reduced API
- `DefaultLockExecutorTest`
  - compile against the reduced API
  - preserve current fencing-context behavior

### Contract tests

Extend `LockClientContract` beyond mutex-only behavior:

- mutex excludes concurrent sessions
- multiple readers may hold the same key concurrently
- writer blocks while readers are active
- reader blocks while writer is active
- fencing tokens increase across sequential acquisitions even when lock mode changes for the same key

### Redis tests

Add or extend tests for:

- renewal survives normal TTL rollover
- renewal task backend failure marks session and leases `LOST`
- owner token deletion still yields `LockOwnershipLostException`
- fencing tokens are monotonic across `MUTEX`, `READ`, and `WRITE`

### ZooKeeper tests

Add or extend tests for:

- concurrent readers may both acquire
- writer cannot acquire while a reader is held
- reader cannot acquire while a writer is held
- no reader/writer overlap appears under repeated concurrent attempts
- session loss invalidates leases
- queue-node cleanup on timeout and interruption
- fencing tokens are monotonic across modes

### Starter tests

Update integration tests so they:

- compile against the reduced API
- still verify working runtime assembly for Redis and ZooKeeper
- still reject async annotation return types

## Migration Plan

Implementation should proceed in this order:

1. reduce the public API and update compile-time callers
2. update core/runtime to the reduced API and internal capability model
3. harden Redis renewal and loss state handling and key-wide fencing counters
4. replace ZooKeeper locking with the sequential-node read/write protocol
5. expand contract and backend tests to cover read/write correctness and failure paths
6. update starter docs, examples, and test-suite documentation

## Risks and Mitigations

### Risk: breaking public API ripples across every module

Mitigation:

- do the API reduction first
- let compile failures identify all call sites
- keep migration mechanical: remove unused policy arguments instead of inventing adapters

### Risk: ZooKeeper sequential protocol is more complex than the current implementation

Mitigation:

- keep the protocol close to standard lock-recipe behavior
- use one watched predecessor per contender instead of broad watchers
- add deterministic contention tests before implementation

### Risk: Redis now fails closed more aggressively

Mitigation:

- this is intentional because silent lease expiry is worse than explicit failure
- document that transient renewal failure becomes session loss
- keep loss reporting stable and testable

## Open Questions Resolved

- `ZooKeeper` read/write support is preserved and reimplemented correctly, not removed
- breaking API changes are allowed
- async support remains unsupported in this redesign instead of being partially implemented
