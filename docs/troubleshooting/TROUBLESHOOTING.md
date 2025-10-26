# 故障排查指南

## 概述

本指南帮助您诊断和解决分布式锁框架使用过程中遇到的问题。

## 快速诊断

### 1. 检查基础连接

```bash
# Redis 连接测试
redis-cli -h localhost -p 6379 ping

# ZooKeeper 连接测试
echo "stat" | nc localhost 2181
```

### 2. 查看应用日志

```bash
# 查看最近的错误日志
tail -f logs/spring.log | grep -i error

# 搜索锁相关日志
grep "distributed-lock" logs/spring.log
```

### 3. 检查健康状态

```bash
# Spring Boot Actuator 健康检查
curl http://localhost:8080/actuator/health

# 详细健康信息
curl http://localhost:8080/actuator/health?details=true
```

## 常见问题

### 连接问题

#### Redis 连接失败

**症状**：
```
ConnectionException: Unable to connect to Redis
```

**可能原因**：
1. Redis 服务未启动
2. 网络连接问题
3. 认证失败
4. 连接池配置错误

**解决方案**：

1. **检查 Redis 服务状态**
   ```bash
   # 检查 Redis 进程
   ps aux | grep redis

   # 检查 Redis 端口
   netstat -tlnp | grep 6379
   ```

2. **验证连接配置**
   ```yaml
   distributed-lock:
     redis:
       hosts: localhost:6379  # 确保地址正确
       password: your-password  # 检查密码
   ```

3. **测试网络连通性**
   ```bash
   # 测试 TCP 连接
   telnet localhost 6379

   # 测试 Redis 命令
   redis-cli -h localhost -p 6379 ping
   ```

4. **检查连接池配置**
   ```yaml
   distributed-lock:
     redis:
       pool:
         max-total: 20  # 不要设置过小
         max-wait-millis: 5000  # 适当增加等待时间
   ```

#### ZooKeeper 连接失败

**症状**：
```
KeeperException$ConnectionLossException: KeeperErrorCode = ConnectionLoss
```

**解决方案**：

1. **检查 ZooKeeper 状态**
   ```bash
   # 检查 ZooKeeper 进程
   ps aux | grep zookeeper

   # 检查 ZooKeeper 端口
   netstat -tlnp | grep 2181
   ```

2. **验证集群配置**
   ```bash
   # 连接到 ZooKeeper 查看状态
   echo "stat" | nc localhost 2181
   ```

3. **检查会话超时配置**
   ```yaml
   distributed-lock:
     zookeeper:
       session-timeout: 60s  # 适当增加超时时间
       connection-timeout: 15s
   ```

### 锁操作问题

#### 锁获取超时

**症状**：
```
LockAcquisitionException: Failed to acquire lock within timeout
```

**可能原因**：
1. 锁竞争激烈
2. 业务逻辑执行时间过长
3. 死锁
4. 配置的等待时间过短

**解决方案**：

1. **增加等待时间**
   ```java
   @DistributedLock(key = "'order:' + #orderId",
                   waitTime = 30, waitTimeUnit = TimeUnit.SECONDS)
   public void processOrder(String orderId) {
       // 业务逻辑
   }
   ```

2. **检查是否存在死锁**
   ```java
   // 确保锁获取顺序一致
   @Autowired
   private DistributedLockFactory lockFactory;

   public void transfer(String fromAccount, String toAccount) {
       // 按字典序获取锁，避免死锁
       List<String> accounts = List.of(fromAccount, toAccount).stream()
           .sorted()
           .collect(Collectors.toList());

       // 批量获取锁
       List<String> lockNames = accounts.stream()
           .map(acc -> "account:" + acc)
           .collect(Collectors.toList());

       BatchLockOperations<DistributedLock> batchOps =
           lockFactory.createBatchLockOperations(lockNames, locks -> {
               // 转账逻辑
               return true;
           });

       batchOps.execute();
   }
   ```

3. **优化业务逻辑**
   - 将耗时操作移到锁外
   - 使用异步处理
   - 分批处理大量数据

#### 锁释放失败

**症状**：
```
LockReleaseException: Failed to release lock
```

**可能原因**：
1. 网络连接断开
2. 后端存储故障
3. 锁已过期

**解决方案**：

1. **使用 try-finally 确保释放**
   ```java
   DistributedLock lock = lockFactory.getLock("resource");
   try {
       lock.lock();
       // 业务逻辑
   } finally {
       if (lock.isHeldByCurrentThread()) {
         lock.unlock();
       }
   }
   ```

2. **使用 AutoCloseable**
   ```java
   try (AutoCloseableLock autoLock = new AutoCloseableLock(lock)) {
       autoLock.lock(30, TimeUnit.SECONDS);
       // 业务逻辑
   } // 自动释放
   ```

#### 锁过期问题

**症状**：
业务逻辑执行时间超过锁的租约时间，导致锁被自动释放

**解决方案**：

1. **启用自动续期**
   ```java
   @DistributedLock(key = "'order:' + #orderId",
                   autoRenewal = true,
                   renewalRatio = 0.7)
   public void longRunningProcess(String orderId) {
       // 长时间运行的业务逻辑
   }
   ```

2. **手动续期**
   ```java
   DistributedLock lock = lockFactory.getLock("resource");
   try {
       lock.lock(30, TimeUnit.SECONDS);

       // 业务逻辑
       while (condition) {
           // 检查是否需要续期
           if (lock.getRemainingTime(TimeUnit.SECONDS) < 10) {
               lock.renewLock(30, TimeUnit.SECONDS);
           }
           // 继续处理
       }
   } finally {
       lock.unlock();
   }
   ```

