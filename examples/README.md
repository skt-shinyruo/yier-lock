# Distributed Lock Examples

## Basic Usage Example

The `BasicUsageExample` demonstrates the core API usage without Spring integration.

To run:
1. Ensure Redis or ZooKeeper is running locally (Redis on localhost:6379, ZooKeeper on localhost:2181).
2. Compile and run: `mvn compile exec:java -Dexec.mainClass="com.mycorp.distributedlock.examples.BasicUsageExample"`

## Spring Integration Example

The Spring Boot example in `spring` package shows integration with Spring and Spring Boot, using annotations and manual lock acquisition.

### Prerequisites
- Java 11+
- Maven 3.6+
- Local Redis server running on `localhost:6379` (no authentication for simplicity).

### Configuration
See `src/main/resources/application.yml` for configuration:
- `spring.distributed-lock.enabled=true`
- `spring.distributed-lock.type=redis`
- `spring.distributed-lock.default-lease-time=30s`
- `spring.distributed-lock.redis.hosts=localhost:6379`

### Running the Example
1. Navigate to the `examples` directory.
2. Run with Maven: `mvn spring-boot:run -Dspring-boot.run.main-class=com.mycorp.distributedlock.examples.spring.SpringBootDistributedLockExample`
   - This starts the Spring Boot application with the distributed lock auto-configuration.

### What It Does
- The `LockService` (inner class in `SpringBootDistributedLockExample`) demonstrates:
  - Manual lock: Acquires a lock for "example-key" for 30s, simulates work, then releases.
  - Annotation-based: `@DistributedLock` on `processOrder` method uses SpEL for key ("order:{orderId}"), 10s timeout. (Note: AOP not yet implemented, falls back to no-lock execution.)

### Expected Output
Upon startup:
```
Spring Boot Distributed Lock Example started!
```

To trigger examples, you can add a CommandLineRunner or run methods manually in a test/debug session:
- Manual lock: "Manual lock acquired for key: example-key" followed by work completion.
- Annotation method: "Processing order: [id] with distributed lock." (without actual locking until AOP is added).

For ZooKeeper, change `type: zookeeper` and `zookeeper.connect-string=localhost:2181` in application.yml.