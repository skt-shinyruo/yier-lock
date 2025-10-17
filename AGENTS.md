# Repository Guidelines

## Project Structure & Module Organization
The repository is a multi-module Maven build anchored by the root `pom.xml`. Core interfaces live in `distributed-lock-api`, while reusable infrastructure sits in `distributed-lock-core`. Backend implementations reside in `distributed-lock-redis` and `distributed-lock-zookeeper`, and the `distributed-lock-spring-boot-starter` packages auto-configuration. JMH benchmarks are under `distributed-lock-benchmarks`, and runnable samples sit in `examples`. Each module follows the Maven standard layout (`src/main/java`, `src/test/java`) and should keep module-specific resources in `src/main/resources`.

## Build, Test, and Development Commands
Use Maven wrappers or a local Maven 3.9+ install. Run `mvn clean install` at the root to compile all modules and publish snapshots to the local repository. `mvn test` executes unit suites, while `mvn verify` adds integration checks that rely on Testcontainers—ensure Docker is available. To exercise JMH baselines, run `mvn clean package -pl distributed-lock-benchmarks -am` followed by `java -jar distributed-lock-benchmarks/target/benchmarks.jar`.

## Coding Style & Naming Conventions
Code targets Java 17; prefer records, sealed interfaces, and `var` only when the inferred type stays obvious. Indent with four spaces, wrap lines at ~120 characters, and keep imports ordered alphabetically within groups. Class names use UpperCamelCase, packages remain lowercase, and constants are SCREAMING_SNAKE_CASE. Favor expressive method names like `acquireOrRenew()` and add Javadoc to public APIs, especially in `distributed-lock-api`. Use SLF4J with parameterized logging (`logger.debug("Lock {} acquired", lockId)`).

## Testing Guidelines
JUnit 5 backs unit and integration tests; organize tests to mirror package structure (e.g., `...redis.lock` → `...redis.lock`). Name tests with behavior cues such as `RedisLockFactoryTest` and methods like `shouldRenewLeaseBeforeExpiration()`. When a test spins up infrastructure (Testcontainers), gate it behind `@Tag("integration")` so quick runs can skip them via `mvn test -Dgroups=!integration`. Maintain meaningful coverage on watchdog renewal, reentrancy, and failure paths before opening a PR.

## Commit & Pull Request Guidelines
Commit history is sparse; follow the existing short, imperative subject style (`init`). Keep subjects ≤72 characters and add descriptive bodies when the change spans multiple modules. Each pull request should summarize the change set, call out impacted modules, link to tracking issues, and include validation notes (`mvn verify`, benchmark deltas, screenshots for dashboards when relevant). Request reviews from maintainers responsible for the touched backend and ensure CI is green before merging.
