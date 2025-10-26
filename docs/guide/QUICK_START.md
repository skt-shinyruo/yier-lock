# 快速入门指南

## 3 分钟上手分布式锁

本指南将帮助你在 3 分钟内完成分布式锁的基本使用。

## 环境准备

### 1. 启动 Redis

```bash
# 使用 Docker 启动 Redis
docker run -d --name redis -p 6379:6379 redis:7-alpine

# 或者使用 Docker Compose
echo 'version: "3.8"
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"' > docker-compose.yml

docker-compose up -d
```

### 2. 创建 Maven 项目

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>distributed-lock-demo</artifactId>
    <version>1.0.0</version>

    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
    </properties>

    <dependencies>
        <!-- 分布式锁框架 -->
        <dependency>
            <groupId>com.mycorp</groupId>
            <artifactId>distributed-lock-spring-boot-starter</artifactId>
            <version>1.0.0-SNAPSHOT</version>
        </dependency>

        <!-- Spring Boot -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
            <version>2.7.18</version>
        </dependency>

        <!-- Lettuce (Redis 客户端) -->
        <dependency>
            <groupId>io.lettuce</groupId>
            <artifactId>lettuce-core</artifactId>
            <version>6.2.6.RELEASE</version>
        </dependency>
    </dependencies>
</project>
```

### 3. 配置应用

创建 `src/main/resources/application.yml`：

```yaml
server:
  port: 8080

distributed-lock:
  backend: redis
  redis:
    hosts: localhost:6379
  monitoring:
    enabled: true
```

## 基本使用

### 1. 创建 Spring Boot 应用

```java
package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

### 2. 创建服务类

```java
package com.example.demo;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    @Autowired
    private DistributedLockFactory lockFactory;

    public void processOrder(String orderId) {
        DistributedLock lock = lockFactory.getLock("order:" + orderId);

        try {
            // 获取锁，最多等待 10 秒，持有 30 秒
            boolean acquired = lock.tryLock(10, 30, TimeUnit.SECONDS);

            if (acquired) {
                // 执行业务逻辑
                System.out.println("Processing order: " + orderId);
                doProcessOrder(orderId);
            } else {
                throw new RuntimeException("Failed to acquire lock for order: " + orderId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while acquiring lock", e);
        } finally {
            // 确保锁被释放
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void doProcessOrder(String orderId) {
        // 模拟业务处理
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("Order processed: " + orderId);
    }
}
```

### 3. 创建控制器

```java
package com.example.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @PostMapping("/{orderId}/process")
    public String processOrder(@PathVariable String orderId) {
        orderService.processOrder(orderId);
        return "Order " + orderId + " processed successfully";
    }
}
```

### 4. 运行应用

```bash
# 编译并运行
mvn clean compile spring-boot:run
```

### 5. 测试并发访问

打开两个终端，分别执行：

```bash
# 终端 1
curl http://localhost:8080/orders/order-123/process

# 终端 2 (同时执行)
curl http://localhost:8080/orders/order-123/process
```

你会看到只有一个请求能成功处理订单，另一个会被阻塞或失败。

## 注解方式使用

### 1. 修改服务类

```java
package com.example.demo;

import com.mycorp.distributedlock.api.annotation.DistributedLock;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    @DistributedLock(key = "'order:' + #orderId", leaseTime = 30)
    public void processOrder(String orderId) {
        // 执行业务逻辑
        System.out.println("Processing order: " + orderId);
        doProcessOrder(orderId);
    }

    private void doProcessOrder(String orderId) {
        // 模拟业务处理
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("Order processed: " + orderId);
    }
}
```

### 2. 测试注解方式

```bash
# 测试并发访问
curl http://localhost:8080/orders/order-456/process &
curl http://localhost:8080/orders/order-456/process &
wait
```

## 高级特性

### 1. 异步操作

```java
package com.example.demo;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class AsyncOrderService {

    @Autowired
    private DistributedLockFactory lockFactory;

    public CompletableFuture<Void> processOrderAsync(String orderId) {
        return lockFactory.getLockAsync("order:" + orderId)
            .thenCompose(lock -> lock.lockAsync(30, TimeUnit.SECONDS)
                .thenRun(() -> doProcessOrder(orderId))
                .whenComplete((result, throwable) -> {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }));
    }

    private void doProcessOrder(String orderId) {
        // 异步业务处理
        System.out.println("Processing order asynchronously: " + orderId);
    }
}
```

### 2. 批量操作

```java
package com.example.demo;

import com.mycorp.distributedlock.api.BatchLockOperations;
import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BatchOrderService {

    @Autowired
    private DistributedLockFactory lockFactory;

    public void processBatchOrders(List<String> orderIds) {
        List<String> lockNames = orderIds.stream()
            .map(id -> "order:" + id)
            .collect(Collectors.toList());

        BatchLockOperations<DistributedLock> batchOps =
            lockFactory.createBatchLockOperations(lockNames, locks -> {
                // 批量处理订单
                for (String orderId : orderIds) {
                    doProcessOrder(orderId);
                }
                return orderIds.size();
            });

        Integer processedCount = batchOps.execute();
        System.out.println("Processed " + processedCount + " orders");
    }

    private void doProcessOrder(String orderId) {
        System.out.println("Processing order: " + orderId);
    }
}
```

## 监控和健康检查

### 1. 查看健康状态

```bash
curl http://localhost:8080/actuator/health
```

响应示例：

```json
{
  "status": "UP",
  "components": {
    "distributedLock": {
      "status": "UP",
      "details": {
        "backend": "redis",
        "connections": 5,
        "activeLocks": 2
      }
    }
  }
}
```

### 2. 查看指标

```bash
curl http://localhost:8080/actuator/metrics/distributed.lock.operations
```

### 3. 查看日志

应用启动时，你会看到类似日志：

```
2023-12-01 10:00:00.000 INFO  DistributedLockFactory - Initialized Redis distributed lock factory
2023-12-01 10:00:00.100 INFO  LockMonitoringService - Lock monitoring enabled
2023-12-01 10:00:01.000 INFO  DemoApplication - Started DemoApplication in 2.5 seconds
```

## 故障排查

### 常见问题

1. **连接 Redis 失败**
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

3. **应用无法启动**
   - 检查依赖版本兼容性
   - 验证配置文件语法
   - 查看详细错误日志

### 调试模式

启用调试日志：

```yaml
logging:
  level:
    com.mycorp.distributedlock: DEBUG
```

## 下一步

现在你已经掌握了分布式锁的基本使用！接下来可以：

1. [查看完整 API 文档](api/API_REFERENCE.md)
2. [学习高级特性](ADVANCED_USAGE.md)
3. [了解 Spring Boot 集成](SPRING_BOOT_INTEGRATION.md)
4. [查看更多示例代码](../examples/)
5. [学习性能调优](PERFORMANCE_TUNING.md)

## 完整示例代码

查看 [examples/spring-boot](../examples/spring-boot/) 目录获取完整的 Spring Boot 集成示例。