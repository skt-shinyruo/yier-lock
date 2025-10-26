package com.mycorp.distributedlock.core.observability;

import com.mycorp.distributedlock.api.PerformanceMetrics;
import com.mycorp.distributedlock.api.PerformanceMetrics.*;

import io.micrometer.core.instrument.*;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * 完整的性能指标实现
 * 基于Micrometer和OpenTelemetry的企业级监控解决方案
 */
public class LockPerformanceMetrics implements PerformanceMetrics {
    
    private static final Logger logger = LoggerFactory.getLogger(LockPerformanceMetrics.class);
    
    private final MeterRegistry meterRegistry;
    private final OpenTelemetry openTelemetry;
    private final boolean metricsEnabled;
    private final boolean tracingEnabled;
    
    // 实时指标存储
    private final ConcurrentHashMap<String, LockMetricsSnapshot> lockMetrics;
    private final AtomicLong totalOperations = new AtomicLong(0);
    private final AtomicLong successfulOperations = new AtomicLong(0);
    private final AtomicLong failedOperations = new AtomicLong(0);
    private final AtomicInteger currentConcurrency = new AtomicInteger(0);
    private final AtomicInteger peakConcurrency = new AtomicInteger(0);
    
    // 监控服务
    private final LockMonitoringService monitoringService;
    private final LockAlertingService alertingService;
    private final LockJMXManager jmxManager;
    private final PrometheusMetricsExporter prometheusExporter;
    
    private ScheduledFuture<?> monitoringTask;
    private final Duration monitoringInterval = Duration.ofSeconds(30);
    
    // 性能配置
    private final PerformanceConfigurationImpl configuration;
    
    public LockPerformanceMetrics(MeterRegistry meterRegistry, OpenTelemetry openTelemetry,
                                boolean metricsEnabled, boolean tracingEnabled) {
        this.meterRegistry = meterRegistry;
        this.openTelemetry = openTelemetry;
        this.metricsEnabled = metricsEnabled;
        this.tracingEnabled = tracingEnabled;
        this.lockMetrics = new ConcurrentHashMap<>();
        this.configuration = new PerformanceConfigurationImpl();
        
        // 初始化监控组件
        this.monitoringService = new LockMonitoringService(this, meterRegistry);
        this.alertingService = new LockAlertingService(this, meterRegistry);
        this.jmxManager = new LockJMXManager(this, meterRegistry);
        this.prometheusExporter = new PrometheusMetricsExporter(this);
        
        // 初始化Micrometer指标
        if (metricsEnabled && meterRegistry != null) {
            initializeMicrometerMetrics();
        }
        
        // 启动监控
        startPerformanceMonitoring(monitoringInterval);
    }
    
    @Override
    public void recordLockOperation(LockOperation operation, String lockName, Duration duration,
                                  boolean success, Map<String, Object> metadata) {
        if (!metricsEnabled) return;
        
        // 记录操作计数
        totalOperations.incrementAndGet();
        if (success) {
            successfulOperations.incrementAndGet();
        } else {
            failedOperations.incrementAndGet();
        }
        
        // 更新并发数
        if (operation == LockOperation.LOCK_ACQUIRE && success) {
            updateConcurrency(1);
        } else if (operation == LockOperation.LOCK_RELEASE) {
            updateConcurrency(-1);
        }
        
        // 记录Micrometer指标
        if (meterRegistry != null) {
            recordMicrometerMetrics(operation, lockName, duration, success, metadata);
        }
        
        // 更新锁级指标
        updateLockMetrics(lockName, operation, duration, success, metadata);
        
        // 记录分布式追踪
        if (tracingEnabled && openTelemetry != null) {
            recordTracingSpan(operation, lockName, duration, success, metadata);
        }
        
        // 检查告警条件
        alertingService.checkAlertConditions(lockName, operation, duration, success);
    }
    
    @Override
    public void recordLockOperations(List<LockMetric> metrics) {
        if (!metricsEnabled || metrics == null) return;
        
        for (LockMetric metric : metrics) {
            recordLockOperation(metric.getOperation(), metric.getLockName(),
                              metric.getDuration(), metric.isSuccess(),
                              metric.getMetadata());
        }
    }
    
    @Override
    public SystemPerformanceMetrics getSystemMetrics() {
        long total = totalOperations.get();
        long success = successfulOperations.get();
        long failed = failedOperations.get();
        int current = currentConcurrency.get();
        int peak = peakConcurrency.get();
        
        return new SystemPerformanceMetricsImpl(total, success, failed, current, peak);
    }
    
