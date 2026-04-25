# Distributed Lock Bootstrap and Capability Hardening Design

## Summary

This design hardens the first stage of the distributed lock architecture around two failure-prone edges:

- runtime and Spring bootstrap still rely on implicit backend selection behavior
- backend capability metadata is too weak to express the safety guarantees already assumed by the public API

The chosen direction is intentionally narrow. This stage does not redesign the lease/session model, async execution, or backend acquisition algorithms. It makes backend activation explicit, removes local-development defaults from backend modules, and expands runtime capability checks so startup can reject unsafe backend compositions before any lock is acquired.

This change is an intentional breaking change for SPI consumers and for programmatic callers that currently depend on implicit backend auto-selection or zero-argument backend module construction.

## Goals

- Require explicit backend selection in both programmatic and Spring bootstrap paths
- Preserve `ServiceLoader` discovery only as a backend registry, not as an auto-selection mechanism
- Remove `defaultLocal()`-driven backend activation from Redis and ZooKeeper modules while preserving discovery through explicit ServiceLoader shims
- Expand `BackendCapabilities` so runtime can validate fencing and renewable-session guarantees
- Fail fast at startup when a selected backend does not satisfy kernel safety requirements
- Update examples, tests, and documentation so the explicit bootstrap model is the only supported path

## Non-Goals

- No async API or execution-model redesign
- No removal of `CurrentLockContext`
- No internal SPI decoupling from public API shapes in this stage
- No Redis or ZooKeeper lock protocol redesign
- No changes to observability architecture beyond keeping it compatible with the new bootstrap rules

## Problem Statement

### 1. Runtime still guesses the backend

`LockRuntimeBuilder` currently discovers backend modules and silently selects one when exactly one candidate exists. That behavior makes backend activation depend on classpath shape instead of application intent.

Consequences:

- adding or removing a jar can change runtime behavior without code or config changes
- local development defaults can leak into production bootstrap
- programmatic and Spring callers do not have one explicit backend-selection contract

### 2. Typed backend activation and discovery are not separated clearly

This stage needs a hard boundary between:

- typed backend modules that own real backend configuration
- discovery-only providers that exist only so `ServiceLoader` can advertise candidate backend ids

Without that separation, the runtime can still discover a backend on the classpath and attempt to create it without caller-declared typed configuration. In the original bootstrap model, `RedisBackendModule()` and `ZooKeeperBackendModule()` delegated to `defaultLocal()` backend configurations. This stage removes that path and replaces it with discovery-only providers that can identify a backend but cannot materialize one.

### 3. Capability metadata under-describes backend safety

The current `BackendCapabilities` record only describes lock-mode availability:

- `mutexSupported`
- `readWriteSupported`

That is not enough for the actual kernel contract anymore. The public API already assumes:

- successful acquisitions return fencing tokens
- sessions can remain valid over the lifetime of a lease through renewable backend ownership

If a backend cannot provide those properties, startup should reject it before lock usage begins.

### 4. Startup failures are too late and too vague

Today runtime validates only that `capabilities()` is non-null and that a backend id can be matched. There is no explicit rule that says:

- backend id must be configured
- the selected backend must support fencing
- the selected backend must support renewable sessions

That leaves core assumptions unenforced at the boundary where they should be checked.

## Design Decisions

### 1. Backend selection becomes mandatory and explicit

`LockRuntimeBuilder` will require a non-blank backend id for every successful `build()` call.

New runtime rules:

- if `backend(String)` was not called with a non-blank value, `build()` fails with `LockConfigurationException`
- discovered backend modules are treated as a candidate registry only
- runtime never auto-selects a backend based on candidate count
- duplicate backend ids still fail fast
- requested backend id must resolve to exactly one discovered or explicitly provided module

Consequences:

- classpath contents no longer activate a backend by accident
- programmatic bootstrap and Spring bootstrap share one model: caller declares backend id, runtime resolves it
- `ServiceLoader` remains useful for modularity without being allowed to make configuration decisions

### 2. Typed backend modules require explicit configuration; discovery providers become shims

`RedisBackendModule` and `ZooKeeperBackendModule` will drop their no-argument constructors.

Rules:

- all module instances must be created with explicit typed configuration
- `RedisBackendConfiguration.defaultLocal()` and `ZooKeeperBackendConfiguration.defaultLocal()` are removed in the same stage
- `RedisServiceLoaderBackendModule` and `ZooKeeperServiceLoaderBackendModule` remain zero-argument providers only for `ServiceLoader` discovery
- discovery-only providers must advertise `id()` and `capabilities()` but fail from `createBackend()` with a `LockConfigurationException` that tells the caller to construct the typed backend module explicitly
- examples, tests, and any helper code that instantiate backend modules must pass explicit configuration

