# 常见问题解答 (FAQ)

## 基础概念

### Q: 什么是分布式锁？

**A**: 分布式锁是一种同步机制，用于协调多个进程或线程对共享资源的访问。在分布式系统中，由于没有共享内存，传统的 JVM 锁无法工作，因此需要使用外部存储（如 Redis、ZooKeeper）来实现跨进程的互斥。

### Q: 为什么需要分布式锁？

**A**: 在微服务架构中，多个服务实例可能同时访问相同的资源（如数据库、缓存、文件）。分布式锁可以：

- 防止数据竞争和不一致
- 确保业务逻辑的原子性
- 避免重复处理
- 控制并发访问量

### Q: Redis 和 ZooKeeper 有什么区别？

**A**:

| 特性 | Redis | ZooKeeper |
|------|-------|-----------|
| 性能 | 高（内存操作） | 中等（磁盘操作） |
| 一致性 | 最终一致性 | 强一致性（CP） |
| 可用性 | 高可用 | 高可用 |
| 锁类型 | 互斥锁为主 | 支持读写锁 |
| 部署复杂度 | 简单 | 中等 |
| 适用场景 | 高性能要求 | 强一致性要求 |

## 使用问题

### Q: 如何选择锁的租约时间？

**A**: 租约时间应该基于业务逻辑的执行时间：

- **短任务**（< 1秒）：5-10 秒
- **中任务**（1-30秒）：30-60 秒
- **长任务**（> 30秒）：启用自动续期

**配置示例**：
```yaml
distributed-lock:
  default-lease-time: 30s
  watchdog:
    enabled: true  # 启用自动续期
```

### Q: 锁键应该如何设计？

**A**: 锁键设计原则：

1. **唯一性**：确保不同资源使用不同锁键
2. **层次化**：使用冒号分隔，如 `order:123`
3. **避免冲突**：不同业务使用不同前缀
4. **长度适中**：控制在合理范围内

**良好示例**：
```
user:profile:123
order:payment:456
inventory:product:789
```

### Q: 如何避免死锁？

**A**: 死锁预防策略：

1. **获取锁的顺序**：总是按相同顺序获取多个锁
2. **设置超时时间**：避免无限等待
3. **使用批量锁**：原子性获取多个锁
4. **避免锁嵌套**：尽量减少锁的嵌套使用

**正确示例**：
```java
// 错误：可能导致死锁
public void transferWrong(String from, String to) {
    DistributedLock fromLock = lockFactory.getLock("account:" + from);
    DistributedLock toLock = lockFactory.getLock("account:" + to);

    fromLock.lock();
    toLock.lock(); // 如果顺序相反，可能死锁
}

// 正确：排序避免死锁
public void transferCorrect(String from, String to) {
    List<String> accounts = List.of(from, to).stream()
        .sorted()
        .collect(Collectors.toList());

    List<String> lockNames = accounts.stream()
        .map(acc -> "account:" + acc)
        .collect(Collectors.toList());

    // 批量获取锁
    BatchLockOperations<DistributedLock> batchOps =
        lockFactory.createBatchLockOperations(lockNames, locks -> {
            // 转账逻辑
            return true;
        });
}
```

## 性能问题

### Q: 锁操作为什么这么慢？

**A**: 可能的原因：

1. **网络延迟**：检查客户端到 Redis/ZooKeeper 的网络延迟
2. **连接池耗尽**：增加连接池大小
3. **锁竞争激烈**：检查是否有热点资源
4. **配置不当**：调整超时和重试设置

**诊断命令**：
```bash
# 检查网络延迟
ping redis-host

# 检查连接池状态
curl http://localhost:8080/actuator/metrics/distributed_lock.active.count

# 查看慢查询
redis-cli slowlog get 10
```

### Q: 如何提高并发性能？

**A**: 性能优化策略：

1. **细粒度锁**：使用更小的锁范围
2. **异步操作**：使用异步锁 API
3. **批量处理**：减少网络往返
4. **缓存锁对象**：避免重复创建

