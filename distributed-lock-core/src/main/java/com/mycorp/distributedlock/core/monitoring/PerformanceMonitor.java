package com.mycorp.distributedlock.core.monitoring;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.core.event.LockEventManager;
import io.micrometer.core.instrument.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 性能监控器 - 简化版本
 * 
 * 功能：
 * - 实时性能指标收集
 * - 锁操作性能分析
 * - 系统性能监控
 * - 性能告警
 */
public class PerformanceMonitor {
    
    private static final Logger logger = LoggerFactory.getLogger(PerformanceMonitor.class);
    
    private final MeterRegistry meterRegistry;
    private final LockEventManager eventManager;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final ScheduledExecutorService monitoringExecutor;
    private final ExecutorService metricsProcessingExecutor;
    
    // 监控数据
    private final Map<String, LockPerformanceMetrics> lockMetrics = new ConcurrentHashMap<>();
    private final Deque<PerformanceSnapshot> performanceHistory = new ArrayDeque<>();
    private final Map<String, Alert> activeAlerts = new ConcurrentHashMap<>();
    private final Set<PerformanceAlertListener> alertListeners = new CopyOnWriteArraySet<>();
    
    // 性能指标
    private final Counter totalLockAcquisitions;
    private final Counter totalLockReleases;
    private final Counter totalLockAcquisitionFailures;
    private final io.micrometer.core.instrument.Timer lockAcquisitionTimer;
    private final io.micrometer.core.instrument.Timer lockHoldTimeTimer;
    private final io.micrometer.core.instrument.Timer lockContentionTimer;
    private final AtomicLong activeLocksCount = new AtomicLong(0);
    private final AtomicLong totalActiveLocksPeak = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);
    
    // 告警阈值
    private final PerformanceThresholds thresholds;
    
    public PerformanceMonitor(MeterRegistry meterRegistry) {
        this(meterRegistry, new LockEventManager());
    }
    
    public PerformanceMonitor(MeterRegistry meterRegistry, LockEventManager eventManager) {
        this.meterRegistry = meterRegistry;
        this.eventManager = eventManager;
        this.thresholds = new PerformanceThresholds();
        
        this.monitoringExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "performance-monitor");
            t.setDaemon(true);
            return t;
        });
        
        this.metricsProcessingExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(), r -> {
                Thread t = new Thread(r, "metrics-processor");
                t.setDaemon(true);
                return t;
            }
        );
        
        // 初始化Micrometer指标
        this.totalLockAcquisitions = Counter.builder("distributed.lock.acquisitions.total")
                .description("Total number of lock acquisitions")
                .register(meterRegistry);
                
        this.totalLockReleases = Counter.builder("distributed.lock.releases.total")
                .description("Total number of lock releases")
                .register(meterRegistry);
                
        this.totalLockAcquisitionFailures = Counter.builder("distributed.lock.acquisition.failures.total")
                .description("Total number of failed lock acquisitions")
                .register(meterRegistry);
                
        this.lockAcquisitionTimer = Timer.builder("distributed.lock.acquisition.duration")
                .description("Time spent acquiring locks")
                .register(meterRegistry);
                
        this.lockHoldTimeTimer = Timer.builder("distributed.lock.hold.duration")
                .description("Time locks are held")
                .register(meterRegistry);
                
        this.lockContentionTimer = Timer.builder("distributed.lock.contention.duration")
                .description("Time spent waiting for locks")
                .register(meterRegistry);
        
        logger.debug("PerformanceMonitor initialized");
    }
    
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            monitoringExecutor.scheduleAtFixedRate(this::collectMetrics,
                                                 1000, 1000, TimeUnit.MILLISECONDS);
            logger.info("Performance monitoring started");
        }
    }
    
    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            monitoringExecutor.shutdown();
            metricsProcessingExecutor.shutdown();
            logger.info("Performance monitoring stopped");
        }
    }
    
    public void recordLockAcquisition(DistributedLock lock, long durationMs, boolean success) {
        String lockName = lock.getName();
        
        totalLockAcquisitions.increment();
        if (success) {
            lockAcquisitionTimer.record(durationMs, TimeUnit.MILLISECONDS);
        } else {
            totalLockAcquisitionFailures.increment();
        }
        
        LockPerformanceMetrics metrics = lockMetrics.computeIfAbsent(lockName,
            name -> new LockPerformanceMetrics(name));
        metrics.recordAcquisition(durationMs, success);
        
        if (success) {
            long activeCount = activeLocksCount.incrementAndGet();
            long peak = totalActiveLocksPeak.get();
            if (activeCount > peak) {
                totalActiveLocksPeak.set(activeCount);
            }
        }
    }
    
    public void recordLockRelease(DistributedLock lock, long holdTimeMs) {
        String lockName = lock.getName();
        
        totalLockReleases.increment();
        lockHoldTimeTimer.record(holdTimeMs, TimeUnit.MILLISECONDS);
        
        LockPerformanceMetrics metrics = lockMetrics.get(lockName);
        if (metrics != null) {
            metrics.recordRelease(holdTimeMs);
        }
        
        activeLocksCount.decrementAndGet();
    }
    
    public void recordLockContention(DistributedLock lock, long contentionTimeMs) {
        String lockName = lock.getName();
        
        lockContentionTimer.record(contentionTimeMs, TimeUnit.MILLISECONDS);
        
        LockPerformanceMetrics metrics = lockMetrics.get(lockName);
        if (metrics != null) {
            metrics.recordContention(contentionTimeMs);
        }
    }
    
    public void recordError(String lockName, Throwable error) {
        totalErrors.incrementAndGet();
        
        LockPerformanceMetrics metrics = lockMetrics.get(lockName);
        if (metrics != null) {
            metrics.recordError(error);
        }
    }
    
    public PerformanceSnapshot getCurrentSnapshot() {
        return createPerformanceSnapshot();
    }
    
    public LockPerformanceMetrics getLockMetrics(String lockName) {
        return lockMetrics.get(lockName);
    }
    
    public PerformanceMetrics getSystemMetrics() {
        return new PerformanceMetrics(
            totalLockAcquisitions.count(),
            totalLockReleases.count(),
            totalLockAcquisitionFailures.count(),
            lockAcquisitionTimer.mean(TimeUnit.MILLISECONDS),
            lockHoldTimeTimer.mean(TimeUnit.MILLISECONDS),
            lockContentionTimer.mean(TimeUnit.MILLISECONDS),
            activeLocksCount.get(),
            totalActiveLocksPeak.get(),
            totalErrors.get(),
            getAverageThroughput(),
            getAverageLatency(),
            getErrorRate()
        );
    }
    
    private PerformanceSnapshot createPerformanceSnapshot() {
        Instant timestamp = Instant.now();
        
        return new PerformanceSnapshot(
            timestamp.toEpochMilli(),
            (long) totalLockAcquisitions.count(),
            (long) totalLockReleases.count(),
            (long) totalLockAcquisitionFailures.count(),
            lockAcquisitionTimer.mean(TimeUnit.MILLISECONDS),
            lockHoldTimeTimer.mean(TimeUnit.MILLISECONDS),
            lockContentionTimer.mean(TimeUnit.MILLISECONDS),
            activeLocksCount.get(),
            totalErrors.get(),
            lockMetrics.size()
        );
    }
    
    private double getAverageThroughput() {
        long totalAcquisitions = (long) totalLockAcquisitions.count();
        long totalReleases = (long) totalLockReleases.count();
        return Math.min(totalAcquisitions, totalReleases) / 60.0;
    }
    
    private double getAverageLatency() {
        return lockAcquisitionTimer.mean(TimeUnit.MILLISECONDS);
    }
    
    private double getErrorRate() {
        long totalOperations = (long) totalLockAcquisitions.count() + (long) totalLockAcquisitionFailures.count();
        return totalOperations > 0 ? 
            (double) totalLockAcquisitionFailures.count() / totalOperations : 0.0;
    }
    
    private void collectMetrics() {
        PerformanceSnapshot snapshot = createPerformanceSnapshot();
        performanceHistory.addLast(snapshot);
        
        if (performanceHistory.size() > 10000) {
            performanceHistory.pollFirst();
        }
    }
    
    // 内部类
    
    public static class LockPerformanceMetrics {
        private final String lockName;
        private final AtomicLong acquisitionCount = new AtomicLong(0);
        private final AtomicLong successfulAcquisitions = new AtomicLong(0);
        private final AtomicLong releaseCount = new AtomicLong(0);
        private final AtomicLong contentionCount = new AtomicLong(0);
        private final AtomicLong errorCount = new AtomicLong(0);
        
        public LockPerformanceMetrics(String lockName) {
            this.lockName = lockName;
        }
        
        public void recordAcquisition(long durationMs, boolean success) {
            acquisitionCount.incrementAndGet();
            if (success) {
                successfulAcquisitions.incrementAndGet();
            }
        }
        
        public void recordRelease(long holdTimeMs) {
            releaseCount.incrementAndGet();
        }
        
        public void recordContention(long contentionTimeMs) {
            contentionCount.incrementAndGet();
        }
        
        public void recordError(Throwable error) {
            errorCount.incrementAndGet();
        }
        
        public String getLockName() { return lockName; }
        public long getAcquisitionCount() { return acquisitionCount.get(); }
        public long getSuccessfulAcquisitions() { return successfulAcquisitions.get(); }
        public long getReleaseCount() { return releaseCount.get(); }
        public long getContentionCount() { return contentionCount.get(); }
        public long getErrorCount() { return errorCount.get(); }
    }
    
    public static class PerformanceSnapshot {
        private final long timestamp;
        private final long totalAcquisitions;
        private final long totalReleases;
        private final long totalFailures;
        private final double averageAcquisitionTime;
        private final double averageHoldTime;
        private final double averageContentionTime;
        private final long activeLocksCount;
        private final long totalErrors;
        private final int trackedLocksCount;
        
        public PerformanceSnapshot(long timestamp, long totalAcquisitions, long totalReleases,
                                 long totalFailures, double averageAcquisitionTime,
                                 double averageHoldTime, double averageContentionTime,
                                 long activeLocksCount, long totalErrors, int trackedLocksCount) {
            this.timestamp = timestamp;
            this.totalAcquisitions = totalAcquisitions;
            this.totalReleases = totalReleases;
            this.totalFailures = totalFailures;
            this.averageAcquisitionTime = averageAcquisitionTime;
            this.averageHoldTime = averageHoldTime;
            this.averageContentionTime = averageContentionTime;
            this.activeLocksCount = activeLocksCount;
            this.totalErrors = totalErrors;
            this.trackedLocksCount = trackedLocksCount;
        }
        
        public long getTimestamp() { return timestamp; }
        public long getTotalAcquisitions() { return totalAcquisitions; }
        public long getTotalReleases() { return totalReleases; }
        public long getTotalFailures() { return totalFailures; }
        public double getAverageAcquisitionTime() { return averageAcquisitionTime; }
        public long getActiveLocksCount() { return activeLocksCount; }
        public long getTotalErrors() { return totalErrors; }
    }
    
    public static class PerformanceMetrics {
        private final long totalAcquisitions;
        private final long totalReleases;
        private final long totalFailures;
        private final double averageAcquisitionTime;
        private final double averageHoldTime;
        private final double averageContentionTime;
        private final long activeLocksCount;
        private final long peakActiveLocks;
        private final long totalErrors;
        private final double averageThroughput;
        private final double averageLatency;
        private final double errorRate;
        
        public PerformanceMetrics(long totalAcquisitions, long totalReleases, long totalFailures,
                                double averageAcquisitionTime, double averageHoldTime,
                                double averageContentionTime, long activeLocksCount,
                                long peakActiveLocks, long totalErrors, double averageThroughput,
                                double averageLatency, double errorRate) {
            this.totalAcquisitions = totalAcquisitions;
            this.totalReleases = totalReleases;
            this.totalFailures = totalFailures;
            this.averageAcquisitionTime = averageAcquisitionTime;
            this.averageHoldTime = averageHoldTime;
            this.averageContentionTime = averageContentionTime;
            this.activeLocksCount = activeLocksCount;
            this.peakActiveLocks = peakActiveLocks;
            this.totalErrors = totalErrors;
            this.averageThroughput = averageThroughput;
            this.averageLatency = averageLatency;
            this.errorRate = errorRate;
        }
        
        public long getTotalAcquisitions() { return totalAcquisitions; }
        public long getTotalReleases() { return totalReleases; }
        public long getTotalFailures() { return totalFailures; }
        public double getAverageAcquisitionTime() { return averageAcquisitionTime; }
        public long getActiveLocksCount() { return activeLocksCount; }
        public long getPeakActiveLocks() { return peakActiveLocks; }
        public long getTotalErrors() { return totalErrors; }
        public double getAverageThroughput() { return averageThroughput; }
        public double getAverageLatency() { return averageLatency; }
        public double getErrorRate() { return errorRate; }
    }
    
    public static class Alert {
        private final String id;
        private final String type;
        private final String message;
        private final AlertSeverity severity;
        private final Instant timestamp;
        
        public Alert(String id, String type, String message, AlertSeverity severity, Instant timestamp) {
            this.id = id;
            this.type = type;
            this.message = message;
            this.severity = severity;
            this.timestamp = timestamp;
        }
        
        public String getId() { return id; }
        public String getType() { return type; }
        public String getMessage() { return message; }
        public AlertSeverity getSeverity() { return severity; }
        public Instant getTimestamp() { return timestamp; }
    }
    
    public enum AlertSeverity {
        INFO, WARNING, CRITICAL
    }
    
    public static class PerformanceThresholds {
        private double maxAcquisitionTimeMs = 1000.0;
        private double maxErrorRate = 0.05;
        private int maxActiveLocks = 1000;
        
        public double getMaxAcquisitionTimeMs() { return maxAcquisitionTimeMs; }
        public void setMaxAcquisitionTimeMs(double maxAcquisitionTimeMs) { 
            this.maxAcquisitionTimeMs = maxAcquisitionTimeMs; 
        }
        
        public double getMaxErrorRate() { return maxErrorRate; }
        public void setMaxErrorRate(double maxErrorRate) { 
            this.maxErrorRate = maxErrorRate; 
        }
        
        public int getMaxActiveLocks() { return maxActiveLocks; }
        public void setMaxActiveLocks(int maxActiveLocks) { 
            this.maxActiveLocks = maxActiveLocks; 
        }
    }
    
    public interface PerformanceAlertListener {
        void onAlert(Alert alert);
    }
}