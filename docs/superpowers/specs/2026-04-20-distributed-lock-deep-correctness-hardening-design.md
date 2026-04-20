# Distributed Lock Deep Correctness Hardening Design

## Summary

This design fixes four correctness problems in the current distributed lock implementation:

- programmatic `LockExecutor` silently allows async escape from a synchronous lock scope
- Redis session and lease loss can be swallowed during scope teardown
- ZooKeeper `LockSession` is not a real backend session because all logical sessions share one Curator client
- Spring lock-key SpEL parsing uses a hand-written template expander that does not match Spring semantics

The chosen direction is a deep correctness refactor instead of a minimal patch. The main structural change is that each ZooKeeper `LockSession` will own an independent Curator client and will manage a terminal lifecycle of `ACTIVE`, `LOST`, or `CLOSED`.

## Goals

- Make programmatic and Spring lock execution semantics consistently synchronous
- Surface ownership loss back to callers instead of allowing successful-looking completion after loss
- Make ZooKeeper logical sessions correspond to real backend sessions
- Make `SessionState.LOST` a terminal state for ZooKeeper, matching Redis semantics
- Make lease state transitions explicit and internally consistent for both Redis and ZooKeeper
- Replace custom lock-key templating with Spring-standard template parsing
- Expand tests so the corrected semantics are enforced directly

## Non-Goals

- No public async lease lifecycle API in this change
- No new fairness guarantees beyond existing backend protocols
- No metrics, tracing, health endpoints, or operator tooling
- No backend-neutral recovery or retry framework
- No redesign of the public `LockClient` / `LockSession` / `LockLease` API surface beyond tightening behavior

## Problem Statement

### 1. Programmatic async escape

`DefaultLockExecutor.withLock(...)` executes a synchronous try-with-resources scope, but it does not reject async return values. A caller can return `CompletionStage`, `Future`, or a reactive publisher and accidentally continue business work after the lease has already been released.

### 2. Redis teardown hides loss

Redis renewal failure correctly flips the session and leases into a lost state, but teardown code can silently return from a lost lease or lost session close path. That allows a `withLock(...)` call to appear successful even after ownership was lost during execution.

### 3. ZooKeeper sessions are not real sessions

`ZooKeeperLockBackend` currently owns one shared `CuratorFramework`, and `openSession()` only wraps it with a UUID. This means:

- all logical sessions share the same backend connection fate
- a temporary connection recovery can move a logical session from effectively lost back to active
- `SessionState.LOST` is not terminal
- lease state is only partially materialized and can drift from validity checks

### 4. SpEL behavior is not Spring behavior

`SpelLockKeyResolver` implements its own `#{...}` substitution loop. This only safely supports trivial expressions and can mis-handle valid nested or structured SpEL templates.

## Design Decisions

### 1. `LockExecutor` becomes explicitly synchronous-only

`DefaultLockExecutor` will enforce the same rule already enforced by the Spring aspect:

- acquire the lock
- bind `CurrentLockContext`
- run the action
- inspect the returned object before leaving scope
- reject async return values with `LockConfigurationException`
- then release lease and session in reverse order

Rejected result categories:

- `CompletionStage`
- `Future`
- reactive `Publisher`

Rules:

- detection happens on the actual returned object when non-null
- detection is best-effort for `Publisher` by checking assignability against `org.reactivestreams.Publisher` when available
- `CurrentLockContext` remains thread-local and synchronous by design
- documentation will explicitly state that `LockExecutor` and `CurrentLockContext` are synchronous-only facilities

Consequences:

- programmatic and Spring integrations have one consistent contract
- callers who need long-lived or cross-thread lease control must use `LockClient` and `LockSession` directly

### 2. Redis ownership loss must surface at scope exit

Redis will keep its existing renewal-based model, but its terminal behavior will be tightened.

`RedisBackendSession` changes:

- store a terminal loss cause when the session transitions to `LOST`
- once lost, remain lost permanently
- `close()` must perform best-effort cleanup even after loss instead of immediately returning
- if the session was previously marked lost, `close()` should surface the terminal loss condition unless a stronger cleanup failure supersedes it

`RedisLease` changes:

- `release()` on `LOST` must throw `LockOwnershipLostException`
- `release()` remains idempotent only for `RELEASED`
- `markLost()` becomes the single path for transitioning to `LOST`

Scope behavior:

- if business logic finishes normally but the lease was lost during execution, the enclosing scope fails during close
- callers can no longer mistake a lost-ownership execution for a successful protected execution

Error precedence:

- ownership loss is preferred over silent success
- backend cleanup failures remain backend failures
- if both occur, the primary exception should describe the loss that invalidates the scope, with cleanup failures attached as suppressed exceptions when practical

### 3. Each ZooKeeper `LockSession` owns an independent Curator client

`ZooKeeperLockBackend` will stop owning a global `CuratorFramework`. Instead:

- the backend stores only immutable configuration
- each `openSession()` creates a new `CuratorFramework`
- each session blocks for initial connection and then owns that client until close

`ZooKeeperBackendSession` responsibilities:

- own the Curator client
- listen to connection state transitions
- manage active leases
- own a terminal session state and terminal loss cause

State rules:

- initial state is `ACTIVE` after successful connection establishment
- `LOST` is terminal
- `CLOSED` is terminal
- a session never returns from `LOST` to `ACTIVE`

Connection-state policy:

