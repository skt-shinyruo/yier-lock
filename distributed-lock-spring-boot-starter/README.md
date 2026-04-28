# Distributed Lock Spring Boot Starter

This starter is the Spring Boot 3 integration layer for distributed lock 2.0.

## Scope

The starter only provides:

- `LockRuntime` auto-configuration
- `LockClient` and `SynchronousLockExecutor` bean exposure
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

### Observability toggles

`distributed.lock.observability.enabled=false` disables runtime decoration. When observability is enabled, `distributed.lock.observability.logging.enabled=false` disables diagnostic log events and `distributed.lock.observability.metrics.enabled=false` disables Micrometer timers. `distributed.lock.observability.include-lock-key-in-logs=true` may include raw lock keys in diagnostic logs only; metrics never include raw lock keys as tags.

## Requirements

- Java 17+
- Spring Boot 3.2.x is the primary tested line
- Spring Boot 3.3.x and 3.4.x are compatibility targets verified by overriding `spring-boot.version` during regression
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

    @DistributedLock(key = "order:#{#p0}", waitFor = "2s", leaseFor = "10s")
    public void processOrder(String orderId) {
        FencingToken token = LockContext.requireCurrentFencingToken();
        // critical section
    }
}
```

Supported annotation fields:

- `key`
- `mode`
- `waitFor`
- `leaseFor`

Blank `waitFor` waits indefinitely, `waitFor = "0s"` tries once, and a positive value waits up to that duration.
Blank `leaseFor` uses the backend default lease duration, while a positive value requests a fixed lease duration.

Lock keys follow Spring template-expression semantics. Literal keys such as `order:42` pass through unchanged, while templates such as `order:#{#p0}` are evaluated against the intercepted method arguments.

Lock modes protect the same resource identity for a given key. `MUTEX` and `WRITE` are exclusive and block all `MUTEX`, `READ`, and `WRITE` acquisitions for the same key. `READ` can coexist only with other `READ` leases for the same key. Use distinct lock keys when you intentionally need independent lock families.

Backend progress semantics are backend-specific. Redis read/write locks are exclusive-preferred once a `WRITE` or `MUTEX` acquisition has registered pending intent, but they are not FIFO fair: later readers wait while existing readers drain, and multiple waiting exclusive acquisitions are resolved by Redis polling and script execution order rather than a strict queue. ZooKeeper uses fail-safe session semantics: `SUSPENDED` and `LOST` Curator states both make the lock session terminally lost because ownership cannot be proven while disconnected.

`@DistributedLock` is intentionally synchronous-only. Methods returning `CompletionStage`, reactive publishers, or other async boundaries fail fast with `LockConfigurationException`.

### AOP boundaries

`@DistributedLock` is a Spring AOP interceptor and follows normal proxy rules. Interface-method and implementation-method annotations are supported for JDK and CGLIB proxies. Self-invocation inside the same bean is not intercepted. The interceptor is synchronous-only and rejects methods annotated with `@Async`, class-level `@Async`, `CompletionStage`, `Future`, and reactive `Publisher` return types before invoking user code when those boundaries are visible from the method signature or annotations.

## Programmatic usage

```java
@Service
class UserService {

    private final SynchronousLockExecutor lockExecutor;

    UserService(SynchronousLockExecutor lockExecutor) {
        this.lockExecutor = lockExecutor;
    }

    int updateUser(String userId) throws Exception {
        return lockExecutor.withLock(
            new LockRequest(
                new LockKey("user:" + userId),
                LockMode.MUTEX,
                WaitPolicy.timed(Duration.ofSeconds(2))
            ),
            lease -> userId.hashCode()
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
- `DistributedLockProxyBoundaryTest`
- `SpelLockKeyResolverTest`
- `RedisBackendModuleAutoConfigurationTest`
- `ZooKeeperBackendModuleAutoConfigurationTest`
- `RedisStarterIntegrationTest`
- `ZooKeeperStarterIntegrationTest`
