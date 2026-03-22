# 分布式锁测试套件配置

本文档描述当前分支仍然有效的测试入口和常用执行命令。

## 当前维护的测试范围

- API 单元测试
  - `DistributedLockApiTest`
  - `DistributedReadWriteLockApiTest`
  - `DistributedLockFactoryApiTest`
  - `ServiceLoaderDistributedLockFactoryTest`
- Core 单元测试
  - `LockConfigurationTest`
  - `EnhancedReentrantLockImplTest`
  - `LockKeyUtilsTest`
- Redis 单元测试
  - `SimpleRedisLockTest`
  - `SimpleRedisLockProviderTest`
  - `RedisDistributedLockFactoryTest`
- ZooKeeper 测试
  - `ZooKeeperBatchLockOperationsTest`
  - `ZooKeeperDistributedLockIntegrationTest` (`@Tag("integration")`)
- Spring Boot 集成测试
  - `DistributedLockAspectIntegrationTest`
  - `DistributedLockAutoConfigurationIntegrationTest`
- Benchmarks 测试
  - `StressAndPerformanceTest`

## 推荐命令

```bash
# 全仓非集成测试
mvn test -Dgroups=!integration

# Redis / ZooKeeper / Starter 修复回归
mvn -pl distributed-lock-redis,distributed-lock-zookeeper,distributed-lock-spring-boot-starter -am \
  -Dtest=SimpleRedisLockTest,RedisDistributedLockFactoryTest,ZooKeeperBatchLockOperationsTest,DistributedLockAutoConfigurationIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

# 仅跑 ZooKeeper 集成测试
mvn -pl distributed-lock-zookeeper -am -Dtest=ZooKeeperDistributedLockIntegrationTest test
```

## 维护说明

- 旧文档中出现的监控、事件、故障容错和端到端测试条目，当前分支已经删除或停止维护。
- `examples` 目录未纳入根 `pom.xml` 的 reactor，旧示例测试不再视为有效回归资产。
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>

<!-- Mockito -->
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

<!-- AssertJ -->
<dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <scope>test</scope>
</dependency>

<!-- TestContainers -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>

<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>redis</artifactId>
    <scope>test</scope>
</dependency>

<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>zookeeper</artifactId>
    <scope>test</scope>
</dependency>
```

### Spring Boot测试依赖
```xml
<!-- Spring Boot Test -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>

<!-- Spring Boot Test Starter Web -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <scope>test</scope>
</dependency>

<!-- Awaitility (异步等待) -->
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <scope>test</scope>
</dependency>
```

### 性能测试依赖
```xml
<!-- JMH性能基准 -->
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-core</artifactId>
    <scope>test</scope>
</dependency>

<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-generator-annprocess</artifactId>
    <scope>test</scope>
</dependency>
```

### 覆盖率工具
```xml
<!-- JaCoCo代码覆盖率 -->
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.8</version>
    <executions>
        <execution>
            <goals>
                <goal>prepare-agent</goal>
            </goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals>
                <goal>report</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

## 测试环境配置

### Docker Compose测试环境
```yaml
# docker-compose.test.yml
version: '3.8'
services:
  redis-test:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    command: redis-server --appendonly yes
    
  zookeeper-test:
    image: confluentinc/cp-zookeeper:7.0.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
    ports:
      - "2181:2181"
```

### 测试配置属性
```properties
# test-application.properties
distributed.lock.enabled=true
distributed.lock.metrics.enabled=true
distributed.lock.aspect.enabled=true

# Redis测试配置
spring.redis.host=localhost
spring.redis.port=6379

# Zookeeper测试配置
zookeeper.connection-string=localhost:2181
zookeeper.session-timeout=60000

# 测试环境特定配置
logging.level.com.mycorp.distributedlock=DEBUG
logging.level.org.springframework=WARN
```

## CI/CD集成

### GitHub Actions工作流
```yaml
# .github/workflows/test.yml
name: Test Suite

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main ]

jobs:
  test:
    runs-on: ubuntu-latest
    
    services:
      redis:
        image: redis:7-alpine
        ports:
          - 6379:6379
        options: >-
          --health-cmd "redis-cli ping"
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
      
      zookeeper:
        image: confluentinc/cp-zookeeper:7.0.0
        env:
          ZOOKEEPER_CLIENT_PORT: 2181
        ports:
          - 2181:2181
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Set up JDK 17
      uses: actions/setup-java@v3
      with:
        java-version: '17'
        distribution: 'temurin'
        
    - name: Cache Maven packages
      uses: actions/cache@v3
      with:
        path: ~/.m2
        key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}
        restore-keys: ${{ runner.os }}-m2
        
    - name: Run tests
      run: mvn clean test
      
    - name: Generate test report
      run: mvn surefire-report:report
      
    - name: Upload test results
      uses: actions/upload-artifact@v3
      if: always()
      with:
        name: test-results
        path: target/surefire-reports/
```

## 测试最佳实践

### 1. 命名约定
- 测试类: `{功能}Test` 或 `{功能}IntegrationTest`
- 测试方法: `shouldXxxWhenYzz()`
- 集成测试: `*IntegrationTest.java`
- 端到端测试: `*EndToEndTest.java`

### 2. 测试分类
```java
@Tag("unit")     // 单元测试
@Tag("integration") // 集成测试
@Tag("performance") // 性能测试
@Tag("e2e")      // 端到端测试
```

### 3. 测试组织
- 每个模块独立的测试
- 测试按功能分组(@Nested)
- 使用描述性@DisplayName
- 测试数据使用工厂方法

### 4. 断言最佳实践
- 使用AssertJ进行流式断言
- 具体的错误消息
- 验证副作用
- 检查异常情况

### 5. Mock使用
- Mockito模拟外部依赖
- 最小化Mock的使用
- 验证交互而不是实现
- 使用@Mock注解

## 待完成任务

### 1. Zookeeper实现单元测试
- `ZooKeeperDistributedLockTest.java`
- `ZooKeeperLockProviderTest.java`
- 预计30+测试方法

### 2. 测试覆盖率验证
- 运行JaCoCo覆盖率报告
- 确保>90%覆盖率目标
- 补充缺失的测试用例

### 3. 性能回归检测
- 建立性能基准
- 设置性能阈值
- 集成到CI/CD流程

### 4. 测试环境自动化
- Docker容器化测试环境
- 自动化测试数据准备
- 测试结果收集和分析

## 维护指南

### 1. 新功能测试
- 为新功能添加单元测试
- 更新集成测试
- 验证性能影响

### 2. Bug修复测试
- 添加重现测试
- 验证修复效果
- 防止回归

### 3. 性能优化测试
- 基准测试对比
- 内存使用监控
- 压力测试验证

---

**版本**: 1.0.0  
**最后更新**: 2025-10-26  
**维护者**: 分布式锁开发团队