### 性能问题

#### 高延迟

**症状**：
锁操作响应时间过长

**诊断步骤**：

1. **检查网络延迟**
   ```bash
   # 测试网络延迟
   ping redis-host

   # Redis 基准测试
   redis-benchmark -h localhost -p 6379 -c 10 -n 1000
   ```

2. **查看性能指标**
   ```bash
   # 查看锁获取时间分布
   curl http://localhost:8080/actuator/metrics/distributed_lock.acquisition.duration

   # 查看活跃锁数量
   curl http://localhost:8080/actuator/metrics/distributed_lock.active.count
   ```

3. **优化配置**
   ```yaml
   distributed-lock:
     redis:
       pool:
         max-total: 50  # 增加连接池大小
     performance:
       retry-interval: 50ms  # 减少重试间隔
   ```

#### 内存溢出

**症状**：
```
OutOfMemoryError: Java heap space
```

**解决方案**：

1. **增加 JVM 堆内存**
   ```bash
   java -Xmx2g -Xms2g -jar app.jar
   ```

2. **优化连接池**
   ```yaml
   distributed-lock:
     redis:
       pool:
         max-total: 20  # 减少连接数
         max-idle: 10
         min-idle: 2
   ```

3. **启用内存监控**
   ```yaml
   management:
     metrics:
       export:
         jvm:
           memory: true
   ```

### 监控问题

#### 指标收集失败

**症状**：
监控指标为空或不准确

**解决方案**：

1. **检查 Micrometer 配置**
   ```yaml
   management:
     metrics:
       export:
         prometheus:
           enabled: true
   ```

2. **验证指标端点**
   ```bash
   curl http://localhost:8080/actuator/metrics
   curl http://localhost:8080/actuator/prometheus | grep distributed_lock
   ```

#### 告警配置错误

**症状**：
预期告警未触发

**检查项**：
1. Prometheus 规则配置
2. 告警阈值设置
3. 通知渠道配置

### 配置问题

#### 配置不生效

**症状**：
修改配置后行为未改变

**检查项**：

1. **配置文件位置**
   ```bash
   # 检查配置文件是否存在
   ls -la application.yml

   # 检查 Spring Profile
   java -jar app.jar --spring.profiles.active=production
   ```

2. **配置优先级**
   - 命令行参数 > 环境变量 > 配置文件
   - 检查是否有覆盖配置

3. **配置验证**
   ```java
   @Autowired
   private LockConfiguration lockConfig;

   @PostConstruct
   public void validateConfig() {
       try {
           lockConfig.validate();
           System.out.println("Configuration is valid");
       } catch (Exception e) {
           System.err.println("Configuration validation failed: " + e.getMessage());
       }
   }
   ```

## 高级诊断

### 线程转储分析

```bash
# 生成线程转储
jstack -l <pid> > thread-dump.txt

# 分析死锁
grep -A 20 "Found one Java-level deadlock" thread-dump.txt
```

### 堆转储分析

```bash
# 生成堆转储
jmap -dump:live,format=b,file=heap.hprof <pid>

# 使用工具分析
# VisualVM, MAT, 或 JProfiler
```

### 网络诊断

```bash
# 网络连接诊断
netstat -antp | grep :6379
ss -tlnp | grep :6379

# 抓包分析
tcpdump -i eth0 port 6379 -w redis-traffic.pcap
```

### 数据库诊断

#### Redis 诊断

```bash
# Redis 信息
redis-cli info

# 慢查询日志
redis-cli slowlog get 10

# 连接信息
redis-cli client list
```

#### ZooKeeper 诊断

```bash
# ZooKeeper 四字命令
echo "stat" | nc localhost 2181
echo "mntr" | nc localhost 2181
echo "cons" | nc localhost 2181
```

## 故障恢复

### 自动恢复

框架内置的故障恢复机制：

1. **连接重试**：自动重试失败的连接
2. **故障转移**：切换到备用后端
3. **锁续期**：自动续期即将过期的锁

### 手动恢复

```java
@Service
public class RecoveryService {

    @Autowired
    private DistributedLockFactory lockFactory;

    /**
     * 强制清理过期的锁
     */
    public int cleanupExpiredLocks() {
        return lockFactory.cleanupExpiredLocks();
    }

    /**
     * 重置统计信息
     */
    public void resetStatistics() {
        lockFactory.resetStatistics();
    }

    /**
     * 重新初始化连接
     */
    public void reinitializeConnections() {
        // 关闭现有连接
        lockFactory.shutdown();

        // 重新创建工厂
        // 注意：这会影响现有锁
        DistributedLockFactory newFactory = createNewFactory();
        // 更新引用
    }
}
```

## 最佳实践

### 预防措施

1. **设置合理的超时时间**
2. **使用 try-finally 确保锁释放**
3. **避免锁嵌套导致死锁**
4. **监控锁的使用情况**
5. **定期清理过期锁**

### 监控建议

1. **设置关键指标告警**
   - 锁获取失败率 > 5%
   - 平均获取时间 > 2秒
   - 活跃锁数量异常

2. **定期审查日志**
   - 查找异常模式
   - 监控性能趋势
   - 识别潜在问题

3. **容量规划**
   - 监控资源使用率
   - 预测峰值负载
   - 规划扩容方案

## 获取帮助

如果问题仍然存在：

1. **查看完整日志**：启用 DEBUG 日志级别
2. **收集诊断信息**：系统信息、配置、错误日志
3. **提交问题**：在 GitHub Issues 中描述问题
4. **提供重现步骤**：创建最小重现案例