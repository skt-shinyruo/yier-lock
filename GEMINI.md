# Gemini Code Assistant Context

This document provides context for the Gemini Code Assistant to understand the `yier-lock` project.

## Project Overview

The `yier-lock` project is a production-grade, high-performance, high-availability distributed lock framework for Java. It features a pluggable backend architecture and ergonomic APIs.

The project is structured as a multi-module Maven project:

- `distributed-lock-api`: Defines the core interfaces for distributed locks (`DistributedLock`, `DistributedReadWriteLock`) and the `DistributedLockFactory`.
- `distributed-lock-core`: Provides shared utilities and configuration management using Typesafe Config.
- `distributed-lock-redis`: An implementation of the lock API using Redis and the Lettuce client. It uses Lua scripts for atomic operations and a watchdog for automatic lock renewal.
- `distributed-lock-zookeeper`: An implementation of the lock API using ZooKeeper and the Apache Curator framework.
- `distributed-lock-spring-boot-starter`: Provides seamless integration with Spring Boot, including auto-configuration and annotation-driven locking (`@DistributedLock`).
- `distributed-lock-benchmarks`: Contains JMH performance benchmarks for the different lock implementations.
- `examples`: Contains example usage of the framework, including a Spring Boot application.

The framework also includes observability features, with support for metrics via Micrometer and tracing via OpenTelemetry.

## Building and Running

The project is built using Apache Maven.

- **Build the project:**
  ```bash
  mvn clean install
  ```

- **Run unit tests:**
  ```bash
  mvn test
  ```

- **Run integration tests (using Testcontainers):**
  ```bash
  mvn verify
  ```

- **Run performance benchmarks:**
  ```bash
  cd distributed-lock-benchmarks
  mvn clean package
  java -jar target/benchmarks.jar
  ```

- **Run the Spring Boot example:**
  ```bash
  cd examples
  mvn spring-boot:run
  ```

## Development Conventions

- **Java Version:** The project uses Java 17.
- **Dependency Management:** Dependencies are managed in the root `pom.xml` file.
- **Logging:** The project uses SLF4J for logging.
- **Testing:**
  - Unit tests are written with JUnit 5 and Mockito.
  - Integration tests use Testcontainers to spin up Redis and ZooKeeper instances.
  - Awaitility is used for testing asynchronous operations.
- **Modularity:** The project is highly modular, with a clear separation of concerns between the API, core, and implementation modules. The `ServiceLoader` pattern is used to discover lock providers at runtime.
- **Configuration:** The core library uses Typesafe Config (`.conf` files) for configuration, while the Spring Boot starter uses the standard `application.yml` or `application.properties` files.

## Key Files

- `pom.xml`: The root Maven project file, defining the project structure, dependencies, and build settings.
- `README.md`: The main project documentation, including a quick start guide, feature overview, and architecture details.
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/DistributedLock.java`: The core interface for a distributed lock.
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/DistributedLockFactory.java`: The interface that abstractions use to create lock instances.
- `distributed-lock-api/src/main/java/com/mycorp/distributedlock/api/ServiceLoaderDistributedLockFactory.java`: Default implementation that discovers providers via `ServiceLoader`.
- `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/config/EnableDistributedLock.java`: The annotation to enable the Spring Boot auto-configuration for the distributed lock framework.
- `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/DistributedLockAutoConfiguration.java`: The main auto-configuration class for the Spring Boot starter.
- `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockAspect.java`: The AOP aspect that handles the `@DistributedLock` annotation.