This preserves typed configuration ownership inside each backend module while eliminating the remaining implicit localhost bootstrap path.

### 3. `BackendCapabilities` expands to represent safety-critical guarantees

`BackendCapabilities` will become:

```java
public record BackendCapabilities(
    boolean mutexSupported,
    boolean readWriteSupported,
    boolean fencingSupported,
    boolean renewableSessionsSupported
) {}
```

Standard capability rules for the current repository:

- Redis advertises all four capabilities as `true`
- ZooKeeper advertises all four capabilities as `true`
- in-memory or test backends must declare the real guarantees they simulate

This stage keeps the capability model deliberately small. It does not add fairness, recovery, or topology metadata.

### 4. Runtime validates kernel-required capabilities before backend creation

`LockRuntimeBuilder.build()` will validate the selected module before calling `createBackend()`.

Required conditions:

- `capabilities()` must be non-null
- `mutexSupported` must be `true`
- `fencingSupported` must be `true`
- `renewableSessionsSupported` must be `true`

`readWriteSupported` remains optional because the public API can still reject unsupported read/write requests at acquisition time.

Failure behavior:

- capability violations fail with `LockConfigurationException`
- the error message must identify the backend id and list each missing required capability

This keeps mode flexibility for backends while enforcing the kernel guarantees this repository now treats as non-negotiable.

### 5. Spring bootstrap adopts the same explicit contract

The generic Spring starter keeps its current responsibility boundary: it consumes generic lock properties and `BackendModule` beans, but it does not know Redis or ZooKeeper configuration fields.

New generic starter rules:

- if `distributed.lock.enabled=true`, `distributed.lock.backend` must be configured with a non-blank value
- startup fails if the configured backend id does not resolve to a `BackendModule` bean
- startup fails if the resolved module lacks required capabilities
- the generic starter resolves only application-context `BackendModule` beans and never falls back to `ServiceLoader` discovery

Backend-specific auto-configuration modules keep their current role:

- bind typed backend properties
- instantiate typed backend configuration
- publish a `BackendModule` bean for the selected backend

This preserves the clean separation between generic and backend-specific Spring code while removing the last implicit startup path.

### 6. `ServiceLoader` is preserved but demoted

`ServiceLoaderBackendRegistry` remains in the architecture because it is still useful for modular runtime packaging and examples.

Its role is narrowed to:

- discover candidate backend modules
- discover zero-argument provider shims that advertise backend identity and capabilities
- return them to runtime unchanged

Its role explicitly does not include:

- selecting a backend
- preferring one backend over another
- authorizing build success when the caller did not declare a backend id
- manufacturing a fully configured Redis or ZooKeeper backend without typed caller input

This is the key behavioral distinction of the stage: discovery survives, auto-selection does not.

### 7. This stage is a documented breaking change

The repository will treat this stage as an explicit contract break.

Breakage includes:

- programmatic callers that previously relied on single-backend auto-selection must now call `.backend("...")`
- callers that used `new RedisBackendModule()` or `new ZooKeeperBackendModule()` must now pass typed configuration
- any custom `BackendModule` implementations must update to the expanded `BackendCapabilities` signature

No compatibility shim is added in this stage. The purpose of the change is to remove ambiguity, not to preserve ambiguous bootstrap behavior.

## Module Changes

### `distributed-lock-runtime`

Modify:

- `LockRuntimeBuilder`
- `BackendCapabilities`
- runtime tests that cover backend selection and capability validation

Responsibilities after change:

- require explicit backend id
- discover candidate modules
- validate unique ids
- validate required capabilities
- construct `DefaultLockClient` and `DefaultLockExecutor` only after bootstrap checks pass

### `distributed-lock-redis`

Modify:

- `RedisBackendModule`
- `RedisServiceLoaderBackendModule`
- `RedisBackendConfiguration`
- tests that assume zero-argument typed module construction or permissive ServiceLoader backend creation

Responsibilities after change:

- own explicit typed Redis configuration
- advertise complete backend capabilities
- stop exposing local-default bootstrap helpers
- keep ServiceLoader discovery available without allowing backend creation from discovery alone

### `distributed-lock-zookeeper`

Modify:

- `ZooKeeperBackendModule`
- `ZooKeeperServiceLoaderBackendModule`
- `ZooKeeperBackendConfiguration`
- tests that assume zero-argument typed module construction or permissive ServiceLoader backend creation

