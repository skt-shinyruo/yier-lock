# 架构设计文档

## 总体架构

分布式锁框架采用模块化设计，分为核心层、实现层、集成层和工具层：

```
┌─────────────────────────────────────────────────────────────┐
│                    应用层 (Application)                      │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │            Spring Boot 自动配置层                        │ │
│  │  ┌─────────────────────────────────────────────────────┐ │ │
│  │  │              API 接口层                              │ │ │
│  │  │  ┌─────────────────────────────────────────────────┐ │ │ │
│  │  │  │            核心实现层                            │ │ │ │
│  │  │  │  ┌─────────────────────────────────────────────┐ │ │ │ │
│  │  │  │  │        后端实现层 (Redis/ZooKeeper)          │ │ │ │ │
│  │  │  │  └─────────────────────────────────────────────┘ │ │ │ │
│  │  │  └─────────────────────────────────────────────────┘ │ │ │
│  │  └─────────────────────────────────────────────────────┘ │ │
│  └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

## 模块架构

### 1. distributed-lock-api

**职责**：定义分布式锁的核心接口和数据结构

**核心接口**：
- `DistributedLock` - 分布式锁接口
- `DistributedLockFactory` - 锁工厂接口
- `DistributedReadWriteLock` - 读写锁接口
- `LockConfigurationBuilder` - 配置构建器

**设计原则**：
- 接口单一职责
- 面向抽象编程
- 异步操作支持
- 扩展性设计

### 2. distributed-lock-core

**职责**：提供核心功能实现和基础设施

**核心组件**：
- `ConfiguredDistributedLockFactory` - 配置化工厂
- `LockEventManager` - 事件管理
- `PerformanceMonitor` - 性能监控
- `LockConfiguration` - 配置管理

**特性**：
- 配置管理
- 事件驱动
- 性能监控
- 健康检查

### 3. distributed-lock-redis

**职责**：基于 Redis 的分布式锁实现

**核心实现**：
- `SimpleRedisLock` - 基础 Redis 锁
- `RedisBatchLockOperations` - 批量操作
- `RedisClusterFactory` - 集群支持

**特性**：
- 单机和集群模式
- Lua 脚本优化
- 连接池管理
- 故障转移

### 4. distributed-lock-zookeeper

**职责**：基于 ZooKeeper 的分布式锁实现

**核心实现**：
- `ZooKeeperDistributedLock` - 基础 ZK 锁
- `ZooKeeperBatchLockOperations` - 批量操作
- `ZooKeeperHighAvailabilityStrategy` - 高可用策略

**特性**：
- 临时节点机制
- 顺序节点保证公平性
- 集群协调
- 监听机制

### 5. distributed-lock-spring-boot-starter

**职责**：Spring Boot 集成和自动配置

**核心功能**：
- 自动配置类
- AOP 切面支持
- 健康检查端点
- 配置属性绑定

**特性**：
- 零配置开箱即用
- 注解驱动
- Actuator 集成
- 配置外部化

## 设计模式应用

### 1. 工厂模式

```java
// 锁工厂接口
public interface DistributedLockFactory {
    DistributedLock getLock(String name);
    DistributedReadWriteLock getReadWriteLock(String name);
}

// 具体工厂实现
public class RedisDistributedLockFactory implements DistributedLockFactory {
    @Override
    public DistributedLock getLock(String name) {
        return new SimpleRedisLock(name, redisClient);
    }
}
```

### 2. 建造者模式

```java
// 配置构建器
public interface LockConfigurationBuilder {
    LockConfigurationBuilder leaseTime(long time, TimeUnit unit);
    LockConfigurationBuilder waitTime(long time, TimeUnit unit);
    LockConfiguration build();
}

// 链式调用
LockConfiguration config = LockConfigurationBuilder.newBuilder()
    .leaseTime(30, TimeUnit.SECONDS)
    .waitTime(10, TimeUnit.SECONDS)
    .build();
```

### 3. 策略模式

```java
// 高可用策略接口
public interface HighAvailabilityStrategy<T> {
    T selectBackend(List<T> backends);
    void onBackendFailure(T backend);
    void onBackendRecovery(T backend);
}

// 具体策略实现
public class FailoverStrategy implements HighAvailabilityStrategy<RedisClient> {
    @Override
    public RedisClient selectBackend(List<RedisClient> backends) {
        // 故障转移逻辑
    }
}
```

### 4. 观察者模式

```java
// 事件监听器
public interface LockEventListener<T extends DistributedLock> {
    void onLockEvent(LockEvent<T> event);
}

