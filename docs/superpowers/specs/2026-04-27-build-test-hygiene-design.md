# Build And Test Hygiene Design

## Summary

This design covers lower-risk engineering hygiene found during the repository review.
It focuses on build reliability, dependency boundaries, API ergonomics, validation, benchmark drift, and documentation consistency.

These changes should be implemented after or alongside the correctness specs, but they should not obscure backend behavior changes.
Most changes are small and can be verified with focused Maven module commands.

## Goals

- Make `LockRuntime.close()` match the no-checked-exception style used by `LockClient` and `LockSession`
- Harden `LockRuntimeBuilder` against invalid `BackendModule` metadata and null backend creation
- Remove unused dependencies from core/runtime/backend modules
- Make Spring configuration validation either real or remove unnecessary validation dependencies
- Make Micrometer optional where possible in the observability Spring extension
- Add a benchmark profile or CI-friendly command so benchmark code does not silently rot
- Document the supported Spring Boot version matrix and provide commands to test it
- Keep test-suite documentation aligned with maintained regression commands
- Avoid adding broad refactors unrelated to the current hardening work

## Non-Goals

- No release publishing workflow
- No artifact signing, deployment, or versioning redesign
- No broad module rename
- No migration from Maven to Gradle
- No new static-analysis framework unless it is directly needed for these hygiene fixes
- No mandatory benchmark execution against real Redis or ZooKeeper in default `mvn test`

## Design Decisions

### 1. Narrow `LockRuntime.close()` checked exception exposure

`LockRuntime` extends `AutoCloseable` but does not redeclare `close()`.
Callers typed as `LockRuntime` therefore see `AutoCloseable.close()` with `throws Exception`, while `LockClient` and `LockSession` already expose no checked close exception.

Add this method to the interface:

```java
@Override
void close();
```

`DefaultLockRuntime.close()` already matches this shape.
The API surface test should assert that `LockRuntime.close()` declares no checked exceptions.

### 2. Validate backend module metadata defensively

`LockRuntimeBuilder` should fail with `LockConfigurationException` for invalid modules instead of throwing incidental `NullPointerException` or grouping null ids.

Validation rules:

- backend module list must not contain null entries
- module id must be non-null and non-blank
- duplicate ids remain rejected
- `capabilities()` must return non-null
- capabilities should be read once per module during build, not repeatedly
- selected module `createBackend()` must return non-null
- backend id selection should trim or reject whitespace consistently

The builder should continue discovering service-loader modules when no explicit modules are provided.
The validation should apply equally to explicit and discovered modules.

### 3. Remove unused dependencies from non-logging modules

Repository search found no `org.slf4j` usage in `distributed-lock-core` or `distributed-lock-runtime` main sources.
Remove unused `slf4j-api` dependencies from those modules.

For backend modules:

- remove `slf4j-api` from Redis and ZooKeeper if main sources do not log after the correctness work
- keep logging dependencies only in modules that directly use logging APIs
- remove `curator-recipes` from ZooKeeper unless the implementation starts using Curator recipes

Dependency cleanup should be verified with module compilation, not only text search.

### 4. Make Spring configuration validation meaningful

Several Spring properties classes are annotated with `@Validated` but mostly rely on manual checks in auto-configuration methods.
Use declarative validation where Spring binds configuration properties:

- add Jakarta validation annotations such as `@NotBlank`, `@NotNull`, and positive duration checks where supported
- keep `spring-boot-starter-validation`
- keep manual validation only for constraints that are easier to express imperatively, such as whole-second Redis lease duration

This gives earlier and more consistent configuration errors.
It also justifies the validation dependency already present in the starter.

### 5. Make Micrometer optional in the Spring observability extension

`distributed-lock-extension-observability-spring` currently depends directly on `micrometer-core`, then conditionally uses `MeterRegistry` at runtime.
If the extension is intended to support logging-only observability, Micrometer should be optional or isolated behind `@ConditionalOnClass`.

Design:

- split metrics sink auto-configuration behind `@ConditionalOnClass(MeterRegistry.class)`
- mark `micrometer-core` optional if the module still compiles against it directly
- add tests for both classpath-present and classpath-absent behavior

If Maven optional-dependency testing requires a separate test fixture, create the fixture in the observability Spring module rather than weakening the requirement.

### 6. Add a benchmark compile profile without changing default reactor behavior

`distributed-lock-benchmarks` is intentionally outside the default reactor.
That is reasonable because benchmarks depend on installed snapshots and external services for full execution.
The risk is silent source drift.

Add a Maven profile at the root that compiles benchmarks when explicitly activated.

