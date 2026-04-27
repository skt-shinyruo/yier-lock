# Distributed Lock API Hardening Design

## Summary

This design hardens the current lease/session/fencing public API after the 2.0 correctness work.
The existing API is small and directionally correct, but several semantics are still implicit or weaker than the behavior expected from a reusable distributed lock library:

- `LockExecutor` is synchronous-only, but its name and callback shape do not make that clear
- programmatic executor callbacks do not receive the acquired `LockLease`, so fencing-token use is awkward
- `WaitPolicy.timed(Duration.ZERO)` is the only way to express an immediate try, which is easy to miss
- Redis lease duration is backend configuration only; callers cannot request a shorter or longer lease for a specific critical section
- public exceptions have no common lock-specific base type
- reentrant acquisition behavior is not documented or enforced consistently
- Spring annotation usage can guard a method, but there is no API-owned way for annotated code to read the current fencing token
- examples show fencing tokens but do not demonstrate how callers must use them to reject stale side effects

The chosen direction is a breaking but focused API hardening pass.
It keeps the lease/session kernel, does not add async or reactive locking, and does not attempt to match Redisson's full feature surface.
It makes the synchronous convenience layer explicit, exposes fencing information in the callback and lock context, adds truthful request-level lease policy support, and codifies non-reentrant semantics.

## Goals

- Make synchronous execution explicit in public names, docs, and callback shape
- Give programmatic executor callbacks direct access to the acquired `LockLease`
- Add an API-owned lock context for synchronous executor and Spring annotation scopes
- Replace ambiguous zero-duration waiting with explicit `tryOnce`, `timed`, and `indefinite` wait modes
- Add an optional per-request lease policy that backends can either honor or reject through capability validation
- Introduce a shared `DistributedLockException` base class for lock-specific runtime failures
- Make same-key reentry fail fast instead of relying on backend timeout or accidental self-deadlock
- Document non-reentrant, synchronous-only, and fencing-token responsibilities in the public README and examples
- Expand API, core, backend, Spring, and documentation tests to enforce the hardened semantics

## Non-Goals

- No async, reactive, Rx, coroutine, or deferred-callback lock API
- No Redisson-compatible `RLock` or `java.util.concurrent.locks.Lock` facade
- No reentrant lock support
- No lock upgrade or downgrade support for read/write leases
- No cross-backend fairness guarantee beyond each backend's documented progress semantics
- No resource-specific fencing adapters such as JDBC row guards or Redis guarded writes
- No attempt to make ZooKeeper use Redis-style TTL leases
- No compatibility layer for the current `LockExecutor` and `LockedSupplier` API

## Public API Changes

### 1. Replace `LockExecutor` with an explicit synchronous executor

Remove the current `LockExecutor` and `LockedSupplier` public API.
Introduce a synchronous executor whose name and callback make the boundary explicit:

```java
package com.mycorp.distributedlock.api;

public interface SynchronousLockExecutor {

    <T> T withLock(LockRequest request, LockedAction<T> action) throws Exception;
}
```

```java
package com.mycorp.distributedlock.api;

@FunctionalInterface
public interface LockedAction<T> {

    T execute(LockLease lease) throws Exception;
}
```

The callback receives the active `LockLease`.
This makes fencing-token use straightforward:

```java
String result = lockExecutor.withLock(request, lease -> {
    guardedResource.write(lease.fencingToken(), "new value");
    return "updated";
});
```

The executor remains synchronous-only.
If a callback returns a known async boundary type such as `CompletionStage`, `Future`, or Reactive Streams `Publisher`, the executor throws `LockConfigurationException` before returning the value to the caller.
This runtime guard remains defense-in-depth; the primary contract is the type name, API docs, and sync-only examples.

### 2. Add API-owned lock context for synchronous scopes

Move the current thread-local convenience concept out of `distributed-lock-core` and into the public API as a synchronous-scope helper:

```java
package com.mycorp.distributedlock.api;

import java.util.Optional;

public final class LockContext {

    public static Optional<LockLease> currentLease();

    public static Optional<FencingToken> currentFencingToken();

    public static boolean containsLease(LockKey key);

    public static LockLease requireCurrentLease();

    public static FencingToken requireCurrentFencingToken();
}
```

`SynchronousLockExecutor` and Spring `@DistributedLock` bind this context for the current thread while the protected action is executing.
The context is read-only for normal callers.
`currentLease()` returns the top active synchronous scope, while `containsLease(...)` scans every nested binding on the current thread.
Any binding hook needed by core or Spring must be treated as an integration detail and kept out of application examples.

