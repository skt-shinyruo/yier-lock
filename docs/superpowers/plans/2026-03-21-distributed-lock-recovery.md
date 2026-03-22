# Distributed Lock Recovery Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore this branch to a buildable, internally consistent state and fix the highest-risk distributed-lock correctness bugs called out in review.

**Architecture:** Keep the current public API surface as the source of truth, then realign implementations, starter integration, tests, and build configuration around it. Fix unsafe lock semantics in the concrete Redis/core implementations, remove generated-marker corruption from source files, and only keep features that can be made correct and verifiable on this branch.

**Tech Stack:** Java 17, Maven multi-module build, JUnit 5, Mockito, Spring Boot starter, Lettuce Redis, Curator ZooKeeper.

**Execution Notes:** User explicitly requested all work stay on the current branch (`minimax`) and not use worktrees. Parallel execution is allowed only for tasks with disjoint write sets.
Execution order: finish Task 1 first, then Task 2, then run Tasks 3 and 4 in parallel, then Task 5.

---

### Task 1: Restore Build Baseline And Source Hygiene

**Files:**
- Modify: `pom.xml`
- Modify: `distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/DistributedLockApiTest.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/observability/AlertingRules.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/observability/LockMetricsCollector.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/observability/LockAlertingService.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/testing/LoadTestScenarios.java`
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/operation/ZooKeeperAsyncLockOperations.java`
- Modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperDistributedReadWriteLock.java`
- Verify: root/module compile commands

- [ ] **Step 1: Capture the failing baseline**

Run:
```bash
mvn test -DskipITs
mvn -pl distributed-lock-core -am -DskipTests compile
```

Expected:
- `distributed-lock-api` test compilation fails on `DistributedLockApiTest`
- `distributed-lock-core` compilation fails on Java source corruption and Java version mismatch

- [ ] **Step 2: Align the Maven compiler level with the repository’s Java requirement**

Update `pom.xml` to compile with Java 17 consistently in properties and compiler plugin configuration.

- [ ] **Step 3: Remove source corruption and stray pasted fragments**

Delete malformed trailing code from `DistributedLockApiTest.java`.
Remove literal `</tool_call>`/broken identifiers and other malformed tokens from the affected `core` and `zookeeper` source files.
Replace invalid local declarations such as method-scope `volatile` flags with valid concurrency primitives or equivalent structure.

- [ ] **Step 4: Re-run targeted compilation to prove the syntax layer is clean**

Run:
```bash
mvn -pl distributed-lock-api -Dtest=DistributedLockApiTest test
mvn -pl distributed-lock-core -am -DskipTests compile
mvn -pl distributed-lock-zookeeper -am -DskipTests compile
```

Expected:
- No syntax-level compiler errors
- Remaining failures, if any, are contract/behavior issues rather than malformed source

### Task 2: Reconcile API, Core Implementations, And Core Tests

**Files:**
- Modify: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/DistributedLock.java`
- Modify: `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/DistributedReadWriteLock.java`
- Modify: `distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/DistributedReadWriteLockApiTest.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/lock/EnhancedReentrantLockImpl.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/strategy/AdvancedLockStrategies.java`
- Modify: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/lock/EnhancedReentrantLockImplTest.java`
- Modify: `distributed-lock-api/src/test/java/com/mycorp/distributedlock/api/DistributedLockApiTest.java`
- Modify or remove outdated tests still bound to the pre-current API shape in `distributed-lock-core/src/test/java/...`

- [ ] **Step 1: Write or update failing tests for the current contract**

Add/adjust tests that pin:
- reentrant acquisition increments count correctly
- partial `unlock()` on a reentrant lock does not release the underlying lock early
- default `healthCheck()` does not unlock a lock the caller already holds
- `AdvancedLockStrategies` either compiles against the current API or is reduced to a supported subset

Run:
```bash
mvn -pl distributed-lock-core -am -Dtest=EnhancedReentrantLockImplTest test
mvn -pl distributed-lock-api -am -Dtest=DistributedLockApiTest,DistributedReadWriteLockApiTest test
```

Expected:
- tests fail for the current broken behavior or compile failures reveal old-API drift

- [ ] **Step 2: Fix the root causes in the shared contracts and core implementation**

Implement:
- safe `renewLock` semantics in `DistributedLock` that do not drop ownership during renewal
- side-effect-free default `healthCheck` behavior
- correct initial and reentrant count handling in `EnhancedReentrantLockImpl`
- valid auto-renew scheduling that does not rely on scheduler-thread ownership checks
- either rewrite `AdvancedLockStrategies` to the current API or remove/replace unsupported wrappers that were copied from an old interface

- [ ] **Step 3: Bring core tests onto the actual API**

Replace obsolete test doubles and assertions that use missing methods such as `acquire`, `release`, `renew`, `getStateInfo`, or duration-based overloads that do not exist on the current interface.

- [ ] **Step 4: Re-run focused verification**

