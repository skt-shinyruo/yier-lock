package com.mycorp.distributedlock.core.observability;

import com.mycorp.distributedlock.api.PerformanceMetrics;

import io.micrometer.core.instrument.*;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * 指标收集器
 * 统一收集和管理分布式锁的各类指标数据
 */
public class LockMetricsCollector {
    
    private static final Logger logger = LoggerFactory.getLogger(LockMetricsCollector.class);
    
    private final MeterRegistry meterRegistry;
    private final OpenTelemetry openTelemetry;
    private final boolean enabled;
    
    // 指标数据存储
    private final MetricsBuffer buffer;
    private final ScheduledExecutorService scheduledExecutor;
    
    // 统计信息
    private final AtomicLong totalMetricsCollected = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);
    private final AtomicLong lastCollectionTime = new AtomicLong(0);
    
    // 配置
    private final MetricsCollectorConfig config;
    
    public LockMetricsCollector(MeterRegistry meterRegistry, OpenTelemetry openTelemetry, 
                               boolean enabled) {
        this.meterRegistry = meterRegistry;
        this.openTelemetry = openTelemetry;
        this.enabled = enabled;
        this.config = new MetricsCollectorConfig();
        this.buffer = new MetricsBuffer(config.getBufferSize(), config.getRetentionDuration());
        
        this.scheduledExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread thread = new Thread(r, "lock-metrics-collector");
            thread.setDaemon(true);
            return thread;
        });
        
        if (enabled) {
            initialize();
        }
    }
    
    private void initialize() {
        // 启动定期清理任务
        scheduledExecutor.scheduleWithFixedDelay(this::cleanupExpiredMetrics, 
                                                config.getCleanupInterval().toSeconds(),
                                                config.getCleanupInterval().toSeconds(),
                                                TimeUnit.SECONDS);
        
        // 启动统计任务
        scheduledExecutor.scheduleWithFixedDelay(this::updateStatistics,
                                                config.getStatsInterval().toSeconds(),
                                                config.getStatsInterval().toSeconds(),
                                                TimeUnit.SECONDS);
        
        logger.info("LockMetricsCollector initialized with buffer size: {}", config.getBufferSize());
    }
    
    /**
     * 收集锁操作指标
     */
    public void collectLockOperation(String lockName, String operation, 
                                   Duration duration, boolean success,
                                   Map<String, Object> metadata) {
        if (!enabled) return;
        
        try {
            LockMetricRecord record = new LockMetricRecord(
                Instant.now(),
                lockName,
                operation,
                duration,
                success,
                metadata
            );
            
            // 添加到缓冲区
            buffer.add(record);
            
            // 立即记录Micrometer指标
            if (meterRegistry != null) {
                recordMicrometerMetrics(record);
            }
            
            // 记录分布式追踪
            if (openTelemetry != null) {
                recordTracingSpan(record);
            }
            
            totalMetricsCollected.incrementAndGet();
            lastCollectionTime.set(System.currentTimeMillis());
            
        } catch (Exception e) {
            totalErrors.incrementAndGet();
            logger.error("Error collecting lock operation metric for lock: {}", lockName, e);
        }
    }
    
    /**
     * 批量收集指标
     */
    public void collectLockOperations(List<LockMetricRecord> records) {
        if (!enabled || records == null || records.isEmpty()) return;
        
        for (LockMetricRecord record : records) {
            collectLockOperation(record.lockName, record.operation</tool_call>,
                               record.duration, record.success, record.metadata);
        }
    }
    
    /**
     * 获取收集的指标数量
     */
    public long getCollectedMetricsCount() {
        return totalMetricsCollected.get();
    }
    
    /**
     * 获取错误数量
     */
    public long getErrorCount() {
        return totalErrors.get();
    }
    
    /**
     * 获取最后收集时间
     */
    public long getLastCollectionTime() {
        return lastCollectionTime.get();
    }
    
    /**
     * 获取缓冲区大小
     */
    public int getBufferSize() {
        return buffer.getCurrentSize();
    }
    
    /**
     * 查询时间范围内的指标
     */
    public List<LockMetricRecord> queryMetrics(Duration timeRange) {
        if (!enabled) return Collections.emptyList();
        
        Instant endTime = Instant.now();
        Instant startTime = endTime.minus(timeRange);
        
        return buffer.query(startTime, endTime);
    }
    
    /**
     * 查询特定锁的指标
     */
    public List<LockMetricRecord> queryLockMetrics(String lockName, Duration timeRange) {
        List<LockMetricRecord> allMetrics = queryMetrics(timeRange);
        return allMetrics.stream()
            .filter(record -> lockName.equals(record.lockName))
            .collect(Collectors.toList());
    }
    
    /**
     * 获取指标统计信息
     */
    public Metrics</tool_call>() {
        return new MetricsImpl();
    }
    
    /**
     * 清理过期指标
     */
    public void cleanup() {
        buffer.cleanup();
        logger.info("Metrics</tool_call> cleaned up");
    }
    
    /**
     * 关闭收集器
     */
    public void shutdown() {
        scheduledExecutor.shutdown();
        try {
            if (!scheduledExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduledExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduledExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        cleanup();
        logger.info("LockMetricsCollector shutdown completed");
    }
    
    // 私有方法
    
    private void cleanupExpiredMetrics() {
        try {
            buffer.cleanup();
</tool_call>() currentStats = getStatistics();
            logger.debug("Metrics buffer cleanup completed. Current size: {}, Total collected: {}", 
                        buffer.getCurrentSize(), currentStats.totalMetrics);
        } catch (Exception e) {
            logger.error("Error during metrics cleanup", e);
        }
    }
    
    private void updateStatistics() {
        try {
            Metrics currentStats = getStatistics();
            logger.debug("Metrics collection stats - Total: {}, Errors: {}, Buffer size: {}",
                        currentStats.totalMetrics, currentStats.totalErrors, buffer.getCurrentSize());
        } catch (Exception e) {
            logger.error("Error updating statistics", e);
        }
    }
    
    private void recordMicrometerMetrics(LockMetricRecord record) {
        if (meterRegistry == null) return;
        
        // 定义标签
        Tags tags = Tags.of(
            "lock_name", record.lockName,
            "operation", record.operation,
            "status", record.success ? "success" : "failure"
        );
        
        // 记录操作计时
        Timer.Sample sample = Timer.start(meterRegistry);
        sample.stop(Timer.builder("lock.metrics.collection.timer")
            .description("Time to collect lock metrics")
            .tags(tags)
            .register(meterRegistry));
        
        // 记录指标计数
        Counter.builder("lock.metrics.collected.counter")
            .description("Number of metrics collected")
            .tags(tags)
            .register(meterRegistry)
            .increment();
        
        // 记录操作持续时间
        Timer.builder("lock.operation.duration.timer")
            .description("Lock operation duration")
            .tags(tags)
            .register(meterRegistry)
            .record(record.duration.toMillis(), TimeUnit.MILLISECONDS);
        
        // 如果操作失败，记录错误指标
        if (!record.success) {
            Counter.builder("lock.metrics.error.counter")
                .description("Number of metric collection errors")
                .tags(tags)
                .register(meterRegistry)
                .increment();
        }
    }
    
    private void recordTracingSpan(LockMetricRecord record) {
        if (openTelemetry == null) return;
        
        Tracer tracer = openTelemetry.getTracer("lock-metrics-collector");
        Span span = tracer.spanBuilder("metrics.collect")
            .setAttribute("lock.name", record.lockName)
            .setAttribute("operation", record.operation)
            .setAttribute("status", record.success ? "success" : "failure")
            .setAttribute("duration.ms", record.duration.toMillis())
            .setAttribute("timestamp", record.timestamp.toEpochMilli())
            .startSpan();
        
        try {
            if (record.success) {
                span.setStatus(io.opentelemetry.api.trace.StatusCode.OK);
            } else {
                span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, "Operation failed");
            }
            
            // 添加元数据
            if (record.metadata != null) {
                record.metadata.forEach((key, value) -> {
                    if (value instanceof String) {
                        span.setAttribute("metadata." + key, (String) value);
                    } else if (value instanceof Number) {
                        span.setAttribute("metadata." + key, ((Number) value).doubleValue());
                    } else if (value instanceof Boolean) {
                        span.setAttribute("metadata." + key, (Boolean) value);
                    }
                });
            }
        } finally {
            span.end();
        }
    }
    
    // 内部类
    
    /**
     * 指标记录
     */
    public static class LockMetricRecord {
        public final Instant timestamp;
        public final String lockName;
        public final String operation;
        public final Duration duration;
        public final boolean success;
        public final Map<String, Object> metadata;
        
        public LockMetricRecord(Instant timestamp, String lockName, String operation,
                              Duration duration, boolean success, Map<String, Object> metadata) {
            this.timestamp = timestamp;
            this.lockName = lockName;
            this.operation = operation;
            this.duration = duration;
            this.success = success;
            this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        }
    }
    
    /**
     * 指标缓冲区
     */
    private static class MetricsBuffer {
        private final int maxSize;
        private final Duration retentionDuration;
        private final ReadWriteLock lock = new ReentrantReadWriteLock();
        private final List<LockMetricRecord> records = new ArrayList<>();
        
        public MetricsBuffer(int maxSize, Duration retentionDuration) {
            this.maxSize = maxSize;
            this.retentionDuration = retentionDuration;
        }
        
        public void add(LockMetricRecord record) {
            lock.writeLock().lock();
            try {
                records.add(record);
                
                // 清理过期记录
                cleanup();
                
                // 如果超过最大大小，删除最旧的记录
                if (records.size() > maxSize) {
                    records.remove(0);
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
        
        public List<LockMetricRecord> query(Instant startTime, Instant endTime) {
            lock.readLock().lock();
            try {
                return records.stream()
                    .filter(record -> !record.timestamp.isBefore(startTime) && 
                                     !record.timestamp.isAfter(endTime))
                    .collect(Collectors.toList());
            } finally {
                lock.readLock().unlock();
            }
        }
        
        public void cleanup() {
            lock.writeLock().lock();
            try {
                Instant cutoffTime = Instant.now().minus(retentionDuration);
                records.removeIf(record -> record.timestamp.isBefore(cutoffTime));
            } finally {
                lock.writeLock().unlock();
            }
        }
        
        public int getCurrentSize() {
            lock.readLock().lock();
            try {
                return records.size();
            } finally {
                lock.readLock().unlock();
            }
        }
    }
    
    /**
     * 收集器配置
     */
    private static class MetricsCollectorConfig {
        private final int bufferSize = 10000;
        private final Duration retentionDuration = Duration.ofHours(24);
        private final Duration cleanupInterval = Duration.ofMinutes(5);
        private final Duration statsInterval = Duration.ofMinutes(1);
        
        public int getBufferSize() {
            return bufferSize;
        }
        
        public Duration getRetentionDuration() {
            return retentionDuration;
        }
        
        public Duration getCleanupInterval() {
            return cleanupInterval;
        }
        
        public Duration getStatsInterval() {
            return statsInterval;
        }
    }
    
    /**
     * 统计信息
     */
    public interface Metrics {
        long getTotalMetrics();
        long getTotalErrors();
        Duration getUptime();
        int getCurrentBufferSize();
        double getErrorRate();
        Map<String, Long> getOperationCounts();
        Map<String, Long> getLockCounts();
        Map<String, Long> getStatusCounts();
    }
    
    private class MetricsImpl implements Metrics {
        @Override
        public long getTotalMetrics() {
            return totalMetricsCollected.get();
        }
        
        @Override
        public long getTotalErrors() {
            return totalErrors.get();
        }
        
        @Override
        public Duration getUptime() {
            return Duration.ofMillis(System.currentTimeMillis() - getStartTime());
        }
        
        @Override
        public int getCurrentBufferSize() {
            return buffer.getCurrentSize();
        }
        
        @Override
        public double getErrorRate() {
            long total = totalMetricsCollected.get();
            return total > 0 ? (double) totalErrors.get() / total : 0.0;
        }
        
        @Override
        public Map<String, Long> getOperationCounts() {
            List<LockMetricRecord> recentMetrics = queryMetrics(Duration.ofMinutes(10));
            return recentMetrics.stream()
                .collect(Collectors.groupingBy(record -> record.operation, 
                                             Collectors.counting()))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
        
        @Override
        public Map<String, Long> getLockCounts() {
            List<LockMetricRecord> recentMetrics = queryMetrics(Duration.ofMinutes(10));
            return recentMetrics.stream()
                .collect(Collectors.groupingBy(record -> record.lockName, 
                                             Collectors.counting()))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
        
        @Override
        public Map<String, Long> getStatusCounts() {
            List<LockMetricRecord> recentMetrics = queryMetrics(Duration.ofMinutes(10));
            return recentMetrics.stream()
                .collect(Collectors.groupingBy(record -> record.success ? "success" : "failure", 
                                             Collectors.counting()))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
        
        private long getStartTime() {
            // 这里可以返回收集器启动时间
            return System.currentTimeMillis() - Duration.ofMinutes(10).toMillis();
        }
    }
}