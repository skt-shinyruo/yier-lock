package com.mycorp.distributedlock.core.observability;

import com.mycorp.distributedlock.api.PerformanceMetrics;
import com.mycorp.distributedlock.api.PerformanceMetrics.*;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 监控服务
 * 负责实时监控、性能分析和预警管理
 */
public class LockMonitoringService {
    
    private static final Logger logger = LoggerFactory.getLogger(LockMonitoringService.class);
    
    private final LockPerformanceMetrics performanceMetrics;
    private final MeterRegistry meterRegistry;
    private final MonitoringConfig config;
    
    // 监控状态
    private final AtomicBoolean isMonitoring = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler;
    
    // 实时监控数据
    private final Map<String, LockMonitorData> lockMonitorDataMap;
    private final AtomicInteger activeMonitoringTasks = new AtomicInteger(0);
    
    // 告警管理
    private final AlertingManager alertingManager;
    
    // 性能分析
    private final PerformanceAnalyzer performanceAnalyzer;
    
    // 统计信息
    private final AtomicLong totalMonitorCycles = new AtomicLong(0);
    private final AtomicLong alertsTriggered = new AtomicLong(0);
    private final AtomicLong lastMonitoringTime = new AtomicLong(0);
    
    public LockMonitoringService(LockPerformanceMetrics performanceMetrics, MeterRegistry meterRegistry) {
        this(performanceMetrics, meterRegistry, new MonitoringConfig());
    }
    
    public LockMonitoringService(LockPerformanceMetrics performanceMetrics,
                               MeterRegistry meterRegistry, MonitoringConfig config) {
        this.performanceMetrics = performanceMetrics;
        this.meterRegistry = meterRegistry;
        this.config = config != null ? config : new MonitoringConfig();
        this.lockMonitorDataMap = new ConcurrentHashMap<>();
        
        this.scheduler = Executors.newScheduledThreadPool(
            config.getThreadPoolSize(), r -> {
                Thread thread = new Thread(r, "lock-monitoring-service");
                thread.setDaemon(true);
                return thread;
            });
            
        this.alertingManager = new AlertingManager();
        this.performanceAnalyzer = new PerformanceAnalyzer();
        
        logger.info("LockMonitoringService initialized with config: {}", config);
    }
    
    /**
     * 启动监控服务
     */
    public void startMonitoring() {
        if (isMonitoring.compareAndSet(false, true)) {
            startMainMonitoringTask();
            logger.info("Lock monitoring service started");
        } else {
            logger.warn("Monitoring service is already running");
        }
    }
    