Run:
```bash
mvn -pl distributed-lock-api -am test
mvn -pl distributed-lock-core -am test -DskipITs
```

Expected:
- API and core module tests compile and the updated targeted suites pass

### Task 3: Fix Redis Lock Safety And Redis Tests

**Files:**
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/SimpleRedisLock.java`
- Modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/SimpleRedisLockProvider.java`
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/SimpleRedisLockTest.java`
- Modify: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/SimpleRedisLockProviderTest.java`
- Modify or replace the simple read-write lock implementation if needed

- [ ] **Step 1: Add failing tests for ownership and renewal correctness**

Add/adjust tests that prove:
- a non-owner thread cannot unlock a held lock
- a renewal only extends a lock if the stored Redis token still belongs to this lock owner
- lock instances for the same name do not share stale ownership state across threads
- read/write lock behavior enforces write exclusion relative to readers

Run:
```bash
mvn -pl distributed-lock-redis -am -Dtest=SimpleRedisLockTest,SimpleRedisLockProviderTest test
```

Expected:
- tests fail under the current unsafe implementation

- [ ] **Step 2: Fix lock ownership, token handling, and provider lifecycle**

Implement:
- owner validation in `unlock()`
- token generation bound to each successful acquisition lifecycle, not the constructor alone
- removal or redesign of provider-level instance caching if it causes shared mutable ownership state
- safe renewal script/logic that verifies the current token before extending TTL

- [ ] **Step 3: Replace the fake read-write lock behavior with a correct minimal implementation**

Do one of:
- wire the provider to a real Redis read-write lock implementation already present in the module, or
- implement a minimal correct read/write coordination layer and test it

- [ ] **Step 4: Re-run Redis verification**

Run:
```bash
mvn -pl distributed-lock-redis -am test -DskipITs
```

Expected:
- Redis unit tests pass
- module compiles cleanly against the updated API/core code

### Task 4: Realign Spring Boot Starter With The Actual API And Property Model

**Files:**
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/SpringDistributedLockFactory.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockAspect.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockAutoConfiguration.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/DistributedLockProperties.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/health/DistributedLockHealthChecker.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/actuator/DistributedLockHealthIndicator.java`
- Modify: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAutoConfigurationIntegrationTest.java`
- Modify: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAspectIntegrationTest.java`

- [ ] **Step 1: Add or update failing starter tests for current API usage**

Pin:
- bean construction uses the real constructors
- AOP resolves the annotation model that actually exists
- lock acquisition uses the real `tryLock(long, long, TimeUnit)` signature
- health components use `DistributedLockFactory.healthCheck()`/`FactoryHealthStatus` rather than non-existent factory APIs

Run:
```bash
mvn -pl distributed-lock-spring-boot-starter -Dtest=DistributedLockAutoConfigurationIntegrationTest,DistributedLockAspectIntegrationTest test
```

Expected:
- current code fails to compile or tests fail due to API/config drift

- [ ] **Step 2: Fix constructor wiring and API calls**

Implement:
- constructor calls in auto-configuration that match actual class signatures
- a single unambiguous `DistributedLock` symbol in the aspect
- lock acquisition code that converts configured durations into the current `DistributedLock` method signature
- health check code that consumes the existing factory and lock health abstractions only

- [ ] **Step 3: Fix property accessors and nested binding usage**

Update either:
- `DistributedLockProperties` with convenience accessors used by auto-config, or
- `DistributedLockAutoConfiguration` to read from the existing nested property objects directly

Prefer the smaller change that keeps the property model coherent and testable.

- [ ] **Step 4: Re-run starter verification**

Run:
```bash
mvn -pl distributed-lock-spring-boot-starter -am test -DskipITs
```

Expected:
- starter module compiles
- focused integration tests pass

### Task 5: Final Repository Verification And Cleanup

**Files:**
- Modify only as needed based on verification fallout
- Modify: `distributed-lock-benchmarks/pom.xml`
- Optionally update documentation comments or examples if they block compilation

- [ ] **Step 1: Run full verification for the main reactor**

Run:
```bash
mvn test -DskipITs
```

Expected:
- reactor test phase completes successfully for included modules
- benchmark module resolves sibling snapshots correctly

- [ ] **Step 2: Run module-specific spot checks for fixed risk areas**

Run:
```bash
mvn -pl distributed-lock-core -am -Dtest=EnhancedReentrantLockImplTest test
mvn -pl distributed-lock-redis -Dtest=SimpleRedisLockTest,SimpleRedisLockProviderTest test
mvn -pl distributed-lock-redis -am -Dtest=SimpleRedisLockTest,SimpleRedisLockProviderTest test
mvn -pl distributed-lock-spring-boot-starter -am -Dtest=DistributedLockAutoConfigurationIntegrationTest,DistributedLockAspectIntegrationTest test
```

Expected:
- targeted correctness suites stay green

- [ ] **Step 3: Review the final diff for scope discipline**

Confirm:
- no worktree usage
- no unrelated refactors
- no leftover generated markers or placeholder implementations in touched files
