# Distributed Lock Framework

A production-grade, high-performance, high-availability distributed lock Java framework with pluggable backend implementations and ergonomic APIs.

## Features

- **High Performance**: Optimized for low latency and high throughput with async/non-blocking I/O
- **High Availability**: Robust fault tolerance with network partition and node failure handling
- **Pluggable Backends**: Support for Redis (Lettuce) and ZooKeeper (Curator) with extensible architecture
- **Rich API**: Both synchronous and asynchronous operations with try-with-resources support
- **Observability**: Built-in metrics (Micrometer), tracing (OpenTelemetry), and structured logging
- **Production Ready**: Comprehensive configuration, monitoring, and testing capabilities
- **Battle-Tested Patterns**: Redis and ZooKeeper integrations mirror proven designs from Redisson and Apache Curator
- **🚀 Optimized Implementation**: Enhanced performance with shared thread pools, fencing tokens, and resource efficiency (see [OPTIMIZATIONS.md](OPTIMIZATIONS.md))

## Supported Backends

### Redis Implementation
- **Client**: Lettuce (high-performance async Redis client)
- **Features**: 
  - Atomic lock operations using Lua scripts
  - Automatic lock expiration and renewal (watchdog)
  - Pub/Sub based waiting strategy to reduce CPU usage
  - Reentrant locks with proper thread safety
  - Read/write locks with multiple reader support

### ZooKeeper Implementation  
- **Client**: Apache Curator Framework
- **Features**:
  - Strong consistency guarantees
  - Automatic cleanup on client disconnect
  - Built-in reentrant lock support
  - Read/write locks with proper semantics
  - Battle-tested reliability

## Design Highlights

- **Redis Reentrancy**: Locks are stored as hashes containing `owner` and `count`, mirroring Redisson's Lua scripts to guarantee atomic reentrant acquisition and renewal.
- **Pub/Sub Coordination**: Waiters subscribe to per-lock channels and auto-unsubscribe after notifications so listeners cannot leak under contention.
- **Watchdog Safety**: Renewals are idempotent and validate ownership before extending TTL, preventing runaway watchdogs during failover scenarios.

### Provider Discovery

- The new `ServiceLoaderDistributedLockFactory` mirrors Redisson's pluggable architecture by discovering available `LockProvider`s via Java's `ServiceLoader`.
- Provider selection is guided by `distributed-lock.type` (Typesafe config), Spring's `spring.distributed-lock.type`, or environment variables like `DISTRIBUTED_LOCK_PROVIDER`.
- Fallback order honours provider-specific priority so production-proven backends (Redis) are preferred when no explicit choice is made.
- A convenience helper `ConfiguredDistributedLockFactory.create(...)` wires the factory using a `LockConfiguration` instance.

```java
import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.core.ConfiguredDistributedLockFactory;
import com.mycorp.distributedlock.core.config.LockConfiguration;

LockConfiguration configuration = new LockConfiguration();
DistributedLockFactory lockFactory = ConfiguredDistributedLockFactory.create(configuration);
```

## Quick Start

> **💡 New**: Check out [OPTIMIZATIONS.md](OPTIMIZATIONS.md) for the enhanced implementations with better performance and resource efficiency!

### Maven Dependencies

```xml
<!-- For Redis implementation -->
<dependency>
    <groupId>com.my-corp</groupId>
    <artifactId>distributed-lock-redis</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- For ZooKeeper implementation -->
<dependency>
    <groupId>com.my-corp</groupId>
    <artifactId>distributed-lock-zookeeper</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Basic Usage

#### Redis Example

```java
import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.redis.RedisDistributedLockFactory;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;

// Create Redis client and lock factory
RedisClient redisClient = RedisClient.create(RedisURI.create("redis://localhost:6379"));
RedisDistributedLockFactory lockFactory = new RedisDistributedLockFactory(redisClient);

// Basic lock usage
DistributedLock lock = lockFactory.getLock("my-resource");

// Synchronous locking
lock.lock(30, TimeUnit.SECONDS);
try {
    // Critical section
    System.out.println("Lock acquired, doing work...");
} finally {
    lock.unlock();
}

// Try-with-resources (automatic unlock)
try (DistributedLock autoLock = lockFactory.getLock("auto-resource")) {
    autoLock.lock(30, TimeUnit.SECONDS);
    // Work is done here, lock automatically released
}

