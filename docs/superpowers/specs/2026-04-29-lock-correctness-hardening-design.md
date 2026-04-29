# Lock Correctness Hardening Design

## Status

Draft for review.

## Context

This project is a Java 17 multi-module distributed lock framework with API/Core/Runtime modules, Redis and ZooKeeper backends, Spring Boot integration, observability, examples, and a testkit. A review found several implementation risks:

- Redis `LeasePolicy.fixed(...)` is currently renewed like backend-default leases.
- Redis Lua scripts require same-slot keys in Redis Cluster, but Spring Boot users cannot configure `RedisKeyStrategy.HASH_TAGGED`.
- Redis renewals use one daemon scheduler and one synchronous connection for all backend sessions and leases.
- ZooKeeper waits install a watcher but still wake by 250 ms polling.
- Spring async protection can reject `Object` methods that return async values only after user code already ran.
- Root `mvn test` enters Redis Testcontainers tests and fails when Docker is not available.

## Goals

- Make lease semantics explicit and enforceable with a documented compatibility path for existing users.
- Make Redis Cluster-safe key layout configurable through Spring Boot.
- Reduce Redis renewal starvation risk while preserving the existing synchronous backend API.
- Make ZooKeeper waiters wake promptly on watched predecessor deletion.
- Move Spring async-boundary failures as early as possible and document the remaining unavoidable boundary.
- Split Redis integration tests from the default local test path so contributors can run non-Docker verification reliably.

## Non-Goals

- Do not rewrite the public lock API into an async/reactive API.
- Do not replace Lettuce or Curator.
- Do not implement strict Redis FIFO fairness.
- Do not make ZooKeeper support fixed-duration leases; ZooKeeper remains session-bound and advertises `withoutFixedLeaseDuration()`.
- Do not add tracing or health checks beyond existing observability extension behavior.

## Design Summary

Recommended approach: keep `LeasePolicy` API source-compatible, introduce explicit Redis renewal behavior, expose Redis key strategy and renewal settings in configuration, reject ambiguous Spring async boundaries by default, improve ZooKeeper waiting, and restructure tests through Maven profiles.

Alternatives considered:

- Rename `LeasePolicy.fixed` to distinguish fixed TTL from renewable TTL. This is clearer but breaks API surface and requires a migration story.
- Leave runtime behavior unchanged and only document fixed leases as renewable. This avoids code change but preserves a misleading API and makes `leaseFor` unsafe for callers expecting a hard maximum.
- Replace Redis backend internals with asynchronous Lettuce commands. This improves scalability but is too large for this hardening pass.

The selected design preserves source compatibility, changes unsafe defaults where correctness depends on them, and provides explicit compatibility switches for users who need migration time.

## Redis Lease Semantics

### Current Problem

`RedisBackendSession.acquire(...)` always calls `lease.startRenewal()` after acquisition. `RedisLease.startRenewal()` refreshes mutex, write, and read leases regardless of whether the request used `LeasePolicy.backendDefault()` or `LeasePolicy.fixed(...)`. Current tests assert that fixed leases renew, which conflicts with the natural reading of "fixed lease duration".

### Required Behavior

Redis must distinguish two concepts:

- Backend-default lease: renewable watchdog lease. This remains the default for `LockRequest` and blank Spring `leaseFor`.
- Fixed lease: finite Redis TTL requested by the caller. By default it must not auto-renew after the requested duration.

To preserve compatibility for existing users, Redis gets a compatibility option:

- `RedisBackendConfiguration.fixedLeaseRenewalEnabled`, default `false` for new constructors.
- Spring property `distributed.lock.redis.fixed-lease-renewal-enabled`, default `false`.

When `fixedLeaseRenewalEnabled=false`, fixed Redis leases expire naturally unless released first. After expiry:

- `lease.isValid()` returns `false`.
- `lease.state()` may remain `ACTIVE` until inspected, but `release()` must mark it lost and throw `LockOwnershipLostException`.
- another session can acquire the key after Redis TTL expiry.

When `fixedLeaseRenewalEnabled=true`, existing behavior is preserved: fixed leases are renewed using the fixed duration as the renewal TTL.

### Implementation Shape

- Extend `RedisBackendConfiguration` with the boolean flag while preserving existing constructors.
- Pass a `renewable` boolean into `RedisLease`.
- In `RedisBackendSession.tryAcquire(...)`, set `renewable` to:
  - `true` for `LeaseMode.BACKEND_DEFAULT`
  - `configuration.fixedLeaseRenewalEnabled()` for `LeaseMode.FIXED`
