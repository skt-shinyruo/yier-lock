# Distributed Lock Benchmarks

## Prerequisites

- local Redis running on `127.0.0.1:6379`, or `BENCHMARK_REDIS_URI` / `-Dbenchmark.redis.uri=...`
- local ZooKeeper running on `127.0.0.1:2181`, or `BENCHMARK_ZOOKEEPER_CONNECT_STRING` / `-Dbenchmark.zookeeper.connect-string=...`
- current 2.0 artifacts installed with `mvn -q install -DskipTests`

The benchmark module is intentionally outside the default root reactor. Mainline `mvn test` does not include it.
Because of that, rerun `mvn -q install -DskipTests` after changing runtime, backend, or starter modules so this module sees the current typed-module API.

## Benchmark Suites

- `MutexLifecycleBenchmark`
- `SynchronousLockExecutor` scoped mutex acquire/release
- `MutexContentionBenchmark`
- `LockClient` + `LockSession` timed contention paths
- `ReadWriteLockBenchmark`
- read/write scoped execution through `SynchronousLockExecutor`
- `RuntimeLifecycleBenchmark`
- runtime construction with `LockClient` / `SynchronousLockExecutor` access
- `SpringStarterBenchmark`
- `@DistributedLock` and programmatic `SynchronousLockExecutor` through Spring Boot starter

## Commands

### Install current 2.0 artifacts

```bash
mvn -q install -DskipTests
```

### Compile the benchmark module

```bash
mvn -q -f distributed-lock-benchmarks/pom.xml -DskipTests compile
```

### Compile through the root benchmark profile

```bash
mvn -Pbenchmarks -DskipTests compile
```

The profile keeps benchmarks outside the default reactor while giving CI a single root-level compile command.

### Run the smoke test

```bash
mvn -q -f distributed-lock-benchmarks/pom.xml -Dtest=BenchmarkEnvironmentSmokeTest test
```

The Spring smoke path relies on `distributed-lock-spring-boot-starter` plus `distributed-lock-redis-spring-boot-autoconfigure`, with Redis properties supplied through `distributed.lock.redis.*`.

### Package the runnable JMH jar

```bash
mvn -q -f distributed-lock-benchmarks/pom.xml package
```

### Run one benchmark

```bash
java -jar distributed-lock-benchmarks/target/benchmarks.jar MutexLifecycleBenchmark
```

### Run one quick benchmark pass

```bash
java -jar distributed-lock-benchmarks/target/benchmarks.jar MutexLifecycleBenchmark -wi 1 -i 1 -f 1
```

### Run a benchmark family

```bash
java -jar distributed-lock-benchmarks/target/benchmarks.jar ".*Spring.*"
java -jar distributed-lock-benchmarks/target/benchmarks.jar ".*ReadWrite.*"
```

## Local Backend Overrides

Use system properties or environment variables to point the benchmarks at non-default local instances:

```bash
-Dbenchmark.redis.uri=redis://127.0.0.1:6380
-Dbenchmark.zookeeper.connect-string=127.0.0.1:2182
-Dbenchmark.zookeeper.base-path=/distributed-lock-benchmarks
```

Or:

```bash
BENCHMARK_REDIS_URI=redis://127.0.0.1:6380
BENCHMARK_ZOOKEEPER_CONNECT_STRING=127.0.0.1:2182
BENCHMARK_ZOOKEEPER_BASE_PATH=/distributed-lock-benchmarks
```
