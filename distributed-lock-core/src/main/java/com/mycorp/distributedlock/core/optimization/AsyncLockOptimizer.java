package com.mycorp.distributedlock.core.optimization;

import com.mycorp.distributedlock.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;

/**
 * 异步操作优化器
 * 
 * 功能特性：
 * 1. 基于CompletableFuture的异步锁操作
 * 2. 批量异步操作优化
 * 3. 异步操作的错误处理和重试机制
 * 4. 异步操作的内存使用优化
 * 5. 异步操作队列管理和限流
 * 6. 异步性能监控和调优
 */
public class AsyncLockOptimizer {
    
    private final Logger logger = LoggerFactory.getLogger(AsyncLockOptimizer.class);
    
    // 配置参数
    private final AsyncOptimizerConfig config;
    
    // 异步操作统计
    private final LongAdder totalAsyncOperations = new LongAdder();
    private final LongAdder successfulAsyncOperations = new LongAdder();
    private final LongAdder failedAsyncOperations = new LongAdder();
    private final LongAdder totalAsyncLatency = new LongAdder();
    private final AtomicLong maxAsyncLatency = new AtomicLong(0);
    private final AtomicInteger activeAsyncOperations = new AtomicInteger(0);
    
    // 性能指标
    private final AtomicLong avgAsyncLatency = new AtomicLong(0);
    private final AtomicLong asyncThroughput = new AtomicLong(0);
    private final AtomicInteger peakConcurrency = new AtomicInteger(0);
    
    // 重试统计
    private final LongAdder totalRetries = new LongAdder();
    private final LongAdder successfulRetries = new LongAdder();
    
    // 线程池管理
    private final ThreadPoolExecutor asyncExecutor;
    private final ScheduledExecutorService retryExecutor;
    private final ScheduledExecutorService monitoringExecutor;
    
    // 异步操作队列
    private final BlockingQueue<AsyncOperation> operationQueue;
    private final Map<String, AsyncOperation> operationCache;
    
    // 限流器
    private final RateLimiter rateLimiter;
    
    public AsyncLockOptimizer(AsyncOptimizerConfig config) {
        this.config = config;
        this.operationQueue = new LinkedBlockingQueue<>(config.getMaxQueueSize());
        this.operationCache = new ConcurrentHashMap<>();
        this.rateLimiter = new RateLimiter(config.getMaxRequestsPerSecond());
        
        // 初始化线程池
        this.asyncExecutor = createAsyncExecutor();
        this.retryExecutor = Executors.newScheduledThreadPool(
                config.getRetryThreadPoolSize());
        this.monitoringExecutor = Executors.newScheduledThreadPool(1);
        
        // 启动监控
        startMonitoring();
        
        logger.info("异步操作优化器初始化完成 - 最大并发: {}, 最大队列: {}, 限流: {}/s",
                config.getMaxConcurrency(), config.getMaxQueueSize(), config.getMaxRequestsPerSecond());
    }
    
