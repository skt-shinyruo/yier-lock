# Spring And Observability Hardening Design

## Summary

This design hardens the Spring Boot integration and observability extension after the backend correctness review.
The work focuses on synchronous-only enforcement, proxy and method-resolution correctness, SpEL key resolution cost and safety, and optional observability overhead.

The current implementation is intentionally small and already rejects declared asynchronous return types.
The remaining risks are edge cases around Spring AOP boundaries and observability defaults:

- `@DistributedLock` can miss `@Async void` and other asynchronous boundaries that do not show up as declared async return types
- method resolution does not consistently use the most specific target method under proxies
- SpEL expressions are parsed on every invocation and evaluated in a broad context
- null SpEL results become the literal lock key `"null"`
- observability is enabled by default and always adds a logging sink
- observation wrappers record `Exception` but not `Error`

The chosen direction keeps the synchronous-only contract and avoids adding async locking.
It makes Spring integration stricter, documents AOP limitations, and makes observability cheaper to disable or run in production.

## Goals

- Reject known asynchronous Spring method boundaries before user code starts
- Resolve annotations, return types, and parameter names from the most specific target method
- Support interface and implementation method annotation scenarios intentionally
- Cache parsed SpEL expressions by method and expression
- Reject null or blank evaluated lock keys instead of silently using a shared `"null"` key
- Keep lock-key expressions compatible with existing Spring template syntax used by the project
- Make AOP ordering with `@Async` and transactions explicit
- Add observability logging and metrics toggles
- Avoid wrapping runtimes when observability has no active sink
- Record failures caused by `Throwable` while preserving original rethrow behavior
- Keep metrics low-cardinality even when diagnostic logs include lock keys

## Non-Goals

- No async, reactive, or `CompletionStage` lock scope support
- No distributed transaction integration
- No guarantee that Spring self-invocation is intercepted
- No support for untrusted runtime lock-key expressions from users or HTTP input
- No high-cardinality lock-key metrics
- No mandatory Micrometer dependency for applications that only want logging
- No automatic decoration of arbitrary custom `LockRuntime` implementations unless it can be done without double wrapping or semantic surprises

## Design Decisions

### 1. Make the Spring async guard method-aware and annotation-aware

The aspect should reject asynchronous boundaries before acquiring a lock or invoking user code.

Guard inputs:

- declared return type on the most specific target method
- declared return type on the proxied interface method
- `@Async` on the most specific target method
- `@Async` on the target class
- `@Async` on the proxied interface method or interface type when visible
- known async return categories: `CompletionStage`, `Future`, and Reactive Streams `Publisher`

When a method is rejected, throw `LockConfigurationException` with the method signature and the unsupported async category.
The exception must happen before `joinPoint.proceed()`.

`DefaultLockExecutor` should keep its actual-result async guard as defense in depth.
That guard cannot prevent user-code side effects when a method declares `Object` but returns `CompletableFuture`, so documentation must state that declared synchronous signatures are part of the contract.

### 2. Resolve the most specific method before annotation and SpEL work

Spring proxies can expose interface methods while the actual implementation method carries parameter names, bridge-method metadata, or annotations.
The integration should resolve a method model once per invocation:

- proxied method from `MethodSignature`
- target object and target class from `ProceedingJoinPoint`
- most specific method using Spring AOP utilities
- bridged method where applicable
- merged `@DistributedLock` annotation from the most specific method first, then proxied method as fallback

The aspect pointcut must support method-level `@DistributedLock` discovered on either the proxied interface method or the most specific implementation method under both JDK and CGLIB proxy modes.
Self-invocation remains unsupported because Spring AOP cannot intercept it without AspectJ weaving.

### 3. Define supported AOP boundaries explicitly

The starter should document these rules:

