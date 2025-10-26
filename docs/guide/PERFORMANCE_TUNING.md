# 性能调优指南

## 概述

本指南提供分布式锁框架的性能调优方法，包括配置优化、监控调优、使用模式优化等。

## 性能基准

### 基础性能指标

| 后端 | 单节点 QPS | 集群 QPS | 平均延迟 | P95 延迟 | P99 延迟 |
|------|-----------|----------|----------|----------|----------|
| Redis 单节点 | ~10,000 | - | < 1ms | < 5ms | < 10ms |
| Redis 集群 | ~50,000 | ~100,000 | < 2ms | < 10ms | < 20ms |
| ZooKeeper | ~5,000 | ~15,000 | < 5ms | < 20ms | < 50ms |

### 影响性能的因素

1. **网络延迟**：客户端到后端的网络往返时间
2. **后端性能**：Redis/ZooKeeper 的处理能力
3. **连接池配置**：连接复用和池大小
4. **锁竞争程度**：并发获取同一锁的频率
5. **锁持有时间**：业务逻辑执行时间
6. **序列化开销**：数据编解码时间

## 配置优化

### Redis 优化配置

```yaml
distributed-lock:
  backend: redis
  redis:
    # 连接池优化
    pool:
      max-total: 50          # 最大连接数，建议：CPU核心数 * 2-4
      max-idle: 30           # 最大空闲连接
      min-idle: 10           # 最小空闲连接
      max-wait-millis: 2000  # 最大等待时间
      test-on-borrow: true   # 借用时测试连接
      test-on-return: false  # 归还时不测试，提高性能
      test-while-idle: true  # 空闲时测试连接
      time-between-eviction-runs-millis: 30000  # 驱逐线程运行间隔
      min-evictable-idle-time-millis: 60000     # 最小空闲时间

    # Lettuce 客户端优化
    lettuce:
      shutdown-timeout: 100ms
      command-timeout: 1000ms  # 命令超时时间
      timeout: 1000ms

  # 锁配置优化
  default-lease-time: 30s
  default-wait-time: 5s      # 减少等待时间，提高响应性
  retry-interval: 50ms       # 减少重试间隔
  max-retries: 2             # 减少重试次数

  # 监控配置
  metrics:
    enabled: true
  tracing:
    enabled: false           # 生产环境可关闭追踪
```

### ZooKeeper 优化配置

```yaml
distributed-lock:
  backend: zookeeper
  zookeeper:
    # 连接优化
    session-timeout: 30s     # 会话超时
    connection-timeout: 10s  # 连接超时
    base-path: /distributed-locks

    # Curator 客户端优化
    curator:
      retry-policy:
        type: exponential_backoff
        base-sleep-time: 100ms
        max-sleep-time: 10s
        max-retries: 3
      connection-state-listener: true

  # 锁配置优化
  default-lease-time: 60s    # ZooKeeper 适合稍长持有时间
  default-wait-time: 10s
  retry-interval: 200ms      # ZooKeeper 重试间隔稍长
  max-retries: 3
```

### JVM 优化配置

```bash
# JVM 参数优化
-Xmx4g -Xms4g                    # 堆内存设置
-XX:+UseG1GC                     # 使用 G1 垃圾回收器
-XX:MaxGCPauseMillis=200         # 最大 GC 暂停时间
-XX:G1HeapRegionSize=16m         # G1 区域大小
-XX:+PrintGCDetails              # 打印 GC 详情
-XX:+PrintGCTimeStamps           # 打印 GC 时间戳
-Xloggc:/logs/gc.log             # GC 日志文件

# 网络优化
-Djava.net.preferIPv4Stack=true  # 优先使用 IPv4
```

## 监控调优

### 关键指标监控