- `LOST` Curator state marks the session lost
- `SUSPENDED` also marks the session lost because ownership safety can no longer be proven for ephemeral nodes
- subsequent reconnect notifications are ignored for semantic recovery because the session is already terminally lost

This change makes logical session boundaries truthful:

- two `LockSession`s no longer share a backend connection lifecycle
- closing one session cannot invalidate another by shutting down shared client state
- failure semantics become per-session instead of global-to-backend

### 4. ZooKeeper lease state becomes explicit and consistent

`ZooKeeperLease` will track its own explicit lifecycle:

- `ACTIVE`
- `LOST`
- `RELEASED`

Rules:

- session loss actively marks every active lease as `LOST`
- `isValid()` returns `false` for any non-`ACTIVE` lease
- `isValid()` also performs a backend ownership check; if the node is missing or ownership data does not match, it transitions the lease to `LOST`
- `state()` reflects the materialized lifecycle, not just a best-effort live probe
- `release()` on `LOST` throws `LockOwnershipLostException`
- `release()` on `RELEASED` remains idempotent

This aligns ZooKeeper with Redis:

- loss is explicit
- loss is terminal
- release after loss is not silently accepted

### 5. ZooKeeper acquisition stays protocol-compatible but session-local

The lock acquisition algorithms remain the current correct protocol shape:

- mutex uses ephemeral owner node
- read/write uses ephemeral sequential contender nodes and predecessor watches
- fencing counter remains per key

The key change is ownership of the Curator client:

- every protocol operation uses the session-owned client
- session close shuts down only that session's client
- node validity checks compare against that session's owner data and client-backed state

No lock-protocol downgrade is introduced in this change.

### 6. SpEL lock-key parsing moves to Spring template parsing

`SpelLockKeyResolver` will stop manually searching for `#{...}` segments.

New parsing model:

- create the existing evaluation context with arguments and parameter names
- if the expression contains no template markers, return it as a literal string
- otherwise evaluate it using `ParserContext.TEMPLATE_EXPRESSION`

Benefits:

- behavior matches Spring expectations
- nested and structured expressions are delegated to Spring parsing
- the resolver stops maintaining its own partial expression language

### 7. Testing strategy becomes behavior-first

The change will be driven by failing tests first.

New or updated test coverage:

- `DefaultLockExecutorTest`
  - rejects `CompletionStage`
  - rejects `Future`
  - rejects reactive `Publisher` when available
- Redis tests
  - closing a lost session surfaces ownership/session loss instead of silently succeeding
  - `LockExecutor.withLock(...)` fails if ownership is lost during action execution
- ZooKeeper tests
  - each opened session uses an independent backend client
  - one session closing does not invalidate a sibling session
  - one session losing connectivity does not automatically flip another live session
  - session loss is terminal and cannot recover to `ACTIVE`
  - lease `state()` transitions to `LOST` when validity checks fail
  - `release()` on lost lease throws `LockOwnershipLostException`
- Spring key resolver tests
  - existing simple template behavior still works
  - nested or structured template expressions parse correctly under Spring template semantics

## Module Changes

### `distributed-lock-core`

- update `DefaultLockExecutor` to reject async return values
- add or reuse helper logic for async-result detection
- extend unit tests around strict synchronous scope semantics

### `distributed-lock-redis`

- store terminal loss cause in `RedisBackendSession`
- tighten `close()` and `release()` semantics for lost ownership
- extend tests for scope-exit failure after in-flight loss

### `distributed-lock-zookeeper`

- refactor `ZooKeeperLockBackend` into a session factory over immutable configuration
- move Curator client ownership into `ZooKeeperBackendSession`
- add terminal session state and terminal loss cause
- wire Curator connection-state events into explicit loss transitions
- align lease lifecycle semantics with Redis
- extend concurrency and session-isolation tests

### `distributed-lock-spring-boot-starter`

- replace hand-written key template expansion with Spring template evaluation
- add focused tests for complex template parsing
- update README wording around synchronous-only semantics

## Error Handling Rules

- session loss is not silently downgraded into success
- lost ownership beats idempotent no-op behavior
- `RELEASED` is the only state where silent repeated close/release is allowed
- cleanup is best-effort, but semantic invalidation must still be reported
- ZooKeeper connection degradation that invalidates safety is treated as terminal loss, not as a transient warning

## Risks and Trade-Offs

### More backend resources for ZooKeeper

Per-session Curator clients are more expensive than one shared client. This is accepted because correctness and truthful semantics are more important than minimizing client count in the current API design.

### Stronger failure signaling may break callers

Some callers may currently rely on silent success after a lost lease. This change will surface those conditions as exceptions. That is intentional because silent success is unsafe.

### `SUSPENDED` treated as loss is conservative

This may fail some workloads earlier than a more optimistic design. The trade-off is deliberate: optimistic continuation after ZooKeeper uncertainty is unsafe for distributed lock ownership.

## Verification

Required verification after implementation:

- focused unit tests for core executor behavior
- focused Redis adapter tests for in-flight loss signaling
- focused ZooKeeper adapter tests for per-session isolation and terminal loss
- starter tests for key-resolution behavior
- full reactor `mvn test`

Success means:

- no async escape remains in `LockExecutor`
- Redis and ZooKeeper both surface ownership loss at scope exit
- ZooKeeper sessions are truly isolated backend sessions
- lock-key SpEL behavior is delegated to Spring template semantics
