# Distributed Lock Regression Matrix

This module is the canonical regression matrix entry point for the distributed lock reactor. It keeps suite-level smoke tests executable while backend-specific coverage remains in each module.

## Backend Prerequisites

- Core, runtime, testkit, Spring starter, observability, and ZooKeeper adapter checks do not require Docker.
- Redis integration checks use Testcontainers and require a working local Docker environment.
- Benchmark compilation is part of the matrix; full JMH execution is opt-in because it may require local backend services and dedicated runtime settings.

## Commands

```bash
mvn test
mvn -pl distributed-lock-core,distributed-lock-testkit -am test
mvn -pl distributed-lock-runtime,distributed-lock-spring-boot-starter -am test
mvn -pl distributed-lock-zookeeper -am test
mvn -pl distributed-lock-redis,distributed-lock-redis-spring-boot-autoconfigure -am test
mvn -Predis-integration -pl distributed-lock-redis,distributed-lock-redis-spring-boot-autoconfigure -am test
mvn -pl distributed-lock-test-suite -am test
mvn -Pbenchmarks -DskipTests compile
```
