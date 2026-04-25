# Redis Read/Write Writer Starvation Hardening Design

## Summary

This design closes a liveness gap in the Redis read/write lock implementation.

Redis currently allows new readers to acquire whenever no mutex owner and no write owner exist. A writer that is waiting behind active readers does not publish any writer intent, so continuous read traffic can keep the reader set non-empty and starve the writer indefinitely.

The chosen direction is to add a Redis-backed pending-writer marker for `WRITE` acquisition attempts. Readers will stop entering while a live writer intent exists, allowing the existing reader set to drain and giving the writer a chance to acquire. This provides writer preference under contention, not strict FIFO fairness.

## Goals

- Prevent unbounded Redis writer starvation under continuous read traffic
- Keep Redis read/read concurrency when no writer is waiting
- Preserve existing mutex, write, read, fencing, renewal, and session-loss semantics
- Keep the Redis protocol small and script-driven
- Document the exact fairness guarantee as writer-preference rather than FIFO fairness
- Add tests that reproduce the starvation pattern and prove readers are gated by pending writers

## Non-Goals

- No backend-neutral fair lock abstraction
- No strict FIFO queue for Redis contenders
- No Redis pub/sub or blocking wait redesign in this stage
- No change to ZooKeeper's sequential-node read/write protocol
- No public API change unless capability metadata is explicitly extended for documentation clarity
- No removal of timed polling from Redis acquisition loops

## Problem Statement

### 1. Readers ignore waiting writers

`READ_ACQUIRE_SCRIPT` checks:

- reader key type safety
- active mutex owner
- active write owner

It does not check whether any writer is already waiting for the same lock key. If there is no active write owner at that instant, a new reader may join the reader zset.

### 2. Writers only poll

`WRITE_ACQUIRE_SCRIPT` succeeds only when:

- no active mutex owner exists
- no active write owner exists
- the reader zset is empty after stale-reader cleanup

When active readers exist, the writer returns failure to Java, sleeps for up to 25 ms, and tries again. That retry loop leaves no durable signal in Redis that would block later readers.

### 3. The capability model does not describe progress semantics

`BackendCapabilities` currently describes support for:

- mutex mode
- read/write mode
- fencing
- renewable sessions

It does not say whether read/write mode is non-fair, writer-preferred, or FIFO. Redis advertises the same standard capabilities as ZooKeeper even though their progress characteristics differ.

### 4. Current tests cover exclusion, not writer progress

Existing contract coverage proves:

- readers can share a key
- writer times out while a reader is actively held
- reader times out while a writer is actively held
- mutex and read/write modes exclude each other

Those tests do not cover a writer waiting while new readers continuously arrive.

## Design Decisions

### 1. Add a per-key pending-writer zset

Redis will maintain a new per-lock-key sorted set:

```java
private static String pendingWritersKey(String key) {
    return "lock:%s:write:pending".formatted(key);
}
```

Members are writer intent ids. Scores are expiration timestamps in Redis server milliseconds.

Writer intent values should be stable for one acquisition call, not regenerated on every retry. A simple value is:

```text
<sessionId>:<writerAttemptId>
```

`writerAttemptId` can be a UUID generated once at the start of a `WRITE` acquire call.

Using a zset instead of a scalar key supports multiple waiting writers and stale-intent cleanup without requiring client-side ownership of a single pending marker.

### 2. Register writer intent before checks that can make the writer wait

`WRITE_ACQUIRE_SCRIPT` will receive five keys:

1. mutex owner key
2. write owner key
3. reader owners zset key
4. fence key
5. pending writers zset key

The script will:

1. compute Redis server time
2. remove expired reader owners
3. remove expired pending writer intents
4. add or refresh this writer's pending intent
5. set an expiry on the pending-writer zset
6. fail if mutex or write owner exists
7. fail if any readers remain
8. remove this writer's pending intent
9. issue the fencing token
10. set the write owner key
11. return the fence

The writer must publish intent before any conflict check that can make it wait. This matters when a writer arrives behind an active writer or mutex owner: after the active owner releases, new readers should still see the pending writer and wait instead of slipping in before the waiting writer's next retry.

The pending intent score should use the Redis server clock. The simplest first version can score the intent at `nowMs + leaseSeconds * 1000` and refresh that score on each retry. The zset itself should receive a matching `PEXPIRE` so an abandoned pending-writer key is eventually removed after all member scores expire.

This keeps stale intents bounded if a process dies while waiting.

### 3. Gate new readers when any live writer intent exists

`READ_ACQUIRE_SCRIPT` will also receive the pending writers key.

The script will:

1. compute Redis server time
2. remove expired reader owners
3. remove expired pending writer intents
4. fail if mutex or write owner exists
5. fail if pending writer zset cardinality is greater than zero
6. add the reader owner to the reader zset
7. issue the fencing token

This is the key starvation fix: once a writer begins waiting, later readers stop entering, so existing readers can drain.

### 4. Clean up writer intent on terminal acquisition paths

The writer acquire loop must remove its pending intent when it stops trying without acquiring.

Cleanup is required when:

- timed wait expires
- thread is interrupted
- backend command throws after intent was registered
- session closes while an acquisition is in progress