**优化示例**：
```java
// 使用缓存的锁对象
@Component
public class LockCache {
    private final Cache<String, DistributedLock> lockCache;

    public LockCache(DistributedLockFactory factory) {
        this.lockCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(key -> factory.getLock(key));
    }

    public DistributedLock getLock(String name) {
        return lockCache.get(name);
    }
}
```

## 配置问题

### Q: 配置不生效怎么办？

**A**: 配置检查步骤：

1. **检查配置文件位置**
   ```bash
   ls -la application.yml
   ```

2. **验证配置加载**
   ```bash
   curl http://localhost:8080/actuator/configprops | grep distributed-lock
   ```

3. **检查环境变量覆盖**
   ```bash
   env | grep DISTRIBUTED_LOCK
   ```

4. **重启应用**
   ```bash
   # 确保配置更改后重启应用
   ```

### Q: 如何在生产环境使用？

**A**: 生产环境配置：

```yaml
distributed-lock:
  backend: redis
  redis:
    cluster:
      nodes:
        - redis://prod-redis-1:6379
        - redis://prod-redis-2:6379
        - redis://prod-redis-3:6379
      password: ${REDIS_PASSWORD}
  monitoring:
    enabled: true
  metrics:
    enabled: true
  watchdog:
    enabled: true
```

## 监控和告警

### Q: 如何设置告警？

**A**: Prometheus 告警规则：

```yaml
groups:
  - name: distributed_lock
    rules:
      - alert: HighLockErrorRate
        expr: rate(distributed_lock_operations_total{status="error"}[5m]) > 0.05
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High distributed lock error rate"

      - alert: SlowLockOperations
        expr: histogram_quantile(0.95, rate(distributed_lock_operation_duration_seconds_bucket[5m])) > 1.0
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Slow distributed lock operations"
```

### Q: 监控指标有哪些？

**A**: 主要监控指标：

- `distributed_lock_operations_total` - 操作总数
- `distributed_lock_active_count` - 活跃锁数量
- `distributed_lock_acquisition_duration` - 获取锁时间
- `distributed_lock_contention_rate` - 竞争率

## 故障处理

### Q: 锁获取失败怎么办？

**A**: 处理策略：

1. **重试机制**
   ```java
   @Retryable(value = LockAcquisitionException.class, maxAttempts = 3)
   public void processWithRetry(String resourceId) {
       // 业务逻辑
   }
   ```

2. **降级处理**
   ```java
   try {
       // 尝试获取锁
       boolean acquired = lock.tryLock(5, TimeUnit.SECONDS);
       if (acquired) {
           // 正常处理
       } else {
           // 降级处理
           processWithoutLock();
       }
   } catch (Exception e) {
       // 降级处理
       processWithoutLock();
   }
   ```

3. **异步处理**
   ```java
   CompletableFuture.runAsync(() -> {
       try {
           // 异步重试
           retryLockAcquisition(resourceId);
       } catch (Exception e) {
           // 记录失败
       }
   });
   ```

### Q: 连接断开怎么办？

**A**: 连接恢复策略：

1. **框架自动处理**：框架内置连接池和重试机制
2. **应用级处理**：实现连接监听器
3. **手动重连**：在检测到连接问题时重新初始化

```java
@Component
public class ConnectionMonitor {

    @Autowired
    private DistributedLockFactory lockFactory;

    @Scheduled(fixedRate = 30000)
    public void checkConnection() {
        try {
            FactoryHealthStatus health = lockFactory.healthCheck();
            if (!health.isHealthy()) {
                System.err.println("Lock factory unhealthy: " + health.getDetails());
                // 发送告警或尝试恢复
            }
        } catch (Exception e) {
            System.err.println("Health check failed: " + e.getMessage());
        }
    }
}
```

## 高级特性

### Q: 如何使用读写锁？

**A**: 读写锁使用示例：