// 事件管理器
public class LockEventManager {
    private final List<LockEventListener<?>> listeners = new ArrayList<>();

    public void fireEvent(LockEvent<?> event) {
        listeners.forEach(listener -> listener.onLockEvent(event));
    }
}
```

### 5. 装饰器模式

```java
// 锁装饰器
public class MonitoredLock implements DistributedLock {
    private final DistributedLock delegate;
    private final PerformanceMonitor monitor;

    @Override
    public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
        monitor.recordOperationStart("lock");
        try {
            delegate.lock(leaseTime, unit);
            monitor.recordOperationSuccess("lock");
        } catch (Exception e) {
            monitor.recordOperationFailure("lock", e);
            throw e;
        }
    }
}
```

## 数据流设计

### 1. 锁获取流程

```
客户端请求
    ↓
锁工厂创建锁实例
    ↓
配置验证和预处理
    ↓
后端存储操作 (Redis/ZK)
    ↓
结果返回和状态更新
    ↓
事件通知和监控记录
```

### 2. 批量操作流程

```
批量请求
    ↓
预检和依赖分析
    ↓
排序和分组
    ↓
顺序执行 (按依赖关系)
    ↓
事务性保证
    ↓
结果聚合和返回
```

### 3. 监控数据流

```
操作执行
    ↓
性能指标收集
    ↓
事件生成和分发
    ↓
指标聚合和存储
    ↓
告警规则评估
    ↓
告警触发 (可选)
```

## 扩展性设计

### 1. SPI 机制

框架使用 Java SPI 机制实现后端扩展：

```java
// META-INF/services/com.mycorp.distributedlock.api.LockProvider
com.mycorp.distributedlock.redis.SimpleRedisLockProvider
com.mycorp.distributedlock.zookeeper.ZooKeeperLockProvider
```

### 2. 插件架构

支持通过插件扩展功能：

```java
public interface LockPlugin {
    void initialize(LockConfiguration config);
    void destroy();

    default void onLockAcquired(DistributedLock lock) {}
    default void onLockReleased(DistributedLock lock) {}
    default void onLockExpired(DistributedLock lock) {}
}
```

### 3. 拦截器模式

```java
public interface LockInterceptor {
    boolean preLock(String lockName, LockConfiguration config);
    void postLock(String lockName, boolean success);
    void onLockException(String lockName, Exception e);
}
```

## 高可用设计

### 1. 多后端支持

```java
public class MultiBackendLockFactory implements DistributedLockFactory {
    private final List<DistributedLockFactory> backends;
    private final HighAvailabilityStrategy<DistributedLockFactory> strategy;

    @Override
    public DistributedLock getLock(String name) {
        DistributedLockFactory backend = strategy.selectBackend(backends);
        return backend.getLock(name);
    }
}
```

### 2. 故障转移机制

```
主后端正常
    ↓
监控健康状态
    ↓
检测到故障
    ↓
切换到备用后端
    ↓
恢复后切换回主后端
```

### 3. 数据一致性保证

- **Redis 集群**：使用 RedLock 算法
- **ZooKeeper**：依赖 ZK 的一致性保证
- **跨后端**：两阶段提交协议

## 性能优化设计

### 1. 连接池管理

```java
public class ConnectionPoolManager {
    private final GenericObjectPool<RedisClient> pool;

    public RedisClient borrowClient() {
        return pool.borrowObject();
    }

    public void returnClient(RedisClient client) {
        pool.returnObject(client);
    }
}
```

### 2. 缓存优化

```java
public class LockCache {
    private final Cache<String, DistributedLock> lockCache;
    private final Cache<String, LockStateInfo> stateCache;

    public DistributedLock getCachedLock(String name) {
        return lockCache.get(name, key -> createNewLock(key));
    }
}
```

### 3. 批量操作优化

```java
public class BatchOptimizer {
    public List<CompletableFuture<Void>> optimizeBatch(
            List<String> lockNames,
            BatchOperationExecutor executor) {

        // 分析依赖关系
        DependencyGraph graph = analyzeDependencies(lockNames);

        // 分组执行
        return graph.getExecutionGroups().stream()
            .map(group -> executeGroup(group, executor))
            .collect(Collectors.toList());
    }
}
```

## 安全设计

### 1. 权限控制

```java
public interface LockSecurityManager {
    boolean hasPermission(String lockName, String operation, Principal principal);
    void checkPermission(String lockName, String operation, Principal principal)
        throws AccessDeniedException;
}
```

### 2. 审计日志

```java
public class LockAuditor {
    public void auditLockOperation(String lockName, String operation,
                                 Principal principal, boolean success) {
        AuditEvent event = new AuditEvent(lockName, operation, principal, success);
        auditLogger.log(event);
    }
}
```

### 3. 加密支持

```java
public class EncryptedLock implements DistributedLock {
    private final DistributedLock delegate;
    private final Cipher cipher;