```java
@Service
public class PerformanceMonitor {

    @Autowired
    private DistributedLockFactory lockFactory;

    @Autowired
    private MeterRegistry meterRegistry;

    @PostConstruct
    public void init() {
        // 注册自定义指标
        registerCustomMetrics();
    }

    private void registerCustomMetrics() {
        // 锁竞争率
        Gauge.builder("distributed_lock.contention_rate", this::calculateContentionRate)
            .description("Lock contention rate")
            .register(meterRegistry);

        // 锁等待时间分布
        Timer.builder("distributed_lock.wait_duration")
            .description("Time spent waiting for locks")
            .register(meterRegistry);

        // 连接池利用率
        Gauge.builder("distributed_lock.connection_pool_utilization", this::getPoolUtilization)
            .description("Connection pool utilization")
            .register(meterRegistry);
    }

    private double calculateContentionRate() {
        FactoryStatistics stats = lockFactory.getStatistics();
        long total = stats.getTotalLockAcquisitions() + stats.getFailedLockAcquisitions();
        return total > 0 ? (double) stats.getFailedLockAcquisitions() / total : 0.0;
    }

    private double getPoolUtilization() {
        // 获取连接池利用率
        // 具体实现取决于后端类型
        return 0.0;
    }

    @Scheduled(fixedRate = 10000) // 10秒采样一次
    public void recordPerformanceMetrics() {
        FactoryStatistics stats = lockFactory.getStatistics();

        // 记录性能指标
        meterRegistry.gauge("distributed_lock.active_count", stats.getActiveLocks());
        meterRegistry.gauge("distributed_lock.acquisition_rate",
            stats.getTotalLockAcquisitions() / (stats.getUptime() / 1000.0));
    }
}
```

### 性能告警配置

```yaml
# Prometheus 告警规则
groups:
  - name: distributed_lock_performance
    rules:
      # 高竞争率告警
      - alert: HighLockContention
        expr: distributed_lock_contention_rate > 0.1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High distributed lock contention detected"
          description: "Lock contention rate is {{ $value }}%"

      # 慢锁获取告警
      - alert: SlowLockAcquisition
        expr: histogram_quantile(0.95, rate(distributed_lock_wait_duration_bucket[5m])) > 5.0
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Slow distributed lock acquisition"
          description: "95th percentile wait time is {{ $value }}s"

      # 连接池利用率过高
      - alert: HighConnectionPoolUtilization
        expr: distributed_lock_connection_pool_utilization > 0.9
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High connection pool utilization"
          description: "Connection pool utilization is {{ $value }}%"
```

## 使用模式优化

### 1. 锁粒度优化

```java
@Service
public class OptimizedLockingService {

    @Autowired
    private DistributedLockFactory lockFactory;

    // ❌ 粗粒度锁：影响并发性能
    @DistributedLock(key = "'user'")
    public void updateUser(String userId, UserData data) {
        // 所有用户更新使用同一个锁
    }

    // ✅ 细粒度锁：提高并发性能
    @DistributedLock(key = "'user:' + #userId")
    public void updateUser(String userId, UserData data) {
        // 每个用户使用独立锁
    }

    // ✅ 业务级锁：平衡性能和一致性
    @DistributedLock(key = "'user:' + #userId + ':profile'")
    public void updateUserProfile(String userId, ProfileData profile) {
        // 针对具体业务使用锁
    }
}
```

### 2. 异步锁优化

```java
@Service
public class AsyncOptimizationService {

    @Autowired
    private DistributedLockFactory lockFactory;

    // 异步锁获取，减少线程阻塞
    public CompletableFuture<Void> processAsync(String resourceId) {
        return lockFactory.getLockAsync("resource:" + resourceId)
            .thenCompose(lock -> lock.tryLockAsync(5, 30, TimeUnit.SECONDS)
                .thenCompose(acquired -> {
                    if (acquired) {
                        return doAsyncWork(resourceId)
                            .whenComplete((result, throwable) -> lock.unlockAsync());
                    } else {
                        return CompletableFuture.failedFuture(
                            new RuntimeException("Failed to acquire lock"));
                    }
                }));
    }

    // 批量异步处理
    public CompletableFuture<List<String>> processBatchAsync(List<String> resourceIds) {
        List<CompletableFuture<String>> futures = resourceIds.stream()
            .map(id -> processSingleAsync(id).exceptionally(ex -> {
                System.err.println("Failed to process " + id + ": " + ex.getMessage());
                return null;
            }))
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
    }

    private CompletableFuture<String> processSingleAsync(String resourceId) {
        return CompletableFuture.supplyAsync(() -> {
            DistributedLock lock = lockFactory.getLock("resource:" + resourceId);
            try {
                if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                    doWork(resourceId);
                    return resourceId;
                } else {
                    throw new RuntimeException("Lock acquisition timeout");
                }
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        });
    }
}
```