- Only call `lease.startRenewal()` when `lease.renewable()` is true.
- Replace existing Redis fixed-lease tests that expect renewal with tests for natural expiry, and add one compatibility-mode test that proves fixed renewal can still be enabled.
- Update Spring starter README to state that `leaseFor` is a hard TTL by default on Redis.

## Redis Cluster Key Strategy

### Current Problem

Redis scripts operate on owner, writer, readers, fence, and pending-writer keys in one script call. Redis Cluster requires all script keys to be in the same slot. `RedisKeyStrategy.HASH_TAGGED` exists but Spring Boot auto-configuration only builds `RedisBackendConfiguration(uri, leaseSeconds)`, which defaults to `LEGACY`.

### Required Behavior

Expose Redis key strategy through Spring Boot:

```yaml
distributed:
  lock:
    redis:
      uri: redis://127.0.0.1:6379
      lease-time: 30s
      key-strategy: hash-tagged
```

Accepted values:

- `legacy`
- `hash-tagged`

Default remains `legacy` for compatibility.

### Implementation Shape

- Add `RedisKeyStrategy keyStrategy = RedisKeyStrategy.LEGACY` to `RedisDistributedLockProperties`.
- Bind Boot relaxed values into the enum.
- Build `RedisBackendConfiguration(uri, leaseSeconds, keyStrategy, fixedLeaseRenewalEnabled, renewal...)`.
- Add Spring auto-configuration tests that assert `HASH_TAGGED` is passed through.
- Update README with cluster guidance: Redis Cluster deployments should use `hash-tagged`.

## Redis Renewal Scalability

### Current Problem

One `ScheduledExecutorService` renews all sessions and all leases. All operations use one synchronous Lettuce connection. Slow Redis calls or many leases can delay renewals enough to lose ownership.

### Required Behavior

Make renewal execution configurable and less fragile without rewriting the backend:

- Default renewal pool size: `max(2, availableProcessors / 2)` capped at 8.
- Spring property `distributed.lock.redis.renewal-pool-size`, default omitted and computed.
- Programmatic configuration accepts an explicit positive renewal pool size.
- Renewal thread names include an index, for example `redis-lock-renewal-1`.
- Renewal tasks still cancel on lease release/session close/session loss.

The synchronous Redis connection remains shared for now. The spec explicitly records this as residual risk. If renewal pool growth exposes Lettuce sync-command contention, the follow-up is a separate async or connection-pool design.

### Implementation Shape

- Extend `RedisBackendConfiguration` with optional renewal pool size.
- Replace `Executors.newSingleThreadScheduledExecutor` with `newScheduledThreadPool(configuration.effectiveRenewalPoolSize(), threadFactory)`.
- Add unit tests for configuration validation and effective pool size.
- Add a Redis test with multiple short backend-default leases to verify renewals stay valid beyond initial TTL when Docker is available.

## ZooKeeper Wait Wakeup

### Current Problem

`ZooKeeperBackendSession.awaitNodeDeletion(...)` registers a watcher that counts down a latch, but the waiting thread sleeps on `terminalMonitor.wait(...)`. The watcher does not notify that monitor, so deletion is observed only after up to 250 ms.

### Required Behavior

Waiters should wake when either:

- watched predecessor is deleted;
- session becomes `SUSPENDED`, `LOST`, or closed;
- timed wait deadline expires.

### Implementation Shape

- Replace the current latch/monitor split with a wait object whose watcher calls `notifyAll()`.
- Keep the bounded 250 ms wait as a fallback for missed events and deadline accounting.
- After waking, always call `ensureActive(request)` before returning to the acquire loop.
- Add a test that holds a predecessor, starts a waiter, releases the predecessor, and asserts the waiter acquires well under the previous polling ceiling. Use a threshold with margin, for example 150 ms after release on local test server.

## Spring Async Boundary

### Current Problem

Methods declared as `CompletionStage`, `Future`, reactive `Publisher`, or annotated with `@Async` are rejected before invocation. A method declared as `Object` can still create and return a `CompletableFuture`; this is rejected only after user code executed.

### Required Behavior

Spring should reject visible async boundaries before invocation and reject ambiguous dynamic return types by default.

Enhance visible checks:

- Reject methods declared with return type `Object` when `@DistributedLock` is present, before invocation.
- Reject Kotlin coroutine continuation signatures if present on the classpath.
- Reject Reactor and Reactive Streams return types by declared return type when present.

Add Spring property `distributed.lock.spring.annotation.allow-dynamic-return-type`, default `false`. When set to `true`, methods declared as `Object` are allowed and the executor keeps its defense-in-depth check for dynamic async return values. In that compatibility mode, README must state that user code has already run by the time dynamic async results can be detected. The recommended user fix is to use a precise synchronous declared return type or remove `@DistributedLock`.

### Implementation Shape

- Keep `DistributedLockAspect.ensureSynchronous(...)` as the early guard.
- Extend `DistributedLockProperties.Spring.Annotation` with `allowDynamicReturnType`, default `false`.
- Add tests proving `Object` return type is rejected before invocation by default.
- Add tests proving compatibility mode preserves existing executor defense-in-depth behavior.
- Add README note under AOP boundaries explaining why `Object` return type is rejected by default and what the compatibility flag means.

## Redis Test Split

### Current Problem

Root `mvn test` enters Redis Testcontainers tests. On machines without Docker, this fails before later modules can run. The test-suite README claims Redis checks require a reachable local Redis instance, while actual tests use Testcontainers.

### Required Behavior

Default local verification must not require Docker:

- Root `mvn test` runs unit tests and non-Docker integration tests.
- Redis Testcontainers tests run under an explicit profile, for example `-Predis-integration`.
- CI can enable the profile where Docker is available.

### Implementation Shape

- Configure Redis module Surefire includes/excludes so Testcontainers-backed tests are skipped unless `redis-integration` is active.
- Rename or tag Redis integration tests consistently. Preferred: JUnit tag `redis-integration` plus Maven profile activation.
- Keep pure Redis unit tests such as key strategy/configuration tests in the default suite.
- Update `distributed-lock-test-suite/README.md` commands:
  - default: `mvn test`
  - Redis integration: `mvn -Predis-integration -pl distributed-lock-redis -am test`
  - all local non-Docker focused checks: core/runtime/testkit/ZooKeeper/Spring commands.

## Compatibility and Migration

- Existing constructors for `RedisBackendConfiguration` remain source-compatible.
- Existing Boot Redis properties remain valid.
- New fixed-lease behavior is stricter. Users relying on renewable fixed leases must set `distributed.lock.redis.fixed-lease-renewal-enabled=true`.
- New Spring annotation behavior rejects `Object` return type by default. Users relying on dynamic return types must set `distributed.lock.spring.annotation.allow-dynamic-return-type=true` during migration.
- `LEGACY` key strategy remains default to avoid changing Redis key names unexpectedly.
- No change to ZooKeeper public API.

## Testing Strategy

Run these commands after implementation:

```bash
mvn test
mvn -pl distributed-lock-zookeeper -am test
mvn -pl distributed-lock-spring-boot-starter,distributed-lock-extension-observability,distributed-lock-extension-observability-spring -am test
mvn -Predis-integration -pl distributed-lock-redis -am test
```

Expected outcomes:

- The first three commands pass without Docker.
- The Redis integration command passes only where Docker/Testcontainers is available.
- If Docker is unavailable, Redis integration failure is environmental and must not block default verification.

## Acceptance Criteria

- Redis fixed leases expire without auto-renewal by default, and a test proves a second session can acquire after the fixed TTL.
- Redis compatibility mode can renew fixed leases when explicitly enabled.
- Spring Boot Redis configuration exposes `key-strategy`, `fixed-lease-renewal-enabled`, and `renewal-pool-size`.
- Redis Cluster documentation tells users to choose `hash-tagged`.
- Redis renewal executor uses configurable multi-thread scheduling with validated positive pool size.
- ZooKeeper waiter wakeup test shows predecessor deletion wakes promptly rather than depending on 250 ms polling.
- Spring async guard tests prove `Object` return types are rejected before invocation by default, and compatibility mode is documented and tested.
- `mvn test` no longer requires Docker.
- Redis integration tests are still available through an explicit profile.

## Residual Risks

- Redis sync-command sharing can still bottleneck under heavy renewal load; this design mitigates scheduler starvation but does not eliminate command serialization.
- Strict fixed-lease semantics can surprise users who previously relied on renewal through `leaseFor`; the compatibility flag and README migration note address this.
- Timing-based ZooKeeper wakeup tests need generous thresholds to avoid local machine flakiness.