// Asynchronous locking
CompletableFuture<Void> lockFuture = lock.lockAsync(30, TimeUnit.SECONDS);
lockFuture.thenRun(() -> {
    try {
        // Critical section
        System.out.println("Async lock acquired!");
    } finally {
        lock.unlockAsync();
    }
});
```

#### ZooKeeper Example

```java
import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.zookeeper.ZooKeeperDistributedLockFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

// Create Curator client and lock factory
CuratorFramework curator = CuratorFrameworkFactory.newClient(
    "localhost:2181", 
    new ExponentialBackoffRetry(1000, 3)
);
curator.start();

ZooKeeperDistributedLockFactory lockFactory = new ZooKeeperDistributedLockFactory(curator);

// Usage is identical to Redis implementation
DistributedLock lock = lockFactory.getLock("my-resource");
lock.lock(30, TimeUnit.SECONDS);
try {
    // Critical section
} finally {
    lock.unlock();
}
```

#### Read/Write Locks

```java
import com.mycorp.distributedlock.api.DistributedReadWriteLock;

DistributedReadWriteLock rwLock = lockFactory.getReadWriteLock("shared-resource");

// Multiple readers can acquire the lock simultaneously
DistributedLock readLock = rwLock.readLock();
readLock.lock(30, TimeUnit.SECONDS);
try {
    // Read operation
} finally {
    readLock.unlock();
}

// Writers have exclusive access
DistributedLock writeLock = rwLock.writeLock();
writeLock.lock(30, TimeUnit.SECONDS);
try {
    // Write operation
} finally {
    writeLock.unlock();
}
```

## Spring Integration

The framework provides seamless integration with Spring and Spring Boot through the `distributed-lock-spring-boot-starter` module. This enables automatic configuration, annotation-driven locking, and simplified dependency management.

### Adding the Starter Dependency

Include the starter in your Maven `pom.xml`:

```xml
<dependency>
    <groupId>com.my-corp</groupId>
    <artifactId>distributed-lock-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Optional: Include specific backends if not auto-detected -->
<dependency>
    <groupId>com.my-corp</groupId>
    <artifactId>distributed-lock-redis</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<!-- or -->
<dependency>
    <groupId>com.my-corp</groupId>
    <artifactId>distributed-lock-zookeeper</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Enabling Spring Integration

Add `@EnableDistributedLock` to your Spring configuration class:

```java
import com.mycorp.distributedlock.springboot.config.EnableDistributedLock;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableDistributedLock
public class LockConfig {
    // Optional custom beans or overrides
}
```

This enables auto-configuration of `SpringDistributedLockFactory` as a Spring Bean and registers the AOP aspect for annotation processing.

### Annotation-Based Locking

Use `@DistributedLock` or `@DistributedReadLock` annotations on service methods:

```java
import com.mycorp.distributedlock.api.annotation.DistributedLock;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    @DistributedLock(value = "order:{#orderId}", timeout = "30s")
    public void processOrder(String orderId) {
        // Critical section: process order
        System.out.println("Processing order: " + orderId);
        // Lock is automatically acquired before method execution
        // and released after, even on exceptions
    }

    @DistributedReadLock(value = "cache:{#key}")
    public String getCachedData(String key) {
        // Read lock: multiple readers allowed
        return "data for " + key;
    }
}
```

- `value`: Lock key, supports SpEL expressions (e.g., method parameters).
- `timeout`: Lease time (defaults to configured value).
- Fallback to simple string concatenation if SpEL evaluation fails.

### Configuration

Configure properties in `application.yml`:

```yaml
distributed:
  lock:
    enabled: true
    default-lease-time: 30s
    default-wait-time: 10s
    backend: redis  # or zookeeper
    redis:
      host: localhost
      port: 6379
      database: 0
    zookeeper:
      connect-string: localhost:2181
      base-path: /distributed-locks
    # Observability
    metrics:
      enabled: true
    tracing:
      enabled: true
    # AOP specific
    aop:
      proxy-target-class: true  # Use CGLIB proxies
```

The `SpringDistributedLockFactory` will be auto-configured based on available backends and properties.

### Running Examples

The `examples` module demonstrates Spring Boot integration:

1. Ensure Redis (localhost:6379) or ZooKeeper (localhost:2181) is running.
2. Build the project: `mvn clean install`
3. Run the example: `cd examples && mvn spring-boot:run`
4. Observe logs for lock acquisition/release in `SpringBootDistributedLockExample`.
5. Test manual locking via injected `SpringDistributedLockFactory` and annotation-based via AOP.