### 3. 锁缓存优化

```java
@Component
public class LockCache {

    private final Cache<String, DistributedLock> lockCache;
    private final DistributedLockFactory lockFactory;

    public LockCache(DistributedLockFactory lockFactory) {
        this.lockFactory = lockFactory;
        this.lockCache = Caffeine.newBuilder()
            .maximumSize(10000)                    // 最大缓存 10000 个锁
            .expireAfterAccess(30, TimeUnit.MINUTES) // 30分钟后过期
            .removalListener((String key, DistributedLock lock, RemovalCause cause) -> {
                // 清理回调
                System.out.println("Lock evicted: " + key + ", cause: " + cause);
            })
            .build();
    }

    public DistributedLock getLock(String name) {
        return lockCache.get(name, key -> {
            System.out.println("Creating new lock: " + key);
            return lockFactory.getLock(key);
        });
    }

    public void invalidate(String name) {
        lockCache.invalidate(name);
    }

    public void invalidateAll() {
        lockCache.invalidateAll();
    }
}

@Service
public class CachedLockingService {

    @Autowired
    private LockCache lockCache;

    public void processWithCachedLock(String resourceId) {
        DistributedLock lock = lockCache.getLock("resource:" + resourceId);

        try {
            lock.lock(30, TimeUnit.SECONDS);
            // 业务处理
            doWork(resourceId);
        } finally {
            lock.unlock();
        }
    }
}
```

### 4. 批量操作优化

```java
@Service
public class BatchOptimizationService {

    @Autowired
    private DistributedLockFactory lockFactory;

    // 分批处理，避免一次性获取太多锁
    public void processLargeBatch(List<String> items) {
        List<List<String>> batches = partition(items, 20); // 每批最多20个

        for (List<String> batch : batches) {
            processBatch(batch);
            // 小延迟，避免对后端造成过大压力
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void processBatch(List<String> batch) {
        // 排序避免死锁
        List<String> sortedBatch = batch.stream().sorted().collect(Collectors.toList());
        List<String> lockNames = sortedBatch.stream()
            .map(item -> "item:" + item)
            .collect(Collectors.toList());

        BatchLockOperations<DistributedLock> batchOps =
            lockFactory.createBatchLockOperations(lockNames, locks -> {
                // 并行处理，提高吞吐量
                sortedBatch.parallelStream().forEach(this::processItem);
                return sortedBatch.size();
            });

        try {
            batchOps.execute();
        } catch (BatchLockException e) {
            // 处理批量失败
            System.err.println("Batch processing failed: " + e.getMessage());
        }
    }

    private void processItem(String item) {
        // 单个项目处理逻辑
        System.out.println("Processing item: " + item);
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
```

## 后端特定优化

### Redis 优化

```java
@Configuration
public class RedisOptimizationConfig {

    @Bean
    public RedisClient redisClient(LockConfiguration config) {
        RedisURI redisUri = RedisURI.Builder.redis("localhost", 6379)
            .withPassword(config.getRedisPassword())
            .withDatabase(config.getRedisDatabase())
            .build();

        // 优化 Lettuce 客户端配置
        LettucePoolingProvider poolingProvider = LettucePoolingProvider.builder()
            .maxTotal(config.getRedisPoolMaxTotal())
            .maxIdle(config.getRedisPoolMaxIdle())
            .minIdle(config.getRedisPoolMinIdle())
            .maxWait(Duration.ofMillis(2000))
            .testOnBorrow(true)
            .testOnReturn(false)
            .testWhileIdle(true)
            .evictionRuns(Duration.ofSeconds(30))
            .minEvictableIdleTime(Duration.ofSeconds(60))
            .build();

        return RedisClient.create(redisUri);
    }

    @Bean
    public RedisDistributedLockFactory redisLockFactory(RedisClient redisClient) {
        return new RedisDistributedLockFactory(redisClient);
    }
}
```

