# Core Session Lease Lifecycle Design

## Summary

This design fixes a lifecycle ownership gap in the lease/session model.
`DefaultLockSession` currently validates requests and delegates to `BackendSession`, but it does not own the public session-level rule that closing a session releases every lease acquired through that session.

Redis and ZooKeeper both maintain backend-local `activeLeases` and release them during backend session close.
That makes those adapters mostly safe, but the guarantee is scattered across backend implementations.
The testkit in-memory backend demonstrates the gap: `InMemoryBackendSession.close()` marks the session closed without releasing any acquired lease.

The chosen direction is to make `distributed-lock-core` the owner of generic session lease lifecycle semantics:

- every lease returned by `DefaultLockSession.acquire(...)` is wrapped by core
- the wrapper registers the lease with its owning `DefaultLockSession`
- `LockLease.release()` and `LockLease.close()` unregister after successful release or terminal loss
- `DefaultLockSession.close()` releases all still-registered leases before closing the backend session
- backend-specific session cleanup remains allowed as a redundant safety net

## Goals

- Make `LockSession.close()` release all unreleased leases acquired through that session
- Centralize session-level lease ownership in `distributed-lock-core`
- Keep backend adapters responsible for backend-specific ownership, renewal, and loss detection
- Preserve idempotent release semantics for already released leases
- Surface release and close failures without silently leaking later leases
- Add shared tests that fail against a backend whose `BackendSession.close()` does not release leases

## Non-Goals

- No public API change to `LockClient`, `LockSession`, or `LockLease`
- No removal of Redis or ZooKeeper backend-local `activeLeases`
- No new async, cross-thread, or reactive lease lifecycle API
- No new reentrancy policy
- No attempt to make session close recover from backend ownership loss
- No metrics or observability changes

## Problem Statement

The current public API says a caller can open a `LockSession`, acquire one or more `LockLease`s, and close both with Java resource semantics.
However, core does not enforce the natural session ownership invariant:

> A session owns the leases acquired through it, and closing the session must release the leases that remain open.

Current behavior is split:

- `DefaultLockSession.acquire(...)` returns `backendSession.acquire(request)` directly
- `DefaultLockSession.close()` only calls `backendSession.close()`
- Redis tracks and releases `activeLeases` in `RedisBackendSession.close()`
- ZooKeeper tracks and releases `activeLeases` in `ZooKeeperBackendSession.close()`
- `InMemoryBackendSession.close()` only marks the session closed

This makes correctness depend on each backend remembering to duplicate the same session-level rule.
The behavior is especially risky for testkit backends because contract tests can accidentally pass only when callers close each lease manually.

## Design Decisions

### 1. Core wraps backend leases

`DefaultLockSession.acquire(...)` will no longer return the backend lease directly.
Instead it will:

1. reject acquisition after session close
2. validate the request
3. acquire a `BackendLockLease`
4. wrap it in a core-owned `SessionBoundLockLease`
5. register the wrapper with the owning session only if the session is still open
6. return the wrapper as the public `LockLease`

If backend acquisition succeeds after another thread has already closed the session, core must release the newly acquired backend lease and then fail acquisition.
That prevents the acquire/close race from leaking a lease that was created too late to appear in the session close snapshot.

The wrapper delegates read-only lease methods to the backend lease:

- `key()`
- `mode()`
- `fencingToken()`
- `state()`
- `isValid()`

The wrapper owns only lifecycle coordination around `release()` and `close()`.

### 2. Session close releases registered leases before backend close

`DefaultLockSession.close()` will become the generic lifecycle coordinator.
On first close it will:

1. mark the session closed to block new acquisitions
2. snapshot registered leases
3. release each still-registered lease best-effort
4. call `backendSession.close()`
5. throw the primary failure, with later failures attached as suppressed exceptions

This ordering makes core release explicit backend leases while the backend session is still available.
Backend session close then remains responsible for backend resources such as renewal tasks, session keys, Curator clients, and adapter-specific cleanup.

### 3. Release unregisters leases exactly once

`SessionBoundLockLease.release()` will delegate to the backend lease and then unregister itself from the owning session when the release reaches a terminal state.

Rules:

- release on `RELEASED` remains idempotent by relying on backend lease behavior
- a successful release unregisters the wrapper
- a release that throws `LockOwnershipLostException` unregisters the wrapper because the backend no longer owns a releasable resource
- a release that throws `LockBackendException` or another runtime backend failure stays registered unless the backend lease state is no longer `ACTIVE`

Keeping backend-failure leases registered is deliberate.
If release fails because the backend was temporarily unavailable, `DefaultLockSession.close()` should still attempt backend session close, and backend-specific close may still have a stronger cleanup path.
The wrapper state machine must therefore distinguish "release is currently running" from "release reached a terminal result".
A failed non-terminal release must not permanently block a later retry.

