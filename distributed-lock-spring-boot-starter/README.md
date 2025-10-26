# 分布式锁 Spring Boot 3.x Starter

Spring Boot 3.x 自动配置分布式锁库，支持 Redis 和 ZooKeeper 后端，提供完整的 AOP 切面支持、健康检查和监控集成。

## 🚀 特性

### 核心功能
- **自动配置**: 开箱即用的 Spring Boot 3.x 自动配置
- **多后端支持**: Redis 和 ZooKeeper 分布式锁实现
- **注解式编程**: `@DistributedLock` 注解支持 AOP 切面自动管理
- **健康检查**: 集成 Spring Boot Actuator 健康检查端点
- **监控指标**: Micrometer 指标收集和 OpenTelemetry 分布式追踪

### Spring Boot 3.x 特性
- **Jakarta EE 9+**: 使用最新的 Jakarta EE 标准
- **GraalVM 原生镜像**: 支持原生镜像构建
- **Spring Boot Actuator**: 完整的监控和管理端点
- **配置验证**: Bean Validation 和配置属性验证
- **条件化配置**: 智能的自动配置条件判断

## 📦 依赖安装

### Maven

```xml
<dependency>
    <groupId>com.mycorp</groupId>
    <artifactId>distributed-lock-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Gradle

```gradle
implementation 'com.mycorp:distributed-lock-spring-boot-starter:1.0.0-SNAPSHOT'
```

## ⚙️ 配置

### 基本配置

```yaml
spring:
  distributed-lock:
    enabled: true
    type: redis  # redis 或 zookeeper
    default-lease-time: 30s
    default-wait-time: 10s
```

### Redis 配置

```yaml
spring:
  distributed-lock:
    type: redis
    redis:
      hosts: "localhost:6379"
      password: "your-password"
      ssl: true
      pool:
        max-total: 8
        max-idle: 8
        min-idle: 0
```

### ZooKeeper 配置

```yaml
spring:
  distributed-lock:
    type: zookeeper
    zookeeper:
      connect-string: "localhost:2181"
      base-path: "/distributed-locks"
      session-timeout: 60s
      connection-timeout: 15s
```

## 💡 使用方法

### 1. 注解式使用

```java
@Service
public class OrderService {

    @DistributedLock(
        key = "order-#{orderId}",
        leaseTime = "30s",
        waitTime = "10s"
    )
    public String processOrder(Long orderId) {
        // 业务逻辑
        return "订单处理完成";
    }
}
```

### 2. 编程式使用

```java
@Service
public class UserService {

    @Autowired
    private DistributedLockFactory lockFactory;

    public String updateUser(Long userId) {
        try (DistributedLock lock = lockFactory.getLock("user-" + userId)) {
            if (lock.tryLock()) {
                // 业务逻辑
                return "用户更新完成";
            } else {
                return "无法获取锁，请稍后重试";
            }
        }
    }
}
```

## 🔧 高级配置

### AOP 切面配置

```yaml
spring:
  distributed-lock:
    aop:
      enabled: true
      pointcut-expression: "@annotation(com.mycorp.distributedlock.api.annotation.DistributedLock)"
      performance-monitoring: true
      exception-handling: true
```

### 健康检查

访问 Actuator 健康检查端点：

```
GET /actuator/health/distributed-lock
```

响应示例：

```json
{
  "status": "UP",
  "components": {
    "distributedLock": {
      "status": "UP",
      "details": {
        "provider": "redis",
        "timestamp": 1703678400000,
        "connection": {
          "connected": true,
          "provider": "redis"
        }
      }
    }
  }
}
```

### 监控指标

访问指标端点：

```
GET /actuator/metrics/distributed.lock.*
```

可用指标：
- `distributed.lock.acquisition.*` - 锁获取统计
- `distributed.lock.success.*` - 成功执行统计
- `distributed.lock.failure.*` - 失败执行统计
- `distributed.lock.execution.time.*` - 执行时间统计

## 🛠️ 开发指南

### 注解说明

#### @DistributedLock

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `key` | String | "" | 锁键，支持 SpEL 表达式 |
| `leaseTime` | String | "" | 租约时间，如 "30s" |
| `waitTime` | String | "" | 等待时间，如 "10s" |
| `lockType` | LockType | SIMPLE | 锁类型 |
| `throwExceptionOnFailure` | boolean | true | 获取锁失败时是否抛出异常 |

#### 锁类型 (LockType)

- `SIMPLE`: 简单互斥锁
- `FAIR`: 公平锁
- `READ_WRITE`: 读写锁

### 配置验证

配置属性使用 Jakarta Bean Validation 进行验证：

```java
@ConfigurationProperties(prefix = "spring.distributed-lock")
@Validated
public class DistributedLockProperties {
    @NotBlank(message = "锁类型不能为空")
    private String type;
    
    @NotNull(message = "默认租约时间不能为空")
    private Duration defaultLeaseTime;
}
```

## 🔍 监控和诊断

### 日志配置

```yaml
logging:
  level:
    com.mycorp.distributedlock: DEBUG
```

### 健康检查诊断

分布式锁健康检查器会自动检测：

1. **连接状态**: 检查到后端的连接
2. **锁获取**: 测试锁的获取和释放
3. **性能指标**: 收集操作性能和错误率
4. **系统状态**: 检查锁工厂状态

### 故障排除

#### 常见问题

1. **锁获取超时**
   - 增加 `waitTime` 配置
   - 检查网络延迟
   - 确认后端服务可用性

2. **配置验证失败**
   - 检查配置格式和类型
   - 确认必填字段
   - 验证值范围

3. **健康检查失败**
   - 检查后端连接配置
   - 确认认证信息
   - 查看错误日志

## 📚 版本兼容性

| Spring Boot | Java | Jakarta EE | 状态 |
|-------------|------|------------|------|
| 3.2.x | 17+ | 9+ | ✅ 支持 |
| 3.1.x | 17+ | 9+ | ✅ 支持 |
| 3.0.x | 17+ | 9+ | ✅ 支持 |

## 🤝 贡献

欢迎提交 Issue 和 Pull Request 来改进这个项目。

## 📄 许可证

本项目采用 Apache 2.0 许可证。详见 LICENSE 文件。