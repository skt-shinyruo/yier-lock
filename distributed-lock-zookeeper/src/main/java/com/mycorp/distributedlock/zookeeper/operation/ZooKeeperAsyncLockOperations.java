package com.mycorp.distributedlock.zookeeper.operation;

import com.mycorp.distributedlock.api.AsyncLockOperations;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import com.mycorp.distributedlock.zookeeper.connection.ZooKeeperConnectionManager;
import com.mycorp.distributedlock.zookeeper.cluster.ZooKeeperClusterManager;
import com.mycorp.distributedlock.zookeeper.lock.OptimizedZooKeeperDistributedLock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.StampedLock;
import java.util.concurrent.locks.LockSupport;

/**
 * Zookeeper异步锁操作实现
 * 
 * 主要功能：
 * - 异步锁获取和释放
 * - 自动续期和超时处理
 * - 异步锁信息查询
 * - 事务性异步操作
 * - 重试机制和错误恢复
 * - 性能监控和统计
 * 
 * 异步特性：
 * - 基于CompletableFuture的非阻塞操作
 * - 线程池隔离和资源管理
 * - 异步事件通知
 * - 批量异步操作优化
 */
public class ZooKeeperAsyncLockOperations implements AsyncLockOperations<OptimizedZooKeeperDistributedLock> {
    
    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperAsyncLockOperations.class);
    
    // 异步操作配置
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(1);
    private static final double DEFAULT_RENEWAL_INTERVAL_RATIO = 0.3; // 租约时间的30%
    private static final int DEFAULT_MAX_CONCURRENCY = 100;
    private static final boolean DEFAULT_AUTO_RENEWAL_ENABLED = true;
    
    // 状态管理
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final Map<String, OptimizedZooKeeperDistributedLock> lockCache = new ConcurrentHashMap<>();
    
    // 异步操作管理
    private final ReentrantLock asyncOperationLock = new ReentrantLock();
    private final Map<String, AsyncOperationState> activeAsyncOperations = new ConcurrentHashMap<>();
    private final AtomicLong asyncOperationIdGenerator = new AtomicLong(0);
    
    // Zookeeper相关
    private final ZooKeeperConnectionManager connectionManager;
    private final ZooKeeperClusterManager clusterManager;
    private final LockConfiguration configuration;
    private final LockMetrics metrics;
    private final LockTracing tracing;
    
    // 线程池管理
    private final ExecutorService asyncLockExecutor;
    private final ExecutorService asyncRenewalExecutor;
    private final ScheduledExecutorService asyncRenewalScheduler;
    private final ExecutorService asyncRetryExecutor;
    
    // 续期任务管理
    private final Map<Long, AsyncRenewalTask> renewalTasks = new ConcurrentHashMap<>();
    private final AtomicLong renewalTaskIdGenerator = new AtomicLong(0);
    
    // 异步操作配置
    private AsyncOperationConfiguration asyncConfig;
    private final StampedLock config</tool_call>Lock = new StampedLock();
    
    // 性能指标
    private final Timer asyncLockAcquisitionTimer;
    private final Timer asyncLockReleaseTimer;
    private final Timer asyncLockRenewalTimer;
    private final Timer asyncRetryOperationTimer;
    private final Counter asyncOperationCounter;
    private final Counter asyncOperationFailedCounter;
    private final Counter asyncRenewalCounter;
    private final Counter asyncRetryCounter;
    private final Gauge activeRenewalTasksGauge;
    private final Gauge activeAsyncOperationsGauge;
    private final AtomicLong totalAsyncOperations = new AtomicLong(0);
    private final AtomicLong successfulAsyncOperations = new AtomicLong(0);
    private final AtomicLong failedAsyncOperations = new AtomicLong(0);
    
    // 事件监听
    private final java.util.List<AsyncLockEventListener> eventListeners = new CopyOnWriteArrayList<>();
    
    /**
     * 构造函数
     * 
     * @param connectionManager 连接管理器
     * @param clusterManager 集群管理器
     * @param configuration 配置
     * @param metrics 指标收集
     * @param tracing 分布式追踪
     */
    public ZooKeeperAsyncLockOperations(ZooKeeperConnectionManager connectionManager,
                                       ZooKeeperClusterManager clusterManager,
                                       LockConfiguration configuration,
                                       LockMetrics metrics,
                                       LockTracing tracing) {
        this.connectionManager = connectionManager;
        this.clusterManager = clusterManager;
        this.configuration = configuration;
        this.metrics = metrics;
        this.tracing = tracing;
        
        // 初始化异步配置
        this.asyncConfig = createDefaultConfiguration();
        
        // 初始化线程池
        this.asyncLockExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(), r -> {
                Thread t = new Thread(r, "zookeeper-async-lock-ops");
                t.setDaemon(true);
                return t;
            }
        );
        
        this.asyncRenewalExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "zookeeper-async-renewal");
            t.setDaemon(true);
            return t;
        });
        
        this.asyncRenewalScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zookeeper-async-renewal-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        this.asyncRetryExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() / 2, r -> {
                Thread t = new Thread(r, "zookeeper-async-retry");
                t.setDaemon(true);
                return t;
            }
        );
        
        // 初始化性能指标
        this.asyncLockAcquisitionTimer = metrics.createTimer("zookeeper.async.lock.acquisition.time");
        this.asyncLockReleaseTimer = metrics.createTimer("zookeeper.async.lock.release.time");
        this.asyncLockRenewalTimer = metrics.createTimer("zookeeper.async.lock.renewal.time");
        this.asyncRetryOperationTimer = metrics.createTimer("zookeeper.async.retry.operation.time");
        this.asyncOperationCounter = metrics.createCounter("zookeeper.async.lock.operation.count");
        this.asyncOperationFailedCounter = metrics.createCounter("zookeeper.async.lock.operation.failed.count");
        this.asyncRenewalCounter = metrics.createCounter("zookeeper.async.lock.renewal.count");
        this.asyncRetryCounter = metrics.createCounter("zookeeper.async.lock.retry.count");
        this.activeRenewalTasksGauge = metrics.createGauge("zookeeper.async.lock.renewal.tasks.count", 
                                                          renewalTasks::size);
        this.activeAsyncOperationsGauge = metrics.createGauge("zookeeper.async.lock.active.operations.count",
                                                            activeAsyncOperations::size);
        
        logger.debug("ZooKeeperAsyncLockOperations initialized");
    }
    
    @Override
    public CompletableFuture<AsyncLockResult<OptimizedZooKeeperDistributedLock>> acquireLockAsync(String lockName,
                                                                                                long leaseTime, TimeUnit timeUnit) {
        checkNotClosed();
        
        long operationId = asyncOperationIdGenerator.incrementAndGet();
        
        return CompletableFuture.supplyAsync(() -> {
            try (var spanContext = tracing.startLockAcquisitionSpan(lockName, "acquireLockAsync")) {
                Timer.Sample sample = metrics.startTimer(asyncLockAcquisitionTimer);
                
                // 记录异步操作状态
                AsyncOperationState operationState = new AsyncOperationState(operationId, lockName, "ACQUIRE");
                activeAsyncOperations.put(String.valueOf(operationId), operationState);
                
                try {
                    OptimizedZooKeeperDistributedLock lock = getLock(lockName);
                    
                    // 记录开始时间
                    long startTime = System.currentTimeMillis();
                    
                    // 执行锁获取
                    boolean acquired = lock.tryLock(asyncConfig.getDefaultTimeoutMs(), leaseTime, timeUnit);
                    
                    long operationTime = System.currentTimeMillis() - startTime;
                    
                    AsyncLockResult<OptimizedZooKeeperDistributedLock> result;
                    
                    if (acquired) {
                        // 自动续期（如果启用）
                        if (asyncConfig.isAutoRenewalEnabled() && shouldEnableAutoRenewal(leaseTime, timeUnit)) {
                            try {
                                lock.enableAutoRenewal(leaseTime, timeUnit);
                            } catch (Exception e) {
                                logger.warn("Failed to enable auto-renewal for lock: {}", lockName, e);
                            }
                        }
                        
                        result = new ImmutableAsyncLockResult<>(
                            true, lock, null, operationTime, 
                            Instant.now().toEpochMilli(), 
                            Instant.now().toEpochMilli() + timeUnit.toMillis(leaseTime)
                        );
                        
                        successfulAsyncOperations.incrementAndGet();
                        notifyEvent(AsyncLockEventType.LOCK_ACQUIRED, lockName, null, "Lock acquired successfully");
                        
                    } else {
                        result = new ImmutableAsyncLockResult<>(
                            false, null, new TimeoutException("Lock acquisition timeout"), 
                            operationTime, 0, 0
                        );
                        
                        failedAsyncOperations.incrementAndGet();
                        notifyEvent(AsyncLockEventType.LOCK_ACQUISITION_FAILED, lockName, 
                                   new TimeoutException("Lock acquisition timeout"), "Lock acquisition failed");
                    }
                    
                    totalAsyncOperations.incrementAndGet();
                    asyncOperationCounter.increment();
                    
                    return result;
                    
                } catch (Exception e) {
                    failedAsyncOperations.incrementAndGet();
                    asyncOperationFailedCounter.increment();
                    
                    spanContext.setError(e);
                    notifyEvent(AsyncLockEventType.LOCK_ACQUISITION_FAILED, lockName, e, 
                               "Lock acquisition failed: " + e.getMessage());
                    
                    return new ImmutableAsyncLockResult<>(
                        false, null, e, 0, 0, 0
                    );
                } finally {
                    sample.stop(asyncLockAcquisitionTimer);
                    activeAsyncOperations.remove(String.valueOf(operationId));
                }
            }
        }, asyncLockExecutor);
    }
    
    @Override
    public CompletableFuture<AsyncLockResult<OptimizedZooKeeperDistributedLock>> tryAcquireLockAsync(String lockName,
                                                                                                  long waitTime, long leaseTime,
                                                                                                  TimeUnit timeUnit) {
        checkNotClosed();
        
        long operationId = asyncOperationIdGenerator.incrementAndGet();
        
        return CompletableFuture.supplyAsync(() -> {
            try (var spanContext = tracing.startLockAcquisitionSpan(lockName, "tryAcquireLockAsync")) {
                Timer.Sample sample = metrics.startTimer(asyncLockAcquisitionTimer);
                
                AsyncOperationState operationState = new AsyncOperationState(operationId, lockName, "TRY_ACQUIRE");
                activeAsyncOperations.put(String.valueOf(operationId), operationState);
                
                try {
                    OptimizedZooKeeperDistributedLock lock = getLock(lockName);
                    
                    long startTime = System.currentTimeMillis();
                    boolean acquired = lock.tryLock(waitTime, leaseTime, timeUnit);
                    long operationTime = System.currentTimeMillis() - startTime;
                    
                    AsyncLockResult<OptimizedZooKeeperDistributedLock> result;
                    
                    if (acquired) {
                        result = new ImmutableAsyncLockResult<>(
                            true, lock, null, operationTime,
                            Instant.now().toEpochMilli(),
                            Instant.now().toEpochMilli() + timeUnit.toMillis(leaseTime)
                        );
                        
                        successfulAsyncOperations.incrementAndGet();
                        notifyEvent(AsyncLockEventType.LOCK_ACQUIRED, lockName, null, "Lock acquired successfully");
                        
                    } else {
                        result = new ImmutableAsyncLockResult<>(
                            false, null, new TimeoutException("Lock acquisition timeout"),
                            operationTime, 0, 0
                        );
                        
                        failedAsyncOperations.incrementAndGet();
                        notifyEvent(AsyncLockEventType.LOCK_ACQUISITION_FAILED, lockName,
                                   new TimeoutException("Lock acquisition timeout"), "Lock acquisition timeout");
                    }
                    
                    totalAsyncOperations.incrementAndGet();
                    asyncOperationCounter.increment();
                    
                    return result;
                    
                } catch (Exception e) {
                    failedAsyncOperations.incrementAndGet();
                    asyncOperationFailedCounter.increment();
                    
                    spanContext.setError(e);
                    notifyEvent(AsyncLockEventType.LOCK_ACQUISITION_FAILED, lockName, e,
                               "Lock acquisition failed: " + e.getMessage());
                    
                    return new ImmutableAsyncLockResult<>(
                        false, null, e, 0, 0, 0
                    );
                } finally {
                    sample.stop(asyncLockAcquisitionTimer);
                    activeAsyncOperations.remove(String.valueOf(operationId));
                }
            }
        }, asyncLockExecutor);
    }
    
    @Override
    public CompletableFuture<AsyncLockReleaseResult> releaseLockAsync(OptimizedZooKeeperDistributedLock lock) {
        checkNotClosed();
        
        long operationId = asyncOperationIdGenerator.incrementAndGet();
        
        return CompletableFuture.supplyAsync(() -> {
            try (var spanContext = tracing.startLockAcquisitionSpan(lock.getName(), "releaseLockAsync")) {
                Timer.Sample sample = metrics.startTimer(asyncLockReleaseTimer);
                
                AsyncOperationState operationState = new AsyncOperationState(operationId, lock.getName(), "RELEASE");
                activeAsyncOperations.put(String.valueOf(operationId), operationState);
                
                try {
                    long startTime = System.currentTimeMillis();
                    
                    // 取消自动续期
                    lock.disableAutoRenewal();
                    
                    // 释放锁
                    lock.unlock();
                    
                    long operationTime = System.currentTimeMillis() - startTime;
                    
                    AsyncLockReleaseResult result = new ImmutableAsyncLockReleaseResult(
                        true, null, operationTime
                    );
                    
                    successfulAsyncOperations.incrementAndGet();
                    notifyEvent(AsyncLockEventType.LOCK_RELEASED, lock.getName(), null, "Lock released successfully");
                    
                    return result;
                    
                } catch (Exception e) {
                    failedAsyncOperations.incrementAndGet();
                    asyncOperationFailedCounter.increment();
                    
                    spanContext.setError(e);
                    notifyEvent(AsyncLockEventType.LOCK_RELEASE_FAILED, lock.getName(), e,
                               "Lock release failed: " + e.getMessage());
                    
                    return new ImmutableAsyncLockReleaseResult(
                        false, e, 0
                    );
                } finally {
                    sample.stop(asyncLockReleaseTimer);
                    activeAsyncOperations.remove(String.valueOf(operationId));
                }
            }
        }, asyncLockExecutor);
    }
    
    @Override
    public CompletableFuture<AsyncLockRenewalResult> renewLockAsync(OptimizedZooKeeperDistributedLock lock,
                                                                   long newLeaseTime, TimeUnit timeUnit) {
        checkNotClosed();
        
        long operationId = asyncOperationIdGenerator.incrementAndGet();
        
        return CompletableFuture.supplyAsync(() -> {
            try (var spanContext = tracing.startLockAcquisitionSpan(lock.getName(), "renewLockAsync")) {
                Timer.Sample sample = metrics.startTimer(asyncLockRenewalTimer);
                
                AsyncOperationState operationState = new AsyncOperationState(operationId, lock.getName(), "RENEW");
                activeAsyncOperations.put(String.valueOf(operationId), operationState);
                
                try {
                    long startTime = System.currentTimeMillis();
                    
                    boolean renewed = lock.renewLock(newLeaseTime, timeUnit);
                    long operationTime = System.currentTimeMillis() - startTime;
                    
                    AsyncLockRenewalResult result;
                    
                    if (renewed) {
                        long newExpirationTime = Instant.now().toEpochMilli() + timeUnit.toMillis(newLeaseTime);
                        
                        result = new ImmutableAsyncLockRenewalResult(
                            true, newExpirationTime, null, operationTime
                        );
                        
                        asyncRenewalCounter.increment();
                        successfulAsyncOperations.incrementAndGet();
                        notifyEvent(AsyncLockEventType.LOCK_RENEWED, lock.getName(), null, "Lock renewed successfully");
                        
                    } else {
                        result = new ImmutableAsyncLockRenewalResult(
                            false, 0, new RuntimeException("Failed to renew lock"), operationTime
                        );
                        
                        failedAsyncOperations.incrementAndGet();
                        notifyEvent(AsyncLockEventType.LOCK_RENEWAL_FAILED, lock.getName(),
                                   new RuntimeException("Failed to renew lock"), "Lock renewal failed");
                    }
                    
                    return result;
                    
                } catch (Exception e) {
                    failedAsyncOperations.incrementAndGet();
                    asyncOperationFailedCounter.increment();
                    
                    spanContext.setError(e);
                    notifyEvent(AsyncLockEventType.LOCK_RENEWAL_FAILED, lock.getName(), e,
                               "Lock renewal failed: " + e.getMessage());
                    
                    return new ImmutableAsyncLockRenewalResult(
                        false, 0, e, 0
                    );
                } finally {
                    sample.stop(asyncLockRenewalTimer);
                    activeAsyncOperations.remove(String.valueOf(operationId));
                }
            }
        }, asyncRenewalExecutor);
    }
    
    @Override
    public CompletableFuture<AsyncLockInfoResult> getLockInfoAsync(String lockName) {
        checkNotClosed();
        
        long operationId = asyncOperationIdGenerator.incrementAndGet();
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                OptimizedZooKeeperDistributedLock lock = getLock(lockName);
                OptimizedZooKeeperDistributedLock.LockInformation lockInfo = lock.getLockInformation();
                
                AsyncLockInfoResult result = new ImmutableAsyncLockInfoResult(
                    true, convertToLockInfo(lockInfo), null
                );
                
                return result;
                
            } catch (Exception e) {
                return new ImmutableAsyncLockInfoResult(
                    false, null, e
                );
            } finally {
                activeAsyncOperations.remove(String.valueOf(operationId));
            }
        }, asyncLockExecutor);
    }
    
    @Override
    public CompletableFuture<AsyncLockStateResult> checkLockStateAsync(String lockName) {
        checkNotClosed();
        
        long operationId = asyncOperationIdGenerator.incrementAndGet();
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                OptimizedZooKeeperDistributedLock lock = getLock(lockName);
                
                // 简化的状态检查
                AsyncLockOperations.LockState state = lock.isLocked() ?
                    AsyncLockOperations.LockState.OCCUPIED :
                    AsyncLockOperations.LockState.AVAILABLE;
                
                AsyncLockStateResult result = new ImmutableAsyncLockStateResult(
                    true, state, null
                );
                
                return result;
                
            } catch (Exception e) {
                return new ImmutableAsyncLockStateResult(
                    false, AsyncLockOperations.LockState.UNKNOWN, e
                );
            } finally {
                activeAsyncOperations.remove(String.valueOf(operationId));
            }
        }, asyncLockExecutor);
    }
    
    @Override
    public CompletableFuture<AsyncLockReleaseResult> releaseLockByNameAsync(String lockName) {
        checkNotClosed();
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                OptimizedZooKeeperDistributedLock lock = getLock(lockName);
                return releaseLockAsync(lock).join();
            } catch (Exception e) {
                return new ImmutableAsyncLockReleaseResult(false, e, 0);
            }
        }, asyncLockExecutor);
    }
    
    @Override
    public CompletableFuture<AsyncWaitResult> waitForLockReleaseAsync(String lockName, long timeout, TimeUnit timeUnit) {
        checkNotClosed();
        
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            long timeoutMillis = timeUnit.toMillis(timeout);
            
            try {
                OptimizedZooKeeperDistributedLock lock = getLock(lockName);
                
                // 简单的等待实现
                long waitTime = 0;
                while (lock.isLocked() && waitTime < timeoutMillis) {
                    Thread.sleep(100); // 等待100ms
                    waitTime = System.currentTimeMillis() - startTime;
                }
                
                boolean success = !lock.isLocked();
                boolean timeout_ = waitTime >= timeoutMillis;
                
                AsyncWaitResult result = new ImmutableAsyncWaitResult(success, timeout_, waitTime);
                
                return result;
                
            } catch (Exception e) {
                return new ImmutableAsyncWaitResult(false, true, timeoutMillis);
            }
        }, asyncLockExecutor);
    }
    
    @Override
    public <R> CompletableFuture<AsyncBusinessOperationResult<R>> executeWithLockAsync(String lockName,
                                                                                      long leaseTime, TimeUnit timeUnit,
                                                                                      AsyncBusinessOperation<R, OptimizedZooKeeperDistributedLock> businessOperation) {
        checkNotClosed();
        
        return acquireLockAsync(lockName, leaseTime, timeUnit)
            .thenComposeAsync(lockResult -> {
                if (lockResult.isSuccess()) {
                    OptimizedZooKeeperDistributedLock lock = lockResult.getLock();
                    
                    return CompletableFuture.supplyAsync(() -> {
                        try {
                            R result = businessOperation.execute(lock).join();
                            
                            AsyncBusinessOperationResult<R> successResult = new ImmutableAsyncBusinessOperationResult<>(
                                true, result, null, System.currentTimeMillis() - lockResult.getOperationTimeMs()
                            );
                            
                            return successResult;
                            
                        } catch (Exception e) {
                            AsyncBusinessOperationResult<R> errorResult = new ImmutableAsyncBusinessOperationResult<>(
                                false, null, e, 0
                            );
                            return errorResult;
                        }
                    }, asyncLockExecutor).thenComposeAsync(businessResult -> {
                        // 无论业务操作成功与否，都要释放锁
                        return releaseLockAsync(lock).thenApply(releaseResult -> {
                            if (!releaseResult.isSuccess()) {
                                logger.warn("Failed to release lock after business operation: {}", lockName);
                            }
                            return businessResult;
                        });
                    });
                } else {
                    // 获取锁失败
                    CompletableFuture<AsyncBusinessOperationResult<R>> failedFuture = new CompletableFuture<>();
                    failedFuture.complete(new ImmutableAsyncBusinessOperationResult<>(
                        false, null, lockResult.getFailureCause(), 0
                    ));
                    return failedFuture;
                }
            }, asyncLockExecutor);
    }
    
    @Override
    public <R> CompletableFuture<AsyncRetryOperationResult<R>> executeWithRetryAsync(String lockName,
                                                                                    long leaseTime, TimeUnit timeUnit,
                                                                                    int maxRetries, long retryDelay,
                                                                                    TimeUnit retryDelayTimeUnit,
                                                                                    AsyncRetryableLockOperation<R, OptimizedZooKeeperDistributedLock> operation) {
        checkNotClosed();
        
        long operationId = asyncOperationIdGenerator.incrementAndGet();
        
        return CompletableFuture.supplyAsync(() -> {
            Timer.Sample sample = metrics.startTimer(asyncRetryOperationTimer);
            
            AsyncOperationState operationState = new AsyncOperationState(operationId, lockName, "RETRY_OPERATION");
            activeAsyncOperations.put(String.valueOf(operationId), operationState);
            
            try {
                int attemptNumber = 0;
                R result = null;
                Throwable lastError = null;
                long totalExecutionTime = 0;
                
                while (attemptNumber <= maxRetries) {
                    attemptNumber++;
                    
                    long attemptStartTime = System.currentTimeMillis();
                    
                    try {
                        // 获取锁
                        AsyncLockResult<OptimizedZooKeeperDistributedLock> lockResult = 
                            acquireLockAsync(lockName, leaseTime, timeUnit).join();
                        
                        if (!lockResult.isSuccess()) {
                            lastError = lockResult.getFailureCause();
                            if (attemptNumber > maxRetries) {
                                break; // 最后一次尝试失败
                            }
                            
                            // 等待重试延迟
                            waitRetryDelay(retryDelay, retryDelayTimeUnit);
                            continue;
                        }
                        
                        OptimizedZooKeeperDistributedLock lock = lockResult.getLock();
                        
                        // 执行业务操作
                        try {
                            result = operation.execute(lock, attemptNumber).join();
                            
                            // 成功，释放锁并返回结果
                            releaseLockAsync(lock).join();
                            
                            asyncRetryCounter.increment();
                            totalExecutionTime = System.currentTimeMillis() - attemptStartTime;
                            
                            successfulAsyncOperations.incrementAndGet();
                            notifyEvent(AsyncLockEventType.RETRY_OPERATION_SUCCESS, lockName, null,
                                       "Retry operation succeeded on attempt " + attemptNumber);
                            
                            return new ImmutableAsyncRetryOperationResult<>(
                                true, result, attemptNumber - 1, null, totalExecutionTime
                            );
                            
                        } catch (Exception e) {
                            // 业务操作失败，释放锁
                            try {
                                releaseLockAsync(lock).join();
                            } catch (Exception releaseException) {
                                logger.warn("Failed to release lock after business operation</tool_call>", releaseException);
                            }
                            
                            lastError = e;
                            totalExecutionTime += System.currentTimeMillis() - attemptStartTime;
                            
                            if (attemptNumber > maxRetries) {
                                break; // 最后一次尝试失败
                            }
                            
                            // 等待重试延迟
                            waitRetryDelay(retryDelay, retryDelayTimeUnit);
                        }
                        
                    } catch (Exception e) {
                        lastError = e;
                        totalExecutionTime += System.currentTimeMillis() - attemptStartTime;
                        
                        if (attemptNumber > maxRetries) {
                            break; // 最后一次尝试失败
                        }
                        
                        // 等待重试延迟
                        waitRetryDelay(retryDelay, retryDelayTimeUnit);
                    }
                }
                
                // 所有重试都失败
                failedAsyncOperations.incrementAndGet();
                asyncOperationFailedCounter.increment();
                
                notifyEvent(AsyncLockEventType.RETRY_OPERATION_FAILED, lockName, lastError,
                           "Retry operation failed after " + (attemptNumber - 1) + " attempts");
                
                return new ImmutableAsyncRetryOperationResult<>(
                    false, null, attemptNumber - 1, lastError, totalExecutionTime
                );
                
            } finally {
                sample.stop(asyncRetryOperationTimer);
                activeAsyncOperations.remove(String.valueOf(operationId));
            }
        }, asyncRetryExecutor);
    }
    
    @Override
    public LockRenewalTask autoRenewLock(OptimizedZooKeeperDistributedLock lock, long renewInterval, TimeUnit timeUnit) {
        checkNotClosed();
        
        long taskId = renewalTaskIdGenerator.incrementAndGet();
        
        // 创建续期任务
        AsyncRenewalTask renewalTask = new AsyncRenewalTask(
            taskId, lock, renewInterval, timeUnit
        );
        
        renewalTasks.put(taskId, renewalTask);
        
        // 启动续期调度
        ScheduledFuture<?> renewalSchedule = asyncRenewalScheduler.scheduleAtFixedRate(() -> {
            try {
                if (!renewalTask.isRunning() || Thread.currentThread().isInterrupted()) {
                    renewalTask.cancel();
                    return;
                }
                
                if (!lock.isHeldByCurrentThread()) {
                    renewalTask.cancel();
                    return;
                }
                
                // 检查是否需要续期
                if (lock</tool_call>IsExpiring(timeUnit.toMillis(renewInterval))) {
                    long leaseTime = lock.getRemainingTime() + timeUnit.toMillis(renewInterval);
                    
                    renewLockAsync(lock, leaseTime, timeUnit).thenAccept(renewalResult -> {
                        if (renewalResult.isSuccess()) {
                            renewalTask.incrementRenewalCount();
                            renewalTask.updateLastRenewalTime(Instant.now().toEpochMilli());
                            
                            logger.debug("Auto-renewal successful for lock: {}", lock.getName());
                            notifyEvent(AsyncLockEventType.LOCK_AUTO_RENEWED, lock.getName(), null,
                                       "Auto-renewal completed successfully");
                        } else {
                            logger.warn("Auto-renewal failed for lock: {}", lock.getName(), 
                                       renewalResult.getFailureCause());
                            notifyEvent(AsyncLockEventType.LOCK_AUTO_RENEWAL_FAILED, lock.getName(),
                                       renewalResult.getFailureCause(), "Auto-renewal failed");
                            
                            // 如果续期失败，取消任务
                            if (renewalTask.getRenewalCount() > 3) {
                                renewalTask.cancel();
                            }
                        }
                   </tool_call>..exceptionally(throwable -> {
                        logger.warn("Auto-renewal async operation failed for lock: {}", lock.getName(), throwable);
                        return null;
                    });
                }
                
            } catch (Exception e) {
                logger.debug("Auto-renewal error for lock: {}", lock.getName(), e);
            }
        }, timeUnit.toMillis(renewInterval), timeUnit.toMillis(renewInterval), TimeUnit.MILLISECONDS);
        
        renewalTask.setScheduledFuture(renewalSchedule);
        
        logger.debug("Started auto-renewal task {} for lock: {}", taskId, lock.getName());
        notifyEvent(AsyncLockEventType.LOCK_AUTO_RENEWAL_STARTED, lock.getName(), null,
                   "Auto-renewal task started");
        
        return renewalTask;
    }
    
    @Override
    public void cancelAutoRenew(LockRenewalTask renewalTask) {
        if (renewalTask instanceof AsyncRenewalTask) {
            AsyncRenewalTask asyncRenewalTask = (AsyncRenewalTask) renewalTask;
            asyncRenewalTask.cancel();
            renewalTasks.remove(asyncRenewalTask.getTaskId());
            
            logger.debug("Cancelled auto-renewal task: {}", asyncRenewalTask.getTaskId());
            notifyEvent(AsyncLockEventType.LOCK_AUTO_RENEWAL_CANCELLED, 
                       asyncRenewalTask.getLock().getName(), null, "Auto-renewal task cancelled");
        }
    }
    
    @Override
    public AsyncOperationConfiguration getConfiguration() {
        long stamp = config.readLock();
        try {
            return new ImmutableAsyncOperationConfiguration(asyncConfig);
        } finally {
            config.unlockRead(stamp);
        }
    }
    
    @Override
    public void updateConfiguration(AsyncOperationConfiguration configuration) {
        long stamp = config.writeLock();
        try {
            this.asyncConfig = new ImmutableAsyncOperationConfiguration(configuration);
            logger.debug("Updated async operation configuration");
        } finally {
            config.unlockWrite(stamp);
        }
    }
    
    /**
     * 获取异步操作统计信息
     * 
     * @return 统计信息
     */
    public AsyncLockStatistics getStatistics() {
        return new AsyncLockStatistics(
            totalAsyncOperations.get(),
            successfulAsyncOperations.get(),
            failedAsyncOperations.get(),
            activeAsyncOperations.size(),
            renewalTasks.size(),
            asyncRenewalCounter.count(),
            asyncRetryCounter.count()
        );
    }
    
    /**
     * 重置统计信息
     */
    public void resetStatistics() {
        totalAsyncOperations.set(0);
        successfulAsyncOperations.set(0);
        failedAsyncOperations.set(0);
        asyncRenewalCounter.reset();
        asyncRetryCounter.reset();
        
        logger.debug("Async lock operation statistics reset");
    }
    
    /**
     * 添加异步锁事件监听器
     * 
     * @param listener 监听器
     */
    public void addAsyncLockEventListener(AsyncLockEventListener listener) {
        if (listener != null) {
            eventListeners.add(listener);
        }
    }
    
    /**
     * 移除异步锁事件监听器
     * 
     * @param listener 监听器
     */
    public void removeAsyncLockEventListener(AsyncLockEventListener listener) {
        eventListeners.remove(listener);
    }
    
    /**
     * 强制清理所有活跃的异步操作
     * 
     * @return 被清理的操作数量
     */
    public int forceCleanupActiveOperations() {
        int cleanedCount = activeAsyncOperations.size();
        activeAsyncOperations.clear();
        
        logger.warn("Force cleaned {} active async operations", cleanedCount);
        return cleanedCount;
    }
    
    @Override
    public void close() {
        if (isClosed.getAndSet(true)) {
            return;
        }
        
        logger.debug("Closing ZooKeeperAsyncLockOperations");
        
        try {
            // 取消所有续期任务
            for (AsyncRenewalTask task : renewalTasks.values()) {
                task.cancel();
            }
            renewalTasks.clear();
            
            // 清理活跃的异步操作
            forceCleanupActiveOperations();
            
            // 关闭所有缓存的锁
            lockCache.values().forEach(lock -> {
                try {
                    lock.disableAutoRenewal();
                    lock.close();
                } catch (Exception e) {
                    logger.warn("Error closing cached lock", e);
                }
            });
            lockCache.clear();
            
            // 关闭线程池
            shutdownExecutor(asyncLockExecutor, "async-lock");
            shutdownExecutor(asyncRenewalExecutor, "async-renewal");
            shutdownExecutor(asyncRenewalScheduler, "async-renewal-scheduler");
            shutdownExecutor(asyncRetryExecutor, "async-retry");
            
            // 清理事件监听器
            eventListeners.clear();
            
            logger.debug("ZooKeeperAsyncLockOperations closed");
        } catch (Exception e) {
            logger.error("Error closing async lock operations", e);
        }
    }
    
    // 私有方法区域
    
    private OptimizedZooKeeperDistributedLock getLock(String name) {
        return lockCache.computeIfAbsent(name, lockName -> {
            String lockPath = "/distributed-locks/" + lockName;
            return new OptimizedZooKeeperDistributedLock(
                lockName, lockPath, connectionManager, clusterManager,
                configuration, metrics, tracing
            );
        });
    }
    
    private AsyncOperationConfiguration createDefaultConfiguration() {
        return new ImmutableAsyncOperationConfiguration(
            DEFAULT_TIMEOUT.toMillis(),
            3, // 默认重试次数
            DEFAULT_RETRY_DELAY.toMillis(),
            DEFAULT_RENEWAL_INTERVAL_RATIO,
            DEFAULT_MAX_CONCURRENCY,
            DEFAULT_AUTO_RENEWAL_ENABLED
        );
    }
    
    private boolean shouldEnableAutoRenewal(long leaseTime, TimeUnit timeUnit) {
        long leaseMillis = timeUnit.toMillis(leaseTime);
        return leaseMillis > 30000 && asyncConfig.isAutoRenewalEnabled(); // 租约时间超过30秒才启用
    }
    
    private void waitRetryDelay(long retryDelay, TimeUnit timeUnit) {
        try {
            timeUnit.sleep(retryDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException("Retry delay interrupted", e);
        }
    }
    
    private AsyncLockOperations.LockInfo convertToLockInfo(OptimizedZooKeeperDistributedLock.LockInformation lockInfo) {
        return new AsyncLockOperations.LockInfo() {
            @Override
            public String getLockName() { return lockInfo.getLockName(); }
            
            @Override
            public String getHolder() { return String.valueOf(lockInfo.getOwnerThreadId()); }
            
            @Override
            public long getCreationTime() { return lockInfo.getAcquisitionTime(); }
            
            @Override
            public long getExpirationTime() { return lockInfo.getExpirationTime(); }
            
            @Override
            public long getRemainingTime() { return lockInfo.getRemainingTime(); }
            
            @Override
            public boolean isExpired() { return lockInfo.getRemainingTime() <= 0; }
        };
    }
    
    private void notifyEvent(AsyncLockEventType eventType, String lockName, Throwable error, String message) {
        AsyncLockEvent event = new AsyncLockEvent(
            eventType, lockName, Instant.now(), error, message
        );
        
        for (AsyncLockEventListener listener : eventListeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                logger.warn("Error in async lock event listener", e);
            }
        }
    }
    
    private void checkNotClosed() {
        if (isClosed.get()) {
            throw new IllegalStateException("ZooKeeperAsyncLockOperations is already closed");
        }
    }
    
    private void shutdownExecutor(ExecutorService executor, String name) {
        try {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.debug("Shut down {} executor", name);
    }
    
    // 内部类定义
    
    private static class AsyncOperationState {
        private final long operationId;
        private final String lockName;
        private final String operationType;
        private final Instant startTime;
        
        public AsyncOperationState(long operationId, String lockName, String operationType) {
            this.operationId = operationId;
            this.lockName = lockName;
            this.operationType = operationType;
            this.startTime = Instant.now();
        }
        
        public long getOperationId() { return operationId; }
        public String getLockName() { return lockName; }
        public String getOperationType() { return operationType; }
        public Instant getStartTime() { return startTime; }
    }
    
    private static class AsyncRenewalTask implements LockRenewalTask {
        private final long taskId;
        private final OptimizedZooKeeperDistributedLock lock;
        private final long renewInterval;
        private final TimeUnit timeUnit;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicLong renewalCount = new AtomicLong(0);
        private volatile long lastRenewalTime = 0;
        private volatile ScheduledFuture<?> scheduledFuture;
        
        public AsyncRenewalTask(long taskId, OptimizedZooKeeperDistributedLock lock,
                              long renewInterval, TimeUnit timeUnit) {
            this.taskId = taskId;
            this.lock = lock;
            this.renewInterval = renewInterval;
            this.timeUnit = timeUnit;
            this.running.set(true);
        }
        
        @Override
        public boolean isRunning() { return running.get() && !Thread.currentThread().isInterrupted(); }
        
        @Override
        public long getRenewalCount() { return renewalCount.get(); }
        
        @Override
        public long getLastRenewalTime() { return lastRenewalTime; }
        
        @Override
        public void cancel() { 
            running.set(false);
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
            }
        }
        
        @Override
        public OptimizedZooKeeperDistributedLock getLock() { return lock; }
        
        public long getTaskId() { return taskId; }
        public void incrementRenewalCount() { renewalCount.incrementAndGet(); }
        public void updateLastRenewalTime(long time) { lastRenewalTime = time; }
        public void setScheduledFuture(ScheduledFuture<?> scheduledFuture) { this.scheduledFuture = scheduledFuture; }
    }
    
    private static class ImmutableAsyncLockResult<T</tool_call> OptimizedZooKeeperDistributedLock> implements AsyncLockResult<T> {
        private final boolean success;
        private final T lock;
        private final Throwable failureCause;
        private final long operationTimeMs;
        private final long acquisitionTime;
        private final long expirationTime;
        
        public ImmutableAsyncLockResult(boolean success, T lock, Throwable failureCause,
                                      long operationTimeMs, long acquisitionTime, long expirationTime) {
            this.success = success;
            this.lock = lock;
            this.failureCause = failureCause;
            this.operationTimeMs = operationTimeMs;
            this.acquisitionTime = acquisitionTime;
            this.expirationTime = expirationTime;
        }
        
        @Override
        public boolean isSuccess() { return success; }
        @Override
        public T getLock() { return lock; }
        @Override
        public Throwable getFailureCause() { return failureCause; }
        @Override
        public long getOperationTimeMs() { return operationTimeMs; }
        @Override
        public long getAcquisitionTime() { return acquisitionTime; }
        @Override
        public long getExpirationTime() { return expirationTime; }
    }
    
    private static class ImmutableAsyncLockReleaseResult implements AsyncLockReleaseResult {
        private final boolean success;
        private final Throwable failureCause;
        private final long operationTimeMs;
        
        public ImmutableAsyncLockReleaseResult(boolean success, Throwable failureCause, long operationTimeMs) {
            this.success = success;
            this.failureCause = failureCause;
            this.operationTimeMs = operationTimeMs;
        }
        
        @Override
        public boolean isSuccess() { return success; }
        @Override
        public Throwable getFailureCause() { return failureCause; }
        @Override
        public long getOperationTimeMs() { return operationTimeMs; }
    }
    
    private static class ImmutableAsyncLockRenewalResult implements AsyncLockRenewalResult {
        private final boolean success;
        private final long newExpirationTime;
        private final Throwable failureCause;
        private final long operationTimeMs;
        
        public ImmutableAsyncLockRenewalResult(boolean success, long newExpirationTime,
                                             Throwable failureCause, long operationTimeMs) {
            this.success = success;
            this.newExpirationTime = newExpirationTime;
            this.failureCause = failureCause;
            this.operationTimeMs = operationTimeMs;
        }
        
        @Override
        public boolean isSuccess() { return success; }
        @Override
        public long getNewExpirationTime() { return newExpirationTime; }
        @Override
        public Throwable getFailureCause() { return failureCause; }
        @Override
        public long getOperationTimeMs() { return operationTimeMs; }
    }
    
    private static class ImmutableAsyncLockInfoResult implements AsyncLockInfoResult {
        private final boolean success;
        private final AsyncLockOperations.LockInfo lockInfo;
        private final Throwable failureCause;
        
        public ImmutableAsyncLockInfoResult(boolean success, AsyncLockOperations.LockInfo lockInfo,
                                          Throwable failureCause) {
            this.success = success;
            this.lockInfo = lockInfo;
            this.failureCause = failureCause;
        }
        
        @Override
        public boolean isSuccess() { return success; }
        @Override
        public AsyncLockOperations.LockInfo getLockInfo() { return lockInfo; }
        @Override
        public Throwable getFailureCause() { return failureCause; }
    }
    
    private static class ImmutableAsyncLockStateResult implements AsyncLockStateResult {
        private final boolean success;
        private final AsyncLockOperations.LockState lockState;
        private final Throwable failureCause;
        
        public ImmutableAsyncLockStateResult(boolean success, AsyncLockOperations.LockState lockState,
                                           Throwable failureCause) {
            this.success = success;
            this.lockState = lockState;
            this.failureCause = failureCause;
        }
        
        @Override
        public boolean isSuccess() { return success; }
        @Override
        public AsyncLockOperations.LockState getLockState() { return lockState; }
        @Override
        public Throwable getFailureCause() { return failureCause; }
    }
    
    private static class ImmutableAsyncWaitResult implements AsyncWaitResult {
        private final boolean success;
        private final boolean timeout;
        private final long waitTimeMs;
        
        public ImmutableAsyncWaitResult(boolean success, boolean timeout, long waitTimeMs) {
            this.success = success;
            this.timeout = timeout;
            this.waitTimeMs = waitTimeMs;
        }
        
        @Override
        public boolean isSuccess() { return success; }
        @Override
        public boolean isTimeout() { return timeout; }
        @Override
        public long getWaitTimeMs() { return waitTimeMs; }
    }
    
    private static class ImmutableAsyncBusinessOperationResult<R> implements AsyncBusinessOperationResult<R> {
        private final boolean success;
        private final R result;
        private final Throwable failureCause;
        private final long executionTimeMs;
        
        public ImmutableAsyncBusinessOperationResult(boolean success, R result,
                                                    Throwable failureCause, long executionTimeMs) {
            this.success = success;
            this.result = result;
            this.failureCause = failureCause;
            this.executionTimeMs = executionTimeMs;
        }
        
        @Override
        public boolean isSuccess() { return success; }
        @Override
        public R getResult() { return result; }
        @Override
        public Throwable getFailureCause() { return failureCause; }
        @Override
        public long getExecutionTimeMs() { return executionTimeMs; }
    }
    
    private static class ImmutableAsyncRetryOperationResult<R> implements AsyncRetryOperationResult<R> {
        private final boolean success;
        private final R result;
        private final int totalRetries;
        private final Throwable failureCause;
        private final long totalExecutionTimeMs;
        
        public ImmutableAsyncRetryOperationResult(boolean success, R result, int totalRetries,
                                                Throwable failureCause, long totalExecutionTimeMs) {
            this.success = success;
            this.result = result;
            this.totalRetries = totalRetries;
            this.failureCause = failureCause;
            this.totalExecutionTimeMs = totalExecutionTimeMs;
        }
        
        @Override
        public boolean isSuccess() { return success; }
        @Override
        public R getResult() { return result; }
        @Override
        public int getTotalRetries() { return totalRetries; }
        @Override
        public Throwable getFailureCause() { return failureCause; }
        @Override
        public long getTotalExecutionTimeMs() { return totalExecutionTimeMs; }
    }
    
    private static class ImmutableAsyncOperationConfiguration implements AsyncOperationConfiguration {
        private final long defaultTimeoutMs;
        private final int defaultRetryCount;
        private final long defaultRetryDelayMs;
        private final double renewalIntervalRatio;
        private final int maxConcurrency;
        private final boolean autoRenewalEnabled;
        
        public ImmutableAsyncOperationConfiguration(long defaultTimeoutMs, int defaultRetryCount,
                                                  long defaultRetryDelayMs, double renewalIntervalRatio,
                                                  int maxConcurrency, boolean autoRenewalEnabled) {
            this.defaultTimeoutMs = defaultTimeoutMs;
            this.defaultRetryCount = defaultRetryCount;
            this.defaultRetryDelayMs = defaultRetryDelayMs;
            this.renewalIntervalRatio = renewalIntervalRatio;
            this.maxConcurrency = maxConcurrency;
            this.autoRenewalEnabled = autoRenewalEnabled;
        }
        
        public ImmutableAsyncOperationConfiguration(AsyncOperationConfiguration config) {
            this(config.getDefaultTimeoutMs(), config.getDefaultRetryCount(),
                 config.getDefaultRetryDelayMs(), config.getRenewalIntervalRatio(),
                 config.getMaxConcurrency(), config.isAutoRenewalEnabled());
        }
        
        @Override
        public long getDefaultTimeoutMs() { return defaultTimeoutMs; }
        @Override
        public int getDefaultRetryCount() { return defaultRetryCount; }
        @Override
        public long getDefaultRetryDelayMs() { return defaultRetryDelayMs; }
        @Override
        public double getRenewalIntervalRatio() { return renewalIntervalRatio; }
        @Override
        public int getMaxConcurrency() { return maxConcurrency; }
        @Override
        public boolean isAutoRenewalEnabled() { return autoRenewalEnabled; }
        
        @Override
        public AsyncOperationConfiguration setDefaultTimeoutMs(long timeoutMs) {
            return new ImmutableAsyncOperationConfiguration(timeoutMs, defaultRetryCount,
                                                           defaultRetryDelayMs, renewalIntervalRatio,
                                                           maxConcurrency, autoRenewalEnabled);
        }
        
        @Override
        public AsyncOperationConfiguration setDefaultRetryCount(int retryCount) {
            return new ImmutableAsyncOperationConfiguration(defaultTimeoutMs, retryCount,
                                                           defaultRetryDelayMs, renewalIntervalRatio,
                                                           maxConcurrency, autoRenewalEnabled);
        }
        
        @Override
        public AsyncOperationConfiguration setDefaultRetryDelayMs(long retryDelayMs) {
            return new ImmutableAsyncOperationConfiguration(defaultTimeoutMs, defaultRetryCount,
                                                           retryDelayMs, renewalIntervalRatio,
                                                           maxConcurrency, autoRenewalEnabled);
        }
        
        @Override
        public AsyncOperationConfiguration setRenewalIntervalRatio(double ratio) {
            return new ImmutableAsyncOperationConfiguration(defaultTimeoutMs, defaultRetryCount,
                                                           defaultRetryDelayMs, ratio,
                                                           maxConcurrency, autoRenewalEnabled);
        }
        
        @Override
        public AsyncOperationConfiguration setMaxConcurrency(int maxConcurrency) {
            return new ImmutableAsyncOperationConfiguration(defaultTimeoutMs, defaultRetryCount,
                                                           defaultRetryDelayMs, renewalIntervalRatio,
                                                           maxConcurrency, autoRenewalEnabled);
        }
        
        @Override
        public AsyncOperationConfiguration setAutoRenewalEnabled(boolean autoRenewalEnabled) {
            return new ImmutableAsyncOperationConfiguration(defaultTimeoutMs, defaultRetryCount,
                                                           defaultRetryDelayMs, renewalIntervalRatio,
                                                           maxConcurrency, autoRenewalEnabled);
        }
    }
    
    public static class AsyncLockStatistics {
        private final long totalOperations;
        private final long successfulOperations;
        private final long failedOperations;
        private final int activeOperations;
        private final int activeRenewalTasks;
        private final long totalRenewals;
        private final long totalRetries;
        
        public AsyncLockStatistics(long totalOperations, long successfulOperations, long failedOperations,
                                 int activeOperations, int activeRenewalTasks, long totalRenewals,
                                 long totalRetries) {
            this.totalOperations = totalOperations;
            this.successfulOperations = successfulOperations;
            this.failedOperations = failedOperations;
            this.activeOperations = activeOperations;
            this.activeRenewalTasks = activeRenewalTasks;
            this.totalRenewals = totalRenewals;
            this.totalRetries = totalRetries;
        }
        
        public long getTotalOperations() { return totalOperations; }
        public long getSuccessfulOperations() { return successfulOperations; }
        public long getFailedOperations() { return failedOperations; }
        public int getActiveOperations() { return activeOperations; }
        public int getActiveRenewalTasks() { return activeRenewalTasks; }
        public long getTotalRenewals() { return totalRenewals; }
        public long getTotalRetries() { return totalRetries; }
        
        public double getSuccessRate() {
            return totalOperations > 0 ? (double) successfulOperations / totalOperations : 0.0;
        }
        
        public double getFailureRate() {
            return totalOperations > 0 ? (double) failedOperations / totalOperations : 0.0;
        }
    }
    
    public static class AsyncLockEvent {
        private final AsyncLockEventType type;
        private final String lockName;
        private final Instant timestamp;
        private final Throwable error;
        private final String message;
        
        public AsyncLockEvent(AsyncLockEventType type, String lockName, Instant timestamp,
                            Throwable error, String message) {
            this.type = type;
            this.lockName = lockName;
            this.timestamp = timestamp;
            this.error = error;
            this.message = message;
        }
        
        public AsyncLockEventType getType() { return type; }
        public String getLockName() { return lockName; }
        public Instant getTimestamp() { return timestamp; }
        public Throwable getError() { return error; }
        public String getMessage() { return message; }
    }
    
    public interface AsyncLockEventListener {
        void onEvent(AsyncLockEvent event);
    }
    
    public enum AsyncLockEventType {
        LOCK_ACQUIRED,
        LOCK_RELEASED,
        LOCK_RENEWED,
        LOCK_RENEWAL_FAILED,
        LOCK_ACQUISITION_FAILED,
        LOCK_RELEASE_FAILED,
        LOCK_AUTO_RENEWED,
        LOCK_AUTO_RENEWAL_FAILED,
        LOCK_AUTO_RENEWAL_STARTED,
        LOCK_AUTO_RENEWAL_CANCELLED,
        RETRY_OPERATION_SUCCESS,
        RETRY_OPERATION_FAILED
    }
}