    @Override
    public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
        // 加密锁名
        String encryptedName = encrypt(delegate.getName());
        // 使用加密后的名称操作
    }
}
```

## 测试架构

### 1. 测试分层

```
单元测试 (Unit Tests)
    ↓
集成测试 (Integration Tests)
    ↓
系统测试 (System Tests)
    ↓
性能测试 (Performance Tests)
    ↓
压力测试 (Stress Tests)
```

### 2. 测试工具

- **JUnit 5**：单元测试框架
- **Testcontainers**：容器化测试
- **JMH**：性能基准测试
- **Mockito**：模拟对象
- **Awaitility**：异步测试

### 3. 测试覆盖

- **单元测试**：核心逻辑 > 80%
- **集成测试**：模块协作 > 70%
- **系统测试**：端到端场景 > 60%
- **性能测试**：关键路径基准

## 部署架构

### 1. 单机部署

```
┌─────────────────┐
│   Application   │
│  ┌────────────┐ │
│  │Distributed │ │
│  │    Lock    │ │
│  └────────────┘ │
│        │        │
│  ┌─────┴─────┐  │
│  │  Backend  │  │
│  │(Redis/ZK) │  │
│  └───────────┘  │
└─────────────────┘
```

### 2. 集群部署

```
┌─────────────────┐  ┌─────────────────┐
│   App Node 1    │  │   App Node 2    │
│  ┌────────────┐ │  │  ┌────────────┐ │
│  │Distributed │ │  │  │Distributed │ │
│  │    Lock    │ │  │  │    Lock    │ │
│  └────────────┘ │  │  └────────────┘ │
└─────────┬───────┘  └─────────┬───────┘
          │                     │
          └─────────┬───────────┘
                    │
          ┌─────────┴───────────┐
          │     Backend         │
          │   Cluster           │
          │  (Redis/ZK)         │
          └─────────────────────┘
```

### 3. 多数据中心部署

```
┌─────────────────────────────────────┐
│           Data Center 1             │
│  ┌─────────────────┐ ┌────────────┐ │
│  │   App Cluster   │ │  Backend   │ │
│  │                 │ │  Cluster   │ │
│  └─────────────────┘ └────────────┘ │
└─────────────────────────────────────┘
                    │
                    │ (Replication)
                    │
┌─────────────────────────────────────┐
│           Data Center 2             │
│  ┌─────────────────┐ ┌────────────┐ │
│  │   App Cluster   │ │  Backend   │ │
│  │                 │ │  Cluster   │ │
│  └─────────────────┘ └────────────┘ │
└─────────────────────────────────────┘
```

## 监控架构

### 1. 指标收集

```java
public class MetricsCollector {
    private final MeterRegistry registry;

    public void recordLockOperation(String operation, long duration, boolean success) {
        Timer.Sample sample = Timer.start(registry);
        // 执行操作
        sample.stop(Timer.builder("distributed_lock.operation")
            .tag("operation", operation)
            .tag("success", String.valueOf(success))
            .register(registry));
    }
}
```

### 2. 健康检查

```java
public class LockHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        try {
            // 执行健康检查
            boolean healthy = performHealthCheck();
            return healthy ? Health.up().build() :
                           Health.down().withDetail("error", "Lock service unavailable").build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
```

### 3. 告警系统

```java
public class AlertingService {
    public void checkAndAlert(MetricsSnapshot snapshot) {
        // 检查阈值
        if (snapshot.getErrorRate() > 0.05) {
            alertManager.sendAlert("High error rate detected: " + snapshot.getErrorRate());
        }

        if (snapshot.getAverageResponseTime() > 1000) {
            alertManager.sendAlert("Slow response time: " + snapshot.getAverageResponseTime() + "ms");
        }
    }
}
```

## 总结

该分布式锁框架采用分层架构设计，确保了：

1. **模块化**：清晰的职责分离和依赖关系
2. **扩展性**：SPI 机制和插件架构支持功能扩展
3. **高可用**：多后端支持和故障转移机制
4. **可观测性**：全面的监控、指标和告警能力
5. **安全性**：权限控制、审计和加密支持
6. **可维护性**：良好的测试覆盖和文档体系

通过精心设计的架构，框架能够在保证高性能的同时，提供企业级的可靠性和可扩展性。