- self-invocation inside the same Spring bean is not intercepted by Spring AOP
- `@DistributedLock` is synchronous-only
- `@Async` and `@DistributedLock` on the same method are rejected
- methods that declare `Object` but return async objects are invalid and may only be detected after user code returns
- transaction ordering should be explicit and tested for the supported default

The aspect must implement an explicit order that runs before Spring's default async advisor.
Tests must prove that `@Async` is rejected before any asynchronous task is scheduled.
The primary requirement is that `@Async` cannot move the protected method body outside the lease scope.

### 4. Cache SpEL expressions without changing template syntax

`SpelLockKeyResolver` currently parses expressions on every invocation.
Add a `ConcurrentHashMap` cache keyed by resolved method and expression string.

Rules:

- literal expressions without `#{` still return directly without parsing
- template expressions use Spring `ParserContext.TEMPLATE_EXPRESSION`
- parsed expressions are cached after successful parse
- parse failures include the expression and method in the exception message
- cache growth is acceptable for annotation-defined expressions because the key set is bounded by application methods

The resolver should continue to support method calls already demonstrated in tests, such as `#p1.toUpperCase()`.
For that reason, a full switch to `SimpleEvaluationContext` is not required in the first implementation.
If expression restriction is added, it must be a separate breaking-change decision with tests for existing expression compatibility.

### 5. Enrich and validate the SpEL evaluation context

The evaluation context should include:

- `args`
- `p0`, `p1`, ...
- `a0`, `a1`, ...
- discovered parameter names when available
- `method`
- `target`
- `targetClass`

After evaluation:

- null result is rejected
- blank result is rejected
- non-null result is converted with `toString()`
- the final string is passed to `LockKey`, preserving API validation

Rejecting null is a safety improvement because the literal `"null"` key can accidentally serialize unrelated operations through one shared lock.

### 6. Make observability sinks independently configurable

Observability should remain easy to enable but cheap to disable.

Configuration model:

- `distributed.lock.observability.enabled` controls all decoration, default `true` to preserve current behavior
- `distributed.lock.observability.logging.enabled` controls logging sink, default `true`
- `distributed.lock.observability.metrics.enabled` controls Micrometer sink, default `true` when `MeterRegistry` is present
- `distributed.lock.observability.include-lock-key-in-logs` continues to affect diagnostic log events only

If both logging and metrics are disabled, the runtime must not be decorated.
This avoids event allocation on the hot path when observability is effectively off.

### 7. Keep metrics low-cardinality and make key behavior explicit

Micrometer metrics must not include raw lock keys.
Even if `include-lock-key-in-logs=true`, metrics tags remain limited to:

- backend
- surface
- operation
- outcome
- mode

This rule should be tested.
The property name may remain unchanged for compatibility, but documentation should clarify that it affects logs and observation events consumed by logging, not metric tags.

### 8. Reduce observability overhead on successful paths

`LoggingLockObservationSink` should check `logger.isDebugEnabled()` before doing success-path debug logging work.
The event may already contain duration, but the sink should avoid unnecessary formatting or derived values when debug logging is disabled.

For metrics, use Micrometer's normal timer lookup initially.
Timer caching is out of scope for this pass because the tag set is already bounded and no benchmark currently proves lookup overhead is material.

### 9. Record `Throwable` outcomes and rethrow exactly

Observed wrappers should record failures for `Throwable`, not only `Exception`.

Rules:

- catch `Throwable`
- publish a failure observation safely
- rethrow the same `Throwable` without wrapping
- do not swallow `InterruptedException`; preserve existing interruption behavior
- classify `Error` outcomes distinctly only if that improves diagnostics without increasing cardinality too much

The safe-publish helper must continue to prevent observation sink failures from altering lock behavior.

### 10. Clarify custom runtime decoration behavior

The current Spring extension only decorates `DefaultLockRuntime`.
That is safe because it avoids surprising custom implementations and double wrapping.

This spec keeps that default and documents the behavior.
Documentation should say:

- standard auto-configured runtimes are observed automatically
- custom `LockRuntime` beans are not automatically decorated unless they are `DefaultLockRuntime`
- users can manually wrap a custom runtime with `ObservedLockRuntime.decorate(...)`

Tests should ensure already observed runtimes are not double-decorated.

## Module Changes

### `distributed-lock-spring-boot-starter`

- refactor aspect method resolution around most-specific methods
- broaden or adjust pointcut to support supported proxy annotation scenarios
- reject `@Async` combinations before user code executes
- cache parsed SpEL expressions
- enrich SpEL variables
- reject null or blank evaluated keys
- add README documentation for proxy boundaries, self-invocation, async restrictions, and transaction order

### `distributed-lock-extension-observability`

- update observed executor/session wrappers to record `Throwable`
- ensure observation publishing remains best effort
- add tests for `AssertionError` or another controlled `Error`

### `distributed-lock-extension-observability-spring`

- add logging and metrics enablement properties
- split sink creation by logging and metrics conditions
- keep metrics conditional on `MeterRegistry`
- avoid runtime decoration when there are no active sinks
- guard debug success logging
- document custom runtime decoration behavior

## Testing Strategy

### Spring AOP tests

- declared `CompletionStage` return is rejected before invocation
- declared `Future` return is rejected before invocation
- declared reactive `Publisher` return is rejected before invocation when publisher type is present
- `@Async void` with `@DistributedLock` is rejected before invocation
- class-level `@Async` with `@DistributedLock` is rejected
- declared `Object` returning `CompletableFuture` is rejected by executor defense-in-depth and documented as after-invocation detection
- JDK proxy with annotation on interface method is supported
- JDK proxy with annotation on implementation method is supported
- CGLIB proxy with implementation method annotation works
- self-invocation is documented and covered by a negative test

### SpEL tests

- literal key bypasses parser
- structured template expression still resolves
- parameter names work on most-specific methods
- interface proxy parameter-name behavior is covered
- `#method`, `#target`, and `#targetClass` variables resolve
- null expression result is rejected
- blank expression result is rejected
- repeated invocation reuses cached parsed expression
- parse errors include method and expression context

### Observability tests

- observability disabled leaves the runtime undecorated
- logging disabled omits logging sink
- metrics disabled omits Micrometer sink even when `MeterRegistry` is present
- no active sinks avoids decoration or uses true no-op behavior
- success debug logging does no sink-side derived formatting when debug is disabled
- action throwing `AssertionError` produces a failure observation and rethrows the same error
- acquire throwing a runtime backend error is recorded and rethrown
- metrics tags never include raw lock key even when key logging is enabled
- custom `LockRuntime` behavior matches documented decoration rules

## Error Handling Rules

- Unsupported async method shapes fail with `LockConfigurationException` before lock acquisition when detectable from annotations or declared types
- Actual async results remain a defense-in-depth failure after invocation
- SpEL parse and evaluation failures must include enough method context to diagnose the annotation
- Null or blank lock keys are configuration errors
- Observation sink failures must not change lock operation results
- `Throwable` from user code must be observed and rethrown unchanged

## Documentation Updates

- Update starter README with proxy, self-invocation, `@Async`, and transaction-order rules
- Update observability README or starter README with logging/metrics toggles
- Clarify that lock keys may appear in diagnostic logs only when enabled and never in metrics tags
- Do not add `LockContext` examples in this pass unless the separate API hardening work has already made that API public

## Acceptance Criteria

- Spring async edge cases are rejected or documented with tests
- SpEL parser cache is covered by tests and does not change existing supported expressions
- Null evaluated keys no longer collapse to the shared `"null"` lock key
- Observability can be disabled without wrapping the runtime
- Metrics remain low-cardinality
- Throwable failures are observed without changing thrown types
- `mvn -pl distributed-lock-spring-boot-starter,distributed-lock-extension-observability,distributed-lock-extension-observability-spring -am test` passes
