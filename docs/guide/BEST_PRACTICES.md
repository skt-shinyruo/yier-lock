# 最佳实践指南

## 概述

本指南提供使用分布式锁框架时的最佳实践，包括设计原则、使用模式、性能优化和故障处理等方面。

## 设计原则

### 1. 锁粒度设计

#### 原则：锁的粒度应尽可能小

```java
// ❌ 不推荐：使用粗粒度锁
@DistributedLock(key = "'user'")  // 所有用户操作使用同一个锁
public void updateUser(String userId, UserData data) {
    // 更新用户逻辑
}

// ✅ 推荐：使用细粒度锁
@DistributedLock(key = "'user:' + #userId")  // 每个用户使用独立的锁
public void updateUser(String userId, UserData data) {
    // 更新用户逻辑
}
```

#### 锁层次设计

```java
@Service
public class HierarchicalLockingService {

    @Autowired
    private DistributedLockFactory lockFactory;

    public void updateUserProfile(String userId, ProfileData profile) {
        // 1. 获取用户级锁
        DistributedLock userLock = lockFactory.getLock("user:" + userId);

        try {
            userLock.lock(30, TimeUnit.SECONDS);

            // 2. 在用户锁内获取更细粒度的锁
            updateProfile(userId, profile);
            updatePreferences(userId, profile.getPreferences());

        } finally {
            userLock.unlock();
        }
    }

    @DistributedLock(key = "'user:' + #userId + ':profile'")
    private void updateProfile(String userId, ProfileData profile) {
        // 更新基本资料
    }

    @DistributedLock(key = "'user:' + #userId + ':preferences'")
    private void updatePreferences(String userId, Preferences prefs) {
        // 更新偏好设置
    }
}
```

### 2. 锁键设计

#### 一致的命名约定

```java
public class LockKeyConstants {

    // 用户相关锁键
    public static final String USER_LOCK_PREFIX = "user:";
    public static final String USER_PROFILE_LOCK_PREFIX = "user:profile:";
    public static final String USER_PREFERENCES_LOCK_PREFIX = "user:prefs:";

    // 订单相关锁键
    public static final String ORDER_LOCK_PREFIX = "order:";
    public static final String ORDER_PAYMENT_LOCK_PREFIX = "order:payment:";

    // 库存相关锁键
    public static final String INVENTORY_LOCK_PREFIX = "inventory:";
    public static final String PRODUCT_STOCK_LOCK_PREFIX = "inventory:stock:";

    // 生成锁键的方法
    public static String userLock(String userId) {
        return USER_LOCK_PREFIX + userId;
    }

    public static String orderLock(String orderId) {
        return ORDER_LOCK_PREFIX + orderId;
    }

    public static String inventoryLock(String productId) {
        return PRODUCT_STOCK_LOCK_PREFIX + productId;
    }
}
```

#### 避免锁键冲突

```java
@Service
public class LockKeyService {

    public String generateLockKey(String domain, String entityId) {
        return domain + ":" + entityId;
    }

    public String generateLockKey(String domain, String entityId, String operation) {
        return domain + ":" + entityId + ":" + operation;
    }

    public String generateCompositeLockKey(String... parts) {
        return String.join(":", parts);
    }
}

// 使用示例
@DistributedLock(key = "@lockKeyService.generateLockKey('order', #orderId)")
public void processOrder(String orderId) {
    // 处理订单
}

@DistributedLock(key = "@lockKeyService.generateCompositeLockKey('user', #userId, 'profile')")
public void updateProfile(String userId) {
    // 更新资料
}
```

### 3. 超时设置

#### 合理设置超时时间