    /**
     * 创建异步执行器
     */
    private ThreadPoolExecutor createAsyncExecutor() {
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "async-lock-" + counter.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            }
        };
        
        return new ThreadPoolExecutor(
                config.getCoreThreadPoolSize(),
                config.getMaxThreadPoolSize(),
                config.getKeepAliveTime(),
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(config.getQueueCapacity()),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy() {
                    @Override
                    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                        if (!isShuttingDown()) {
                            super.rejectedExecution(r, e);
                        }
                    }
                }
        );
    }
    
    /**
     * 异步获取锁
     */
    public CompletableFuture<LockOperationResult> acquireLockAsync(
            String lockKey, 
            DistributedLockFactory lockFactory,
            long timeout,
            TimeUnit timeUnit) {
        
        return acquireLockAsync(lockKey, lockFactory, timeout, timeUnit, 0);
    }
    
    /**
     * 异步获取锁（带重试）
     */
    public CompletableFuture<LockOperationResult> acquireLockAsync(
            String lockKey,
            DistributedLockFactory lockFactory,
            long timeout,
            TimeUnit timeUnit,
            int maxRetries) {
        
        String operationId = generateOperationId();
        long startTime = System.nanoTime();
        
        return CompletableFuture.supplyAsync(() -> {
            activeAsyncOperations.incrementAndGet();
            updatePeakConcurrency();
            
            try {
                // 限流检查
                rateLimiter.acquire();
                
                // 缓存检查
                if (config.isCacheEnabled()) {
                    AsyncOperation cached = operationCache.get(lockKey);
                    if (cached != null && cached.isValid()) {
                        return cached.getResult();
                    }
                }
                
                // 执行锁获取
                return executeLockOperation(lockKey, lockFactory, timeout, timeUnit, 
                        maxRetries, startTime, operationId);
                
            } catch (Exception e) {
                recordAsyncFailure(startTime);
                return new LockOperationResult(false, e);
            } finally {
                activeAsyncOperations.decrementAndGet();
            }
        }, asyncExecutor).whenComplete((result, throwable) -> {
            recordAsyncOperation(result != null, System.nanoTime() - startTime);
            
            // 缓存结果
            if (result != null && result.isSuccess() && config.isCacheEnabled()) {
                cacheOperationResult(lockKey, result);
            }
        });
    }
    
    /**
     * 异步释放锁
     */
    public CompletableFuture<LockOperationResult> releaseLockAsync(
            DistributedLock lock,
            String lockKey) {
        
        return CompletableFuture.supplyAsync(() -> {
            activeAsyncOperations.incrementAndGet();
            
            try {
                long startTime = System.nanoTime();
                lock.unlock();
                
                // 从缓存中移除
                if (config.isCacheEnabled()) {
                    operationCache.remove(lockKey);
                }
                
                recordAsyncOperation(true, System.nanoTime() - startTime);
                return new LockOperationResult(true, null);
                
            } catch (Exception e) {
                recordAsyncFailure(System.nanoTime());
                return new LockOperationResult(false, e);
            } finally {
                activeAsyncOperations.decrementAndGet();
            }
        }, asyncExecutor);
    }
    
    /**
     * 异步批量锁操作
     */
    public CompletableFuture<BatchLockOperationResult> batchLockAsync(
            List<String> lockKeys,
            DistributedLockFactory lockFactory,
            long timeout,
            TimeUnit timeUnit) {
        
        return CompletableFuture.supplyAsync(() -> {
            activeAsyncOperations.incrementAndGet();
            long startTime = System.nanoTime();
            
            try {
                Map<String, Boolean> results = new ConcurrentHashMap<>();
                CountDownLatch latch = new CountDownLatch(lockKeys.size());
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                
                // 提交所有锁获取任务
                for (String lockKey : lockKeys) {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            LockOperationResult result = executeLockOperation(
                                    lockKey, lockFactory, timeout, timeUnit, 0, startTime, 
                                    generateOperationId());
                            results.put(lockKey, result.isSuccess());
                        } catch (Exception e) {
                            results.put(lockKey, false);
                        } finally {
                            latch.countDown();
                        }
                    }, asyncExecutor);
                    futures.add(future);
                }
                
                // 等待所有任务完成
                latch.await(timeout, timeUnit);
                
                recordAsyncOperation(true, System.nanoTime() - startTime);
                return new BatchLockOperationResult(results);
                
            } catch (Exception e) {
                recordAsyncFailure(System.nanoTime());
                return new BatchLockOperationResult(e);
            } finally {
                activeAsyncOperations.decrementAndGet();
            }
        }, asyncExecutor);
    }
    
    /**
     * 执行锁操作（带重试机制）
     */
    private LockOperationResult executeLockOperation(
            String lockKey,
            DistributedLockFactory lockFactory,
            long timeout,
            TimeUnit timeUnit,
            int maxRetries,
            long startTime,
            String operationId) {
        
        int currentRetry = 0;
        
        while (currentRetry <= maxRetries) {
            try {
                DistributedLock lock = lockFactory.getLock(lockKey);
                
                boolean acquired = lock.tryLock(timeout, timeUnit);
                
                if (acquired) {
                    // 尝试续期（如果启用）
                    if (config.isAutoRenewalEnabled()) {
                        scheduleAutoRenewal(lock, lockKey);
                    }
                    
                    return new LockOperationResult(true, null);
                } else {
                    throw new LockAcquisitionException("Failed to acquire lock: " + lockKey);
                }
                
            } catch (Exception e) {
                currentRetry++;
                totalRetries.increment();
                
                if (currentRetry > maxRetries) {
                    logger.warn("锁操作失败，已达到最大重试次数: {} - {}", lockKey, e.getMessage());
                    return new LockOperationResult(false, e);
                }
                
                // 计算退避延迟
                long backoffDelay = calculateBackoffDelay(currentRetry);
                
                try {
                    Thread.sleep(backoffDelay);
                    successfulRetries.increment();
                    logger.debug("锁操作重试 #{}: {} - 延迟: {}ms", currentRetry, lockKey, backoffDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new LockOperationResult(false, ie);
                }
            }
        }
        
        return new LockOperationResult(false, new LockAcquisitionException("Maximum retries exceeded"));
    }
    
    /**
     * 计算退避延迟
     */
    private long calculateBackoffDelay(int retryCount) {
        if (config.getBackoffStrategy() == BackoffStrategy.EXPONENTIAL) {
            return Math.min(config.getBaseBackoffDelay() * (1L << retryCount), config.getMaxBackoffDelay());
        } else if (config.getBackoffStrategy() == BackoffStrategy.FIBONACCI) {
            long delay = config.getBaseBackoffDelay();
            for (int i = 1; i < retryCount; i++) {
                delay = delay * i; // Fibonacci approximation
            }
            return Math.min(delay, config.getMaxBackoffDelay());
        } else { // LINEAR
            return Math.min(config.getBaseBackoffDelay() * retryCount, config.getMaxBackoffDelay());
        }
    }
    
    /**
     * 调度自动续期
     */
    private void scheduleAutoRenewal(DistributedLock lock, String lockKey) {
        Runnable renewalTask = () -> {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.renewLease();
                }
            } catch (Exception e) {
                logger.warn("自动续期失败: {}", lockKey, e);
            }
        };
        
        retryExecutor.scheduleAtFixedRate(renewalTask, 
                config.getRenewalInterval(), 
                config.getRenewalInterval(), 
                TimeUnit.MILLISECONDS);
    }
    
    /**
     * 更新峰值并发数
     */
    private void updatePeakConcurrency() {
        int current = activeAsyncOperations.get();
        int currentPeak = peakConcurrency.get();
        
        while (current > currentPeak) {
            if (peakConcurrency.compareAndSet(currentPeak, current)) {
                break;
            }
            currentPeak = peakConcurrency.get();
        }
    }
    
    /**
     * 缓存操作结果
     */
    private void cacheOperationResult(String lockKey, LockOperationResult result) {
        if (result.isSuccess() && config.isCacheEnabled()) {
            AsyncOperation operation = new AsyncOperation(lockKey, result, System.currentTimeMillis());
            operationCache.put(lockKey, operation);
            
            // 清理过期缓存
            if (operationCache.size() > config.getMaxCacheSize()) {
                cleanupExpiredCache();
            }
        }
    }
    
    /**
     * 清理过期缓存
     */
    private void cleanupExpiredCache() {
        long currentTime = System.currentTimeMillis();
        long expiryTime = config.getCacheExpiryTime();
        
        operationCache.entrySet().removeIf(entry -> {
            AsyncOperation operation = entry.getValue();
            return (currentTime - operation.getTimestamp()) > expiryTime;
        });
    }
    
    /**
     * 记录异步操作
     */
    private void recordAsyncOperation(boolean success, long latency) {
        totalAsyncOperations.increment();
        
        if (success) {
            successfulAsyncOperations.increment();
        } else {
            failedAsyncOperations.increment();
        }
        
        totalAsyncLatency.add(latency);
        
        if (latency > maxAsyncLatency.get()) {
            maxAsyncLatency.set(latency);
        }
        
        // 更新平均延迟
        long totalOps = totalAsyncOperations.sum();
        long totalLatency = totalAsyncLatency.sum();
        long newAvg = totalLatency / Math.max(totalOps, 1);
        avgAsyncLatency.set(newAvg);
    }
    
    private void recordAsyncFailure(long startTime) {
        recordAsyncOperation(false, System.nanoTime() - startTime);
    }
    
    /**
     * 生成操作ID
     */
    private String generateOperationId() {
        return "async-" + System.currentTimeMillis() + "-" + 
                Thread.currentThread().getId() + "-" + 
                (operationIdCounter.incrementAndGet());
    }
    
    private final AtomicLong operationIdCounter = new AtomicLong(0);
    
    /**
     * 启动监控
     */
    private void startMonitoring() {
        if (config.isMonitoringEnabled()) {
            monitoringExecutor.scheduleWithFixedDelay(() -> {
                try {
                    printAsyncPerformanceStats();
                    checkOptimizationTriggers();
                } catch (Exception e) {
                    logger.error("异步操作监控异常", e);
                }
            }, 10, 30, TimeUnit.SECONDS);
        }
    }
    
    /**
     * 打印异步性能统计
     */
    private void printAsyncPerformanceStats() {
        long totalOps = totalAsyncOperations.sum();
        long successfulOps = successfulAsyncOperations.sum();
        long failedOps = failedAsyncOperations.sum();
        long totalLatency = totalAsyncLatency.sum();
        
        if (totalOps > 0) {
            double successRate = (double) successfulOps / totalOps * 100;
            double avgLatency = (double) totalLatency / totalOps / 1_000_000; // 转换为毫秒
            double throughput = successfulOps * 1000.0 / Math.max(totalLatency / 1_000_000, 1);
            
            logger.info("异步操作统计 - 总操作: {}, 成功: {}, 失败: {}, 成功率: {:.2f}%, 平均延迟: {:.2f}ms, 峰值并发: {}",
                    totalOps, successfulOps, failedOps, successRate, avgLatency, peakConcurrency.get());
        }
    }
    
    /**
     * 检查优化触发条件
     */
    private void checkOptimizationTriggers() {
        int activeOps = activeAsyncOperations.get();
        int queueSize = operationQueue.size();
        double avgLatencyMs = avgAsyncLatency.get() / 1_000_000.0;
        
        // 检查是否需要调整线程池大小
        if (activeOps > asyncExecutor.getMaximumPoolSize() * 0.8) {
            logger.info("活跃操作数接近线程池上限，增加线程池大小");
            adjustThreadPoolSize();
        }
        
        // 检查队列是否过满
        if (queueSize > config.getMaxQueueSize() * 0.8) {
            logger.warn("操作队列过满，当前大小: {}/{}", queueSize, config.getMaxQueueSize());
        }
        
        // 检查延迟是否过高
        if (avgLatencyMs > config.getMaxAcceptableLatency()) {
            logger.warn("异步操作延迟过高: {:.2f}ms > {}ms", avgLatencyMs, config.getMaxAcceptableLatency());
        }
    }
    
    /**
     * 调整线程池大小
     */
    private void adjustThreadPoolSize() {
        int currentMax = asyncExecutor.getMaximumPoolSize();
        int newMax = Math.min(currentMax + 10, config.getMaxThreadPoolSize());
        
        asyncExecutor.setMaximumPoolSize(newMax);
        logger.info("调整线程池最大大小: {} -> {}", currentMax, newMax);
    }
    
    /**
     * 检查是否正在关闭
     */
    private boolean isShuttingDown() {
        return asyncExecutor.isShutdown() || Thread.currentThread().isInterrupted();
    }
    
    /**
     * 获取异步操作统计
     */
    public AsyncOperationStatistics getStatistics() {
        long totalOps = totalAsyncOperations.sum();
        long successfulOps = successfulAsyncOperations.sum();
        long failedOps = failedAsyncOperations.sum();
        long totalLatency = totalAsyncLatency.sum();
        
        return new AsyncOperationStatistics(
                totalOps,
                successfulOps,
                failedOps,
                totalOps > 0 ? (double) successfulOps / totalOps * 100 : 0,
                totalOps > 0 ? totalLatency / totalOps / 1_000_000 : 0, // 转换为毫秒
                maxAsyncLatency.get() / 1_000_000, // 转换为毫秒
                activeAsyncOperations.get(),
                peakConcurrency.get(),
                totalRetries.sum(),
                successfulRetries.sum(),
                operationCache.size(),
                operationQueue.size()
        );
    }
    
    /**
     * 关闭优化器
     */
    public void shutdown() {
        logger.info("关闭异步操作优化器");
        
        try {
            // 关闭监控
            if (monitoringExecutor != null) {
                monitoringExecutor.shutdown();
            }
            
            // 关闭重试执行器
            if (retryExecutor != null) {
                retryExecutor.shutdown();
            }
            
            // 关闭异步执行器
            if (asyncExecutor != null) {
                asyncExecutor.shutdown();
                if (!asyncExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    asyncExecutor.shutdownNow();
                }
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 异步操作优化器配置
     */
    public static class AsyncOptimizerConfig {
        private int maxConcurrency = 100;
        private int maxQueueSize = 1000;
        private int maxRequestsPerSecond = 1000;
        private int coreThreadPoolSize = 10;
        private int maxThreadPoolSize = 50;
        private long keepAliveTime = 60;
        private int queueCapacity = 100;
        private int retryThreadPoolSize = 2;
        private boolean autoRenewalEnabled = true;
        private boolean cacheEnabled = true;
        private long cacheExpiryTime = 300000; // 5分钟
        private int maxCacheSize = 1000;
        private long renewalInterval = 30000; // 30秒
        private long baseBackoffDelay = 100; // 100ms
        private long maxBackoffDelay = 5000; // 5秒
        private BackoffStrategy backoffStrategy = BackoffStrategy.EXPONENTIAL;
        private boolean monitoringEnabled = true;
        private long maxAcceptableLatency = 1000; // 1秒
        
        // Getters
        public int getMaxConcurrency() { return maxConcurrency; }
        public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
        public int getMaxQueueSize() { return maxQueueSize; }
        public void setMaxQueueSize(int maxQueueSize) { this.maxQueueSize = maxQueueSize; }
        public int getMaxRequestsPerSecond() { return maxRequestsPerSecond; }
        public void setMaxRequestsPerSecond(int maxRequestsPerSecond) { this.maxRequestsPerSecond = maxRequestsPerSecond; }
        public int getCoreThreadPoolSize() { return coreThreadPoolSize; }
        public void setCoreThreadPoolSize(int coreThreadPoolSize) { this.coreThreadPoolSize = coreThreadPoolSize; }
        public int getMaxThreadPoolSize() { return maxThreadPoolSize; }
        public void setMaxThreadPoolSize(int maxThreadPoolSize) { this.maxThreadPoolSize = maxThreadPoolSize; }
        public long getKeepAliveTime() { return keepAliveTime; }
        public void setKeepAliveTime(long keepAliveTime) { this.keepAliveTime = keepAliveTime; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
        public int getRetryThreadPoolSize() { return retryThreadPoolSize; }
        public void setRetryThreadPoolSize(int retryThreadPoolSize) { this.retryThreadPoolSize = retryThreadPoolSize; }
        public boolean isAutoRenewalEnabled() { return autoRenewalEnabled; }
        public void setAutoRenewalEnabled(boolean autoRenewalEnabled) { this.autoRenewalEnabled = autoRenewalEnabled; }
        public boolean isCacheEnabled() { return cacheEnabled; }
        public void setCacheEnabled(boolean cacheEnabled) { this.cacheEnabled = cacheEnabled; }
        public long getCacheExpiryTime() { return cacheExpiryTime; }
        public void setCacheExpiryTime(long cacheExpiryTime) { this.cacheExpiryTime = cacheExpiryTime; }
        public int getMaxCacheSize() { return maxCacheSize; }
        public void setMaxCacheSize(int maxCacheSize) { this.maxCacheSize = maxCacheSize; }
        public long getRenewalInterval() { return renewalInterval; }
        public void setRenewalInterval(long renewalInterval) { this.renewalInterval = renewalInterval; }
        public long getBaseBackoffDelay() { return baseBackoffDelay; }
        public void setBaseBackoffDelay(long baseBackoffDelay) { this.baseBackoffDelay = baseBackoffDelay; }
        public long getMaxBackoffDelay() { return maxBackoffDelay; }
        public void setMaxBackoffDelay(long maxBackoffDelay) { this.maxBackoffDelay = maxBackoffDelay; }
        public BackoffStrategy getBackoffStrategy() { return backoffStrategy; }
        public void setBackoffStrategy(BackoffStrategy backoffStrategy) { this.backoffStrategy = backoffStrategy; }
        public boolean isMonitoringEnabled() { return monitoringEnabled; }
        public void setMonitoringEnabled(boolean monitoringEnabled) { this.monitoringEnabled = monitoringEnabled; }
        public long getMaxAcceptableLatency() { return maxAcceptableLatency; }
        public void setMaxAcceptableLatency(long maxAcceptableLatency) { this.maxAcceptableLatency = maxAcceptableLatency; }
    }
    
    /**
     * 退避策略
     */
    public enum BackoffStrategy {
        LINEAR,       // 线性退避
        EXPONENTIAL,  // 指数退避
        FIBONACCI     // 斐波那契退避
    }
    
    /**
     * 异步操作
     */
    private static class AsyncOperation {
        private final String lockKey;
        private final LockOperationResult result;
        private final long timestamp;
        
        public AsyncOperation(String lockKey, LockOperationResult result, long timestamp) {
            this.lockKey = lockKey;
            this.result = result;
            this.timestamp = timestamp;
        }
        
        public boolean isValid() {
            return result.isSuccess() && (System.currentTimeMillis() - timestamp) < 300000; // 5分钟有效期
        }
        
        public String getLockKey() { return lockKey; }
        public LockOperationResult getResult() { return result; }
        public long getTimestamp() { return timestamp; }
    }
    
    /**
     * 锁操作结果
     */
    public static class LockOperationResult {
        private final boolean success;
        private final Exception exception;
        
        public LockOperationResult(boolean success, Exception exception) {
            this.success = success;
            this.exception = exception;
        }
        
        public boolean isSuccess() { return success; }
        public Exception getException() { return exception; }
    }
    
    /**
     * 批量锁操作结果
     */
    public static class BatchLockOperationResult {
        private final Map<String, Boolean> results;
        private final Exception exception;
        
        public BatchLockOperationResult(Map<String, Boolean> results) {
            this.results = results;
            this.exception = null;
        }
        
        public BatchLockOperationResult(Exception exception) {
            this.results = null;
            this.exception = exception;
        }
        
        public Map<String, Boolean> getResults() { return results; }
        public boolean isSuccess() { return exception == null; }
        public Exception getException() { return exception; }
    }
    
    /**
     * 异步操作统计
     */
    public static class AsyncOperationStatistics {
        private final long totalOperations;
        private final long successfulOperations;
        private final long failedOperations;
        private final double successRate;
        private final double averageLatency;
        private final long maximumLatency;
        private final int activeOperations;
        private final int peakConcurrency;
        private final long totalRetries;
        private final long successfulRetries;
        private final int cacheSize;
        private final int queueSize;
        
        public AsyncOperationStatistics(long totalOperations, long successfulOperations, 
                                      long failedOperations, double successRate, 
                                      double averageLatency, long maximumLatency,
                                      int activeOperations, int peakConcurrency,
                                      long totalRetries, long successfulRetries,
                                      int cacheSize, int queueSize) {
            this.totalOperations = totalOperations;
            this.successfulOperations = successfulOperations;
            this.failedOperations = failedOperations;
            this.successRate = successRate;
            this.averageLatency = averageLatency;
            this.maximumLatency = maximumLatency;
            this.activeOperations = activeOperations;
            this.peakConcurrency = peakConcurrency;
            this.totalRetries = totalRetries;
            this.successfulRetries = successfulRetries;
            this.cacheSize = cacheSize;
            this.queueSize = queueSize;
        }
        
        // Getters
        public long getTotalOperations() { return totalOperations; }
        public long getSuccessfulOperations() { return successfulOperations; }
        public long getFailedOperations() { return failedOperations; }
        public double getSuccessRate() { return successRate; }
        public double getAverageLatency() { return averageLatency; }
        public long getMaximumLatency() { return maximumLatency; }
        public int getActiveOperations() { return activeOperations; }
        public int getPeakConcurrency() { return peakConcurrency; }
        public long getTotalRetries() { return totalRetries; }
        public long getSuccessfulRetries() { return successfulRetries; }
        public int getCacheSize() { return cacheSize; }
        public int getQueueSize() { return queueSize; }
    }
    
    /**
     * 简单的限流器
     */
    private static class RateLimiter {
        private final int maxRequestsPerSecond;
        private long lastRefillTime;
        private int tokens;
        
        public RateLimiter(int maxRequestsPerSecond) {
            this.maxRequestsPerSecond = maxRequestsPerSecond;
            this.lastRefillTime = System.currentTimeMillis();
            this.tokens = maxRequestsPerSecond;
        }
        
        public synchronized void acquire() throws InterruptedException {
            long now = System.currentTimeMillis();
            long timePassed = now - lastRefillTime;
            
            // 重置令牌
            if (timePassed >= 1000) {
                tokens = maxRequestsPerSecond;
                lastRefillTime = now;
            }
            
            // 等待令牌
            while (tokens <= 0) {
                long waitTime = 1000 - (System.currentTimeMillis() - lastRefillTime);
                if (waitTime <= 0) {
                    tokens = maxRequestsPerSecond;
                    lastRefillTime = System.currentTimeMillis();
                    break;
                }
                wait(waitTime);
            }
            
            tokens--;
        }
    }
}