### ZooKeeper 优化

```java
@Configuration
public class ZooKeeperOptimizationConfig {

    @Bean
    public CuratorFramework curatorFramework(LockConfiguration config) {
        return CuratorFrameworkFactory.newClient(
            config.getZookeeperConnectString(),
            new ExponentialBackoffRetry(100, 10, 10000)
        );
    }

    @Bean
    public ZooKeeperDistributedLockFactory zkLockFactory(CuratorFramework curator) {
        curator.start();
        return new ZooKeeperDistributedLockFactory(curator);
    }
}
```

## 性能测试和基准测试

### JMH 性能基准测试

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
public class LockBenchmark {

    @State(Scope.Benchmark)
    public static class BenchmarkState {
        DistributedLockFactory lockFactory;
        String lockName;

        @Setup
        public void setup() {
            // 初始化锁工厂
            lockFactory = createLockFactory();
            lockName = "benchmark-lock";
        }

        @TearDown
        public void tearDown() {
            // 清理资源
            lockFactory.shutdown();
        }
    }

    @Benchmark
    public void testLockAcquisition(BenchmarkState state) {
        DistributedLock lock = state.lockFactory.getLock(state.lockName);
        try {
            lock.lock(1, TimeUnit.SECONDS);
            // 模拟少量工作
            Blackhole.consumeCPU(10);
        } finally {
            lock.unlock();
        }
    }