Profile shape:

- root profile `benchmarks` adds `distributed-lock-benchmarks` as a module when explicitly activated
- README documents `mvn -Pbenchmarks -DskipTests compile`
- default `mvn test` remains unchanged

Benchmark smoke tests that require Redis or ZooKeeper should remain opt-in.

### 7. Clarify Spring Boot support matrix

The root POM currently pins Spring Boot `3.2.0`, while documentation says Spring Boot 3.x.
The project should state what is actually supported.

Initial support matrix:

- Java 17+
- Spring Boot 3.2.x as the primary tested line
- Spring Boot 3.3.x and 3.4.x as compatibility targets if Maven override tests pass

Add documentation commands such as:

```bash
mvn test -Dspring-boot.version=3.3.13
mvn test -Dspring-boot.version=3.4.5
```

Implementation must choose current patch versions for the target lines and record the tested versions in the README.
If compatibility fails, narrow the README claim instead of leaving an untested `3.x` promise.

### 8. Keep maintained test-suite docs synchronized

`distributed-lock-test-suite/README.md` should remain the source of truth for focused regression commands.
Update it after the correctness and Spring specs add tests.

Rules:

- add new test class names to the maintained list
- keep commands copy-pasteable
- separate tests requiring Testcontainers from pure unit tests when useful
- keep benchmarks documented as outside default reactor unless the root profile changes that wording

### 9. Add lightweight hygiene checks only if they stay local

Avoid introducing a broad static-analysis stack in this pass.
Small checks are acceptable when they are immediately useful:

- API surface assertions for checked exceptions and public method shape
- focused tests for invalid builder metadata
- compile checks for examples and benchmarks
- dependency cleanup verified by Maven compile/test

Do not add Checkstyle, SpotBugs, Error Prone, or Revapi as part of this spec unless the user explicitly asks for a larger build-quality initiative.

## Module Changes

### `distributed-lock-api`

- update API surface tests if they assert runtime types or close semantics

### `distributed-lock-runtime`

- redeclare `LockRuntime.close()` with no checked exception
- harden `LockRuntimeBuilder` module validation
- remove unused dependencies identified by this spec
- add tests for null module, blank id, null capabilities, null backend, duplicate ids, and repeated capabilities calls

### `distributed-lock-core`

- remove unused logging dependency

### `distributed-lock-redis`

- remove unused logging dependency
- keep dependency changes separate from Lua correctness edits where possible

### `distributed-lock-zookeeper`

- remove unused logging dependency
- remove `curator-recipes`

### Spring modules

- add real validation annotations and keep validation dependency justified
- make Micrometer dependency behavior explicit
- add configuration binding tests for invalid properties

### Documentation and benchmarks

- update benchmark README/profile commands
- update test-suite README
- update starter README Spring Boot support statement

## Testing Strategy

### Runtime/API tests

- `LockRuntime.close()` declares no checked exception
- null backend module entry fails with `LockConfigurationException`
- null or blank module id fails with `LockConfigurationException`
- duplicate module ids still fail clearly
- null capabilities fail clearly
- unsupported capabilities fail before backend creation
- null backend returned by selected module fails clearly
- capabilities are not called repeatedly in a way that can change behavior during one build

### Spring configuration tests

- missing Redis URI fails with a clear configuration error
- invalid Redis lease time fails with a clear configuration error
- missing ZooKeeper connect string fails clearly
- invalid ZooKeeper base path fails clearly after backend validation changes
- validation dependency behavior matches the chosen approach
- observability works when Micrometer is present
- logging-only observability behavior is tested

### Build checks

- `mvn test` passes for the default reactor
- focused module tests from `distributed-lock-test-suite/README.md` pass
- examples compile in the default reactor
- benchmark compile command succeeds after `mvn -q install -DskipTests`
- Spring Boot version override commands are run during implementation and their results are recorded in documentation

## Documentation Updates

- Update `distributed-lock-test-suite/README.md` with new tests and commands
- Update `distributed-lock-benchmarks/README.md` for the root benchmark profile
- Update starter README with exact Spring Boot compatibility claim
- Note any dependency behavior changes that affect consumers, especially optional Micrometer

## Acceptance Criteria

- No production module keeps an unused direct dependency identified by this spec unless documented
- Runtime builder failures are deterministic `LockConfigurationException`s
- `LockRuntime.close()` no longer exposes a checked exception to interface-typed callers
- Benchmarks have a documented or profiled compile path that can be run during CI
- Spring Boot support wording matches tested versions
- Default `mvn test` behavior remains unchanged except for added tests