For detailed code, see `examples/src/main/java/com/mycorp/distributedlock/examples/spring/SpringBootDistributedLockExample.java`.

### Migration Guide

- **From Manual Locking**: Replace direct `DistributedLockFactory` instantiation with `@Autowired SpringDistributedLockFactory`.
- **To Annotations**: Annotate methods with `@DistributedLock`; use SpEL in `value` for dynamic keys (e.g., `#{#param}`).
- **Key Utils**: Leverage `SpringLockKeyUtils` for SpEL evaluation in non-annotation contexts.
- **Configuration**: Migrate Typesafe Config to Spring Properties; backends are optional dependencies.
- **Observability**: Metrics and tracing integrate automatically with Spring Boot Actuator/Micrometer.
- **Compatibility**: Core API remains unchanged; annotations add declarative layer without breaking existing code.

For full migration from the planning document, refer to the Architect mode design: Starter auto-configures factory, AOP handles annotations, and properties map to core config.

## Configuration

The framework uses Typesafe Config for configuration. Create an `application.conf` file:

```hocon
distributed-lock {
  # Default lease time for locks
  default-lease-time = 30s
  
  # Default wait time for lock acquisition
  default-wait-time = 10s
  
  # Watchdog configuration (Redis only)
  watchdog {
    enabled = true
    renewal-interval = 10s
  }
  
  # Retry configuration
  retry-interval = 100ms
  max-retries = 3
  
  # Observability
  metrics.enabled = true
  tracing.enabled = true
  
  # ZooKeeper specific
  zookeeper {
    base-path = "/distributed-locks"
  }
}
```

## Observability

### Metrics (Micrometer)

The framework exposes the following metrics:

- `lock.acquisition.timer`: Lock acquisition latency
- `lock.held.duration.timer`: Lock hold duration  
- `lock.contention.counter`: Lock contention failures
- `lock.watchdog.renewal.counter`: Watchdog renewals (Redis)
- `lock.acquisition.counter`: Lock acquisition attempts
- `lock.release.counter`: Lock release attempts

### Tracing (OpenTelemetry)

Lock operations are automatically traced with spans:
- `lock.lock`: Lock acquisition span
- `lock.unlock`: Lock release span

### Logging (SLF4J)

Structured logging with contextual information:
- Lock acquisition/release events
- Watchdog renewal activities
- Error conditions and retries

## Testing

### Unit Tests
```bash
mvn test
```

### Integration Tests (with Testcontainers)
```bash
mvn verify
```

### Performance Benchmarks (JMH)
```bash
cd distributed-lock-benchmarks
mvn clean package
java -jar target/benchmarks.jar
```

## Architecture

```
distributed-lock/
├── distributed-lock-api/          # Core interfaces
├── distributed-lock-core/         # Shared utilities and configuration
├── distributed-lock-redis/        # Redis implementation (Lettuce)
├── distributed-lock-zookeeper/    # ZooKeeper implementation (Curator)
└── distributed-lock-benchmarks/   # JMH performance tests
```

## Performance Characteristics

### Redis Implementation
- **Throughput**: ~10,000-50,000 ops/sec (depending on network latency)
- **Latency**: Sub-millisecond for local Redis, 1-5ms for remote
- **Scalability**: Excellent horizontal scaling
- **Consistency**: Eventually consistent (AP in CAP theorem)

### ZooKeeper Implementation  
- **Throughput**: ~1,000-5,000 ops/sec (due to consensus overhead)
- **Latency**: 5-20ms (depends on cluster size and network)
- **Scalability**: Good but limited by consensus protocol
- **Consistency**: Strong consistency (CP in CAP theorem)

## Production Considerations

### Redis Deployment
- Use Redis Cluster or Sentinel for high availability
- Configure appropriate timeout and retry settings
- Monitor Redis memory usage and eviction policies
- Consider using Redis persistence for durability

### ZooKeeper Deployment
- Deploy odd number of nodes (3, 5, 7) for fault tolerance
- Ensure low-latency network between ZooKeeper nodes
- Monitor ZooKeeper ensemble health and performance
- Configure appropriate session timeouts

### General Recommendations
- Choose Redis for high-throughput, low-latency scenarios
- Choose ZooKeeper for strong consistency requirements
- Always configure appropriate lease times to prevent deadlocks
- Monitor lock contention and acquisition latencies
- Use circuit breakers for external dependencies
- Implement proper retry and backoff strategies

## License

This project is licensed under the Apache License 2.0.
