# API 参考文档

## 核心接口

### DistributedLock

分布式锁的核心接口，提供同步和异步的锁操作。

#### 基本方法

```java
public interface DistributedLock extends AutoCloseable {

    // 同步获取锁
    void lock(long leaseTime, TimeUnit unit) throws InterruptedException;
    void lock() throws InterruptedException;

    // 尝试获取锁
    boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException;
    boolean tryLock() throws InterruptedException;

    // 释放锁
    void unlock();

    // 异步操作
    CompletableFuture<Void> lockAsync(long leaseTime, TimeUnit unit);
    CompletableFuture<Void> lockAsync();
    CompletableFuture<Boolean> tryLockAsync(long waitTime, long leaseTime, TimeUnit unit);
    CompletableFuture<Boolean> tryLockAsync();
    CompletableFuture<Void> unlockAsync();
}
```

#### 状态查询方法

```java
// 锁状态检查
boolean isLocked();
boolean isHeldByCurrentThread();
boolean isExpired();

// 锁信息获取
String getName();
int getReentrantCount();
long getRemainingTime(TimeUnit unit);
String getLockHolder();

// 锁状态信息
LockStateInfo getLockStateInfo();
LockConfigurationInfo getConfigurationInfo();
```

#### 高级特性方法

```java
// 锁续期
boolean renewLock(long newLeaseTime, TimeUnit unit) throws InterruptedException;
CompletableFuture<Boolean> renewLockAsync(long newLeaseTime, TimeUnit unit);

// 自动续期
ScheduledFuture<?> scheduleAutoRenewal(long renewInterval, TimeUnit unit);
ScheduledFuture<?> scheduleAutoRenewal(long renewInterval, TimeUnit unit,
                                      Consumer<RenewalResult> renewalCallback);
boolean cancelAutoRenewal(ScheduledFuture<?> renewalTask);

// 健康检查
HealthCheckResult healthCheck();
```

### DistributedLockFactory

分布式锁工厂接口，负责创建和管理锁实例。

#### 锁创建方法

```java
public interface DistributedLockFactory extends AutoCloseable {

    // 基本锁创建
    DistributedLock getLock(String name);
    DistributedReadWriteLock getReadWriteLock(String name);

    // 配置锁创建
    DistributedLock getConfiguredLock(String name, LockConfiguration configuration);
    DistributedReadWriteLock getConfiguredReadWriteLock(String name, LockConfiguration configuration);

    // 批量锁创建
    Map<String, DistributedLock> getLocks(List<String> lockNames);
    Map<String, DistributedReadWriteLock> getReadWriteLocks(List<String> lockNames);

    // 异步锁创建
    CompletableFuture<DistributedLock> getLockAsync(String name);
    CompletableFuture<DistributedReadWriteLock> getReadWriteLockAsync(String name);
}
```

#### 批量操作方法

```java
// 批量锁操作
<R> BatchLockOperations<DistributedLock> createBatchLockOperations(
    List<String> lockNames,
    BatchLockOperations.BatchOperationExecutor<R> operation);

// 异步锁操作器
AsyncLockOperations<DistributedLock> createAsyncLockOperations();
```

#### 管理方法

```java
// 健康检查
FactoryHealthStatus healthCheck();
CompletableFuture<FactoryHealthStatus> healthCheckAsync();

// 锁管理
boolean isLockAvailable(String name);
CompletableFuture<Boolean> isLockAvailableAsync(String name);
boolean releaseLock(String name);
CompletableFuture<Boolean> releaseLockAsync(String name);

// 统计信息
FactoryStatistics getStatistics();
void resetStatistics();

// 活跃锁管理
List<String> getActiveLocks();
int cleanupExpiredLocks();
CompletableFuture<Integer> cleanupExpiredLocksAsync();
```

## 配置接口

### LockConfigurationBuilder

锁配置构建器，用于创建锁的配置参数。

```java
public interface LockConfigurationBuilder {

    // 基础配置
    LockConfigurationBuilder leaseTime(long time, TimeUnit unit);
    LockConfigurationBuilder waitTime(long time, TimeUnit unit);
    LockConfigurationBuilder retryCount(int count);
    LockConfigurationBuilder retryInterval(long interval, TimeUnit unit);

    // 锁类型配置
    LockConfigurationBuilder lockType(LockType type);
    LockConfigurationBuilder fair(boolean fair);
    LockConfigurationBuilder reentrant(boolean reentrant);

    // 高级配置
    LockConfigurationBuilder autoRenewal(boolean enabled);
    LockConfigurationBuilder renewalRatio(double ratio);
    LockConfigurationBuilder renewalInterval(long interval, TimeUnit unit);

    // 监控配置
    LockConfigurationBuilder monitoring(boolean enabled);
    LockConfigurationBuilder metrics(boolean enabled);

    // 构建配置
    LockConfiguration build();
}
```