### 4. Core does not replace backend lifecycle state

Redis and ZooKeeper should keep their current backend-local lease tracking.
Those maps still serve backend-specific purposes:

- renewal and refresh of active leases
- marking leases lost when a backend session is lost
- cleanup of backend session resources
- session close behavior for direct backend SPI users in tests

Core wrapping is not a substitute for those backend responsibilities.
It provides the public `LockSession` guarantee for users going through `DefaultLockClient`.

### 5. Backend SPI direct use remains backend-defined

`BackendSession` is an internal SPI, not the public client API.
The core guarantee applies to `LockSession` instances returned by `DefaultLockClient`.

A direct test that opens `backend.openSession()` and then forgets to release a `BackendLockLease` still observes backend-specific behavior.
Redis and ZooKeeper should continue to release on direct backend session close.
The in-memory testkit backend does not need to duplicate lifecycle tracking once shared public contract tests exercise the behavior through `LockClient`.

## Error Handling

`DefaultLockSession.close()` should never stop at the first lease release failure.
It should continue attempting to release every registered lease and then close the backend session.

Failure precedence:

- the first lease release failure is the primary exception
- subsequent lease release failures are suppressed on the primary exception
- backend session close failure is suppressed if a lease failure already exists
- backend session close failure becomes primary if all lease releases succeeded
- checked backend close failures are wrapped in `LockBackendException`

Ownership loss should be preserved.
If a backend lease reports `LockOwnershipLostException`, session close should surface that exception rather than silently treating close as successful.

## Concurrency Rules

The lifecycle code must tolerate concurrent `release()` and `close()` calls.

Required properties:

- no lease is released concurrently by core coordination
- terminally released or lost leases are not released again by core coordination
- a lease acquired before session close either registers and is closed by the session, or acquisition fails
- acquisitions after session close fail with `IllegalStateException`
- unregistering during session close is safe
- session close is idempotent

A practical implementation can use:

- `AtomicBoolean closed` on `DefaultLockSession`
- a concurrent set or map of registered `SessionBoundLockLease` instances
- an `AtomicReference` on each wrapper with states such as `ACTIVE`, `RELEASING`, and `TERMINAL`

## Testing Strategy

Add focused core tests first.

### Core session tests

Add tests in `DefaultLockClientTest` or a new `DefaultLockSessionTest`:

- closing a session releases an acquired lease when the lease was not closed directly
- closing a session releases multiple acquired leases
- closing a session is idempotent and does not release the same lease twice
- manually releasing a lease unregisters it so session close does not release it again
- if one lease release fails, session close still attempts later leases and backend session close
- backend session close failure is surfaced when lease cleanup succeeds
- acquisition after session close fails

The test backend should deliberately make `BackendSession.close()` not release leases.
That proves the behavior is provided by core rather than by backend cooperation.

### Testkit contract coverage

Add a public `LockClientContract` case:

- acquire a mutex lease through a session
- close the session without closing the lease
- verify a second session can acquire the same key

This contract should pass for the in-memory backend through `DefaultLockClient` after core wrapping exists.

### Backend regression tests

Keep Redis and ZooKeeper tests that exercise direct backend session close behavior.
Those tests verify backend SPI users and backend resource cleanup still behave correctly.

## Compatibility

The public API shape does not change.
Most callers only observe stricter cleanup:

- try-with-resources on both session and lease behaves the same
- closing a session without closing leases becomes safer
- release failures during session close become visible instead of being hidden by backend-specific behavior

The main behavioral change is that a backend that previously leaked leases on `BackendSession.close()` no longer leaks them when used through `DefaultLockClient`.

## Implementation Notes

Suggested new core type:

```java
final class SessionBoundLockLease implements LockLease {
    private final BackendLockLease delegate;
    private final DefaultLockSession owner;
    private final AtomicReference<LifecycleState> lifecycle = new AtomicReference<>(LifecycleState.ACTIVE);
}
```

Suggested `DefaultLockSession` additions:

```java
private final Set<SessionBoundLockLease> activeLeases = ConcurrentHashMap.newKeySet();
```

The wrapper can be package-private under `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/`.
No backend module should depend on it.

## Open Questions Resolved

- Should backend `activeLeases` be deleted?
  No. Backend maps still perform renewal, loss propagation, and direct SPI cleanup.

- Should `BackendSession.close()` gain a stronger SPI contract?
  Not in this change. The public guarantee belongs in `LockSession`; backend SPI behavior can remain adapter-defined.

- Should core mark lease state itself?
  No. Lease state remains backend truth. Core only coordinates registration and release attempts.
