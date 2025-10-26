# 高级特性使用指南

## 概述

本指南介绍分布式锁框架的高级特性，包括读写锁、批量操作、异步编程、监控告警等。

## 读写锁

### 基本概念

读写锁允许多个读操作同时进行，但写操作需要独占访问。

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
            // 读取缓存数据
            return loadFromCache(key);
        } finally {
            readLock.unlock();
        }
    }

    public void updateData(String key, String value) {
        DistributedReadWriteLock rwLock = lockFactory.getReadWriteLock("cache:" + key);
        DistributedLock writeLock = rwLock.writeLock();

        try {
            writeLock.lock();
            // 更新缓存数据
            saveToCache(key, value);
        } finally {
            writeLock.unlock();
        }
    }
}
```

### 锁升级和降级

```java
public void upgradeLock(String key) {
    DistributedReadWriteLock rwLock = lockFactory.getReadWriteLock("data:" + key);

    // 先获取读锁
    DistributedLock readLock = rwLock.readLock();
    readLock.lock();

    try {
        // 检查是否需要更新
        if (needsUpdate(key)) {
            // 释放读锁
            readLock.unlock();

            // 获取写锁 (锁升级)
            DistributedLock writeLock = rwLock.writeLock();
            writeLock.lock();

            try {
                // 执行更新
                updateData(key);
            } finally {
                writeLock.unlock();
            }
        }
    } finally {
        // 如果读锁还没释放，确保释放
        if (readLock.isHeldByCurrentThread()) {
            readLock.unlock();
        }
    }
}
```

## 批量操作

### 事务性批量锁

```java
@Service
public class BatchProcessingService {

    @Autowired
    private DistributedLockFactory lockFactory;

    public void processBatch(List<String> resourceIds) {
        // 创建锁名称列表
        List<String> lockNames = resourceIds.stream()
            .map(id -> "resource:" + id)
            .collect(Collectors.toList());

        // 创建批量操作
        BatchLockOperations<DistributedLock> batchOps =
            lockFactory.createBatchLockOperations(lockNames, locks -> {
                try {
                    // 批量业务处理
                    for (String resourceId : resourceIds) {
                        processResource(resourceId);
                    }

                    // 模拟可能出现的异常
                    if (Math.random() < 0.1) { // 10% 概率失败
                        throw new RuntimeException("Batch processing failed");
                    }

                    return resourceIds.size();
                } catch (Exception e) {
                    // 记录错误但不抛出，保持事务性
                    System.err.println("Error processing batch: " + e.getMessage());
                    throw e; // 重新抛出以触发回滚
                }
            });

        try {
            Integer processedCount = batchOps.execute();
            System.out.println("Successfully processed " + processedCount + " resources");
        } catch (BatchLockException e) {
            System.err.println("Batch operation failed: " + e.getMessage());
            // 处理失败情况
        }
    }

