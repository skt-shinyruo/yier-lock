# Distributed Lock 2.0 Examples

`distributed-lock-examples` contains compile-checked usage samples for the 2.0 API.

## Included examples

- `ProgrammaticRedisExample`
  - builds a `LockRuntime` explicitly
  - acquires a mutex through `LockManager`
- `ProgrammaticZooKeeperExample`
  - uses the same 2.0 runtime flow against ZooKeeper
- `SpringBootRedisExampleApplication`
  - shows Spring Boot 3 auto-configuration
  - demonstrates both `@DistributedLock` and programmatic `LockManager` usage

## Configuration model

All examples use the 2.0 configuration namespace:

```yaml
distributed:
  lock:
    enabled: true
    backend: redis
```

Backend-specific settings stay under `distributed.lock.redis.*` or `distributed.lock.zookeeper.*`.

## Notes

- These examples are part of the default reactor and must compile on every build.
- They are documentation assets, not part of the regression test contract.
- Running the Redis or ZooKeeper examples requires a local backend instance.
