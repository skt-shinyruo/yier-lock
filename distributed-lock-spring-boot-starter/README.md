# Distributed Lock Spring Boot Starter

This starter is the Spring Boot 3 integration layer for distributed lock 2.0.

## Scope

The starter only provides:

- `LockRuntime` auto-configuration
- `LockManager` bean exposure
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

## Requirements

- Java 17+
- Spring Boot 3.x
- one backend Spring auto-config module on the classpath, or an explicit backend module bean

## Configuration

The generic starter owns only generic runtime and annotation settings:

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

`@DistributedLock` is intentionally synchronous-only. Methods returning `CompletionStage`, reactive publishers, or other async boundaries fail fast with `LockConfigurationException`.

## Programmatic usage

```java
@Service
class UserService {

    private final LockManager lockManager;

    UserService(LockManager lockManager) {
        this.lockManager = lockManager;
    }

    void updateUser(String userId) throws InterruptedException {
        MutexLock lock = lockManager.mutex("user:" + userId);
        if (!lock.tryLock(Duration.ofSeconds(2))) {
            throw new IllegalStateException("lock busy");
        }
        try (lock) {
            // critical section
        }
    }
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