Responsibilities after change:

- own explicit typed ZooKeeper configuration
- advertise complete backend capabilities
- stop exposing local-default bootstrap helpers
- keep ServiceLoader discovery available without allowing backend creation from discovery alone

### `distributed-lock-spring-boot-starter`

Modify:

- `DistributedLockAutoConfiguration`
- integration tests and README content that assume optional backend property behavior

Responsibilities after change:

- require explicit backend property when enabled
- continue consuming only generic properties plus `BackendModule` beans
- never treat classpath ServiceLoader providers as Spring backend beans
- delegate backend-specific configuration ownership to backend Spring modules

### `distributed-lock-redis-spring-boot-autoconfigure`

Modify only as needed:

- tests and documentation expectations that depend on old capability shape or optional backend selection

The bean graph remains thin and typed.

### `distributed-lock-zookeeper-spring-boot-autoconfigure`

Modify only as needed:

- tests and documentation expectations that depend on old capability shape or optional backend selection

The bean graph remains thin and typed.

### `distributed-lock-examples`

Modify:

- all programmatic examples to pass explicit typed backend configuration
- any example or sample docs that imply backend auto-selection

Examples become the reference for the new explicit bootstrap contract.

### `distributed-lock-testkit`

Modify:

- helper backend modules to expose the expanded capability record explicitly

This keeps test-only modules aligned with runtime SPI without pulling internal SPI redesign into this stage.

### `distributed-lock-benchmarks`

Modify only as needed:

- benchmark helper bootstrap paths that need explicit backend selection to stay aligned with the starter contract

Benchmarks are not part of the runtime API surface, but they must compile and bootstrap against the same explicit configuration model.

## Startup Validation Rules

### Programmatic runtime

For `LockRuntimeBuilder.build()` to succeed:

1. `backendId` must be configured and non-blank
2. candidate backend ids must be unique
3. one module must exist whose `id()` matches `backendId`
4. that module's `capabilities()` must be non-null
5. `mutexSupported` must be `true`
6. `fencingSupported` must be `true`
7. `renewableSessionsSupported` must be `true`

If any condition fails, runtime throws `LockConfigurationException` before `createBackend()` is called.

### Spring runtime

For `DistributedLockAutoConfiguration` to succeed when `distributed.lock.enabled=true`:

1. `distributed.lock.backend` must be configured and non-blank
2. the application context must provide a matching backend module for that backend id
3. the matching backend module must satisfy the same required capabilities as programmatic runtime

`ServiceLoader` discovery does not participate in this Spring path. If no matching `BackendModule` bean exists, application startup fails during bean creation.

## Testing Strategy

This stage is behavior-driven and must be test-first.

Required runtime coverage:

- `build()` fails when backend id is omitted, even if exactly one backend module is available
- `build()` fails when backend id is blank
- `build()` fails when requested backend id is missing
- `build()` fails when duplicate backend ids are present
- `build()` fails when required capabilities are absent
- `build()` succeeds when backend id is explicit and required capabilities are present

Required backend-module coverage:

- Redis module requires explicit configuration
- ZooKeeper module requires explicit configuration
- Redis and ZooKeeper advertise the expanded capability record correctly
- ServiceLoader-discovered Redis and ZooKeeper providers are discoverable but fail with explicit typed-configuration guidance when `createBackend()` is called

Required Spring coverage:

- generic starter fails fast when enabled and backend property is missing
- generic starter fails fast when configured backend module is absent
- generic starter ignores ServiceLoader-only backends when Spring provides no `BackendModule` beans
- Redis starter path still succeeds with explicit backend property plus Redis backend module
- ZooKeeper starter path still succeeds with explicit backend property plus ZooKeeper backend module

Required example/documentation coverage:

- examples compile with explicit backend id plus typed backend module construction
- README snippets and starter docs no longer show implicit backend activation

## Rollout Notes

- This stage should land before internal SPI decoupling or async design work
- observability modules should remain unchanged except for capability-constructor fallout
- benchmark and example helper code should be updated in the same series so repository documentation does not point to removed bootstrap paths

## Open Follow-Up After This Stage

This design intentionally leaves these problems for later stages:

- decoupling internal backend SPI from public API records
- deciding whether the synchronous convenience layer should stay thread-bound forever or gain a separate async execution surface
- reviewing Redis wait tuning and ZooKeeper session cost models

Those are real architecture issues, but they are intentionally out of scope for this first hardening step.