### LockConfiguration

锁配置接口，定义锁的所有配置参数。

```java
public interface LockConfiguration {

    // 时间配置
    long getLeaseTime(TimeUnit unit);
    long getWaitTime(TimeUnit unit);
    long getRenewalInterval(TimeUnit unit);

    // 重试配置
    int getRetryCount();
    long getRetryInterval(TimeUnit unit);

    // 锁特性配置
    LockType getLockType();
    boolean isFair();
    boolean isReentrant();
    boolean isAutoRenewalEnabled();
    double getRenewalRatio();

    // 监控配置
    boolean isMonitoringEnabled();
    boolean isMetricsEnabled();
}
```

## 批量操作接口

### BatchLockOperations

批量锁操作接口，支持事务性批量锁操作。

```java
public interface BatchLockOperations<T extends DistributedLock> {

    // 执行批量操作
    <R> R execute(BatchOperationExecutor<R> executor) throws BatchLockException;

    // 异步执行批量操作
    <R> CompletableFuture<R> executeAsync(BatchOperationExecutor<R> executor);

    // 锁管理
    List<T> getLocks();
    boolean isAllLocksAcquired();
    void releaseAllLocks();

    // 操作执行器接口
    @FunctionalInterface
    interface BatchOperationExecutor<R> {
        R execute(List<T> locks) throws Exception;
    }
}
```

### AsyncLockOperations

异步锁操作接口，支持异步锁操作编排。

```java
public interface AsyncLockOperations<T extends DistributedLock> {

    // 异步锁获取
    CompletableFuture<T> acquireLock(String name);
    CompletableFuture<T> acquireLock(String name, LockConfiguration config);

    // 异步锁释放
    CompletableFuture<Void> releaseLock(T lock);
    CompletableFuture<Void> releaseLock(String name);

    // 异步批量操作
    <R> CompletableFuture<R> executeWithLocks(List<String> lockNames,
                                             BatchOperationExecutor<R> executor);

    // 锁状态查询
    CompletableFuture<Boolean> isLockAvailable(String name);
    CompletableFuture<LockStateInfo> getLockState(String name);
}
```

## 监控和指标接口

### PerformanceMetrics

性能指标接口，提供锁操作的性能统计。

```java
public interface PerformanceMetrics {

    // 基础指标
    long getTotalOperations();
    long getSuccessfulOperations();
    long getFailedOperations();

    // 时间指标
    double getAverageOperationTime();
    long getMinOperationTime();
    long getMaxOperationTime();
    double getPercentile95OperationTime();
    double getPercentile99OperationTime();

    // 并发指标
    int getCurrentConcurrency();
    int getPeakConcurrency();

    // 错误指标
    double getErrorRate();
    Map<String, Long> getErrorCounts();

    // 资源指标
    long getMemoryUsage();
    double getCpuUsage();

    // 导出方法
    String export(ReportFormat format);
}
```

### LockEventListener

锁事件监听器接口，用于监听锁的各种事件。

```java
public interface LockEventListener<T extends DistributedLock> {

    // 锁事件处理
    void onLockEvent(LockEvent<T> event);

    // 死锁检测
    default void onDeadlockDetected(DeadlockCycleInfo deadlockInfo) {
        // 默认实现
    }

    // 事件过滤
    default boolean shouldHandleEvent(LockEvent<T> event) {
        return true;
    }
}
```

## 异常类

### DistributedLockException

分布式锁基础异常类。

```java
public class DistributedLockException extends RuntimeException {

    public DistributedLockException(String message) {
        super(message);
    }

    public DistributedLockException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

### LockAcquisitionException

锁获取异常。

```java
public class LockAcquisitionException extends DistributedLockException {

    private final String lockName;
    private final long waitTime;
    private final TimeUnit timeUnit;

