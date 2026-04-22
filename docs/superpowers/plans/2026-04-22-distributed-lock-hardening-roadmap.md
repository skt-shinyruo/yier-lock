# Distributed Lock Hardening Roadmap

## Purpose

This roadmap turns the current architecture review into an execution order that protects correctness first, then performance, then structural cleanup.

The key rule is simple:

- do not start deep SPI surgery before the project can observe failures and block accidental regressions
- do not optimize hot paths before contention, timeout, and session-loss behavior are measurable
- do not widen the public surface to solve observability or async needs

## P0: Make the System Observable and Safe to Evolve

P0 is intentionally split into three tracks because they touch different parts of the repository and can be reviewed independently.

### Track A: Observability and Diagnostics

**Why first**

This library currently has no metrics, tracing bridge, or structured diagnostic path for lock acquisition, timeout, session loss, or ownership loss. That makes every later tuning task guesswork.

**Outcome**

- add optional observability extension modules instead of pushing metrics contracts into `core`
- expose acquisition and scope outcomes for both programmatic and Spring usage
- keep high-cardinality lock keys out of metrics by default while still allowing controlled diagnostic logging

**Plan**

- `docs/superpowers/plans/2026-04-22-distributed-lock-observability-hardening.md`

**Exit criteria**

- a Spring Boot application can enable lock metrics without replacing the kernel API
- `LockExecutor` and manual `LockClient` usage both emit consistent outcome events
- timeout and ownership-loss paths are visible in logs and metrics

### Track B: Build, Compatibility, and Regression Gates

**Why second**

Once observability starts landing, the project needs hard protection around binary compatibility, dependency drift, and focused regression suites.

**Outcome**

- add Maven enforcer and dependency convergence checks
- add binary compatibility checks for `api`, `runtime`, and starter-facing public artifacts
- wire mainline focused suites and benchmark smoke into CI-friendly commands

**Exit criteria**

- breaking public API or SPI signatures fails the build before merge
- the repository has one documented command set for local verification and one CI command set
- benchmark smoke compile/test runs are part of the guarded path

### Track C: Spring Hot-Path Caching

**Why third**

This is a low-risk optimization, but it is still easier to evaluate once Track A exposes baseline timings.

**Outcome**

- cache `Method`-level annotation parsing, return-type validation, parsed SpEL expressions, and parameter-name lookups
- reduce per-invocation reflection and parser churn in `@DistributedLock`

**Exit criteria**

- `@DistributedLock` interception avoids repeated parse work on steady-state calls
- focused starter tests and benchmark smoke confirm identical behavior with lower overhead

## P1: Deepen Correctness and Tune the Backends

P1 uses the measurement and build gates from P0 to improve the real lock implementations.

### Track A: Randomized Concurrency and Fault Injection

**Outcome**

- add randomized contention tests
- add longer-running soak tests around fencing, timeout, and session loss
- make renewal and teardown races reproducible

### Track B: Redis Wait and Renewal Tuning

**Outcome**

- replace fixed retry sleep with configurable backoff and jitter
- make renewal scheduling measurable and tunable
- verify no correctness regression under loss and timeout scenarios

### Track C: ZooKeeper Session-Cost Review

**Outcome**

- benchmark the current one-session-one-client model
- decide whether to keep it, pool it, or split logical and physical sessions
- only implement a new model if tests and measurements show a clear win

## P2: Structural Cleanup After the System Is Measurable

P2 is where longer-horizon design work belongs.

### Track A: Decouple Internal Backend SPI from Public API Shapes

**Outcome**

- shrink coupling between `core.backend` and `api`
- keep public contracts stable while making internal evolution cheaper

### Track B: Tighten Backend Discovery and Defaults

**Outcome**

- reduce accidental classpath magic from `ServiceLoader` plus local default configuration
- make production bootstrap more explicit

### Track C: Productize Benchmarks and Release Flow

**Outcome**

- keep benchmark smoke in guarded verification
- add repeatable release checks for docs, compatibility, examples, and performance sanity

## Recommended Execution Order

1. Finish P0 Track A and merge it cleanly.
2. Add P0 Track B before making any new public extension default.
3. Add P0 Track C after baseline measurements are available.
4. Use the resulting metrics and gates to drive P1 backend work.
5. Do not start P2 SPI cleanup until P0 and the relevant P1 backend track are complete.