This does not make the kernel thread-bound.
Manual `LockClient` and `LockSession` usage remains lease/session-oriented and does not depend on `ThreadLocal`.
`LockContext` exists only for synchronous convenience scopes.

### 3. Harden `WaitPolicy`

Replace the current `Duration waitTime, boolean unbounded` record shape with an explicit mode:

```java
package com.mycorp.distributedlock.api;

import java.time.Duration;

public record WaitPolicy(WaitMode mode, Duration timeout) {

    public static WaitPolicy tryOnce();

    public static WaitPolicy timed(Duration timeout);

    public static WaitPolicy indefinite();
}
```

```java
package com.mycorp.distributedlock.api;

public enum WaitMode {
    TRY_ONCE,
    TIMED,
    INDEFINITE
}
```

Validation rules:

- `tryOnce()` uses `WaitMode.TRY_ONCE` and `Duration.ZERO`
- `timed(Duration)` requires a strictly positive duration
- `indefinite()` uses `WaitMode.INDEFINITE` and `Duration.ZERO`
- direct record construction must reject contradictory states

Backend acquisition code must switch on `WaitMode` instead of inspecting `unbounded`.
`TRY_ONCE` attempts acquisition once and throws `LockAcquisitionTimeoutException` immediately if the lock is unavailable.

Spring annotation parsing may continue accepting `waitFor = "0s"` as user-friendly syntax, but it must translate that value to `WaitPolicy.tryOnce()` rather than constructing a zero-duration timed policy.

### 4. Add request-level lease policy

Reintroduce a narrow `LeasePolicy` type, but only for behavior that will be implemented and capability-checked:

```java
package com.mycorp.distributedlock.api;

import java.time.Duration;

public record LeasePolicy(LeaseMode mode, Duration duration) {

    public static LeasePolicy backendDefault();

    public static LeasePolicy fixed(Duration duration);
}
```

```java
package com.mycorp.distributedlock.api;

public enum LeaseMode {
    BACKEND_DEFAULT,
    FIXED
}
```

Validation rules:

- `backendDefault()` uses `LeaseMode.BACKEND_DEFAULT` and `Duration.ZERO`
- `fixed(Duration)` requires a strictly positive duration
- direct record construction must reject contradictory states

Update `LockRequest` to include the lease policy:

```java
package com.mycorp.distributedlock.api;

public record LockRequest(
    LockKey key,
    LockMode mode,
    WaitPolicy waitPolicy,
    LeasePolicy leasePolicy
) {

    public LockRequest(LockKey key, LockMode mode, WaitPolicy waitPolicy) {
        this(key, mode, waitPolicy, LeasePolicy.backendDefault());
    }
}
```

The three-argument constructor exists to keep simple call sites readable, but the canonical public shape includes `leasePolicy`.

Backend behavior:

- Redis supports `LeasePolicy.fixed(Duration)` and uses the requested duration as the owner TTL and renewal basis for that lease
- ZooKeeper initially supports only `LeasePolicy.backendDefault()` because lock ownership is tied to the ZooKeeper session and ephemeral sequential nodes
- any backend that does not support fixed request leases must reject `LeaseMode.FIXED` with `UnsupportedLockCapabilityException` before acquiring remote state

### 5. Expand backend capabilities without making fixed leases mandatory

Extend backend capability metadata with optional fixed request lease support:

```java
public record BackendCapabilities(
    boolean mutexSupported,
    boolean readWriteSupported,
    boolean fencingSupported,
    boolean renewableSessionsSupported,
    boolean fixedLeaseDurationSupported
) {
}
```

Runtime startup must continue requiring the safety-critical capabilities already required today:

- mutex support
- fencing support
- renewable sessions support

`fixedLeaseDurationSupported` is not a startup requirement.
It is validated per request by core.
This lets Redis advertise support while ZooKeeper remains a valid backend for requests that use `LeasePolicy.backendDefault()`.

### 6. Add a lock-specific exception hierarchy

Introduce:

```java
package com.mycorp.distributedlock.api.exception;

public class DistributedLockException extends RuntimeException {

    public DistributedLockException(String message) {
        super(message);
    }

    public DistributedLockException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

The following exception types must extend `DistributedLockException`:

- `LockAcquisitionTimeoutException`
- `LockBackendException`
- `LockConfigurationException`
- `LockOwnershipLostException`
- `LockSessionLostException`
- `UnsupportedLockCapabilityException`
- new `LockReentryException`

Invalid value-object construction should continue using `IllegalArgumentException`.
Operations on a closed local object may continue using `IllegalStateException`.
The shared base class is for lock-specific operational failures that application code may want to catch together.

### 7. Codify non-reentrant semantics

The public API is non-reentrant.
The library must not promise Java `Lock`-style reentry for the same lock key.

Rules:

- A single `LockSession` must not hold two active leases for the same `LockKey`, regardless of mode
- A same-session same-key acquisition attempt fails fast with `LockReentryException`
- `SynchronousLockExecutor` must fail fast with `LockReentryException` when any lease in the current synchronous `LockContext` binding stack already holds the same `LockKey`
- read-to-write upgrades and write-to-read downgrades are not supported
- callers that need nested critical sections must compose them under one outer lease or use distinct lock keys

This is deliberately stricter than "same-mode read reentry is allowed".
The strict rule avoids ambiguous release ordering, upgrade semantics, and backend-specific behavior differences.

## Runtime and Core Design

### Runtime surface

Update `LockRuntime` to expose the renamed executor:

```java
public interface LockRuntime extends AutoCloseable {

    LockClient lockClient();

    SynchronousLockExecutor synchronousLockExecutor();
}
```

The old `lockExecutor()` accessor is removed in this breaking release.
Examples and Spring auto-configuration must inject `SynchronousLockExecutor`.

### Core executor flow

`DefaultSynchronousLockExecutor` should:

1. validate request and callback
2. check the full `LockContext` binding stack for same-key reentry before opening a new session
3. open a session
4. acquire the lease
5. bind `LockContext`
6. invoke `LockedAction.execute(lease)`
7. reject known async return values
8. close context binding, release lease, and close session through try-with-resources

The action receives the same lease that is bound in `LockContext`.

### Core session flow

`DefaultLockSession` should track active lease keys in addition to active lease objects.
Before delegating to `BackendSession.acquire(...)`, it should reject same-key active acquisition with `LockReentryException`.
The key tracking must be released when the session-bound lease reaches a terminal state.

The existing session-close guarantee remains:

- closing a session releases all unreleased leases acquired through that session
- release remains idempotent for already released leases
- release after ownership loss surfaces `LockOwnershipLostException`

### Request validation

`LockRequestValidator` should validate:

- supported lock mode
- supported fixed lease policy
- non-null request fields
- positive wait and fixed lease durations through value-object constructors

The validator should not contact remote backends.
Backend adapters remain responsible for backend-specific ownership checks and session loss detection.

## Backend Design

### Redis

Redis should support both lease policies:

- `LeasePolicy.backendDefault()` uses the configured default lease duration
- `LeasePolicy.fixed(Duration)` uses the request duration for that acquired lease

Implementation expectations:

- convert effective lease duration to milliseconds
- use millisecond-precision Redis expiration for fixed request leases
- use the effective lease duration for mutex owner keys, write owner keys, and each reader zset member score
- keep the shared reader zset key expiry aligned with the furthest live reader score, not whichever reader most recently acquired or renewed
- keep pending writer intent expiry separate from acquired lease duration and long enough to preserve writer-preference semantics between acquire retries
- derive the renewal cadence from the effective lease duration
- keep the fencing counter per business key, not per mode
- preserve current writer-preference behavior for read/write locks

Redis backend configuration may keep its current default lease-duration property for callers that use `LeasePolicy.backendDefault()`.
This design does not require changing `RedisBackendConfiguration`; the new public `Duration` input is the request-level `LeasePolicy.fixed(...)`.

### ZooKeeper

ZooKeeper should support only `LeasePolicy.backendDefault()` in this stage.

Rationale:

- ZooKeeper lock ownership is represented by ephemeral sequential nodes
- ownership lifetime is governed by the ZooKeeper session
- adding request-scoped fixed TTL release would be a separate feature with different failure semantics

When a request uses `LeasePolicy.fixed(...)`, core should reject it with `UnsupportedLockCapabilityException` before creating any contender node.

ZooKeeper should still use the hardened `WaitPolicy` modes:

- `TRY_ONCE` creates a contender, checks once, and deletes the contender before timing out if acquisition is unavailable
- `TIMED` follows the existing queue wait with a deadline
- `INDEFINITE` follows the existing queue wait without a caller deadline

## Spring Design

### Annotation shape

Extend the annotation with a lease-duration attribute:

```java
public @interface DistributedLock {

    String key();

    DistributedLockMode mode() default DistributedLockMode.MUTEX;

    String waitFor() default "";