```java
@Configuration
public class LockTimeoutConfiguration {

    @Bean
    public LockConfiguration lockConfiguration() {
        return LockConfigurationBuilder.newBuilder()
            .defaultLeaseTime(30, TimeUnit.SECONDS)  // 默认持有时间
            .defaultWaitTime(10, TimeUnit.SECONDS)   // 默认等待时间
            .build();
    }
}

// 按操作类型设置不同超时
@Service
public class TimeoutBestPracticeService {

    @DistributedLock(key = "'fast:' + #id", leaseTime = 5, waitTime = 2)
    public void fastOperation(String id) {
        // 快速操作：5秒持有，2秒等待
    }

    @DistributedLock(key = "'slow:' + #id", leaseTime = 300, waitTime = 30)
    public void slowOperation(String id) {
        // 慢操作：5分钟持有，30秒等待
    }

    @DistributedLock(key = "'critical:' + #id", leaseTime = 60, waitTime = 5, autoRenewal = true)
    public void criticalOperation(String id) {
        // 关键操作：启用自动续期
    }
}
```

## 使用模式

### 1. 悲观锁模式

适用于写多读少的场景：

```java
@Service
public class PessimisticLockingService {

    @Autowired
    private DistributedLockFactory lockFactory;

    public void updateInventory(String productId, int quantity) {
        DistributedLock lock = lockFactory.getLock("inventory:" + productId);

        try {
            lock.lock(30, TimeUnit.SECONDS);
            // 读取当前库存
            int currentStock = getCurrentStock(productId);

            // 计算新库存
            int newStock = currentStock + quantity;

            // 更新库存
            updateStock(productId, newStock);

        } finally {
            lock.unlock();
        }
    }
}
```

### 2. 乐观锁模式

适用于读多写少的场景：

```java
@Service
public class OptimisticLockingService {

    @Autowired
    private DistributedLockFactory lockFactory;

    public boolean updateInventoryOptimistic(String productId, int quantity, int expectedVersion) {
        DistributedLock lock = lockFactory.getLock("inventory:" + productId);

        try {
            // 短暂持有锁进行版本检查和更新
            boolean acquired = lock.tryLock(1, 5, TimeUnit.SECONDS);
            if (!acquired) {
                return false; // 获取锁失败
            }

            // 检查版本
            Inventory inventory = getInventory(productId);
            if (inventory.getVersion() != expectedVersion) {
                return false; // 版本不匹配
            }

            // 更新库存和版本
            updateStock(productId, inventory.getStock() + quantity, expectedVersion + 1);
            return true;

        } finally {
            lock.unlock();
        }
    }
}
```

### 3. 读写锁模式

适用于读多写少的场景：

```java
@Service
public class ReadWriteLockingService {

    @Autowired
    private DistributedLockFactory lockFactory;

    public Product getProduct(String productId) {
        DistributedReadWriteLock rwLock = lockFactory.getReadWriteLock("product:" + productId);
        DistributedLock readLock = rwLock.readLock();

        try {
            readLock.lock();
            return getProductFromCache(productId);
        } finally {
            readLock.unlock();
        }
    }

    public void updateProduct(String productId, Product product) {
        DistributedReadWriteLock rwLock = lockFactory.getReadWriteLock("product:" + productId);
        DistributedLock writeLock = rwLock.writeLock();

        try {
            writeLock.lock(30, TimeUnit.SECONDS);
            updateProductInDatabase(productId, product);
            invalidateCache(productId);
        } finally {
            writeLock.unlock();
        }
    }
}
```

### 4. 批量操作模式

```java
@Service
public class BatchOperationService {

    @Autowired
    private DistributedLockFactory lockFactory;

    public void processBatchOrders(List<String> orderIds) {
        // 按字典序排序避免死锁
        List<String> sortedOrderIds = orderIds.stream()
            .sorted()
            .collect(Collectors.toList());

        List<String> lockNames = sortedOrderIds.stream()
            .map(id -> "order:" + id)
            .collect(Collectors.toList());

        BatchLockOperations<DistributedLock> batchOps =
            lockFactory.createBatchLockOperations(lockNames, locks -> {
                // 批量处理订单
                for (String orderId : sortedOrderIds) {
                    processSingleOrder(orderId);
                }
                return sortedOrderIds.size();
            });

        try {
            Integer processedCount = batchOps.execute();
            System.out.println("Successfully processed " + processedCount + " orders");
        } catch (BatchLockException e) {
            System.err.println("Batch processing failed: " + e.getMessage());
            // 处理失败情况
        }
    }
}
```