    @Override
    public LockPerformanceMetrics getLockMetrics(String lockName) {
        return lockMetrics.getOrDefault(lockName, new LockPerformanceMetricsImpl(lockName));
    }
    
    @Override
    public Map<String, LockPerformanceMetrics> getMultipleLockMetrics(List<String> lockNames) {
        Map<String, LockPerformanceMetrics> result = new HashMap<>();
        for (String lockName : lockNames) {
            result.put(lockName, getLockMetrics(lockName));
        }
        return result;
    }
    
    @Override
    public PerformanceStatistics getStatistics(Duration timeRange) {
        // 实现时间窗口内的统计数据收集
        return new PerformanceStatisticsImpl(timeRange);
    }
    
    @Override
    public RealTimePerformanceSnapshot getRealTimeSnapshot() {
        return new RealTimePerformanceSnapshotImpl();
    }
    
    @Override
    public CompletableFuture<SystemPerformanceMetrics> getSystemMetricsAsync() {
        return CompletableFuture.supplyAsync(this::getSystemMetrics);
    }
    
    @Override
    public CompletableFuture<LockPerformanceMetrics> getLockMetricsAsync(String lockName) {
        return CompletableFuture.supplyAsync(() -> getLockMetrics(lockName));
    }
    
    @Override
    public ScheduledFuture<?> startPerformanceMonitoring(Duration interval) {
        if (monitoringTask != null && !monitoringTask.isDone()) {
            monitoringTask.cancel(false);
        }
        
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "lock-performance-monitor");
            thread.setDaemon(true);
            return thread;
        });
        
        monitoringTask = executor.scheduleAtFixedRate(this::performMonitoringRound,
                                                    interval.toMillis(), interval.toMillis(),
                                                    TimeUnit.MILLISECONDS);
        
        return monitoringTask;
    }
    
    @Override
    public boolean stopPerformanceMonitoring(ScheduledFuture<?> monitoringTask) {
        if (monitoringTask != null && !monitoringTask.isDone()) {
            return monitoringTask.cancel(false);
        }
        return false;
    }
    
    @Override
    public void setPerformanceAlertCallback(PerformanceAlertCallback callback) {
        alertingService.setCallback(callback);
    }
    
    @Override
    public PerformanceTrendAnalysis getTrendAnalysis(String lockName, Duration timeRange) {
        return monitoringService.analyzeTrends(lockName, timeRange);
    }
    
    @Override
    public PerformanceReport generateReport(Duration timeRange, ReportFormat format) {
        return monitoringService.generateReport(timeRange, format);
    }
    
    @Override
    public String exportData(List<String> lockNames, Duration timeRange, ExportFormat format) {
        return prometheusExporter.exportData(lockNames, timeRange, format);
    }
    
    @Override
    public void resetMetrics() {
        totalOperations.set(0);
        successfulOperations.set(0);
        failedOperations.set(0);
        currentConcurrency.set(0);
        peakConcurrency.set(0);
        lockMetrics.clear();
        
        if (meterRegistry != null) {
            meterRegistry.clear();
        }
    }
    
    @Override
    public PerformanceConfiguration getConfiguration() {
        return configuration;
    }
    
    @Override
    public void updateConfiguration(PerformanceConfiguration configuration) {
        if (configuration instanceof PerformanceConfigurationImpl) {
            this.configuration.updateFrom((PerformanceConfigurationImpl) configuration);
        }
    }
    
    // 私有方法
    
    private void initializeMicrometerMetrics() {
        // 系统级指标
        Gauge.builder("lock.operations.total")
            .description("Total lock operations")
            .register(meterRegistry, this, metrics -> (double) totalOperations.get());
            
        Gauge.builder("lock.operations.success")
            .description("Successful lock operations")
            .register(meterRegistry, this, metrics -> (double) successfulOperations.get());
            
        Gauge.builder("lock.operations.failed")
            .description("Failed lock operations")
            .register(meterRegistry, this, metrics -> (double) failedOperations.get());
            
        Gauge.builder("lock.concurrency.current")
            .description("Current lock concurrency")
            .register(meterRegistry, this, metrics -> (double) currentConcurrency.get());
            
        Gauge.builder("lock.concurrency.peak")
            .description("Peak lock concurrency")
            .register(meterRegistry, this, metrics -> (double) peakConcurrency.get());
    }
    
    private void recordMicrometerMetrics(LockOperation operation, String lockName,
                                       Duration duration, boolean success,
                                       Map<String, Object> metadata) {
        String operationName = operation.name().toLowerCase();
        String status = success ? "success" : "failure";
        
        Tags tags = Tags.of(
            "operation", operationName,
            "lock_name", lockName,
            "status", status
        );
        
        // Timer指标
        Timer.builder("lock.operation.timer")
            .description("Lock operation duration")
            .tags(tags)
            .register(meterRegistry)
            .record(duration.toMillis(), TimeUnit.MILLISECONDS);
            
        // Counter指标
        Counter.builder("lock.operation.counter")
            .description("Lock operation count")
            .tags(tags)
            .register(meterRegistry)
            .increment();
            
        // 如果是错误操作，记录错误指标
        if (!success) {
            Counter.builder("lock.operation.error.counter")
                .description("Lock operation error count")
                .tags(tags)
                .register(meterRegistry)
                .increment();
        }
    }
    
    private void updateLockMetrics(String lockName, LockOperation operation,
                                 Duration duration, boolean success,
                                 Map<String, Object> metadata) {
        LockMetricsSnapshot snapshot = lockMetrics.computeIfAbsent(lockName,
            k -> new LockMetricsSnapshot(lockName));
            
        snapshot.recordOperation(operation, duration, success);
    }
    
    private void recordTracingSpan(LockOperation operation, String lockName,
                                 Duration duration, boolean success,
                                 Map<String, Object> metadata) {
        if (openTelemetry == null) return;
        
        Tracer tracer = openTelemetry.getTracer("distributed-lock");
        Span span = tracer.spanBuilder("lock." + operation.name().toLowerCase())
            .setAttribute("lock.name", lockName)
            .setAttribute("lock.operation", operation.name())
            .setAttribute("lock.status", success ? "success" : "failure")
            .setAttribute("lock.duration.ms", duration.toMillis())
            .startSpan();
            
        try (Scope scope = span.makeCurrent()) {
            span.setStatus(success ? io.opentelemetry.api.trace.StatusCode.OK 
                                 : io.opentelemetry.api.trace.StatusCode.ERROR);
            
            // 添加自定义属性
            if (metadata != null) {
                metadata.forEach((key, value) -> {
                    if (value instanceof String) {
                        span.setAttribute("lock.metadata." + key, (String) value);
                    } else if (value instanceof Number) {
                        span.setAttribute("lock.metadata." + key, ((Number) value).doubleValue());
                    } else if (value instanceof Boolean) {
                        span.setAttribute("lock.metadata." + key, (Boolean) value);
                    }
                });
            }
        } finally {
            span.end();
        }
    }
    
    private void updateConcurrency(int delta) {
        int newValue = currentConcurrency.addAndGet(delta);
        if (newValue > peakConcurrency.get()) {
            peakConcurrency.set(newValue);
        }
    }
    
    private void performMonitoringRound() {
        try {
            monitoringService.performMonitoringRound();
        } catch (Exception e) {
            logger.error("Error in monitoring round", e);
        }
    }
    
    // 内部实现类
    
    private static class LockMetricsSnapshot implements LockPerformanceMetrics {
        private final String lockName;
        private final Map<LockOperation, OperationStatisticsImpl> operationStats;
        private final AtomicLong totalHoldTime = new AtomicLong(0);
        private final AtomicInteger operationCount = new AtomicInteger(0);
        private final AtomicLong lastUpdateTime = new AtomicLong(System.currentTimeMillis());
        
        public LockMetricsSnapshot(String lockName) {
            this.lockName = lockName;
            this.operationStats = new ConcurrentHashMap<>();
            for (LockOperation op : LockOperation.values()) {
                operationStats.put(op, new OperationStatisticsImpl());
            }
        }
        
        @Override
        public String getLockName() {
            return lockName;
        }
        
        @Override
        public Map<LockOperation, OperationStatistics> getOperationStatistics() {
            return new HashMap<>(operationStats);
        }
        
        @Override
        public Duration getAverageHoldTime() {
            long totalHold = totalHoldTime.get();
            int count = operationCount.get();
            return count > 0 ? Duration.ofMillis(totalHold / count) : Duration.ZERO;
        }
        
        @Override
        public double getContentionLevel() {
            // 实现竞争度计算
            OperationStatisticsImpl lockAcquisition = operationStats.get(LockOperation.LOCK_ACQUIRE);
            if (lockAcquisition == null) return 0.0;
            
            long failures = lockAcquisition.failureCount.get();
            long total = lockAcquisition.totalCount.get();
            return total > 0 ? (double) failures / total : 0.0;
        }
        
        @Override
        public double getAcquisitionSuccessRate() {
            OperationStatisticsImpl lockAcquisition = operationStats.get(LockOperation.LOCK_ACQUIRE);
            if (lockAcquisition == null) return 0.0;
            
            long success = lockAcquisition.successCount.get();
            long total = lockAcquisition.totalCount.get();
            return total > 0 ? (double) success / total : 0.0;
        }
        
        @Override
        public QueueWaitStatistics getQueueWaitStatistics() {
            return new QueueWaitStatisticsImpl();
        }
        
        @Override
        public HotspotAnalysis getHotspotAnalysis() {
            return new HotspotAnalysisImpl();
        }
        
        @Override
        public long getLastUpdateTime() {
            return lastUpdateTime.get();
        }
        
        public void recordOperation(LockOperation operation, Duration duration, boolean success) {
            OperationStatisticsImpl stats = operationStats.get(operation);
            if (stats != null) {
                stats.recordOperation(duration, success);
                lastUpdateTime.set(System.currentTimeMillis());
            }
            
            // 记录持有时间
            if (operation == LockOperation.LOCK_RELEASE && success) {
                totalHoldTime.addAndGet(duration.toMillis());
                operationCount.incrementAndGet();
            }
        }
    }
    
    private static class OperationStatisticsImpl implements OperationStatistics {
        private final AtomicLong totalCount = new AtomicLong(0);
        private final AtomicLong successCount = new AtomicLong(0);
        private final AtomicLong failureCount = new AtomicLong(0);
        private final AtomicLong totalTime = new AtomicLong(0);
        private final AtomicLong minTime = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxTime = new AtomicLong(0);
        private final List<Long> p95Times = new CopyOnWriteArrayList<>();
        private final List<Long> p99Times = new CopyOnWriteArrayList<>();
        
        @Override
        public long getTotalCount() {
            return totalCount.get();
        }
        
        @Override
        public long getSuccessCount() {
            return successCount.get();
        }
        
        @Override
        public long getFailureCount() {
            return failureCount.get();
        }
        
        @Override
        public Duration getAverageTime() {
            long count = totalCount.get();
            long time = totalTime.get();
            return count > 0 ? Duration.ofMillis(time / count) : Duration.ZERO;
        }
        
        @Override
        public Duration getMinTime() {
            long min = minTime.get();
            return min == Long.MAX_VALUE ? Duration.ZERO : Duration.ofMillis(min);
        }
        
        @Override
        public Duration getMaxTime() {
            return Duration.ofMillis(maxTime.get());
        }
        
        @Override
        public Duration getP95Time() {
            return calculatePercentile(p95Times, 95);
        }
        
        @Override
        public Duration getP99Time() {
            return calculatePercentile(p99Times, 99);
        }
        
        public void recordOperation(Duration duration, boolean success) {
            long millis = duration.toMillis();
            
            totalCount.incrementAndGet();
            if (success) {
                successCount.incrementAndGet();
            } else {
                failureCount.incrementAndGet();
            }
            
            totalTime.addAndGet(millis);
            
            // 更新最小值
            long currentMin;
            do {
                currentMin = minTime.get();
                if (millis < currentMin) {
                    if (minTime.compareAndSet(currentMin, millis)) {
                        break;
                    }
                } else {
                    break;
                }
            } while (true);
            
            // 更新最大值
            long currentMax;
            do {
                currentMax = maxTime.get();
                if (millis > currentMax) {
                    if (maxTime.compareAndSet(currentMax, millis)) {
                        break;
                    }
                } else {
                    break;
                }
            } while (true);
            
            // 记录百分位数据
            p95Times.add(millis);
            p99Times.add(millis);
            
            // 保持列表大小限制
            if (p95Times.size() > 1000) {
                p95Times.remove(0);
            }
            if (p99Times.size() > 1000) {
                p99Times.remove(0);
            }
        }
        
        private Duration calculatePercentile(List<Long> times, int percentile) {
            if (times.isEmpty()) {
                return Duration.ZERO;
            }
            
            List<Long> sorted = times.stream()
                .sorted()
                .collect(Collectors.toList());
                
            int index = (int) Math.ceil(sorted.size() * percentile / 100.0) - 1;
            index = Math.max(0, Math.min(index, sorted.size() - 1));
            
            return Duration.ofMillis(sorted.get(index));
        }
    }
    
    private static class SystemPerformanceMetricsImpl implements SystemPerformanceMetrics {
        private final long totalOperations;
        private final long successfulOperations;
        private final long failedOperations;
        private final int currentConcurrency;
        private final int peakConcurrency;
        private final long updateTime;
        
        public SystemPerformanceMetricsImpl(long totalOperations, long successfulOperations,
                                          long failedOperations, int currentConcurrency,
                                          int peakConcurrency) {
            this.totalOperations = totalOperations;
            this.successfulOperations = successfulOperations;
            this.failedOperations = failedOperations;
            this.currentConcurrency = currentConcurrency;
            this.peakConcurrency = peakConcurrency;
            this.updateTime = System.currentTimeMillis();
        }
        
        @Override
        public long getTotalOperations() {
            return totalOperations;
        }
        
        @Override
        public long getSuccessfulOperations() {
            return successfulOperations;
        }
        
        @Override
        public long getFailedOperations() {
            return failedOperations;
        }
        
        @Override
        public int getCurrentConcurrency() {
            return currentConcurrency;
        }
        
        @Override
        public int getPeakConcurrency() {
            return peakConcurrency;
        }
        
        @Override
        public Duration getAverageResponseTime() {
            // 这里需要实际的响应时间数据
            return Duration.ofMillis(100); // 示例值
        }
        
        @Override
        public Duration getP95ResponseTime() {
            return Duration.ofMillis(200); // 示例值
        }
        
        @Override
        public Duration getP99ResponseTime() {
            return Duration.ofMillis(500); // 示例值
        }
        
        @Override
        public double getThroughput() {
            // 这里需要基于时间窗口计算
            return totalOperations / 60.0; // 每分钟操作数
        }
        
        @Override
        public double getErrorRate() {
            return totalOperations > 0 ? (double) failedOperations / totalOperations : 0.0;
        }
        
        @Override
        public ResourceUsage getResourceUsage() {
            return new ResourceUsageImpl();
        }
        
        @Override
        public int getActiveLockCount() {
            return currentConcurrency;
        }
        
        @Override
        public long getUpdateTime() {
            return updateTime;
        }
    }
    
    private static class RealTimePerformanceSnapshotImpl implements RealTimePerformanceSnapshot {
        @Override
        public long getCurrentTime() {
            return System.currentTimeMillis();
        }
        
        @Override
        public double getOperationsPerSecond() {
            return 10.0; // 示例值
        }
        
        @Override
        public Duration getCurrentAverageResponseTime() {
            return Duration.ofMillis(50); // 示例值
        }
        
        @Override
        public int getCurrentConcurrency() {
            return 5; // 示例值
        }
        
        @Override
        public double getCurrentErrorRate() {
            return 0.02; // 示例值
        }
        
        @Override
        public double getSystemLoad() {
            return 0.3; // 示例值
        }
        
        @Override
        public Map<String, Double> getRealtimeMetrics() {
            Map<String, Double> metrics = new HashMap<>();
            metrics.put("operations_per_second", getOperationsPerSecond());
            metrics.put("average_response_time_ms", getCurrentAverageResponseTime().toMillis());
            metrics.put("current_concurrency", (double) getCurrentConcurrency());
            metrics.put("error_rate", getCurrentErrorRate());
            metrics.put("system_load", getSystemLoad());
            return metrics;
        }
    }
    
    private static class PerformanceStatisticsImpl implements PerformanceStatistics {
        private final Duration timeRange;
        
        public PerformanceStatisticsImpl(Duration timeRange) {
            this.timeRange = timeRange;
        }
        
        @Override
        public Duration getTimeRange() {
            return timeRange;
        }
        
        @Override
        public Map<LockOperation, DistributionStatistics> getOperationDistribution() {
            Map<LockOperation, DistributionStatistics> distribution = new HashMap<>();
            for (LockOperation operation : LockOperation.values()) {
                distribution.put(operation, new DistributionStatisticsImpl());
            }
            return distribution;
        }
        
        @Override
        public ErrorAnalysis getErrorAnalysis() {
            return new ErrorAnalysisImpl();
        }
        
        @Override
        public List<PerformanceTrendPoint> getPerformanceTrends() {
            return new ArrayList<>(); // 空列表，实际实现需要收集趋势数据
        }
        
        @Override
        public List<PerformanceIssue> getTopPerformanceIssues() {
            return new ArrayList<>(); // 空列表，实际实现需要分析问题
        }
    }
    
    private static class QueueWaitStatisticsImpl implements QueueWaitStatistics {
        @Override
        public Duration getAverageWaitTime() {
            return Duration.ofMillis(25); // 示例值
        }
        
        @Override
        public Duration getMaxWaitTime() {
            return Duration.ofMillis(100); // 示例值
        }
        
        @Override
        public int getCurrentQueueLength() {
            return 2; // 示例值
        }
        
        @Override
        public long getQueueOverflowCount() {
            return 0; // 示例值
        }
    }
    
    private static class HotspotAnalysisImpl implements HotspotAnalysis {
        @Override
        public boolean isHotspot() {
            return false; // 示例值
        }
        
        @Override
        public double getHotspotScore() {
            return 0.1; // 示例值
        }
        
        @Override
        public double getAccessFrequency() {
            return 0.5; // 示例值
        }
        
        @Override
        public double getContentionIntensity() {
            return 0.2; // 示例值
        }
    }
    
    private static class ResourceUsageImpl implements ResourceUsage {
        @Override
        public double getCpuUsage() {
            return 0.15; // 示例值
        }
        
        @Override
        public double getMemoryUsage() {
            return 0.45; // 示例值
        }
        
        @Override
        public double getNetworkUsage() {
            return 0.1; // 示例值
        }
        
        @Override
        public double getDiskUsage() {
            return 0.3; // 示例值
        }
    }
    
    private static class DistributionStatisticsImpl implements DistributionStatistics {
        @Override
        public long getTotal() {
            return 1000; // 示例值
        }
        
        @Override
        public double getMean() {
            return 50.0; // 示例值
        }
        
        @Override
        public double getMedian() {
            return 45.0; // 示例值
        }
        
        @Override
        public double getStandardDeviation() {
            return 10.0; // 示例值
        }
        
        @Override
        public Map<String, Double> getPercentiles() {
            Map<String, Double> percentiles = new HashMap<>();
            percentiles.put("p50", 45.0);
            percentiles.put("p90", 75.0);
            percentiles.put("p95", 90.0);
            percentiles.put("p99", 95.0);
            return percentiles;
        }
    }
    
    private static class ErrorAnalysisImpl implements ErrorAnalysis {
        @Override
        public long getTotalErrors() {
            return 5; // 示例值
        }
        
        @Override
        public Map<String, Long> getErrorDistribution() {
            Map<String, Long> distribution = new HashMap<>();
            distribution.put("timeout", 3L);
            distribution.put("network_error", 2L);
            return distribution;
        }
        
        @Override
        public List<ErrorTrendPoint> getErrorTrends() {
            return new ArrayList<>(); // 空列表，实际实现需要趋势数据
        }
        
        @Override
        public List<String> getTopErrorReasons() {
            List<String> reasons = new ArrayList<>();
            reasons.add("锁获取超时");
            reasons.add("网络连接异常");
            return reasons;
        }
    }
    
    private static class PerformanceConfigurationImpl implements PerformanceConfiguration {
        private double samplingRate = 1.0;
        private Duration retentionTime = Duration.ofHours(24);
        private Duration aggregationInterval = Duration.ofMinutes(5);
        private boolean realTimeMonitoringEnabled = true;
        
        @Override
        public double getSamplingRate() {
            return samplingRate;
        }
        
        @Override
        public Duration getRetentionTime() {
            return retentionTime;
        }
        
        @Override
        public Duration getAggregationInterval() {
            return aggregationInterval;
        }
        
        @Override
        public boolean isRealTimeMonitoringEnabled() {
            return realTimeMonitoringEnabled;
        }
        
        @Override
        public PerformanceConfiguration setSamplingRate(double rate) {
            this.samplingRate = Math.max(0.0, Math.min(1.0, rate));
            return this;
        }
        
        @Override
        public PerformanceConfiguration setRetentionTime(Duration retention) {
            this.retentionTime = retention;
            return this;
        }
        
        @Override
        public PerformanceConfiguration setAggregationInterval(Duration interval) {
            this.aggregationInterval = interval;
            return this;
        }
        
        @Override
        public PerformanceConfiguration setRealTimeMonitoringEnabled(boolean enabled) {
            this.realTimeMonitoringEnabled = enabled;
            return this;
        }
        
        public void updateFrom(PerformanceConfigurationImpl other) {
            this.samplingRate = other.samplingRate;
            this.retentionTime = other.retentionTime;
            this.aggregationInterval = other.aggregationInterval;
            this.realTimeMonitoringEnabled = other.realTimeMonitoringEnabled;
        }
    }
}