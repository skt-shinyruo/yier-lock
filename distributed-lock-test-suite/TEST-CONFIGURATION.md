# 分布式锁 2.0 测试配置

本文档描述 2.0 当前维护的测试入口和常用命令。

## 当前维护的测试范围

- `ApiSurfaceTest`
- `LockBackendSurfaceTest`
- `DefaultLockClientTest`
- `DefaultSynchronousLockExecutorTest`
- `LockRuntimeBuilderTest`
- `InMemoryLockClientContractTest`
- `RedisBackendModuleTest`
- `RedisLockBackendContractTest`
- `RedisLeaseRenewalTest`
- `RedisOwnershipLossTest`
- `ZooKeeperBackendModuleTest`
- `ZooKeeperLockBackendContractTest`
- `ZooKeeperSessionLossTest`
- `DistributedLockAutoConfigurationIntegrationTest`
- `DistributedLockAspectIntegrationTest`
- `DistributedLockAsyncGuardTest`
- `RedisBackendModuleAutoConfigurationTest`
- `ZooKeeperBackendModuleAutoConfigurationTest`
- `RedisStarterIntegrationTest`
- `ZooKeeperStarterIntegrationTest`

## 推荐命令

```bash
# runtime / starter / backend Spring 模块
mvn -pl distributed-lock-runtime,distributed-lock-spring-boot-starter,distributed-lock-redis-spring-boot-autoconfigure,distributed-lock-zookeeper-spring-boot-autoconfigure -am test \
  -Dtest=LockRuntimeBuilderTest,DistributedLockAutoConfigurationIntegrationTest,DistributedLockAspectIntegrationTest,DistributedLockAsyncGuardTest,RedisBackendModuleAutoConfigurationTest,ZooKeeperBackendModuleAutoConfigurationTest,RedisStarterIntegrationTest,ZooKeeperStarterIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# Redis adapter
mvn -pl distributed-lock-redis -am test \
  -Dtest=RedisBackendModuleTest,RedisLockBackendContractTest,RedisLeaseRenewalTest,RedisOwnershipLossTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# ZooKeeper adapter
mvn -pl distributed-lock-zookeeper -am test \
  -Dtest=ZooKeeperBackendModuleTest,ZooKeeperLockBackendContractTest,ZooKeeperSessionLossTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# core / testkit
mvn -pl distributed-lock-core,distributed-lock-testkit -am test \
  -Dtest=LockBackendSurfaceTest,DefaultLockClientTest,DefaultSynchronousLockExecutorTest,InMemoryLockClientContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

## 维护说明

- 2.0 不再维护 1.x 的 factory/provider/async/batch/health 测试资产。
- `distributed-lock-benchmarks` 不参与默认 reactor，也不作为主线验收条件。
- `distributed-lock-benchmarks` 依赖本地已安装的 snapshot；修改 runtime/backend/starter 后要先重新执行 `mvn -q install -DskipTests`。
- 示例代码已经迁移到 `distributed-lock-examples`，旧 `examples/` 不再是有效资产。
- 当前主线覆盖非重入、`LockReentryException`、`WaitPolicy.tryOnce()` 和 `LeasePolicy.fixed(...)` 的兼容性边界。