## 性能优化

### 1. 锁缓存

```java
@Component
public class LockCache {

    private final Cache<String, DistributedLock> lockCache;

    public LockCache() {
        this.lockCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build();
    }

    public DistributedLock getLock(DistributedLockFactory factory, String name) {
        return lockCache.get(name, key -> factory.getLock(key));
    }
}

@Service
public class CachedLockingService {

    @Autowired
    private LockCache lockCache;

    @Autowired
    private DistributedLockFactory lockFactory;

    public void processWithCachedLock(String resourceId) {
        DistributedLock lock = lockCache.getLock(lockFactory, "resource:" + resourceId);

        try {
            lock.lock();
            // 处理资源
        } finally {
            lock.unlock();
        }
    }
}
```

### 2. 异步操作

```java
@Service
public class AsyncLockingService {

    @Autowired
    private DistributedLockFactory lockFactory;

    public CompletableFuture<Void> processAsync(String resourceId) {
        return lockFactory.getLockAsync("resource:" + resourceId)
            .thenCompose(lock -> lock.lockAsync(30, TimeUnit.SECONDS)
                .thenRun(() -> doWork(resourceId))
                .whenComplete((result, throwable) -> lock.unlockAsync()));
    }

    public CompletableFuture<List<String>> processBatchAsync(List<String> resourceIds) {
        List<CompletableFuture<String>> futures = resourceIds.stream()
            .map(this::processSingleAsync)
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList()));
    }

    private CompletableFuture<String> processSingleAsync(String resourceId) {
        return CompletableFuture.supplyAsync(() -> {
            processSync(resourceId);
            return resourceId;
        });
    }

    private void processSync(String resourceId) {
        DistributedLock lock = lockFactory.getLock("resource:" + resourceId);
        try {
            lock.lock();
            doWork(resourceId);
        } finally {
            lock.unlock();
        }
    }

    private void doWork(String resourceId) {
        // 业务逻辑
    }
}
```

### 3. 连接池优化

```yaml
# Redis 连接池优化配置
distributed-lock:
  redis:
    pool:
      max-total: 20      # 最大连接数
      max-idle: 10       # 最大空闲连接
      min-idle: 5        # 最小空闲连接
      max-wait-millis: 5000  # 最大等待时间
      test-on-borrow: true   # 借用时测试连接
      test-on-return: true   # 归还时测试连接
      test-while-idle: true  # 空闲时测试连接
```

### 4. 批量处理优化

```java
@Service
public class OptimizedBatchService {

    @Autowired
    private DistributedLockFactory lockFactory;

    public void processLargeBatch(List<String> items) {
        // 分批处理，避免一次性获取太多锁
        List<List<String>> batches = partition(items, 10); // 每批10个

        for (List<String> batch : batches) {
            processBatch(batch);
        }
    }

    private void processBatch(List<String> batch) {
        List<String> lockNames = batch.stream()
            .map(item -> "item:" + item)
            .sorted() // 排序避免死锁
            .collect(Collectors.toList());

        BatchLockOperations<DistributedLock> batchOps =
            lockFactory.createBatchLockOperations(lockNames, locks -> {
                // 并行处理批次内的项目
                batch.parallelStream().forEach(this::processItem);
                return batch.size();
            });

        batchOps.execute();
    }

    private void processItem(String item) {
        // 处理单个项目
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

## 故障处理

### 1. 重试机制

```java
@Service
public class RetryableLockingService {

