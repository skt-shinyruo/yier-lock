# 分布式锁框架

[![Maven Central](https://img.shields.io/maven-central/v/com.mycorp/distributed-lock.svg)](https://search.maven.org/artifact/com.mycorp/distributed-lock)
[![Java](https://img.shields.io/badge/Java-11+-blue.svg)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](https://opensource.org/licenses/Apache-2.0)

## 简介

这是一个高性能、高可用的分布式锁框架，支持多种后端存储（Redis、ZooKeeper），提供丰富的锁类型和企业级特性。

## 特性

- 🚀 **高性能**：基于 Lettuce 和 Curator 的高效实现
- 🔒 **多种锁类型**：互斥锁、可重入锁、读写锁、公平锁
- 📊 **监控告警**：内置 Micrometer 指标收集和 Prometheus 导出
- 🔄 **自动续期**：智能锁续期机制防止业务中断
- 🏗️ **Spring Boot 集成**：开箱即用的自动配置
- 📈 **批量操作**：支持事务性批量锁操作
- 🔍 **健康检查**：完整的健康检查和状态监控
- 🛡️ **高可用**：多节点部署和故障转移支持

## 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>com.mycorp</groupId>
    <artifactId>distributed-lock-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Gradle 依赖

```gradle
implementation 'com.mycorp:distributed-lock-spring-boot-starter:1.0.0-SNAPSHOT'
```

### 基础使用

```java
@Service
public class OrderService {

    @Autowired
    private DistributedLockFactory lockFactory;

    @DistributedLock(key = "'order:' + #orderId")
    public void processOrder(String orderId) {
        // 业务逻辑
        System.out.println("Processing order: " + orderId);
    }

    // 手动使用
    public void manualLock(String orderId) {
        DistributedLock lock = lockFactory.getLock("order:" + orderId);
        try {
            lock.lock(30, TimeUnit.SECONDS);
            // 业务逻辑
        } finally {
            lock.unlock();
        }
    }
}
```

### 配置示例

```yaml
distributed-lock:
  backend: redis
  redis:
    cluster:
      nodes:
        - redis://localhost:6379
        - redis://localhost:6380
    password: your-password
  monitoring:
    enabled: true
  metrics:
    enabled: true
```

## 架构优势

### 与其他方案对比

| 特性 | 本框架 | Redisson | Curator | Jedis Lock |
|------|--------|----------|---------|------------|
| 锁类型 | 丰富 | 丰富 | 基础 | 基础 |
| 监控 | ✅ | ✅ | ❌ | ❌ |
| 自动续期 | ✅ | ✅ | ❌ | ❌ |
| 批量操作 | ✅ | ✅ | ❌ | ❌ |
| Spring 集成 | ✅ | ✅ | ❌ | ❌ |
| 健康检查 | ✅ | ❌ | ❌ | ❌ |
| 性能优化 | ✅ | ✅ | ❌ | ❌ |

### 性能表现

- **Redis 单节点**：QPS > 10,000
- **Redis 集群**：QPS > 50,000
- **ZooKeeper**：QPS > 5,000
- **内存占用**：< 50MB (基础配置)
- **网络延迟**：< 1ms (同机房)

## 文档导航

- [快速开始](guide/QUICK_START.md) - 3分钟上手指南
- [API 参考](api/API_REFERENCE.md) - 完整接口文档
- [架构设计](guide/ARCHITECTURE.md) - 系统架构详解
- [Spring Boot 集成](api/SPRING_BOOT_INTEGRATION.md) - 自动配置指南
- [配置参考](guide/CONFIGURATION.md) - 详细配置说明
- [最佳实践](guide/BEST_PRACTICES.md) - 使用建议和规范
- [性能调优](guide/PERFORMANCE_TUNING.md) - 性能优化指南
- [监控运维](monitoring/MONITORING_GUIDE.md) - 监控和告警配置
- [故障排查](troubleshooting/TROUBLESHOOTING.md) - 常见问题解决

## 示例项目

查看 [examples](../examples/) 目录获取完整示例：

- [基础使用示例](../examples/java/BasicUsageExample.java)
- [高级特性示例](../examples/java/AdvancedFeaturesExample.java)
- [Spring Boot 集成示例](../examples/spring-boot/)

## 贡献

欢迎提交 Issue 和 Pull Request！

1. Fork 本项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

## 许可证

本项目采用 Apache 2.0 许可证 - 查看 [LICENSE](../LICENSE) 文件了解详情。

## 联系我们

- 项目主页：[GitHub](https://github.com/your-org/distributed-lock)
- 问题反馈：[Issues](https://github.com/your-org/distributed-lock/issues)
- 邮件：dev@yourcompany.com