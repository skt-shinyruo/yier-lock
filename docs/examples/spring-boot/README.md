# Spring Boot 分布式锁示例

这个示例项目演示了如何在 Spring Boot 应用中使用分布式锁框架。

## 功能特性

- ✅ **开箱即用**：基于 Spring Boot Starter 自动配置
- ✅ **注解驱动**：使用 `@DistributedLock` 注解简化开发
- ✅ **REST API**：提供完整的 HTTP 接口演示
- ✅ **监控集成**：集成 Spring Boot Actuator 和 Micrometer
- ✅ **批量操作**：演示事务性批量锁操作
- ✅ **性能监控**：内置性能指标收集和报告
- ✅ **健康检查**：自动健康状态监控

## 快速开始

### 1. 启动依赖服务

```bash
# 启动 Redis
docker run -d --name redis -p 6379:6379 redis:7-alpine

# 或者使用 Docker Compose
docker-compose up -d
```

### 2. 运行应用

```bash
# 编译并运行
mvn spring-boot:run

# 或者使用 Maven Wrapper
./mvnw spring-boot:run
```

### 3. 测试接口

```bash
# 基础锁操作
curl http://localhost:8080/api/locks/basic/test-resource

# 注解锁操作
curl -X POST http://localhost:8080/api/locks/orders/order-123/process

# 批量操作
curl -X POST http://localhost:8080/api/locks/batch \
  -H "Content-Type: application/json" \
  -d '["product-1", "product-2", "product-3"]'

# 查看锁状态
curl http://localhost:8080/api/locks/status/order:order-123

# 查看健康状态
curl http://localhost:8080/actuator/health

# 查看指标
curl http://localhost:8080/actuator/metrics/distributed_lock.operations.total
```

## 项目结构

```
src/main/java/com/mycorp/distributedlock/examples/springboot/
├── Application.java              # Spring Boot 主类
├── Controller.java               # REST API 控制器
├── OrderService.java             # 订单服务（注解示例）
├── InventoryService.java         # 库存服务（批量操作示例）
├── Configuration.java            # 自定义配置
├── LockPerformanceMonitor.java   # 性能监控
└── LockCleanupService.java       # 锁清理服务
```

## 配置说明

### application.yml

```yaml
distributed-lock:
  backend: redis                    # 后端类型
  redis:
    hosts: localhost:6379          # Redis 地址
  monitoring:
    enabled: true                  # 启用监控
  metrics:
    enabled: true                  # 启用指标收集

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: when-authorized
```

### 环境变量配置

```bash
# Redis 配置
export DISTRIBUTED_LOCK_REDIS_HOSTS=redis-cluster:6379
export DISTRIBUTED_LOCK_REDIS_PASSWORD=your-password

# 监控配置
export DISTRIBUTED_LOCK_MONITORING_ENABLED=true
export DISTRIBUTED_LOCK_METRICS_ENABLED=true
```

## API 接口

### 基础锁操作

- `GET /api/locks/basic/{resourceId}` - 基础锁使用示例
- `POST /api/locks/orders/{orderId}/process` - 注解锁使用示例
- `POST /api/locks/batch` - 批量锁操作示例
- `POST /api/locks/async/{resourceId}` - 异步锁操作示例

### 锁管理

- `GET /api/locks/status/{lockName}` - 查询锁状态
- `GET /api/locks/health/{lockName}` - 锁健康检查
- `GET /api/locks/stats` - 工厂统计信息
- `POST /api/locks/renew/{lockName}` - 手动续期锁
- `POST /api/locks/auto-renew/{lockName}` - 启动自动续期

### 库存管理

- `GET /api/inventory/{productId}` - 查询库存
- `POST /api/inventory/{productId}/update?quantity={qty}` - 更新库存
- `GET /api/inventory` - 查询所有库存

### 订单管理

- `GET /api/orders/{orderId}/status` - 查询订单状态
- `POST /api/orders/{orderId}/cancel` - 取消订单

## 监控和指标

### 健康检查

访问 `http://localhost:8080/actuator/health` 查看应用健康状态：

```json
{
  "status": "UP",
  "components": {
    "distributedLock": {
      "status": "UP",
      "details": {
        "backend": "redis",
        "responseTime": "5ms",
        "activeLocks": 2,
        "totalOperations": 150
      }
    }
  }
}
```

### 性能指标

访问 `http://localhost:8080/actuator/metrics` 查看可用指标：

- `distributed_lock.operations.total` - 锁操作总数
- `distributed_lock.active.count` - 活跃锁数量
- `distributed_lock.acquisition.duration` - 锁获取时间
- `distributed_lock.hold.duration` - 锁持有时间

### Prometheus 指标

配置 Prometheus 从 `/actuator/prometheus` 端点收集指标。

## 性能测试

### 并发测试

```bash
# 使用 Apache Bench 进行并发测试
ab -n 1000 -c 10 http://localhost:8080/api/locks/basic/test-resource
```

### 负载测试

```bash
# 使用 JMeter 或其他负载测试工具
# 创建测试计划，模拟多用户并发访问锁资源
```

## 故障排查

### 常见问题

1. **连接失败**
   ```bash
   # 检查 Redis 是否运行
   docker ps | grep redis

   # 检查网络连接
   telnet localhost 6379
   ```

2. **锁获取超时**
   - 检查 Redis 性能
   - 调整等待时间配置
   - 查看是否有死锁

3. **内存不足**
   - 增加 JVM 堆内存
   - 优化连接池配置
   - 启用垃圾回收调优

### 日志分析

```bash
# 查看应用日志
tail -f logs/spring.log

# 搜索锁相关日志
grep "distributed-lock" logs/spring.log

# 查看错误日志
grep "ERROR" logs/spring.log
```

## 扩展开发

### 添加新服务

1. 创建服务类
2. 使用 `@DistributedLock` 注解
3. 添加到控制器
4. 更新配置

### 自定义配置

```java
@Configuration
public class CustomLockConfiguration {

    @Bean
    public LockEventListener<DistributedLock> customEventListener() {
        return new CustomLockEventListener();
    }
}
```

### 集成测试

```java
@SpringBootTest
public class LockIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Test
    public void testDistributedLockAnnotation() {
        // 测试注解生效
    }
}
```

## 部署说明

### Docker 部署

```dockerfile
FROM openjdk:11-jre-slim
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app.jar"]
```

### Kubernetes 部署

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: distributed-lock-demo
spec:
  replicas: 3
  template:
    spec:
      containers:
      - name: app
        image: your-app:latest
        env:
        - name: DISTRIBUTED_LOCK_REDIS_HOSTS
          value: "redis-service:6379"
```

## 更多示例

查看其他示例项目：

- [基础使用示例](../java/BasicUsageExample.java)
- [高级特性示例](../java/AdvancedFeaturesExample.java)
- [批量操作示例](../java/BatchOperationsExample.java)
- [监控示例](../java/MonitoringExample.java)

## 许可证

本示例项目遵循与主框架相同的许可证。