# Distributed Lock Spring Boot Starter

This starter is the Spring Boot 3 integration layer for distributed lock 2.0.

## Scope

The starter only provides:

- `LockRuntime` auto-configuration
- `LockClient` and `LockExecutor` bean exposure
- `@DistributedLock` method interception
- SpEL-based lock key resolution

It does not provide 1.x features such as health checks, metrics, async APIs, batch APIs, or provider/factory abstractions.

## Dependencies

Use the generic starter plus one backend-specific Spring auto-config module.

```xml
<dependency>
  <groupId>com.mycorp</groupId>
  <artifactId>distributed-lock-spring-boot-starter</artifactId>
</dependency>
<dependency>
  <groupId>com.mycorp</groupId>
  <artifactId>distributed-lock-redis-spring-boot-autoconfigure</artifactId>
</dependency>
```

Swap the backend module when using ZooKeeper:

```xml
<dependency>
  <groupId>com.mycorp</groupId>
  <artifactId>distributed-lock-zookeeper-spring-boot-autoconfigure</artifactId>
</dependency>
```

## Observability extension

The generic starter intentionally does not define metrics, tracing, or actuator contracts.
If you want lock metrics and diagnostic logs, add the optional Spring observability extension:

~~~xml
<dependency>
  <groupId>com.mycorp</groupId>
  <artifactId>distributed-lock-extension-observability-spring</artifactId>
</dependency>
~~~

This extension decorates the standard `LockRuntimeBuilder` runtime composition without changing the kernel API.
It records low-cardinality timers such as `distributed.lock.acquire` and `distributed.lock.scope`.
Raw lock keys stay out of metrics by default; `distributed.lock.observability.include-lock-key-in-logs=true` only affects diagnostic logs.

## Requirements

- Java 17+
- Spring Boot 3.x
- one backend Spring auto-config module on the classpath, or an explicit backend module bean
- explicit `distributed.lock.backend` selection whenever `distributed.lock.enabled=true`

## Configuration

The generic starter owns only generic runtime and annotation settings:

The `backend` property is required. The generic starter will not auto-select a backend from discovered modules.
If the application defines any explicit `BackendModule` bean, backend-specific backend-module registration backs off.
At that point the application owns the full backend module registry and must supply a module whose `id()` matches `distributed.lock.backend`.

```yaml
distributed:
  lock:
    enabled: true
    backend: redis
    spring:
      annotation:
        enabled: true
        default-timeout: 5s
```

Backend-specific properties are provided by the matching backend Spring module. For Redis:

```yaml
distributed:
  lock:
    redis:
      uri: redis://127.0.0.1:6379
      lease-time: 30s
```

For ZooKeeper:

```yaml
distributed:
  lock:
    zookeeper:
      connect-string: 127.0.0.1:2181
      base-path: /distributed-locks
```

## Annotation usage

```java
@Service
class OrderService {

    @DistributedLock(key = "order:#{#p0}", waitFor = "2s")
    public void processOrder(String orderId) {
        // critical section
    }
}
```

Supported annotation fields:

- `key`
- `mode`
- `waitFor`

Lock keys follow Spring template-expression semantics. Literal keys such as `order:42` pass through unchanged, while templates such as `order:#{#p0}` are evaluated against the intercepted method arguments.

Lock modes protect the same resource identity for a given key. `MUTEX` and `WRITE` are exclusive and block all `MUTEX`, `READ`, and `WRITE` acquisitions for the same key. `READ` can coexist only with other `READ` leases for the same key. Use distinct lock keys when you intentionally need independent lock families.

Backend progress semantics are backend-specific. Redis read/write locks are writer-preferred once a writer has registered pending intent, but they are not FIFO fair: later readers wait while existing readers drain, and multiple waiting writers are resolved by Redis polling and script execution order rather than a strict queue.

`@DistributedLock` is intentionally synchronous-only. Methods returning `CompletionStage`, reactive publishers, or other async boundaries fail fast with `LockConfigurationException`.

## Programmatic usage

```java
@Service
class UserService {

    private final LockExecutor lockExecutor;

    UserService(LockExecutor lockExecutor) {
        this.lockExecutor = lockExecutor;
    }

    int updateUser(String userId) throws Exception {
        return lockExecutor.withLock(
            new LockRequest(
                new LockKey("user:" + userId),
                LockMode.MUTEX,
                WaitPolicy.timed(Duration.ofSeconds(2))
            ),
            userId::hashCode
        );
    }
}
```

If you need manual lease control or fencing tokens, inject `LockClient` instead:

```java
try (LockSession session = lockClient.openSession();
     LockLease lease = session.acquire(new LockRequest(
         new LockKey("user:" + userId),
         LockMode.MUTEX,
         WaitPolicy.timed(Duration.ofSeconds(2))
     ))) {
    System.out.println(lease.fencingToken().value());
}
```

## Verification

The starter is covered by:

- `DistributedLockAutoConfigurationIntegrationTest`
- `DistributedLockAspectIntegrationTest`
- `DistributedLockAsyncGuardTest`
- `RedisBackendModuleAutoConfigurationTest`
- `ZooKeeperBackendModuleAutoConfigurationTest`
- `RedisStarterIntegrationTest`
- `ZooKeeperStarterIntegrationTest`