    private void processResource(String resourceId) {
        System.out.println("Processing resource: " + resourceId);
        // 模拟处理时间
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### 依赖关系处理

```java
public void processWithDependencies() {
    // 定义资源及其依赖关系
    Map<String, List<String>> dependencies = Map.of(
        "resource:A", List.of("resource:B", "resource:C"),
        "resource:B", List.of("resource:D"),
        "resource:C", List.of(),
        "resource:D", List.of()
    );

    // 拓扑排序确保正确的获取顺序
    List<String> sortedResources = topologicalSort(dependencies.keySet(), dependencies);

    // 创建锁名称列表 (按依赖顺序)
    List<String> lockNames = sortedResources.stream()
        .map(resource -> "lock:" + resource)
        .collect(Collectors.toList());

    BatchLockOperations<DistributedLock> batchOps =
        lockFactory.createBatchLockOperations(lockNames, locks -> {
            // 按依赖顺序处理
            for (String resource : sortedResources) {
                processResource(resource);
            }
            return sortedResources.size();
        });

    batchOps.execute();
}

private List<String> topologicalSort(Set<String> resources, Map<String, List<String>> dependencies) {
    // 实现拓扑排序算法
    // 这里简化为按字典序排序，实际应实现 Kahn 算法或 DFS
    return resources.stream()
        .sorted()
        .collect(Collectors.toList());
}
```

## 异步操作

### 异步锁操作

```java
@Service
public class AsyncService {

    @Autowired
    private DistributedLockFactory lockFactory;

    public CompletableFuture<String> processAsync(String resourceId) {
        return lockFactory.getLockAsync("resource:" + resourceId)
            .thenCompose(lock -> lock.tryLockAsync(5, 30, TimeUnit.SECONDS)
                .thenCompose(acquired -> {
                    if (acquired) {
                        return doAsyncWork(resourceId)
                            .whenComplete((result, throwable) -> lock.unlockAsync());
                    } else {
                        return CompletableFuture.failedFuture(
                            new RuntimeException("Failed to acquire lock for: " + resourceId));
                    }
                }));
    }

    private CompletableFuture<String> doAsyncWork(String resourceId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(1000);
                return "Processed: " + resourceId;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        });
    }
}
```

### 异步批量操作

```java
public CompletableFuture<List<String>> processBatchAsync(List<String> resourceIds) {
    List<String> lockNames = resourceIds.stream()
        .map(id -> "resource:" + id)
        .collect(Collectors.toList());

    return lockFactory.createAsyncLockOperations()
        .executeWithLocks(lockNames, locks -> {
            // 异步批量处理
            List<CompletableFuture<String>> futures = resourceIds.stream()
                .map(this::processResourceAsync)
                .collect(Collectors.toList());

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList()));
        });
}

private CompletableFuture<String> processResourceAsync(String resourceId) {
    return CompletableFuture.supplyAsync(() -> {
        System.out.println("Processing: " + resourceId);
        return resourceId;
    });
}
```

## 自动续期

### 看门狗机制

```java
@Service
public class LongRunningService {

    @Autowired
    private DistributedLockFactory lockFactory;

    public void longRunningOperation(String taskId) {
        DistributedLock lock = lockFactory.getLock("task:" + taskId);

        try {
            lock.lock(60, TimeUnit.SECONDS); // 持有 60 秒

            // 设置自动续期 (每 20 秒续期一次)
            ScheduledFuture<?> renewalTask = lock.scheduleAutoRenewal(20, TimeUnit.SECONDS,
                result -> {
                    if (result.isSuccess()) {
                        System.out.println("Lock renewed successfully for task: " + taskId);
                    } else {
                        System.err.println("Lock renewal failed: " + result.getFailureCause().getMessage());
                    }
                });

            try {
                // 执行长时间运行的操作
                performLongRunningTask(taskId);
            } finally {
                // 取消自动续期
                lock.cancelAutoRenewal(renewalTask);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    private void performLongRunningTask(String taskId) {
        // 模拟长时间运行的任务
        for (int i = 0; i < 10; i++) {
            System.out.println("Processing task " + taskId + ", step " + (i + 1));
            try {
                Thread.sleep(10000); // 10 秒每步
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
```

### 自定义续期策略

```java
public class CustomRenewalStrategy implements RenewalStrategy {

    @Override
    public boolean shouldRenew(DistributedLock lock, long remainingTime) {
        // 剩余时间少于 30% 时续期
        long leaseTime = lock.getConfigurationInfo().getDefaultLeaseTime(TimeUnit.MILLISECONDS);
        return remainingTime < leaseTime * 0.3;
    }

    @Override
    public long getRenewalInterval(DistributedLock lock) {
        // 续期间隔为租约时间的 20%
        long leaseTime = lock.getConfigurationInfo().getDefaultLeaseTime(TimeUnit.MILLISECONDS);
        return leaseTime / 5;
    }

    @Override
    public long getRenewalAmount(DistributedLock lock) {
        // 续期时间等于原始租约时间
        return lock.getConfigurationInfo().getDefaultLeaseTime(TimeUnit.MILLISECONDS);
    }
}
```

## 事件监听

### 锁事件监听

```java
@Component
public class LockEventLogger implements LockEventListener<DistributedLock> {

    private static final Logger logger = LoggerFactory.getLogger(LockEventLogger.class);

    @Override
    public void onLockEvent(LockEvent<DistributedLock> event) {
        switch (event.getEventType()) {
            case LOCK_ACQUIRED:
                logger.info("Lock acquired: {} by thread {}",
                    event.getLock().getName(),
                    event.getThreadName());
                break;

            case LOCK_RELEASED:
                logger.info("Lock released: {} by thread {}",
                    event.getLock().getName(),
                    event.getThreadName());
                break;

            case LOCK_EXPIRED:
                logger.warn("Lock expired: {}", event.getLock().getName());
                break;

            case DEADLOCK_DETECTED:
                logger.error("Deadlock detected involving lock: {}", event.getLock().getName());
                break;
        }
    }

    @Override
    public void onDeadlockDetected(DeadlockCycleInfo deadlockInfo) {
        logger.error("Deadlock cycle detected: {}",
            deadlockInfo.getCycle().stream()
                .map(lock -> lock.getName())
                .collect(Collectors.joining(" -> ")));
    }
}
```

### 注册事件监听器

```java
@Configuration
public class LockConfiguration {

    @Autowired
    private DistributedLockFactory lockFactory;

    @Autowired
    private LockEventLogger eventLogger;

    @PostConstruct
    public void init() {
        // 注册事件监听器
        lockFactory.registerEventListener(eventLogger);
    }
}
```

## 健康检查和监控

### 自定义健康检查

```java
@Component
public class DistributedLockHealthIndicator implements HealthIndicator {

    @Autowired
    private DistributedLockFactory lockFactory;

    @Override
    public Health health() {
        try {
            // 执行工厂健康检查
            FactoryHealthStatus status = lockFactory.healthCheck();

            if (status.isHealthy()) {
                return Health.up()
                    .withDetail("backend", "redis")
                    .withDetail("responseTime", status.getPerformanceMetrics().getResponseTimeMs() + "ms")
                    .withDetail("activeLocks", lockFactory.getStatistics().getActiveLocks())
                    .build();
            } else {
                return Health.down()
                    .withDetail("error", status.getErrorMessage())
                    .build();
            }

        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
```

### 性能监控

```java
@Service
public class LockPerformanceMonitor {

    @Autowired
    private DistributedLockFactory lockFactory;

    @Scheduled(fixedRate = 60000) // 每分钟检查一次
    public void monitorPerformance() {
        FactoryStatistics stats = lockFactory.getStatistics();

        // 记录性能指标
        Metrics.gauge("distributed_lock.active", stats.getActiveLocks());
        Metrics.gauge("distributed_lock.total_acquisitions", stats.getTotalLockAcquisitions());
        Metrics.gauge("distributed_lock.failed_acquisitions", stats.getFailedLockAcquisitions());
        Metrics.gauge("distributed_lock.average_acquisition_time", stats.getAverageLockAcquisitionTime());

        // 检查性能阈值
        if (stats.getAverageLockAcquisitionTime() > 1000) { // 超过 1 秒
            alertService.sendAlert("Slow lock acquisition detected: " +
                stats.getAverageLockAcquisitionTime() + "ms");
        }

        double errorRate = (double) stats.getFailedLockAcquisitions() /
                          (stats.getTotalLockAcquisitions() + stats.getFailedLockAcquisitions());

        if (errorRate > 0.05) { // 错误率超过 5%
            alertService.sendAlert("High lock error rate: " + (errorRate * 100) + "%");
        }
    }
}
```

## 配置管理

### 动态配置更新

```java
@Service
public class DynamicConfigurationService {

    @Autowired
    private LockConfiguration lockConfig;

    @Autowired
    private DistributedLockFactory lockFactory;

    public void updateLeaseTime(int newLeaseTimeSeconds) {
        // 创建新的配置
        Config newConfig = ConfigFactory.parseString(
            String.format("distributed-lock.default-lease-time = %ds", newLeaseTimeSeconds)
        ).withFallback(lockConfig.getConfig());

        // 更新配置
        lockConfig.updateConfiguration(newConfig);

        System.out.println("Updated default lease time to " + newLeaseTimeSeconds + " seconds");
    }

    public void enableMetrics(boolean enabled) {
        Config newConfig = ConfigFactory.parseString(
            "distributed-lock.metrics.enabled = " + enabled
        ).withFallback(lockConfig.getConfig());

        lockConfig.updateConfiguration(newConfig);
        System.out.println("Metrics " + (enabled ? "enabled" : "disabled"));
    }
}
```

### 配置监听

```java
@Component
public class ConfigurationChangeHandler implements Consumer<LockConfiguration> {

    @Override
    public void accept(LockConfiguration config) {
        System.out.println("Configuration updated:");
        System.out.println("  Default lease time: " + config.getDefaultLeaseTime());
        System.out.println("  Default wait time: " + config.getDefaultWaitTime());
        System.out.println("  Metrics enabled: " + config.isMetricsEnabled());
        System.out.println("  Watchdog enabled: " + config.isWatchdogEnabled());

        // 重新初始化相关组件
        reinitializeComponents(config);
    }

    private void reinitializeComponents(LockConfiguration config) {
        // 重新初始化监控、告警等组件
        // ...
    }
}
```

## 故障转移和高可用

### 多后端配置

```java
@Configuration
public class HighAvailabilityConfiguration {

    @Bean
    public DistributedLockFactory lockFactory(
            @Qualifier("redisLockFactory") DistributedLockFactory redisFactory,
            @Qualifier("zookeeperLockFactory") DistributedLockFactory zkFactory) {

        // 创建高可用策略
        HighAvailabilityStrategy<DistributedLockFactory> strategy =
            new FailoverStrategy<>(List.of(redisFactory, zkFactory));

        return new HighAvailabilityLockFactory(strategy);
    }
}
```

### 自定义高可用策略

```java
public class SmartFailoverStrategy implements HighAvailabilityStrategy<DistributedLockFactory> {

    private final List<DistributedLockFactory> backends;
    private volatile DistributedLockFactory currentBackend;
    private final Map<DistributedLockFactory, HealthStatus> healthStatus = new ConcurrentHashMap<>();

    @Override
    public DistributedLockFactory selectBackend(List<DistributedLockFactory> backends) {
        // 选择健康的后端
        return backends.stream()
            .filter(this::isHealthy)
            .findFirst()
            .orElseThrow(() -> new RuntimeException("No healthy backend available"));
    }

    @Override
    public void onBackendFailure(DistributedLockFactory backend) {
        healthStatus.put(backend, HealthStatus.UNHEALTHY);
        // 触发告警
        alertService.sendAlert("Backend failure detected: " + backend.getFactoryName());
    }

    @Override
    public void onBackendRecovery(DistributedLockFactory backend) {
        healthStatus.put(backend, HealthStatus.HEALTHY);
        // 触发恢复通知
        alertService.sendAlert("Backend recovered: " + backend.getFactoryName());
    }

    private boolean isHealthy(DistributedLockFactory backend) {
        return healthStatus.getOrDefault(backend, HealthStatus.UNKNOWN) == HealthStatus.HEALTHY;
    }
}
```

## 最佳实践

### 1. 合理设置超时时间

```java
public class TimeoutBestPractice {

    public void processWithAppropriateTimeouts(String resourceId) {
        DistributedLock lock = lockFactory.getLock("resource:" + resourceId);

        try {
            // 设置合理的等待和持有时间
            boolean acquired = lock.tryLock(30, 300, TimeUnit.SECONDS); // 等待 30 秒，持有 5 分钟

            if (acquired) {
                // 执行业务逻辑
                performBusinessLogic(resourceId);
            } else {
                // 处理获取锁失败的情况
                handleLockAcquisitionFailure(resourceId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleInterruption(resourceId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

### 2. 使用 try-with-resources

```java
public class ResourceManagementPractice {

    public void processWithAutoCloseable(String resourceId) {
        DistributedLock lock = lockFactory.getLock("resource:" + resourceId);

        try (AutoCloseableLock autoLock = new AutoCloseableLock(lock)) {
            autoLock.lock(30, TimeUnit.SECONDS);
            // 执行业务逻辑
            performBusinessLogic(resourceId);
        } catch (Exception e) {
            handleError(resourceId, e);
        }
    }

    static class AutoCloseableLock implements AutoCloseable {
        private final DistributedLock lock;
        private boolean locked = false;

        public AutoCloseableLock(DistributedLock lock) {
            this.lock = lock;
        }

        public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
            lock.lock(leaseTime, unit);
            locked = true;
        }

        @Override
        public void close() {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

### 3. 避免死锁

```java
public class DeadlockPreventionPractice {

    public void transferMoney(String fromAccount, String toAccount, BigDecimal amount) {
        // 按固定顺序获取锁，避免死锁
        List<String> accounts = List.of(fromAccount, toAccount).stream()
            .sorted() // 按字典序排序
            .collect(Collectors.toList());

        List<String> lockNames = accounts.stream()
            .map(account -> "account:" + account)
            .collect(Collectors.toList());

        BatchLockOperations<DistributedLock> batchOps =
            lockFactory.createBatchLockOperations(lockNames, locks -> {
                // 执行转账逻辑
                performTransfer(fromAccount, toAccount, amount);
                return 1;
            });

        batchOps.execute();
    }

    private void performTransfer(String fromAccount, String toAccount, BigDecimal amount) {
        // 转账业务逻辑
        System.out.println("Transferring " + amount + " from " + fromAccount + " to " + toAccount);
    }
}
```

### 4. 监控和告警

```java
@Service
public class MonitoringBestPractice {

    @Autowired
    private DistributedLockFactory lockFactory;

    @Autowired
    private AlertService alertService;

    @Scheduled(fixedRate = 300000) // 5 分钟检查一次
    public void comprehensiveHealthCheck() {
        FactoryHealthStatus health = lockFactory.healthCheck();
        FactoryStatistics stats = lockFactory.getStatistics();

        // 检查健康状态
        if (!health.isHealthy()) {
            alertService.sendAlert("Lock factory unhealthy: " + health.getErrorMessage());
        }

        // 检查性能指标
        if (stats.getAverageLockAcquisitionTime() > 2000) {
            alertService.sendAlert("Slow lock acquisition: " + stats.getAverageLockAcquisitionTime() + "ms");
        }

        // 检查错误率
        long total = stats.getTotalLockAcquisitions() + stats.getFailedLockAcquisitions();
        if (total > 0) {
            double errorRate = (double) stats.getFailedLockAcquisitions() / total;
            if (errorRate > 0.1) { // 10% 错误率
                alertService.sendAlert("High lock error rate: " + (errorRate * 100) + "%");
            }
        }

        // 检查活跃锁数量
        if (stats.getActiveLocks() > 1000) {
            alertService.sendAlert("Too many active locks: " + stats.getActiveLocks());
        }
    }
}
```

## 总结

本指南涵盖了分布式锁框架的主要高级特性：

1. **读写锁**：支持并发读操作，独占写操作
2. **批量操作**：事务性批量锁操作，提高效率
3. **异步操作**：非阻塞的异步锁操作
4. **自动续期**：看门狗机制防止锁过期
5. **事件监听**：锁生命周期事件监听
6. **健康检查**：全面的健康状态监控
7. **动态配置**：运行时配置更新
8. **高可用**：多后端故障转移

通过合理使用这些高级特性，可以构建出高性能、高可用的分布式应用。