    String leaseFor() default "";
}
```

Mapping rules:

- blank `waitFor` uses the configured default wait timeout
- absent configured default wait timeout maps to `WaitPolicy.indefinite()`
- parsed zero wait maps to `WaitPolicy.tryOnce()`
- parsed positive wait maps to `WaitPolicy.timed(duration)`
- blank `leaseFor` maps to `LeasePolicy.backendDefault()`
- parsed positive `leaseFor` maps to `LeasePolicy.fixed(duration)`
- parsed zero or negative `leaseFor` fails fast with `IllegalArgumentException`

### Synchronous-only annotation behavior

The existing async return-type rejection remains.
Annotated methods returning `CompletionStage`, `Future`, or Reactive Streams `Publisher` must fail before acquiring the lock.

During the method body, `LockContext.currentLease()` and `LockContext.currentFencingToken()` must return the active lease and token.
This gives annotation users a supported way to pass the fencing token to guarded resources without importing core-internal classes.

Example:

```java
@DistributedLock(key = "account:#{#accountId}", waitFor = "2s", leaseFor = "10s")
public void updateAccount(String accountId, String value) {
    FencingToken token = LockContext.requireCurrentFencingToken();
    accountRepository.updateIfTokenIsCurrent(accountId, token.value(), value);
}
```

## Documentation Design

Update the Spring starter README, examples, and test-suite documentation around these points:

- the library is non-reentrant
- the convenience executor and annotation are synchronous-only
- async work must acquire and hold its own lease for the full async lifetime; this design does not provide that API
- `WaitPolicy.tryOnce()` is the immediate-acquisition API
- `LeasePolicy.fixed(...)` is optional and backend capability-checked
- Redis supports fixed request leases; ZooKeeper does not in this stage
- fencing tokens must be checked by the protected resource or stale writes are still possible
- `LockContext` is available only inside synchronous executor and annotation scopes

Add a fenced-resource example that demonstrates the resource-side rule:

```java
final class FencedAccountRepository {
    void updateIfTokenIsCurrent(String accountId, long fencingToken, String value) {
        int rows = jdbc.update(
            "update account set value = ?, fencing_token = ? where id = ? and fencing_token < ?",
            value,
            fencingToken,
            accountId,
            fencingToken
        );
        if (rows != 1) {
            throw new IllegalStateException("stale fencing token");
        }
    }
}
```

The example should explain that the lock service issues monotonic tokens, but the external resource must reject older tokens.

## Testing Strategy

### API tests

Extend `ApiSurfaceTest` to verify:

- `SynchronousLockExecutor` replaces `LockExecutor`
- `LockedAction.execute(LockLease)` is the callback shape
- `LockedSupplier` is gone
- `LockRuntime` exposes `synchronousLockExecutor()`
- `WaitPolicy` exposes `tryOnce`, `timed`, and `indefinite`
- direct contradictory `WaitPolicy` states are rejected
- `LeasePolicy` exposes `backendDefault` and `fixed`
- direct contradictory `LeasePolicy` states are rejected
- `LockRequest` record components are `key`, `mode`, `waitPolicy`, `leasePolicy`
- the three-argument `LockRequest` constructor defaults to `LeasePolicy.backendDefault()`
- all operational lock exceptions extend `DistributedLockException`
- `LockReentryException` exists
- `LockContext` exposes current and required lease/token accessors

### Core tests

Add or update core tests for:

- `SynchronousLockExecutor` passes the acquired lease into the action
- `SynchronousLockExecutor` binds and clears `LockContext`
- nested same-key synchronous execution fails fast with `LockReentryException`
- callback returning `CompletionStage`, `Future`, or `Publisher` still fails with `LockConfigurationException`
- same-session same-key acquisition fails fast with `LockReentryException`
- same-session different-key acquisition remains allowed
- session close still releases unclosed leases after key tracking is added
- fixed lease policy is rejected when backend capabilities do not support it

### Contract tests

Extend `LockClientContract` to cover:

- `WaitPolicy.tryOnce()` fails immediately when a conflicting lease is held
- non-reentrant same-session acquisition fails with `LockReentryException`
- different keys can be held by the same session
- backend-default lease policy works for all contract backends

Do not require fixed request leases in the shared contract because not every backend supports them.

### Redis tests

Add Redis-specific tests for:

- fixed request lease duration controls the owner TTL
- fixed request lease duration controls reader zset expiry
- renewal uses the fixed lease duration rather than the backend default
- backend-default lease duration still uses Redis configuration
- `WaitPolicy.tryOnce()` does not sleep before timing out
- writer pending intent cleanup still happens after failed fixed-lease write acquisition

### ZooKeeper tests

Add ZooKeeper-specific tests for:

- `LeasePolicy.fixed(...)` fails with `UnsupportedLockCapabilityException`
- no contender node remains after a fixed-lease rejection
- `WaitPolicy.tryOnce()` cleans up the contender node on unavailable acquisition
- existing timed and indefinite acquisition behavior still works

### Spring tests

Add or update Spring integration tests for:

- `@DistributedLock(leaseFor = "10s")` builds a request with `LeasePolicy.fixed(Duration.ofSeconds(10))`
- `@DistributedLock(waitFor = "0s")` builds `WaitPolicy.tryOnce()`
- blank `leaseFor` builds `LeasePolicy.backendDefault()`
- zero or negative `leaseFor` fails fast
- annotated method code can read `LockContext.requireCurrentFencingToken()`
- async return types still fail before acquisition
- auto-configuration exposes `SynchronousLockExecutor` instead of `LockExecutor`

### Documentation and examples tests

Update examples so they compile against the hardened API:

- programmatic Redis example uses `SynchronousLockExecutor` and `lease ->`
- programmatic ZooKeeper example uses manual `LockClient` where fencing-token control is central
- Spring README examples use `LockContext` for annotation fencing-token access
- test-suite README names non-reentrant and synchronous-only semantics explicitly

## Migration Plan

Implementation should proceed in this order:

1. Update API surface tests for the target public shape
2. Implement new API types and remove old executor callback types
3. Update core executor, lock context binding, and session reentry checks
4. Extend backend capability metadata and request validation
5. Update runtime and Spring auto-configuration to expose `SynchronousLockExecutor`
6. Add Redis fixed lease support
7. Add ZooKeeper fixed-lease rejection and try-once cleanup tests
8. Update observability decorators for the renamed executor and callback shape
9. Update examples, READMEs, and test-suite docs
10. Run the reactor tests and focused Redis/ZooKeeper integration tests

This order makes compile failures identify all API call sites before backend behavior changes are added.

## Risks and Mitigations

### Risk: breaking API changes disrupt current examples and downstream users

Mitigation:

- make the break intentional and documented
- update all examples in the same change set
- keep `LockRequest`'s three-argument constructor for simple migration
- provide direct before/after snippets in README

### Risk: fixed lease duration means different things across backends

Mitigation:

- make fixed lease duration capability-checked per request
- support it in Redis where TTL leases are natural
- reject it in ZooKeeper until a separate design defines fixed-duration session-independent semantics

### Risk: `LockContext` reintroduces thread-bound assumptions

Mitigation:

- document it as synchronous-scope-only
- bind it only in `SynchronousLockExecutor` and Spring annotation interception
- keep manual `LockClient` and `LockSession` independent of thread-local context
- reject async return types before returning from synchronous scopes

### Risk: non-reentrant enforcement breaks callers relying on timeout-based nested acquisition

Mitigation:

- fail fast with a specific `LockReentryException`
- document the migration path: pass the outer lease/token through the call stack or use distinct keys
- keep different-key nested acquisition valid

### Risk: Redis fixed lease support adds precision and renewal complexity

Mitigation:

- use one computed effective lease duration per acquired lease
- derive owner TTL, reader member scores, and renewal cadence from that value
- derive shared reader zset expiry from the furthest live reader score
- keep pending writer expiry tied to the backend/session intent TTL so very short fixed write leases cannot let later readers bypass a waiting writer
- cover mutex, read, write, and renewal paths with focused tests

## Acceptance Criteria

The hardening is complete when all of the following are true:

- public API no longer exposes `LockExecutor` or `LockedSupplier`
- `SynchronousLockExecutor` and `LockedAction` are the only convenience execution API
- executor and annotation scopes expose the active lease through `LockContext`
- `WaitPolicy.tryOnce()`, `WaitPolicy.timed(...)`, and `WaitPolicy.indefinite()` are the only valid wait factories
- `LockRequest` includes `LeasePolicy`
- Redis honors `LeasePolicy.fixed(...)`
- ZooKeeper rejects `LeasePolicy.fixed(...)` without creating remote lock state
- same-session same-key reentry fails with `LockReentryException`
- nested same-key synchronous executor use fails with `LockReentryException`
- all lock operational exceptions extend `DistributedLockException`
- Spring annotation supports `leaseFor`
- README and examples document non-reentrant, synchronous-only, and fencing-token responsibilities
- API, core, runtime, Spring, observability, Redis, ZooKeeper, and contract tests pass
