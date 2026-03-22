# 分布式锁 2.0 测试配置

本文档描述 2.0 当前维护的测试入口和常用命令。

## 当前维护的测试范围

- API
  - `ApiSurfaceTest`
- Core
  - `DefaultLockManagerTest`
- Runtime
  - `LockRuntimeBuilderTest`
- Contract testkit
  - `InMemoryLockManagerContractTest`
- Redis adapter
  - `RedisBackendModuleTest`
  - `RedisLockBackendContractTest`
- ZooKeeper adapter
  - `ZooKeeperBackendModuleTest`
  - `ZooKeeperLockBackendContractTest`
- Spring Boot starter
  - `DistributedLockAutoConfigurationIntegrationTest`
  - `DistributedLockAspectIntegrationTest`
  - `RedisStarterIntegrationTest`
  - `ZooKeeperStarterIntegrationTest`

## 推荐命令

```bash
# starter 重点回归
mvn -pl distributed-lock-spring-boot-starter -am test \
  -Dtest=DistributedLockAutoConfigurationIntegrationTest,DistributedLockAspectIntegrationTest,RedisStarterIntegrationTest,ZooKeeperStarterIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# Redis adapter 契约测试
mvn -pl distributed-lock-redis -am test \
  -Dtest=RedisLockBackendContractTest,RedisBackendModuleTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# ZooKeeper adapter 契约测试
mvn -pl distributed-lock-zookeeper -am test \
  -Dtest=ZooKeeperLockBackendContractTest,ZooKeeperBackendModuleTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

## 维护说明

- 2.0 不再维护 1.x 的 factory/provider/async/batch/health 测试资产。
- `distributed-lock-benchmarks` 不参与默认 reactor，也不作为主线验收条件。
- 示例代码已经迁移到 `distributed-lock-examples`，旧 `examples/` 不再是有效资产。
