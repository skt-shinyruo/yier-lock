# Backend Correctness Hardening Design

## Summary

This design hardens the backend and core correctness issues found during the repository review.
The work is intentionally focused on lease/session safety, backend progress semantics, and contract-test reliability.
It does not introduce a new public lock API and does not attempt to add asynchronous locking.

The highest-risk areas are:

- the in-memory test backend is thread-owned because it delegates ownership to `ReentrantReadWriteLock`
- Redis read/write progress protects waiting writers but not waiting mutex acquisitions, so a mutex can starve behind continuous readers
- ZooKeeper indefinite acquisition can block forever after session loss because predecessor-watch waits are not woken by terminal session transitions
- ZooKeeper returns a lease after queue eligibility without a final ownership recheck around fencing-token issuance
- several backend helpers accept malformed paths, malformed queue nodes, or unsafe concurrent release paths

The chosen direction is a correctness pass across `distributed-lock-core`, `distributed-lock-testkit`, `distributed-lock-redis`, and `distributed-lock-zookeeper`.
The implementation should be test-driven and should prefer small local changes over a protocol rewrite.

## Goals

- Make the in-memory test backend model lease/session ownership instead of Java thread ownership
- Make stale fencing-token rejection atomic in test helpers
- Prevent Redis mutex acquisitions from starving behind a stream of readers
- Keep Redis read/write writer-preference behavior while documenting that it is not FIFO fairness
- Ensure Redis key strategy can support Redis Cluster without silently resetting persisted fencing counters
- Ensure ZooKeeper waits terminate promptly on session loss, close, timeout, or interruption
- Make ZooKeeper suspended-session semantics explicit and tested instead of accidental
- Recheck ZooKeeper ownership before returning a fenced lease
- Harden ZooKeeper path validation, queue-node parsing, and fencing counter retry behavior
- Reduce CPU waste in concurrent release paths that may wait on network-backed release operations
- Add targeted tests that fail on the current behavior and pass after the hardening work

## Non-Goals

- No public async, reactive, or deferred lock lifecycle API
- No reentrant lock support
- No backend-neutral FIFO fairness guarantee
- No replacement of the Redis Lua protocol with Redisson or another library
- No replacement of the ZooKeeper queue protocol with Curator recipes in this change
- No global migration of existing Redis fencing counters without an explicit migration command or operator step
- No new external service dependency

## Design Decisions

### 1. Replace thread-owned in-memory locking with owner-owned state

`distributed-lock-testkit` should stop relying on `ReentrantReadWriteLock` to represent distributed lease ownership.
`ReentrantReadWriteLock` requires the acquiring Java thread to release the lock, which is not the contract of `LockLease`.
A lease may be released by another thread, or a session may close on a management thread.

The in-memory backend should instead maintain explicit per-key state under a per-key monitor:

- active exclusive owner, if any
- active read owners with lease ids and counts
- per-key fencing counter
- optional waiter metadata only if needed by tests

Each acquired lease receives an internal lease id.
Release validates and removes that lease id, regardless of the calling thread.
This keeps the testkit aligned with the real distributed-lock contract and prevents false failures when contract tests exercise cross-thread release.

The lock state map should also be allowed to remove idle keys after the last owner releases.
That keeps long-running test processes from accumulating unbounded key state.

### 2. Make test fencing-resource writes atomic

`FencedResource` currently reads the latest token and then writes the new token in separate atomic operations.
Concurrent writes can allow a stale token to win if it observes the old value before a newer token commits.

Replace the update with a CAS loop:

- read current token
- reject when the incoming token is less than or equal to current
- attempt `compareAndSet(current, incoming)`
- retry when another writer advanced the token concurrently

The helper should remain intentionally small because it is test infrastructure, not a production fencing adapter.

### 3. Avoid busy-spin during concurrent lease release

`SessionBoundLockLease.release()` currently spins with `Thread.onSpinWait()` while another thread is releasing the same delegate.
Backend release can involve Redis or ZooKeeper network I/O, so pure spinning can waste CPU for the entire I/O duration.

Replace the release lifecycle guard with `synchronized` state transitions around delegate release.
This is the smallest correct change and avoids adding another lock abstraction.

Rules:

- repeated release after terminal state remains a no-op
- release after ownership loss must still surface `LockOwnershipLostException`
- only one caller may run the delegate release operation
- waiting callers block instead of spin while delegate release is in progress

The important invariant is unchanged: only one delegate release runs, and all callers observe the same terminal state once release completes.

### 4. Extend Redis pending-intent protection to mutex acquisitions

