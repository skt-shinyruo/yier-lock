# 分布式锁 2.0 测试套件

本文档只记录 2.0 分支当前维护的回归入口。

## 当前测试布局

- `distributed-lock-api`
  - `ApiSurfaceTest`
- `distributed-lock-core`
  - `DefaultLockManagerTest`
- `distributed-lock-runtime`
  - `LockRuntimeBuilderTest`
- `distributed-lock-testkit`
  - `InMemoryLockManagerContractTest`
- `distributed-lock-redis`
  - `RedisBackendModuleTest`
  - `RedisLockBackendContractTest`
- `distributed-lock-zookeeper`
  - `ZooKeeperBackendModuleTest`
  - `ZooKeeperLockBackendContractTest`
- `distributed-lock-spring-boot-starter`
  - `DistributedLockAutoConfigurationIntegrationTest`
  - `DistributedLockAspectIntegrationTest`
  - `RedisStarterIntegrationTest`
  - `ZooKeeperStarterIntegrationTest`

## 推荐命令

```bash
# starter 定向验证
mvn -pl distributed-lock-spring-boot-starter -am test \
  -Dtest=DistributedLockAutoConfigurationIntegrationTest,DistributedLockAspectIntegrationTest,RedisStarterIntegrationTest,ZooKeeperStarterIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# 全仓回归
mvn test
```

## 说明

- `distributed-lock-benchmarks` 不在默认 reactor 中，不作为 2.0 主线回归入口。
- 顶层旧 `examples/` 已废弃；当前示例资产位于 `distributed-lock-examples`。
- 1.x 的 provider、factory、batch、async、health 相关测试已不再适用。