```java
@Service
public class CacheService {

    @Autowired
    private DistributedLockFactory lockFactory;

    public String getData(String key) {
        DistributedReadWriteLock rwLock = lockFactory.getReadWriteLock("cache:" + key);
        DistributedLock readLock = rwLock.readLock();

        try {
            readLock.lock();
            return loadFromCache(key);  // 允许多个读操作并发
        } finally {
            readLock.unlock();
        }
    }

    public void updateData(String key, String value) {
        DistributedReadWriteLock rwLock = lockFactory.getReadWriteLock("cache:" + key);
        DistributedLock writeLock = rwLock.writeLock();

        try {
            writeLock.lock();  // 独占写操作
            saveToCache(key, value);
            invalidateRelatedCaches(key);
        } finally {
            writeLock.unlock();
        }
    }
}
```

### Q: 如何实现批量锁操作？

**A**: 批量锁操作：

```java
public void processBatch(List<String> items) {
    // 排序避免死锁
    List<String> sortedItems = items.stream().sorted().collect(Collectors.toList());
    List<String> lockNames = sortedItems.stream()
        .map(item -> "item:" + item)
        .collect(Collectors.toList());

    BatchLockOperations<DistributedLock> batchOps =
        lockFactory.createBatchLockOperations(lockNames, locks -> {
            // 批量处理逻辑
            for (String item : sortedItems) {
                processItem(item);
            }
            return sortedItems.size();
        });

    try {
        Integer processedCount = batchOps.execute();
        System.out.println("Processed " + processedCount + " items");
    } catch (BatchLockException e) {
        System.err.println("Batch processing failed: " + e.getMessage());
    }
}
```

## 迁移和升级

### Q: 如何从其他锁方案迁移？

**A**: 迁移步骤：

1. **评估现有方案**
   - 分析当前锁的使用情况
   - 识别性能瓶颈和问题

2. **渐进式迁移**
   - 从非关键业务开始迁移
   - 保留原有方案作为备份

3. **配置调优**
   - 根据现有负载调整配置
   - 进行性能测试

4. **监控和验证**
   - 监控迁移后的性能指标
   - 验证业务逻辑正确性

### Q: 如何升级框架版本？

**A**: 升级步骤：

1. **备份配置和数据**
2. **查看变更日志**
3. **更新依赖版本**
4. **检查不兼容变更**
5. **测试升级**
6. **灰度发布**

## 最佳实践

### Q: 使用分布式锁的最佳实践有哪些？

**A**:

1. **锁粒度**：使用最小必要的锁范围
2. **超时设置**：设置合理的超时时间
3. **异常处理**：确保锁最终会被释放
4. **监控告警**：监控锁的使用情况
5. **性能优化**：使用异步和批量操作
6. **测试充分**：编写完整的单元和集成测试

### Q: 如何进行容量规划？

**A**: 容量规划考虑因素：

1. **预期负载**：QPS、并发用户数
2. **资源需求**：CPU、内存、网络
3. **后端容量**：Redis/ZooKeeper 集群大小
4. **故障转移**：备用节点数量
5. **监控告警**：阈值设置

**估算公式**：
- 连接池大小 ≈ 并发请求数 × 平均响应时间
- 内存需求 ≈ 基础内存 + 连接数 × 连接开销
- CPU 需求 ≈ 基础 CPU + 锁操作数 × 操作开销

## 获取帮助

### Q: 在哪里可以获得更多帮助？

**A**:

1. **官方文档**：查看完整文档和示例
2. **GitHub Issues**：搜索相似问题或提交新问题
3. **社区论坛**：参与社区讨论
4. **技术支持**：联系商业支持（如果适用）

### Q: 如何贡献代码？

**A**:

1. Fork 项目
2. 创建特性分支
3. 提交更改
4. 编写测试
5. 提交 Pull Request

### Q: 商业支持是否可用？

**A**: 是的，企业版提供：

- 24/7 技术支持
- 性能优化咨询
- 定制开发服务
- 培训服务

请联系 sales@company.com 了解详情。