    public LockAcquisitionException(String lockName, long waitTime, TimeUnit timeUnit) {
        super("Failed to acquire lock '" + lockName + "' within " + waitTime + " " + timeUnit);
        this.lockName = lockName;
        this.waitTime = waitTime;
        this.timeUnit = timeUnit;
    }
}
```

### LockReleaseException

锁释放异常。

```java
public class LockReleaseException extends DistributedLockException {

    private final String lockName;

    public LockReleaseException(String lockName, Throwable cause) {
        super("Failed to release lock '" + lockName + "'", cause);
        this.lockName = lockName;
    }
}
```

### LockRenewalException

锁续期异常。

```java
public class LockRenewalException extends DistributedLockException {

    private final String lockName;
    private final long renewalTime;

    public LockRenewalException(String lockName, long renewalTime, Throwable cause) {
        super("Failed to renew lock '" + lockName + "' for " + renewalTime + "ms", cause);
        this.lockName = lockName;
        this.renewalTime = renewalTime;
    }
}
```

## 注解

### @DistributedLock

Spring AOP 分布式锁注解。

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

    // 锁键表达式
    String key();

    // 租约时间
    long leaseTime() default 30;
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    // 等待时间
    long waitTime() default 10;
    TimeUnit waitTimeUnit() default TimeUnit.SECONDS;

    // 锁类型
    LockType lockType() default LockType.MUTEX;

    // 自动续期
    boolean autoRenewal() default false;
    double renewalRatio() default 0.7;

    // 异常处理
    Class<? extends Throwable>[] ignoreExceptions() default {};
}
```

### @DistributedReadLock

读锁注解。

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedReadLock {

    String key();
    long leaseTime() default 30;
    TimeUnit timeUnit() default TimeUnit.SECONDS;
    long waitTime() default 10;
    TimeUnit waitTimeUnit() default TimeUnit.SECONDS;
    boolean autoRenewal() default false;
    double renewalRatio() default 0.7;
}
```

## 枚举类型

### LockType

锁类型枚举。

```java
public enum LockType {
    MUTEX,      // 互斥锁
    REENTRANT,  // 可重入锁
    READ_WRITE, // 读写锁
    FAIR,       // 公平锁
    SEMAPHORE   // 信号量
}
```

### ReportFormat

报告格式枚举。

```java
public enum ReportFormat {
    JSON,
    XML,
    CSV,
    PLAIN_TEXT
}
```

## 使用示例

### 基础锁使用

```java
@Autowired
private DistributedLockFactory lockFactory;

public void processOrder(String orderId) {
    DistributedLock lock = lockFactory.getLock("order:" + orderId);
    try {
        lock.lock(30, TimeUnit.SECONDS);
        // 业务逻辑
        doProcessOrder(orderId);
    } finally {
        lock.unlock();
    }
}
```

### 异步锁使用

```java
public CompletableFuture<Void> processOrderAsync(String orderId) {
    return lockFactory.getLockAsync("order:" + orderId)
        .thenCompose(lock -> lock.lockAsync(30, TimeUnit.SECONDS)
            .thenRun(() -> doProcessOrder(orderId))
            .whenComplete((result, throwable) -> lock.unlockAsync()));
}
```

### 批量锁操作

```java
public void processBatchOrders(List<String> orderIds) {
    List<String> lockNames = orderIds.stream()
        .map(id -> "order:" + id)
        .collect(Collectors.toList());

    BatchLockOperations<DistributedLock> batchOps =
        lockFactory.createBatchLockOperations(lockNames, locks -> {
            // 批量业务逻辑
            for (String orderId : orderIds) {
                doProcessOrder(orderId);
            }
            return orderIds.size();
        });

    batchOps.execute();
}
```

### 配置锁使用

```java
LockConfiguration config = LockConfigurationBuilder.newBuilder()
    .leaseTime(60, TimeUnit.SECONDS)
    .waitTime(20, TimeUnit.SECONDS)
    .lockType(LockType.REENTRANT)
    .autoRenewal(true)
    .renewalRatio(0.8)
    .monitoring(true)
    .build();

DistributedLock lock = lockFactory.getConfiguredLock("order:" + orderId, config);
```

### 注解使用

```java
@Service
public class OrderService {

    @DistributedLock(key = "'order:' + #orderId", leaseTime = 60)
    public void processOrder(String orderId) {
        // 业务逻辑
    }

    @DistributedReadLock(key = "'order:read:' + #orderId")
    public Order getOrder(String orderId) {
        // 读操作
        return orderRepository.findById(orderId);
    }
}