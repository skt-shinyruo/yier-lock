package com.mycorp.distributedlock.core.observability;

import com.mycorp.distributedlock.api.PerformanceMetrics;
import com.mycorp.distributedlock.api.PerformanceMetrics.*;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.config.MeterRegistryConfig;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.logging.LoggingMeterRegistry;
import io.micrometer.core.instrument.prometheus.PrometheusMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Micrometer指标适配器
 * 将分布式锁指标转换为Micrometer格式并注册到注册表中
 */
public class MicrometerMetricsAdapter {
    
    private static final Logger logger = LoggerFactory.getLogger(MicrometerMetricsAdapter.class);
    
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final String applicationName;
    private final String instanceId;
    
    // 锁级指标映射
    private final Map<String, LockMetricGroup> lockMetricsMap;
    
    // 系统级指标
    private final Counter totalOperationsCounter;
    private final Counter successfulOperationsCounter;
    private final Counter failedOperationsCounter;
    private final Gauge currentConcurrencyGauge;
    private final Gauge peakConcurrencyGauge;
    private final Timer operationDurationTimer;
    
    // 线程池用于异步指标记录
    private final ScheduledExecutorService scheduler;
    
    public MicrometerMetricsAdapter(MeterRegistry meterRegistry, boolean enabled,
                                  String applicationName, String instanceId) {
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.applicationName = applicationName;
        this.instanceId = instanceId;
        this.lockMetricsMap = new ConcurrentHashMap<>();
        
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread thread = new Thread(r, "micrometer-metrics-adapter");
            thread.setDaemon(true);
            return thread;
        });
        
        if (enabled && meterRegistry != null) {
            initializeSystemMetrics();
            startPeriodicCleanup();
        }
    }
    
    private void initializeSystemMetrics() {
        Tags baseTags = Tags.of(
            "application", applicationName,
            "instance", instanceId
        );
        
        // 初始化系统级指标
        this.totalOperationsCounter = Counter.builder("lock.operations.total")
            .description("Total number of lock operations")
            .tags(baseTags)
            .register(meterRegistry);
            
        this.successfulOperationsCounter = Counter.builder("lock.operations.success")
            .description("Number of successful lock operations")
            .tags(baseTags)
            .register(meterRegistry);
            
        this.failedOperationsCounter = Counter.builder("lock.operations.failed")
            .description("Number of failed lock operations")
            .tags(baseTags)
            .register(meterRegistry);
            
        this.currentConcurrencyGauge = Gauge.builder("lock.concurrency.current")
            .description("Current lock concurrency level")
            .tags(baseTags)
            .register(meterRegistry, this, metrics -> {
                // 这里需要从实际的并发度计数器获取值
                return 0.0;
            });
            
        this.peakConcurrencyGauge = Gauge.builder("lock.concurrency.peak")
            .description("Peak lock concurrency level")
            .tags(baseTags)
            .register(meterRegistry, this, metrics -> {
                // 这里需要从峰值并发度计数器获取值
                return 0.0;
            });
            
        this.operationDurationTimer = Timer.builder("lock.operation.duration")
            .description("Distribution of lock operation durations")
            .tags(baseTags)
            .distributionStatisticConfig(DistributionStatisticConfig.builder()
                .percentiles(0.5, 0.95, 0.99)
                .build())
            .register(meterRegistry);
            
        logger.info("Micrometer system metrics initialized");
    }
    
    /**
     * 记录锁操作指标
     */
    public void recordLockOperation(String lockName, LockOperation operation,
                                  Duration duration, boolean success,
                                  Map<String, Object> metadata) {
        if (!enabled || meterRegistry == null) return;
        
        try {
            // 记录系统级指标
            recordSystemMetrics(operation, duration, success);
            
            // 获取锁级指标组
            LockMetricGroup lockGroup = getLockMetricGroup(lockName);
            lockGroup.recordOperation(operation, duration, success, metadata);
            
            // 记录分布统计
            recordDistributionStatistics(lockName, operation, duration, success);
            
        } catch (Exception e) {
            logger.error("Error recording lock operation metrics for lock: {}", lockName, e);
        }
    }
    
    /**
     * 批量记录锁操作指标
     */
    public void recordLockOperations(List<LockMetricRecord> records) {
        if (!enabled || meterRegistry == null || records == null || records.isEmpty()) return;
        
        for (LockMetricRecord record : records) {
            try {
                LockOperation operation = parseOperation(record.operation);
                recordLockOperation(record.lockName, operation,
                                  record.duration, record.success, record.metadata);
            } catch (Exception e) {
                logger.error("Error processing record for lock: {}", record.lockName, e);
            }
        }
    }
    
    /**
     * 获取锁级指标
     */
    public LockMetricGroup getLockMetricGroup(String lockName) {
        return lockMetricsMap.computeIfAbsent(lockName, 
            k -> new LockMetricGroup(k, meterRegistry, applicationName, instanceId));
    }
    
    /**
     * 获取所有锁的指标
     */
    public Map<String, LockMetricGroup> getAllLockMetrics() {
        return new HashMap<>(lockMetricsMap);
    }
    
    /**
     * 导出Prometheus格式的指标
     */
    public String exportPrometheusMetrics() {
        if (!(meterRegistry instanceof PrometheusMeterRegistry)) {
            throw new IllegalStateException("MeterRegistry is not a PrometheusMeterRegistry");
        }
        
        PrometheusMeterRegistry prometheusRegistry = (PrometheusMeterRegistry) meterRegistry;
        return prometheusRegistry.scrape();
    }
    
    /**
     * 导出日志格式的指标
     */
    public void exportLoggingMetrics() {
        if (!(meterRegistry instanceof LoggingMeterRegistry)) {
            logger.warn("MeterRegistry is not a LoggingMeterRegistry, cannot export logging metrics");
            return;
        }
        
        LoggingMeterRegistry loggingRegistry = (LoggingMeterRegistry) meterRegistry;
        loggingRegistry.stop();
        logger.info("Logging metrics exported");
    }
    
    /**
     * 获取指标统计信息
     */
    public MetricsStats getMetricsStats() {
        return new MetricsStatsImpl();
    }
    
    /**
     * 清理不活跃的锁指标
     */
    public void cleanupInactiveLockMetrics(Duration inactiveThreshold) {
        Instant cutoffTime = Instant.now().minus(inactiveThreshold);
        
        lockMetricsMap.entrySet().removeIf(entry -> {
            LockMetricGroup group = entry.getValue();
            return group.getLastUpdateTime().isBefore(cutoffTime);
        });
        
        logger.info("Cleaned up inactive lock metrics. Remaining locks: {}", lockMetricsMap.size());
    }
    
    /**
     * 重置所有指标
     */
    public void resetAllMetrics() {
        if (meterRegistry != null) {
            meterRegistry.clear();
        }
        lockMetricsMap.clear();
        logger.info("All metrics reset");
    }
    
    /**
     * 关闭适配器
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("MicrometerMetricsAdapter shutdown completed");
    }
    
    // 私有方法
    
    private void recordSystemMetrics(LockOperation operation, Duration duration, boolean success) {
        totalOperationsCounter.increment();
        
        if (success) {
            successfulOperationsCounter.increment();
        } else {
            failedOperationsCounter.increment();
        }
        
        operationDurationTimer.record(duration.toMillis(), TimeUnit.MILLISECONDS);
    }
    
    private void recordDistributionStatistics(String lockName, LockOperation operation,
                                            Duration duration, boolean success) {
        Tags tags = Tags.of(
            "application", applicationName,
            "instance", instanceId,
            "lock_name", lockName,
            "operation", operation.name().toLowerCase(),
            "status", success ? "success" : "failure"
        );
        
        // 记录操作计数分布
        Counter.builder("lock.operation.count")
            .description("Count of lock operations by type and status")
            .tags(tags)
            .register(meterRegistry)
            .increment();
            
        // 记录操作时间分布
        Timer.builder("lock.operation.time.distribution")
            .description("Distribution of lock operation times")
            .tags(tags)
            .register(meterRegistry)
            .record(duration.toMillis(), TimeUnit.MILLISECONDS);
    }
    
    private LockOperation parseOperation(String operation) {
        try {
            return LockOperation.valueOf(operation.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown operation: {}, defaulting to LOCK_ACQUIRE", operation);
            return LockOperation.LOCK_ACQUIRE;
        }
    }
    
    private void startPeriodicCleanup() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                cleanupInactiveLockMetrics(Duration.ofHours(1));
            } catch (Exception e) {
                logger.error("Error during periodic cleanup", e);
            }
        }, 5, 5, TimeUnit.MINUTES);
    }
    
    /**
     * 锁级指标组
     */
    public static class LockMetricGroup {
        private final String lockName;
        private final MeterRegistry meterRegistry;
        private final String applicationName;
        private final String instanceId;
        private final AtomicReference<Instant> lastUpdateTime = new AtomicReference<>(Instant.now());
        
        // 锁级指标
        private final Counter acquisitionCounter;
        private final Counter releaseCounter;
        private final Counter renewalCounter;
        private final Timer acquisitionTimer;
        private final Timer holdTimeTimer;
        private final Timer waitTimeTimer;
        private final Gauge activeHoldersGauge;
        private final Gauge queueLengthGauge;
        private final Counter contentionCounter;
        private final Counter errorCounter;
        
        public LockMetricGroup(String lockName, MeterRegistry meterRegistry,
                             String applicationName, String instanceId) {
            this.lockName = lockName;
            this.meterRegistry = meterRegistry;
            this.applicationName = applicationName;
            this.instanceId = instanceId;
            
            Tags baseTags = Tags.of(
                "application", applicationName,
                "instance", instanceId,
                "lock_name", lockName
            );
            
            // 初始化锁级指标
            this.acquisitionCounter = Counter.builder("lock.acquisition.count")
                .description("Number of lock acquisition attempts")
                .tags(baseTags)
                .register(meterRegistry);
                
            this.releaseCounter = Counter.builder("lock.release.count")
                .description("Number of lock release attempts")
                .tags(baseTags)
                .register(meterRegistry);
                
            this.renewalCounter = Counter.builder("lock.renewal.count")
                .description("Number of lock renewals")
                .tags(baseTags)
                .register(meterRegistry);
                
            this.acquisitionTimer = Timer.builder("lock.acquisition.time")
                .description("Time to acquire lock")
                .tags(baseTags)
                .distributionStatisticConfig(DistributionStatisticConfig.builder()
                    .percentiles(0.5, 0.95, 0.99)
                    .build())
                .register(meterRegistry);
                
            this.holdTimeTimer = Timer.builder("lock.hold.time")
                .description("Time lock is held")
                .tags(baseTags)
                .distributionStatisticConfig(DistributionStatisticConfig.builder()
                    .percentiles(0.5, 0.95, 0.99)
                    .build())
                .register(meterRegistry);
                
            this.waitTimeTimer = Timer.builder("lock.wait.time")
                .description("Time waiting for lock")
                .tags(baseTags)
                .distributionStatisticConfig(DistributionStatisticConfig.builder()
                    .percentiles(0.5, 0.95, 0.99)
                    .build())
                .register(meterRegistry);
                
            this.activeHoldersGauge = Gauge.builder("lock.active.holders")
                .description("Number of active lock holders")
                .tags(baseTags)
                .register(meterRegistry, this, group -> {
                    // 这里需要从实际的锁状态获取
                    return 0.0;
                });
                
            this.queueLengthGauge = Gauge.builder("lock.queue.length")
                .description("Current lock queue length")
                .tags(baseTags)
                .register(meterRegistry, this, group -> {
                    // 这里需要从实际的队列状态获取
                    return 0.0;
                });
                
            this.contentionCounter = Counter.builder("lock.contention.count")
                .description("Number of lock contention events")
                .tags(baseTags)
                .register(meterRegistry);
                
            this.errorCounter = Counter.builder("lock.error.count")
                .description("Number of lock operation errors")
                .tags(baseTags)
                .register(meterRegistry);
        }
        
        public void recordOperation(LockOperation operation, Duration duration,
                                  boolean success, Map<String, Object> metadata) {
            lastUpdateTime.set(Instant.now());
            
            if (!success) {
                errorCounter.increment();
            }
            
            switch (operation) {
                case LOCK_ACQUIRE:
                    acquisitionCounter.increment();
                    acquisitionTimer.record(duration.toMillis(), TimeUnit.MILLISECONDS);
                    break;
                case LOCK_RELEASE:
                    releaseCounter.increment();
                    holdTimeTimer.record(duration.toMillis(), TimeUnit.MILLISECONDS);
                    break;
                case LOCK_RENEW:
                    renewalCounter.increment();
                    break;
                default:
                    // 其他操作类型
                    break;
            }
            
            // 记录元数据中的等待时间
            if (metadata != null && metadata.containsKey("wait_time")) {
                Object waitTime = metadata.get("wait_time");
                if (waitTime instanceof Duration) {
                    waitTimeTimer.record(((Duration) waitTime).toMillis(), TimeUnit.MILLISECONDS);
                }
            }
        }
        
        public Instant getLastUpdateTime() {
            return lastUpdateTime.get();
        }
        
        public String getLockName() {
            return lockName;
        }
        
        public Counter getAcquisitionCounter() {
            return acquisitionCounter;
        }
        
        public Counter getReleaseCounter() {
            return releaseCounter;
        }
        
        public Timer getAcquisitionTimer() {
            return acquisitionTimer;
        }
        
        public Timer getHoldTimeTimer() {
            return holdTimeTimer;
        }
    }
    
    /**
     * 指标统计信息
     */
    public interface MetricsStats {
        int getLockCount();
        long getTotalOperations();
        long getTotalErrors();
        double getErrorRate();
        Map<String, LockOperationStats> getOperationStats();
        Duration getUptime();
    }
    
    /**
     * 操作统计信息
     */
    public interface LockOperationStats {
        long getCount();
        Duration getAverageDuration();
        Duration getMaxDuration();
        double getSuccessRate();
    }
    
    private class MetricsStatsImpl implements MetricsStats {
        @Override
        public int getLockCount() {
            return lockMetricsMap.size();
        }
        
        @Override
        public long getTotalOperations() {
            return (long) totalOperationsCounter.count();
        }
        
        @Override
        public long getTotalErrors() {
            return (long) failedOperationsCounter.count();
        }
        
        @Override
        public double getErrorRate() {
            long total = (long) totalOperationsCounter.count();
            long errors = (long) failedOperationsCounter.count();
            return total > 0 ? (double) errors / total : 0.0;
        }
        
        @Override
        public Map<String, LockOperationStats> getOperationStats() {
            Map<String, LockOperationStats> stats = new HashMap<>();
            for (Map.Entry<String, LockMetricGroup> entry : lockMetricsMap.entrySet()) {
                String lockName = entry.getKey();
                LockMetricGroup group = entry.getValue();
                
                // 这里需要从实际的指标中计算统计信息
                stats.put(lockName, new LockOperationStatsImpl(group));
            }
            return stats;
        }
        
        @Override
        public Duration getUptime() {
            // 这里需要返回启动时间
            return Duration.ofMinutes(60);
        }
    }
    
    private class LockOperationStatsImpl implements LockOperationStats {
        private final LockMetricGroup group;
        
        public LockOperationStatsImpl(LockMetricGroup group) {
            this.group = group;
        }
        
        @Override
        public long getCount() {
            return (long) group.acquisitionCounter.count() + 
                   (long) group.releaseCounter.count();
        }
        
        @Override
        public Duration getAverageDuration() {
            // 这里需要从Timer中计算平均值
            return Duration.ofMillis(100); // 示例值
        }
        
        @Override
        public Duration getMaxDuration() {
            // 这里需要从Timer中获取最大值
            return Duration.ofMillis(1000); // 示例值
        }
        
        @Override
        public double getSuccessRate() {
            long total = getCount();
            long errors = (long) group.errorCounter.count();
            return total > 0 ? (double) (total - errors) / total : 1.0;
        }
    }
}