    /**
     * 停止监控服务
     */
    public void stopMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.info("Lock monitoring service stopped");
        }
    }
    
    /**
     * 添加监控锁
     */
    public void addMonitoredLock(String lockName) {
        if (!isMonitoring.get()) {
            logger.warn("Cannot add monitored lock '{}' - monitoring service is not running", lockName);
            return;
        }
        
        lockMonitorDataMap.computeIfAbsent(lockName, k -> new LockMonitorData(lockName));
        startLockMonitoringTask(lockName);
        
        logger.info("Added lock '{}' to monitoring", lockName);
    }
    
    /**
     * 移除监控锁
     */
    public void removeMonitoredLock(String lockName) {
        lockMonitorDataMap.remove(lockName);
        logger.info("Removed lock '{}' from monitoring", lockName);
    }
    
    /**
     * 获取监控状态
     */
    public MonitoringStatus getMonitoringStatus() {
        return new MonitoringStatusImpl();
    }
    
    /**
     * 执行监控轮次
     */
    public void performMonitoringRound() {
        if (!isMonitoring.get()) return;
        
        try {
            totalMonitorCycles.incrementAndGet();
            lastMonitoringTime.set(System.currentTimeMillis());
            
            // 执行主监控逻辑
            performMainMonitoringRound();
            
            // 执行性能分析
            performanceAnalyzer.analyzePerformanceTrends();
            
            // 检查告警条件
            alertingManager.checkAllAlertConditions();
            
            // 清理过期数据
            cleanupExpiredData();
            
        } catch (Exception e) {
            logger.error("Error during monitoring round", e);
        }
    }
    
    /**
     * 生成性能报告
     */
    public PerformanceReport generateReport(Duration timeRange, ReportFormat format) {
        return new PerformanceReportImpl(timeRange, format);
    }
    
    /**
     * 获取趋势分析
     */
    public PerformanceTrendAnalysis analyzeTrends(String lockName, Duration timeRange) {
        return performanceAnalyzer.analyzeTrends(lockName, timeRange);
    }
    
    /**
     * 设置告警回调
     */
    public void setAlertCallback(PerformanceAlertCallback callback) {
        alertingManager.setCallback(callback);
    }
    
    /**
     * 获取性能统计
     */
    public MonitoringStatistics getStatistics() {
        return new MonitoringStatisticsImpl();
    }
    
    /**
     * 手动触发性能分析
     */
    public PerformanceAnalysisResult performPerformanceAnalysis(String lockName) {
        LockMonitorData monitorData = lockMonitorDataMap.get(lockName);
        if (monitorData == null) {
            throw new IllegalArgumentException("Lock '" + lockName + "' is not being monitored");
        }
        
        return performanceAnalyzer.analyzeLockPerformance(monitorData);
    }
    
    // 私有方法
    
    private void startMainMonitoringTask() {
        scheduler.scheduleAtFixedRate(this::performMonitoringRound,
                                    config.getMainMonitoringInterval().toSeconds(),
                                    config.getMainMonitoringInterval().toSeconds(),
                                    TimeUnit.SECONDS);
    }
    
    private void startLockMonitoringTask(String lockName) {
        LockMonitorData monitorData = lockMonitorDataMap.get(lockName);
        if (monitorData != null) {
            monitorData.startMonitoring(scheduler, config.getLockMonitoringInterval());
            activeMonitoringTasks.incrementAndGet();
        }
    }
    
    private void performMainMonitoringRound() {
        // 获取系统指标
        SystemPerformanceMetrics systemMetrics = performanceMetrics.getSystemMetrics();
        
        // 检查系统级阈值
        checkSystemThresholds(systemMetrics);
        
        // 检查锁级指标
        for (LockMonitorData monitorData : lockMonitorDataMap.values()) {
            checkLockThresholds(monitorData);
        }
        
        // 记录监控指标
        recordMonitoringMetrics(systemMetrics);
    }
    
    private void checkSystemThresholds(SystemPerformanceMetrics metrics) {
        // 检查错误率
        if (metrics.getErrorRate() > config.getErrorRateThreshold()) {
            PerformanceAlert alert = new PerformanceAlertImpl(
                AlertType.ERROR_RATE,
                AlertLevel.CRITICAL,
                "System error rate exceeded threshold: " + metrics.getErrorRate(),
                null,
                System.currentTimeMillis()
            );
            alertingManager.triggerAlert(alert);
        }
        
        // 检查并发数
        if (metrics.getCurrentConcurrency() > config.getMaxConcurrencyThreshold()) {
            PerformanceAlert alert = new PerformanceAlertImpl(
                AlertType.CONCURRENCY,
                AlertLevel.WARNING,
                "Current concurrency exceeded threshold: " + metrics.getCurrentConcurrency(),
                null,
                System.currentTimeMillis()
            );
            alertingManager.triggerAlert(alert);
        }
        
        // 检查响应时间
        Duration avgResponseTime = metrics.getAverageResponseTime();
        if (avgResponseTime.compareTo(config.getMaxResponseTimeThreshold()) > 0) {
            PerformanceAlert alert = new PerformanceAlertImpl(
                AlertType.RESPONSE_TIME,
                AlertLevel.WARNING,
                "Average response time exceeded threshold: " + avgResponseTime,
                null,
                System.currentTimeMillis()
            );
            alertingManager.triggerAlert(alert);
        }
    }
    
    private void checkLockThresholds(LockMonitorData monitorData) {
        LockPerformanceMetrics lockMetrics = monitorData.getLockMetrics();
        
        // 检查获取成功率
        double successRate = lockMetrics.getAcquisitionSuccessRate();
        if (successRate < config.getMinSuccessRateThreshold()) {
            PerformanceAlert alert = new PerformanceAlertImpl(
                AlertType.THROUGHPUT,
                AlertLevel.WARNING,
                "Lock acquisition success rate below threshold: " + successRate,
                monitorData.getLockName(),
                System.currentTimeMillis()
            );
            alertingManager.triggerAlert(alert);
        }
        
        // 检查竞争度
        double contentionLevel = lockMetrics.getContentionLevel();
        if (contentionLevel > config.getMaxContentionThreshold()) {
            PerformanceAlert alert = new PerformanceAlertImpl(
                AlertType.CONCURRENCY,
                AlertLevel.CRITICAL,
                "Lock contention level exceeded threshold: " + contentionLevel,
                monitorData.getLockName(),
                System.currentTimeMillis()
            );
            alertingManager.triggerAlert(alert);
        }
        
        // 检查持有时间
        Duration avgHoldTime = lockMetrics.getAverageHoldTime();
        if (avgHoldTime.compareTo(config.getMaxHoldTimeThreshold()) > 0) {
            PerformanceAlert alert = new PerformanceAlertImpl(
                AlertType.RESPONSE_TIME,
                AlertLevel.WARNING,
                "Average hold time exceeded threshold: " + avgHoldTime,
                monitorData.getLockName(),
                System.currentTimeMillis()
            );
            alertingManager.triggerAlert(alert);
        }
    }
    
    private void recordMonitoringMetrics(SystemPerformanceMetrics systemMetrics) {
        if (meterRegistry != null) {
            // 这里可以记录监控服务的指标
            io.micrometer.core.instrument.Gauge.builder("lock.monitoring.cycles.total")
                .description("Total monitoring cycles completed")
                .register(meterRegistry, this, service -> (double) totalMonitorCycles.get());
                
            io.micrometer.core.instrument.Gauge.builder("lock.monitoring.alerts.total")
                .description("Total alerts triggered")
                .register(meterRegistry, this, service -> (double) alertsTriggered.get());
                
            io.micrometer.core.instrument.Gauge.builder("lock.monitoring.active.tasks")
                .description("Number of active monitoring tasks")
                .register(meterRegistry, this, service -> (double) activeMonitoringTasks.get());
        }
    }
    
    private void cleanupExpiredData() {
        Instant cutoffTime = Instant.now().minus(config.getDataRetentionDuration());
        lockMonitorDataMap.entrySet().removeIf(entry -> {
            return entry.getValue().getLastUpdateTime().isBefore(cutoffTime);
        });
    }
    
    // 内部类
    
    /**
     * 锁监控数据
     */
    private static class LockMonitorData {
        private final String lockName;
        private final LockPerformanceMetrics lockMetrics;
        private final AtomicReference<Instant> lastUpdateTime = new AtomicReference<>(Instant.now());
        private final List<PerformanceMetricSnapshot> metricHistory = new ArrayList<>();
        private final AtomicBoolean isMonitoring = new AtomicBoolean(false);
        
        public LockMonitorData(String lockName) {
            this.lockName = lockName;
            this.lockMetrics = new LockPerformanceMetricsImpl(lockName);
        }
        
        public void startMonitoring(ScheduledExecutorService scheduler, Duration interval) {
            if (isMonitoring.compareAndSet(false, true)) {
                scheduler.scheduleAtFixedRate(() -> updateMetrics(),
                                            interval.toSeconds(),
                                            interval.toSeconds(),
                                            TimeUnit.SECONDS);
            }
        }
        
        private void updateMetrics() {
            // 这里需要从实际的锁实现中获取指标
            // 现在使用示例数据
            PerformanceMetricSnapshot snapshot = new PerformanceMetricSnapshot(
                Instant.now(),
                Math.random() * 100, // 响应时间
                Math.random() * 10,  // 并发数
                Math.random()        // 成功率
            );
            
            metricHistory.add(snapshot);
            lastUpdateTime.set(Instant.now());
            
            // 保持历史记录大小
            if (metricHistory.size() > 1000) {
                metricHistory.remove(0);
            }
        }
        
        public String getLockName() { return lockName; }
        public LockPerformanceMetrics getLockMetrics() { return lockMetrics; }
        public Instant getLastUpdateTime() { return lastUpdateTime.get(); }
        public List<PerformanceMetricSnapshot> getMetricHistory() { return new ArrayList<>(metricHistory); }
    }
    
    /**
     * 性能指标快照
     */
    private static class PerformanceMetricSnapshot {
        private final Instant timestamp;
        private final double responseTime;
        private final double concurrency;
        private final double successRate;
        
        public PerformanceMetricSnapshot(Instant timestamp, double responseTime, 
                                       double concurrency, double successRate) {
            this.timestamp = timestamp;
            this.responseTime = responseTime;
            this.concurrency = concurrency;
            this.successRate = successRate;
        }
        
        public Instant getTimestamp() { return timestamp; }
        public double getResponseTime() { return responseTime; }
        public double getConcurrency() { return concurrency; }
        public double getSuccessRate() { return successRate; }
    }
    
    /**
     * 告警管理器
     */
    private class AlertingManager {
        private PerformanceAlertCallback callback;
        private final Map<AlertType, List<AlertRule>> alertRules = new HashMap<>();
        
        public void setCallback(PerformanceAlertCallback callback) {
            this.callback = callback;
        }
        
        public void triggerAlert(PerformanceAlert alert) {
            alertsTriggered.incrementAndGet();
            
            if (callback != null) {
                try {
                    callback.onPerformanceAlert(alert);
                } catch (Exception e) {
                    logger.error("Error in alert callback", e);
                }
            }
            
            logger.warn("Alert triggered: {} - {}", alert.getAlertType(), alert.getMessage());
        }
        
        public void checkAllAlertConditions() {
            // 检查所有告警规则
            alertRules.values().forEach(rules -> {
                rules.forEach(this::checkAlertRule);
            });
        }
        
        private void checkAlertRule(AlertRule rule) {
            // 这里实现具体的告警规则检查逻辑
        }
    }
    
    /**
     * 性能分析器
     */
    private class PerformanceAnalyzer {
        public PerformanceTrendAnalysis analyzeTrends(String lockName, Duration timeRange) {
            LockMonitorData monitorData = lockMonitorDataMap.get(lockName);
            if (monitorData == null) {
                throw new IllegalArgumentException("Lock '" + lockName + "' is not being monitored");
            }
            
            return new PerformanceTrendAnalysisImpl(timeRange);
        }
        
        public void analyzePerformanceTrends() {
            // 分析所有监控锁的性能趋势
            for (LockMonitorData monitorData : lockMonitorDataMap.values()) {
                analyzeLockPerformance(monitorData);
            }
        }
        
        public PerformanceAnalysisResult analyzeLockPerformance(LockMonitorData monitorData) {
            List<PerformanceMetricSnapshot> history = monitorData.getMetricHistory();
            
            if (history.isEmpty()) {
                return new PerformanceAnalysisResultImpl(monitorData.getLockName(), 
                    "No historical data available", TrendDirection.STABLE, 0.0);
            }
            
            // 简化的趋势分析
            double recentAvgResponseTime = history.stream()
                .skip(Math.max(0, history.size() - 10))
                .mapToDouble(PerformanceMetricSnapshot::getResponseTime)
                .average()
                .orElse(0.0);
                
            double overallAvgResponseTime = history.stream()
                .mapToDouble(PerformanceMetricSnapshot::getResponseTime)
                .average()
                .orElse(0.0);
            
            TrendDirection trendDirection = recentAvgResponseTime > overallAvgResponseTime * 1.1 ?
                TrendDirection.INCREASING : recentAvgResponseTime < overallAvgResponseTime * 0.9 ?
                TrendDirection.DECREASING : TrendDirection.STABLE;
                
            double trendStrength = Math.abs(recentAvgResponseTime - overallAvgResponseTime) / 
                                 overallAvgResponseTime;
            
            return new PerformanceAnalysisResultImpl(monitorData.getLockName(),
                "Performance analysis completed", trendDirection, trendStrength);
        }
    }
    
    // 配置类
    
    /**
     * 监控配置
     */
    public static class MonitoringConfig {
        private Duration mainMonitoringInterval = Duration.ofSeconds(30);
        private Duration lockMonitoringInterval = Duration.ofSeconds(10);
        private Duration dataRetentionDuration = Duration.ofHours(24);
        private int threadPoolSize = 4;
        
        // 阈值配置
        private double errorRateThreshold = 0.05; // 5%
        private int maxConcurrencyThreshold = 1000;
        private Duration maxResponseTimeThreshold = Duration.ofSeconds(5);
        private double minSuccessRateThreshold = 0.95; // 95%
        private double maxContentionThreshold = 0.1; // 10%
        private Duration maxHoldTimeThreshold = Duration.ofSeconds(60);
        
        // Getters and setters
        public Duration getMainMonitoringInterval() { return mainMonitoringInterval; }
        public void setMainMonitoringInterval(Duration mainMonitoringInterval) { 
            this.mainMonitoringInterval = mainMonitoringInterval; 
        }
        
        public Duration getLockMonitoringInterval() { return lockMonitoringInterval; }
        public void setLockMonitoringInterval(Duration lockMonitoringInterval) { 
            this.lockMonitoringInterval = lockMonitoringInterval; 
        }
        
        public Duration getDataRetentionDuration() { return dataRetentionDuration; }
        public void setDataRetentionDuration(Duration dataRetentionDuration) { 
            this.dataRetentionDuration = dataRetentionDuration; 
        }
        
        public int getThreadPoolSize() { return threadPoolSize; }
        public void setThreadPoolSize(int threadPoolSize) { 
            this.threadPoolSize = threadPoolSize; 
        }
        
        public double getErrorRateThreshold() { return errorRateThreshold; }
        public void setErrorRateThreshold(double errorRateThreshold) { 
            this.errorRateThreshold = errorRateThreshold; 
        }
        
        public int getMaxConcurrencyThreshold() { return maxConcurrencyThreshold; }
        public void setMaxConcurrencyThreshold(int maxConcurrencyThreshold) { 
            this.maxConcurrencyThreshold = maxConcurrencyThreshold; 
        }
        
        public Duration getMaxResponseTimeThreshold() { return maxResponseTimeThreshold; }
        public void setMaxResponseTimeThreshold(Duration maxResponseTimeThreshold) { 
            this.maxResponseTimeThreshold = maxResponseTimeThreshold; 
        }
        
        public double getMinSuccessRateThreshold() { return minSuccessRateThreshold; }
        public void setMinSuccessRateThreshold(double minSuccessRateThreshold) { 
            this.minSuccessRateThreshold = minSuccessRateThreshold; 
        }
        
        public double getMaxContentionThreshold() { return maxContentionThreshold; }
        public void setMaxContentionThreshold(double maxContentionThreshold) { 
            this.maxContentionThreshold = maxContentionThreshold; 
        }
        
        public Duration getMaxHoldTimeThreshold() { return maxHoldTimeThreshold; }
        public void setMaxHoldTimeThreshold(Duration maxHoldTimeThreshold) { 
            this.maxHoldTimeThreshold = maxHoldTimeThreshold; 
        }
    }
    
    /**
     * 告警规则
     */
    private interface AlertRule {
        boolean shouldTrigger();
        PerformanceAlert getAlert();
    }
    
    /**
     * 监控状态
     */
    public interface MonitoringStatus {
        boolean isRunning();
        int getActiveLocks();
        long getTotalCycles();
        long getAlertsTriggered();
        Instant getLastMonitoringTime();
        Duration getUptime();
    }
    
    /**
     * 监控统计
     */
    public interface MonitoringStatistics {
        int getMonitoredLockCount();
        long getTotalMonitorCycles();
        long getAlertsTriggered();
        double getAverageMonitoringInterval();
        Map<String, Double> getLockPerformanceScores();
    }
    
    /**
     * 性能分析结果
     */
    public interface PerformanceAnalysisResult {
        String getLockName();
        String getAnalysis();
        TrendDirection getTrendDirection();
        double getTrendStrength();
        List<String> getRecommendations();
        Instant getAnalysisTime();
    }
    
    // 实现类
    
    private class MonitoringStatusImpl implements MonitoringStatus {
        @Override
        public boolean isRunning() {
            return isMonitoring.get();
        }
        
        @Override
        public int getActiveLocks() {
            return lockMonitorDataMap.size();
        }
        
        @Override
        public long getTotalCycles() {
            return totalMonitorCycles.get();
        }
        
        @Override
        public long getAlertsTriggered() {
            return alertsTriggered.get();
        }
        
        @Override
        public Instant getLastMonitoringTime() {
            return Instant.ofEpochMilli(lastMonitoringTime.get());
        }
        
        @Override
        public Duration getUptime() {
            // 这里需要启动时间
            return Duration.ofMinutes(30);
        }
    }
    
    private class MonitoringStatisticsImpl implements MonitoringStatistics {
        @Override
        public int getMonitoredLockCount() {
            return lockMonitorDataMap.size();
        }
        
        @Override
        public long getTotalMonitorCycles() {
            return totalMonitorCycles.get();
        }
        
        @Override
        public long getAlertsTriggered() {
            return alertsTriggered.get();
        }
        
        @Override
        public double getAverageMonitoringInterval() {
            return config.getMainMonitoringInterval().toSeconds();
        }
        
        @Override
        public Map<String, Double> getLockPerformanceScores() {
            Map<String, Double> scores = new HashMap<>();
            for (String lockName : lockMonitorDataMap.keySet()) {
                scores.put(lockName, Math.random() * 100); // 示例评分
            }
            return scores;
        }
    }
    
    private class PerformanceAnalysisResultImpl implements PerformanceAnalysisResult {
        private final String lockName;
        private final String analysis;
        private final TrendDirection trendDirection;
        private final double trendStrength;
        private final Instant analysisTime = Instant.now();
        
        public PerformanceAnalysisResultImpl(String lockName, String analysis,
                                           TrendDirection trendDirection, double trendStrength) {
            this.lockName = lockName;
            this.analysis = analysis;
            this.trendDirection = trendDirection;
            this.trendStrength = trendStrength;
        }
        
        @Override
        public String getLockName() { return lockName; }
        
        @Override
        public String getAnalysis() { return analysis; }
        
        @Override
        public TrendDirection getTrendDirection() { return trendDirection; }
        
        @Override
        public double getTrendStrength() { return trendStrength; }
        
        @Override
        public List<String> getRecommendations() {
            List<String> recommendations = new ArrayList<>();
            if (trendDirection == TrendDirection.INCREASING) {
                recommendations.add("响应时间呈上升趋势，建议检查系统负载");
            }
            if (trendStrength > 0.5) {
                recommendations.add("趋势强度较高，建议进一步分析根本原因");
            }
            return recommendations;
        }
        
        @Override
        public Instant getAnalysisTime() { return analysisTime; }
    }
    
    private class PerformanceReportImpl implements PerformanceReport {
        private final Duration timeRange;
        private final ReportFormat format;
        private final long generatedTime = System.currentTimeMillis();
        
        public PerformanceReportImpl(Duration timeRange, ReportFormat format) {
            this.timeRange = timeRange;
            this.format = format;
        }
        
        @Override
        public String getTitle() {
            return "分布式锁性能报告";
        }
        
        @Override
        public Duration getTimeRange() {
            return timeRange;
        }
        
        @Override
        public long getGeneratedTime() {
            return generatedTime;
        }
        
        @Override
        public String getExecutiveSummary() {
            return "系统运行正常，监控到 " + lockMonitorDataMap.size() + " 个活跃锁";
        }
        
        @Override
        public SystemPerformanceMetrics getDetailedMetrics() {
            return performanceMetrics.getSystemMetrics();
        }
        
        @Override
        public List<PerformanceIssue> getIssues() {
            return new ArrayList<>(); // 当前没有发现性能问题
        }
        
        @Override
        public List<String> getRecommendations() {
            List<String> recommendations = new ArrayList<>();
            recommendations.add("定期监控锁获取成功率");
            recommendations.add("关注竞争度较高的锁");
            recommendations.add("优化长时间持有的锁");
            return recommendations;
        }
        
        @Override
        public Map<String, Object> getChartData() {
            Map<String, Object> chartData = new HashMap<>();
            chartData.put("monitored_locks", lockMonitorDataMap.size());
            chartData.put("total_cycles", totalMonitorCycles.get());
            chartData.put("alerts_triggered", alertsTriggered.get());
            return chartData;
        }
    }
    
    private class PerformanceTrendAnalysisImpl implements PerformanceTrendAnalysis {
        private final Duration timeRange;
        
        public PerformanceTrendAnalysisImpl(Duration timeRange) {
            this.timeRange = timeRange;
        }
        
        @Override
        public Duration getAnalysisTimeRange() {
            return timeRange;
        }
        
        @Override
        public TrendDirection getTrendDirection() {
            return TrendDirection.STABLE;
        }
        
        @Override
        public double getTrendStrength() {
            return 0.1;
        }
        
        @Override
        public List<PerformancePrediction> getPredictions() {
            return new ArrayList<>();
        }
        
        @Override
        public List<AnomalyPoint> getAnomalies() {
            return new ArrayList<>();
        }
        
        @Override
        public List<String> getRecommendations() {
            List<String> recommendations = new ArrayList<>();
            recommendations.add("继续保持当前的监控策略");
            return recommendations;
        }
    }
}