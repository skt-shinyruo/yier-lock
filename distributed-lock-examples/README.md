# Distributed Lock 2.0 Examples

`distributed-lock-examples` contains compile-checked usage samples for the 2.0 API.

## Included examples

- `ProgrammaticRedisExample`
  - selects the Redis backend explicitly
  - builds a `LockRuntime` from a typed `RedisBackendModule`
  - uses `LockRuntime.synchronousLockExecutor()` for scoped execution
- `ProgrammaticZooKeeperExample`
  - selects the ZooKeeper backend explicitly
  - uses `LockRuntime.lockClient()` plus `LockSession.acquire(...)`
  - prints the acquired lease fencing token
- `SpringBootRedisExampleApplication`
  - shows Spring Boot 3 auto-configuration with a backend-specific Spring module
  - demonstrates both `@DistributedLock` and programmatic `SynchronousLockExecutor` usage

## Configuration model

Programmatic examples instantiate typed backend modules directly:

Programmatic runtime construction must always declare `.backend("...")`; backend discovery can supply candidate modules, but it is not allowed to auto-select one.

```java
LockRuntime runtime = LockRuntimeBuilder.create()
    .backend("redis")
    .backendModules(List.of(new RedisBackendModule(
        new RedisBackendConfiguration("redis://127.0.0.1:6379", 30L)
    )))
    .build();

String result = runtime.synchronousLockExecutor().withLock(
    new LockRequest(
        new LockKey("example:redis:order-42"),
        LockMode.MUTEX,
        WaitPolicy.timed(Duration.ofSeconds(2))
    ),
    lease -> "Redis lease acquired with fencing token " + lease.fencingToken().value()
);
```

Spring examples use the 2.0 namespace and require the matching backend Spring auto-config module on the classpath:

```yaml
distributed:
  lock:
    enabled: true
    backend: redis
    redis:
      uri: redis://127.0.0.1:6379
      lease-time: 30s
```

Backend-specific settings stay under `distributed.lock.redis.*` or `distributed.lock.zookeeper.*`, but the generic starter only owns generic `distributed.lock.*` runtime and annotation settings.

## Notes

- These examples are part of the default reactor and must compile on every build.
- They are documentation assets, not part of the regression test contract.
- Running the Redis or ZooKeeper examples requires a local backend instance.
- Spring examples also require the matching backend Spring auto-config artifact, which this module now depends on directly.