Redis currently uses the pending writer zset to block later readers once a writer is waiting.
`MUTEX` is also an exclusive acquisition, but mutex waiters do not register pending intent.
That means continuous new readers can keep entering while a mutex waits for existing readers to drain.

The Redis backend should generalize pending writer intent into pending exclusive intent while preserving the existing key shape unless a key-strategy change is explicitly selected.

Rules:

- `WRITE` and `MUTEX` acquisition attempts create a pending exclusive intent before waiting behind active readers
- `READ` acquisition refuses to enter while any non-expired pending exclusive intent exists
- successful exclusive acquisition removes its own pending intent
- timeout, interruption, session close, or acquisition failure removes the caller's own pending intent on a best-effort basis
- stale pending intents expire by score and are cleaned by acquisition scripts
- competing exclusive waiters are not required to be FIFO in this change

The existing `pendingWritersKey` can remain as the physical Redis key to minimize live-key churn.
Its meaning should be documented as pending exclusive intent after the change.

### 5. Make Redis key strategy cluster-aware without silently resetting fences

Redis multi-key Lua scripts require all keys to reside in the same Redis Cluster hash slot.
The current key format does not use hash tags, so it is unsafe for Redis Cluster.

However, the fencing counter key is persistent and changing key names can reset fencing tokens for an existing resource.
Because fencing counters are persisted data, key-strategy changes must be explicit.

Design:

- introduce a backend configuration enum with `LEGACY` and `HASH_TAGGED` strategies
- keep `LEGACY` as the default for existing users
- format `HASH_TAGGED` per-resource keys with a shared hash tag, for example `lock:{<encoded-resource>}:mutex:owner`
- document that switching strategies for an existing deployment requires migrating fencing counters or accepting a new fencing domain
- ensure every multi-key script uses only keys from the same resource hash tag
- keep session keys separate because they are not part of lock-resource multi-key scripts

This pass adds strategy selection and documentation only.
It does not include automatic Redis key migration tooling.

### 6. Make ZooKeeper acquisition waits session-aware

ZooKeeper `awaitNodeDeletion()` should not block forever when the session is already lost or closed.
The session should own a terminal signal that is triggered by `markSessionLost()` and `close()`.

Acquisition waiting should loop in bounded intervals:

- register a predecessor deletion watcher
- wait for either the predecessor event, a short interval, or terminal session signal
- after every wake-up, call `ensureActive()`
- recompute remaining timeout for timed waits
- clean up the contender node on timeout, interruption, or runtime failure

For `WaitPolicy.indefinite()`, the wait is indefinite only while the session stays active.
Session loss must terminate the acquire call with `LockSessionLostException`.

### 7. Make ZooKeeper suspended-session policy explicit

Curator `SUSPENDED` means the connection is temporarily disconnected and the ZooKeeper session may still be alive.
The current implementation treats `SUSPENDED` as permanent loss for safety.
That is conservative but can reduce availability during short network blips.

This hardening pass should make the policy explicit instead of implicit.
The default should remain fail-safe:

- `LOST` always transitions the lock session to `SessionState.LOST`
- `SUSPENDED` transitions to `LOST` by default because ownership cannot be proven while disconnected
- the exception message and documentation should say the session was lost due to suspended connectivity under the fail-safe policy

This pass does not add a tolerant suspended-session mode.
That would require representing uncertain ownership, but the public API has no `SUSPENDED` or `UNCERTAIN` state.
A future design may add such a mode explicitly.

### 8. Recheck ZooKeeper ownership around fencing-token issuance

The ZooKeeper queue algorithm should not return a lease solely because `canAcquire(nodes, current)` was true before the fencing counter update.
Session loss or node deletion can happen between eligibility and token issuance.

Before returning a lease:

- verify the contender node still exists and its owner data matches the session
- advance the fencing counter
- verify the contender node still exists and still matches the session
- refresh the queue snapshot and verify the current node still satisfies `canAcquire`
- only then create and register the `ZooKeeperLease`

If the post-token check fails, the incremented token may be skipped.
Skipped fencing tokens are acceptable; returning a lease without ownership is not.

### 9. Harden ZooKeeper path and queue parsing

`ZooKeeperBackendConfiguration` should validate and normalize `basePath`.
Use ZooKeeper or Curator path validation where possible.

Rules:

- blank paths are rejected
- paths must be absolute
- invalid ZooKeeper path syntax is rejected early
- trailing slash is normalized, except root
- root base path is handled by a path-join helper so generated paths are `/rw/...`, not `//rw/...`