Cleanup can use a small Lua script or `ZREM` with the writer intent member. The cleanup path should be best-effort; failure to cleanup is bounded by the pending-intent zset score expiration.

Successful writer acquisition removes its own pending intent inside `WRITE_ACQUIRE_SCRIPT` before setting the write owner.

### 5. Mutex behavior remains exclusive but not writer-intent-aware

The pending-writer marker is for read/write liveness, not general mutex fairness.

Rules:

- active mutex still blocks read and write
- active readers and active writer still block mutex
- pending writers do not block mutex acquisition unless the mutex script is deliberately extended later

This keeps the change scoped to the reported writer starvation problem. If mutex fairness becomes a requirement, that should be a separate design because it affects all mode interactions.

### 6. Capability and documentation wording becomes explicit

The public-facing behavior should be documented as:

- Redis read/write locks are writer-preferred once a writer has registered intent
- Redis read/write locks are not strict FIFO fair locks
- readers that arrive after a live writer intent must wait
- readers that already hold leases continue until release or lease expiration
- multiple waiting writers are not ordered by a FIFO queue; Redis polling and script execution decide which writer wins after readers drain

The current `BackendCapabilities` record does not need to change to implement the fix. If the project wants capability metadata to expose progress semantics, add a follow-up field such as:

```java
ReadWriteProgressPolicy readWriteProgressPolicy
```

with values like `NONE`, `WRITER_PREFERRED`, and `FIFO`. That is optional because runtime does not currently use progress policy for validation.

## Module Changes

### `distributed-lock-redis`

Modify:

- `RedisLockBackend`

Responsibilities after change:

- add `pendingWritersKey(String)`
- pass pending-writer key into read and write acquire scripts
- create a stable writer attempt id for each `WRITE` acquire call
- register and refresh writer intent from the write acquire script
- make read acquire fail while live writer intents exist
- clean up writer intent on timeout, interruption, backend failure, and successful acquisition
- delete empty pending-writer zsets after cleanup when practical

### `distributed-lock-testkit`

Modify contract coverage only if the writer-progress behavior should be backend-wide.

If only Redis is targeted in this stage, keep the new starvation regression test in `distributed-lock-redis` so the shared contract does not impose writer preference on all backends.

### `distributed-lock-runtime`

No required runtime change.

Optional follow-up:

- extend `BackendCapabilities` or add internal metadata for read/write progress policy
- avoid using that metadata for startup rejection until a clear cross-backend contract exists

### `distributed-lock-api`

No required API change.

Optional documentation-only updates:

- clarify that `READ` and `WRITE` define compatibility, not global fairness
- point backend-specific progress semantics to backend documentation

### Documentation

Modify:

- Spring starter README or backend documentation surfaces that describe lock modes
- Redis examples or README if a Redis-specific doc surface exists

Required wording:

- Redis read/write mode is writer-preferred, not FIFO fair
- pending writer intent prevents later readers from entering
- existing readers are not revoked and can continue until release or expiration

## Testing Strategy

### Redis starvation regression

Add a Redis-specific test that proves a writer can acquire while read traffic continues.

Shape:

1. start one long-lived reader on key `redis:rw:writer-progress`
2. start a writer acquisition with a bounded wait
3. wait until Redis shows a pending writer intent for the key
4. repeatedly attempt short-lived readers from other sessions
5. assert those later readers do not acquire while writer intent is live
6. release the original reader
7. assert the writer acquires before its wait timeout

This test should fail on the current implementation because later readers can keep entering while the writer waits.

### Pending intent cleanup tests

Add focused Redis tests for cleanup:

- writer timeout removes its pending intent
- writer interruption removes its pending intent
- successful writer acquisition removes its pending intent
- expired pending intents do not block readers forever

The expired-intent test should use a short lease configuration so it does not slow the suite.

### Existing contract regression

Keep existing Redis contract coverage passing:

- concurrent readers still share when no writer is waiting
- writer still times out while a reader is held and no timeout window allows drain
- reader still times out while active writer is held
- mutex remains mutually exclusive with read/write owners
- fencing tokens remain monotonic across modes
- stale reader cleanup still allows later writer acquisition

### Documentation checks

No automated docs tooling is required in this stage, but tests or review should verify examples do not describe Redis read/write mode as FIFO fair.

## Rollout Notes

- Land this after the current cross-mode Redis correctness changes, because both touch the Redis acquire scripts and key lists.
- Keep the script change small and reviewable; do not combine it with Redis wait-loop or pub/sub redesign.
- Prefer Redis server time inside Lua scripts for all pending-intent expiration decisions.
- Use best-effort cleanup plus TTL-based expiry so a failed cleanup cannot permanently block readers.
- Call out the semantic change in release notes: under writer contention, new Redis readers may wait where they previously acquired immediately.

## Open Follow-Up After This Stage

- Decide whether `BackendCapabilities` should expose read/write progress semantics
- Decide whether Redis should eventually use a true FIFO contender queue
- Decide whether pending writers should also block mutex acquisition
- Review whether Redis polling should be replaced with notification-based wakeups
