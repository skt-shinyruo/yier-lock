# 分布式锁 2.0 测试套件

本文档记录 2.0 当前维护的主线回归入口。

## 当前维护的测试

- `ApiSurfaceTest`
- `LockBackendSurfaceTest`
- `DefaultLockClientTest`
- `DefaultSynchronousLockExecutorTest`
- `SessionBoundLockLeaseConcurrencyTest`
- `LockRuntimeBuilderTest`
- `InMemoryLockClientContractTest`
- `InMemoryLockBackendThreadOwnershipTest`
- `FencedResourceConcurrencyTest`
- `RedisBackendModuleTest`
- `RedisLockBackendContractTest`
- `RedisLeaseRenewalTest`
- `RedisOwnershipLossTest`
- `RedisReadWriteWriterPreferenceTest`
- `RedisKeyStrategyTest`
- `ZooKeeperBackendModuleTest`
- `ZooKeeperLockBackendContractTest`
- `ZooKeeperSessionLossTest`
- `ZooKeeperAcquireWaitLifecycleTest`
- `ZooKeeperPathAndQueueValidationTest`
- `ZooKeeperFencingOwnershipRecheckTest`
- `DistributedLockAutoConfigurationIntegrationTest`
- `DistributedLockAspectIntegrationTest`
- `DistributedLockAsyncGuardTest`
- `DistributedLockProxyBoundaryTest`
- `SpelLockKeyResolverTest`
- `RedisBackendModuleAutoConfigurationTest`
- `ZooKeeperBackendModuleAutoConfigurationTest`
- `RedisStarterIntegrationTest`
- `ZooKeeperStarterIntegrationTest`
- `ObservedLockSessionTest`
- `ObservedLockExecutorTest`
- `ObservedLockThrowableTest`
- `CompositeLockObservationSinkTest`
- `DistributedLockObservabilityAutoConfigurationTest`

## 推荐命令

```bash
# core / testkit 主线验证
mvn -pl distributed-lock-core,distributed-lock-testkit -am test \
  -Dtest=LockBackendSurfaceTest,DefaultLockClientTest,DefaultSynchronousLockExecutorTest,SessionBoundLockLeaseConcurrencyTest,InMemoryLockClientContractTest,InMemoryLockBackendThreadOwnershipTest,FencedResourceConcurrencyTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# runtime / starter / backend Spring 模块验证
mvn -pl distributed-lock-runtime,distributed-lock-spring-boot-starter,distributed-lock-redis-spring-boot-autoconfigure,distributed-lock-zookeeper-spring-boot-autoconfigure -am test \
  -Dtest=LockRuntimeBuilderTest,DistributedLockAutoConfigurationIntegrationTest,DistributedLockAspectIntegrationTest,DistributedLockAsyncGuardTest,DistributedLockProxyBoundaryTest,SpelLockKeyResolverTest,RedisBackendModuleAutoConfigurationTest,ZooKeeperBackendModuleAutoConfigurationTest,RedisStarterIntegrationTest,ZooKeeperStarterIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# Redis adapter 验证
mvn -pl distributed-lock-redis -am test \
  -Dtest=RedisBackendModuleTest,RedisLockBackendContractTest,RedisLeaseRenewalTest,RedisOwnershipLossTest,RedisReadWriteWriterPreferenceTest,RedisKeyStrategyTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# ZooKeeper adapter 验证
mvn -pl distributed-lock-zookeeper -am test \
  -Dtest=ZooKeeperBackendModuleTest,ZooKeeperLockBackendContractTest,ZooKeeperSessionLossTest,ZooKeeperAcquireWaitLifecycleTest,ZooKeeperPathAndQueueValidationTest,ZooKeeperFencingOwnershipRecheckTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# observability extension 验证
mvn -pl distributed-lock-extension-observability -am test \
  -Dtest=ObservedLockSessionTest,ObservedLockExecutorTest,ObservedLockThrowableTest \
  -Dsurefire.failIfNoSpecifiedTests=false

mvn -pl distributed-lock-extension-observability-spring -am test \
  -Dtest=CompositeLockObservationSinkTest,DistributedLockObservabilityAutoConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# 全仓回归
mvn test
```

## 说明

- `distributed-lock-benchmarks` 不在默认 reactor 中，不作为 2.0 主线回归入口。
- benchmarks 依赖安装到本地仓库的 snapshot；修改 runtime/backend/starter 后需要先重新执行 `mvn -q install -DskipTests`。
- 顶层旧 `examples/` 已废弃；当前示例资产位于 `distributed-lock-examples`。
- 当前回归覆盖非重入语义、`LockReentryException`、`WaitPolicy.tryOnce()` 和 `LeasePolicy.fixed(...)` 能力检查。
- 1.x 的 provider、factory、batch、async、health 相关测试已不再适用。