    @Benchmark
    public void testTryLock(BenchmarkState state) {
        DistributedLock lock = state.lockFactory.getLock(state.lockName);
        try {
            boolean acquired = lock.tryLock(0, 1, TimeUnit.SECONDS);
            if (acquired) {
                Blackhole.consumeCPU(10);
            }
        } catch (InterruptedException e) {
            // 处理中断
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

### 运行基准测试

```bash
# 编译基准测试
mvn clean package -pl distributed-lock-benchmarks -am

# 运行基准测试
java -jar distributed-lock-benchmarks/target/benchmarks.jar \
  -bm thrpt -tu s -f 1 -wi 5 -i 10 -rf json -rff benchmark-result.json
```

### 性能分析工具

```java
@Service
public class PerformanceAnalyzer {

    @Autowired
    private DistributedLockFactory lockFactory;

    public PerformanceReport analyzePerformance() {
        FactoryStatistics stats = lockFactory.getStatistics();

        PerformanceReport report = new PerformanceReport();
        report.setTotalOperations(stats.getTotalLockAcquisitions());
        report.setSuccessfulOperations(stats.getTotalLockAcquisitions() - stats.getFailedLockAcquisitions());
        report.setFailedOperations(stats.getFailedLockAcquisitions());
        report.setAverageAcquisitionTime(stats.getAverageLockAcquisitionTime());
        report.setPeakConcurrency(stats.getPeakConcurrency());
        report.setUptime(stats.getUptime());

        // 计算性能指标
        report.setSuccessRate(calculateSuccessRate(stats));
        report.setThroughput(calculateThroughput(stats));
        report.setErrorRate(calculateErrorRate(stats));

        return report;
    }

    private double calculateSuccessRate(FactoryStatistics stats) {
        long total = stats.getTotalLockAcquisitions() + stats.getFailedLockAcquisitions();
        return total > 0 ? (double) stats.getTotalLockAcquisitions() / total : 0.0;
    }

    private double calculateThroughput(FactoryStatistics stats) {
        long uptimeSeconds = stats.getUptime() / 1000;
        return uptimeSeconds > 0 ? (double) stats.getTotalLockAcquisitions() / uptimeSeconds : 0.0;
    }

    private double calculateErrorRate(FactoryStatistics stats) {
        long total = stats.getTotalLockAcquisitions() + stats.getFailedLockAcquisitions();
        return total > 0 ? (double) stats.getFailedLockAcquisitions() / total : 0.0;
    }
}
```

## 容量规划

### 资源需求估算

```java
public class CapacityPlanner {

    public CapacityPlan planCapacity(int expectedQps, double targetLatency) {
        CapacityPlan plan = new CapacityPlan();

        // 估算连接池大小
        plan.setRecommendedPoolSize(calculatePoolSize(expectedQps, targetLatency));

        // 估算内存需求
        plan.setEstimatedMemoryUsage(calculateMemoryUsage(expectedQps));

        // 估算 CPU 需求
        plan.setEstimatedCpuUsage(calculateCpuUsage(expectedQps));

        return plan;
    }

    private int calculatePoolSize(int qps, double latency) {
        // 基于 Little's Law: L = λ * W
        // 连接数 = QPS * 平均响应时间
        return (int) Math.ceil(qps * latency);
    }

    private long calculateMemoryUsage(int qps) {
        // 估算内存使用：基础内存 + 连接池内存 + 缓存内存
        long baseMemory = 256 * 1024 * 1024; // 256MB 基础内存
        long connectionMemory = qps * 50 * 1024; // 每个连接约50KB
        long cacheMemory = qps * 10 * 1024; // 缓存约10KB per QPS

        return baseMemory + connectionMemory + cacheMemory;
    }

    private double calculateCpuUsage(int qps) {
        // 估算 CPU 使用率：基础 CPU + 锁操作 CPU
        double baseCpu = 0.1; // 10% 基础 CPU
        double lockCpu = qps * 0.0001; // 每个操作约0.01% CPU

        return Math.min(baseCpu + lockCpu, 0.8); // 最高80%
    }
}
```

## 故障排查

### 性能问题诊断

```java
@Service
public class PerformanceTroubleshooter {

    @Autowired
    private DistributedLockFactory lockFactory;

    public List<PerformanceIssue> diagnoseIssues() {
        List<PerformanceIssue> issues = new ArrayList<>();
        FactoryStatistics stats = lockFactory.getStatistics();

        // 检查锁竞争
        if (stats.getAverageLockAcquisitionTime() > 1000) {
            issues.add(new PerformanceIssue(
                "HIGH_CONTENTION",
                "High lock contention detected",
                "Average acquisition time: " + stats.getAverageLockAcquisitionTime() + "ms"
            ));
        }

        // 检查连接池
        double poolUtilization = getPoolUtilization();
        if (poolUtilization > 0.9) {
            issues.add(new PerformanceIssue(
                "POOL_EXHAUSTED",
                "Connection pool nearly exhausted",
                "Pool utilization: " + String.format("%.1f%%", poolUtilization * 100)
            ));
        }

        // 检查内存使用
        MemoryUsage memory = stats.getMemoryUsage();
        if (memory.getUsageRate() > 0.8) {
            issues.add(new PerformanceIssue(
                "HIGH_MEMORY",
                "High memory usage detected",
                "Memory usage: " + String.format("%.1f%%", memory.getUsageRate() * 100)
            ));
        }

        return issues;
    }

    private double getPoolUtilization() {
        // 获取连接池利用率的具体实现
        return 0.0;
    }
}
```

## 总结

通过以下调优措施，可以显著提升分布式锁的性能：

1. **配置优化**：合理设置连接池、超时时间、重试策略
2. **监控调优**：建立完善的性能监控和告警体系
3. **使用模式优化**：选择合适的锁粒度、使用异步操作、实现锁缓存
4. **后端优化**：针对 Redis/ZooKeeper 的特定优化配置
5. **性能测试**：使用 JMH 进行基准测试，持续监控性能指标
6. **容量规划**：根据预期负载进行资源规划
7. **故障排查**：建立性能问题诊断和解决机制

持续的性能调优是确保分布式锁系统稳定高效运行的关键。