# 分布式锁测试套件

本目录的文档只记录当前仓库里仍然存在、且在当前分支维护的测试入口。

## 当前测试布局

- `distributed-lock-api`
  - `DistributedLockApiTest`
  - `DistributedReadWriteLockApiTest`
  - `DistributedLockFactoryApiTest`
  - `ServiceLoaderDistributedLockFactoryTest`
- `distributed-lock-core`
  - `LockConfigurationTest`
  - `EnhancedReentrantLockImplTest`
  - `LockKeyUtilsTest`
- `distributed-lock-redis`
  - `SimpleRedisLockTest`
  - `SimpleRedisLockProviderTest`
  - `RedisDistributedLockFactoryTest`
- `distributed-lock-zookeeper`
  - `ZooKeeperBatchLockOperationsTest`
  - `ZooKeeperDistributedLockIntegrationTest` (`@Tag("integration")`)
- `distributed-lock-spring-boot-starter`
  - `DistributedLockAspectIntegrationTest`
  - `DistributedLockAutoConfigurationIntegrationTest`
- `distributed-lock-benchmarks`
  - `StressAndPerformanceTest`

## 执行命令

```bash
# 当前默认的非集成测试回归入口
mvn test -Dgroups=!integration

# 定向验证 Redis/Starter/ZooKeeper 修复
mvn -pl distributed-lock-redis,distributed-lock-zookeeper,distributed-lock-spring-boot-starter -am \
  -Dtest=SimpleRedisLockTest,RedisDistributedLockFactoryTest,ZooKeeperBatchLockOperationsTest,DistributedLockAutoConfigurationIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## 说明

- 早期文档里提到的监控、事件、故障容错和端到端测试文件，当前分支已经删除或不再维护，不应再作为现行测试清单引用。
- `examples` 目录当前不在根 `pom.xml` 的 reactor 中，因此不再把其中的旧测试文件视为有效回归入口。
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>

<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <scope>test</scope>
</dependency>

<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

## 🏃‍♂️ 测试执行

### 运行所有测试
```bash
# 在项目根目录执行
mvn clean test

# 运行特定模块测试
mvn clean test -pl distributed-lock-api
mvn clean test -pl distributed-lock-core
mvn clean test -pl distributed-lock-redis
```

### 运行特定测试类
```bash
# API接口测试
mvn test -Dtest=DistributedLockApiTest
mvn test -Dtest=DistributedReadWriteLockApiTest
mvn test -Dtest=DistributedLockFactoryApiTest

# 核心模块测试
mvn test -Dtest=LockConfigurationTest
mvn test -Dtest=LockMonitoringServiceTest
mvn test -Dtest=LockEventManagerTest
mvn test -Dtest=EnhancedReentrantLockImplTest
mvn test -Dtest=LockKeyUtilsTest

# Redis实现测试
mvn test -Dtest=SimpleRedisLockTest
mvn test -Dtest=SimpleRedisLockProviderTest
```

### 生成测试报告
```bash
# 生成HTML测试报告
mvn surefire-report:report

# 查看覆盖率报告
mvn jacoco:report
```

## 📈 测试质量保证

### 测试标准
- **测试方法命名**: 使用 `shouldXxx` 格式
- **测试分类**: 使用 `@Tag` 注解区分测试类型
- **异常测试**: 所有异常情况必须有对应测试
- **并发测试**: 关键功能必须包含并发测试
- **性能测试**: 性能关键路径需要性能基准

### 最佳实践
1. **隔离性**: 每个测试独立运行，不依赖其他测试
2. **可重复性**: 测试结果应该一致
3. **快速性**: 单元测试应该在秒级完成
4. **可维护性**: 测试代码应该清晰易懂
5. **覆盖率**: 保持高代码覆盖率

## 🚀 待实现测试

### 下阶段计划
1. **Spring Boot集成测试** (5个测试文件)
2. **高级特性测试** (3个测试文件)
3. **监控和指标测试** (2个测试文件)
4. **压力和性能测试** (3个测试文件)
5. **故障容错测试** (4个测试文件)
6. **端到端测试** (2个测试文件)

### 测试环境配置
1. **Docker容器化测试环境**
2. **Redis集群测试环境**
3. **Zookeeper集群测试环境**
4. **性能测试基准环境**

## 📞 测试支持

### 常见问题
1. **测试运行失败**: 检查依赖和环境配置
2. **覆盖率不达标**: 分析未覆盖代码，补充测试
3. **性能测试不稳定**: 检查测试环境，减少外部干扰

### 扩展指南
1. **添加新测试**: 在相应模块创建测试文件
2. **修复失败测试**: 检查测试逻辑和环境
3. **优化测试性能**: 使用模拟对象，减少实际依赖

---

**测试套件版本**: 1.0.0  
**最后更新**: 2025-10-26  
**维护者**: 分布式锁开发团队