Queue-node parsing should validate sequence suffixes before parsing.
Malformed children under the queue root must fail fast with a clear `LockBackendException` that includes the queue path and child name.
Unexpected nodes indicate external mutation of internal state, so silently ignoring malformed lock nodes would hide operator or application errors.

### 10. Add backoff and active-state checks to ZooKeeper fencing counter retries

The optimistic fencing counter loop should check session state and avoid tight retry loops under contention.

Rules:

- call `ensureActive()` on each retry
- add small bounded backoff with jitter after `BadVersionException`
- preserve monotonic token behavior
- preserve interruption status if the backoff is interrupted

This reduces local CPU pressure and ZooKeeper write pressure during high-contention acquire bursts.

## Module Changes

### `distributed-lock-core`

- replace busy-spin release coordination in `SessionBoundLockLease`
- add concurrent release tests with a blocking backend delegate
- keep lease release idempotency and ownership-loss semantics unchanged

### `distributed-lock-testkit`

- replace `ReentrantReadWriteLock` with owner-owned in-memory state
- make `FencedResource` CAS-based
- add cross-thread release and session-close contract tests
- add concurrent stale-token rejection tests
- add idle key cleanup and tests

### `distributed-lock-redis`

- add pending exclusive intent for mutex acquisition
- update read acquisition script to treat pending exclusive intent as reader-blocking
- clean pending intent on timeout, interruption, session close, and acquisition failure
- add optional cluster-compatible key strategy without changing the default key strategy
- document key-strategy migration implications for fencing counters
- keep Redis script caching out of this correctness pass unless needed by the implementation plan for key-strategy tests

### `distributed-lock-zookeeper`

- add session terminal signal for wait loops
- make predecessor wait loops call `ensureActive()` after wake-ups
- keep or explicitly configure fail-safe `SUSPENDED` handling
- add final ownership and queue rechecks around `nextFence`
- normalize and validate base path
- validate queue-node sequence suffixes
- add backoff and active checks to fencing counter CAS retries
- expose additional backend configuration only when directly needed by these changes

## Testing Strategy

### Core and testkit tests

- releasing a lease from a different thread succeeds
- closing a session from a different thread releases active leases
- repeated release during a blocked backend release does not spin and remains idempotent
- stale fencing tokens are rejected under concurrent writes
- in-memory read/read sharing and read/write exclusion still match the lock contract

### Redis tests

- a waiting mutex blocks later readers while existing readers drain
- a waiting writer still blocks later readers and acquires after readers drain
- mutex timeout removes its pending intent
- write timeout removes its pending intent
- interrupted exclusive acquire removes its pending intent on a best-effort basis
- Redis Cluster key strategy maps all per-resource script keys to one hash slot
- default key strategy remains unchanged unless configured

### ZooKeeper tests

- indefinite acquire returns promptly with `LockSessionLostException` when the waiting session is lost
- timed acquire still times out and deletes its contender node
- session close wakes an in-flight acquire
- fail-safe `SUSPENDED` behavior is explicit and tested
- contender deletion between queue eligibility and fencing issuance does not return a valid lease
- malformed base paths fail at configuration time
- root base path generates valid single-slash paths
- malformed queue child names fail with a clear backend exception
- high-contention fencing counter increments remain monotonic

## Error Handling Rules

- Ownership loss must never be downgraded to successful completion
- Session loss must wake and fail pending acquisition
- Timeout and interruption must delete local contender state when the backend still permits cleanup
- Redis pending-intent cleanup is best effort, but stale intents must expire without permanently blocking readers
- ZooKeeper skipped fencing tokens are acceptable when ownership changes during acquisition

## Documentation Updates

- Update backend README or starter README to describe Redis exclusive-intent semantics
- Document that Redis read/write progress is writer/exclusive-preferred but not FIFO fair
- Document Redis Cluster key strategy and fencing-counter migration implications
- Document ZooKeeper fail-safe `SUSPENDED` behavior and state that tolerant suspended ownership is out of scope for this pass
- Update test-suite README with new focused regression commands

## Acceptance Criteria

- New tests fail against the pre-hardening behavior and pass after implementation
- Cross-thread lease release works in the testkit
- Redis mutex no longer starves behind continuous readers in the regression test
- ZooKeeper indefinite acquire cannot remain blocked after session loss or close
- ZooKeeper never returns a lease when its contender node no longer belongs to the session
- Existing public API remains source-compatible except for backend-specific configuration additions
- `mvn test` passes for the default reactor