    @Autowired
    private DistributedLockFactory lockFactory;

    @Retryable(value = LockAcquisitionException.class,
               maxAttempts = 3,
               backoff = @Backoff(delay = 100, multiplier = 2))
    public void processWithRetry(String resourceId) {
        DistributedLock lock = lockFactory.getLock("resource:" + resourceId);

        try {
            boolean acquired = lock.tryLock(5, 30, TimeUnit.SECONDS);
            if (!acquired) {
                throw new LockAcquisitionException("resource:" + resourceId, 5, TimeUnit.SECONDS);
            }

            // 业务处理
            doWork(resourceId);

        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Recover
    public void recover(LockAcquisitionException e, String resourceId) {
        // 重试失败后的处理逻辑
        System.err.println("Failed to acquire lock for resource: " + resourceId +
                          " after retries: " + e.getMessage());
        // 可以选择异步处理或者记录失败
    }
}
```

### 2. 降级处理

```java
@Service
public class DegradableLockingService {

    @Autowired
    private DistributedLockFactory lockFactory;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    public void processWithCircuitBreaker(String resourceId) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("lock-service");

        try {
            circuitBreaker.executeCallable(() -> {
                processWithLock(resourceId);
                return null;
            });
        } catch (Exception e) {
            // 熔断器打开时的降级处理
            processWithoutLock(resourceId);
        }
    }

    private void processWithLock(String resourceId) {
        DistributedLock lock = lockFactory.getLock("resource:" + resourceId);

        try {
            boolean acquired = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (acquired) {
                doWork(resourceId);
            } else {
                throw new RuntimeException("Failed to acquire lock");
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void processWithoutLock(String resourceId) {
        // 降级处理：不使用锁，但可能有数据不一致风险
        System.out.println("Processing without lock due to circuit breaker: " + resourceId);
        doWorkUnsafe(resourceId);
    }
}
```

### 3. 监控和告警

```java
@Service
public class LockMonitoringService {

    @Autowired
    private DistributedLockFactory lockFactory;

    @Autowired
    private AlertService alertService;

    @Scheduled(fixedRate = 30000) // 30秒检查一次
    public void monitorLockHealth() {
        FactoryStatistics stats = lockFactory.getStatistics();

        // 检查错误率
        long total = stats.getTotalLockAcquisitions() + stats.getFailedLockAcquisitions();
        if (total > 0) {
            double errorRate = (double) stats.getFailedLockAcquisitions() / total;
            if (errorRate > 0.05) { // 5% 错误率阈值
                alertService.sendAlert("High lock error rate: " + String.format("%.2f%%", errorRate * 100));
            }
        }

        // 检查平均获取时间
        if (stats.getAverageLockAcquisitionTime() > 2000) { // 2秒阈值
            alertService.sendAlert("Slow lock acquisition: " + stats.getAverageLockAcquisitionTime() + "ms");
        }

        // 检查活跃锁数量
        if (stats.getActiveLocks() > 100) { // 活跃锁数量阈值
            alertService.sendAlert("Too many active locks: " + stats.getActiveLocks());
        }
    }

    @Scheduled(fixedRate = 300000) // 5分钟清理一次
    public void cleanupExpiredLocks() {
        int cleaned = lockFactory.cleanupExpiredLocks();
        if (cleaned > 0) {
            System.out.println("Cleaned up " + cleaned + " expired locks");
        }
    }
}
```

## 安全考虑

### 1. 权限控制

```java
@Service
public class SecureLockingService {

    @Autowired
    private DistributedLockFactory lockFactory;

    @Autowired
    private PermissionService permissionService;

    public void secureOperation(String resourceId, String userId) {
        // 检查权限
        if (!permissionService.hasPermission(userId, "LOCK", resourceId)) {
            throw new AccessDeniedException("No permission to lock resource: " + resourceId);
        }

        DistributedLock lock = lockFactory.getLock("resource:" + resourceId);

        try {
            lock.lock();
            // 执行操作
            doSecureWork(resourceId, userId);
        } finally {
            lock.unlock();
        }
    }
}
```

### 2. 审计日志

```java
@Component
public class LockAuditAspect {

    private static final Logger auditLogger = LoggerFactory.getLogger("LOCK_AUDIT");

    @Around("@annotation(distributedLock)")
    public Object auditLockOperation(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        String lockKey = parseLockKey(distributedLock.key(), joinPoint);
        String userId = SecurityContextHolder.getContext().getAuthentication().getName();
        long startTime = System.currentTimeMillis();

        try {
            Object result = joinPoint.proceed();

            // 记录成功操作
            auditLogger.info("LOCK_SUCCESS|user:{}|lock:{}|duration:{}ms",
                           userId, lockKey, System.currentTimeMillis() - startTime);

            return result;

        } catch (Exception e) {
            // 记录失败操作
            auditLogger.error("LOCK_FAILURE|user:{}|lock:{}|error:{}|duration:{}ms",
                            userId, lockKey, e.getMessage(), System.currentTimeMillis() - startTime);
            throw e;
        }
    }
}
```

## 测试最佳实践

### 1. 单元测试

```java
@SpringBootTest
public class LockingServiceTest {

    @Autowired
    private LockingService lockingService;

    @MockBean
    private DistributedLockFactory lockFactory;

    @Mock
    private DistributedLock mockLock;

    @Test
    public void testLockingLogic() {
        // Mock 锁行为
        when(lockFactory.getLock(anyString())).thenReturn(mockLock);
        when(mockLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);

        // 执行测试
        lockingService.processWithLock("test-resource");

        // 验证锁操作
        verify(mockLock).tryLock(10, 30, TimeUnit.SECONDS);
        verify(mockLock).unlock();
    }
}
```

### 2. 集成测试

```java
@SpringBootTest
@ActiveProfiles("test")
public class DistributedLockIntegrationTest {

    @Autowired
    private DistributedLockFactory lockFactory;

    @Test
    public void testConcurrentLocking() throws InterruptedException {
        String lockName = "test-concurrent";
        int threadCount = 5;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    DistributedLock lock = lockFactory.getLock(lockName);
                    boolean acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);

                    if (acquired) {
                        try {
                            successCount.incrementAndGet();
                            Thread.sleep(100); // 模拟工作
                        } finally {
                            lock.unlock();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        assertEquals(5, successCount.get()); // 所有线程都应该成功，但顺序执行
    }
}
```

### 3. 性能测试

```java
@SpringBootTest
public class LockPerformanceTest {

    @Autowired
    private DistributedLockFactory lockFactory;

    @Test
    public void testLockPerformance() {
        String lockName = "perf-test";
        int iterations = 1000;

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < iterations; i++) {
            DistributedLock lock = lockFactory.getLock(lockName + i);
            try {
                lock.lock(1, TimeUnit.SECONDS);
                // 最小工作负载
            } finally {
                lock.unlock();
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        double avgTime = (double) duration / iterations;

        System.out.println("Average lock operation time: " + avgTime + "ms");
        assertTrue(avgTime < 10); // 期望平均时间小于10ms
    }
}
```

## 总结

遵循这些最佳实践可以帮助你：

1. **正确设计锁粒度**：避免不必要的阻塞，提高并发性能
2. **合理设置超时**：防止死锁和无限等待
3. **选择合适的使用模式**：根据场景选择最优的锁策略
4. **实施性能优化**：通过缓存、异步、连接池等技术提升性能
5. **建立故障处理机制**：重试、降级、监控确保系统稳定性
6. **考虑安全因素**：权限控制和审计确保系统安全
7. **编写高质量测试**：单元测试、集成测试、性能测试确保代码质量

通过综合应用这些最佳实践，可以构建出高性能、高可用、安全的分布式锁系统。