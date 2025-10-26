package com.mycorp.distributedlock.core.monitoring;

import com.mycorp.distributedlock.api.PerformanceMetrics;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 性能回归检测器 - 智能检测和分析分布式锁系统的性能回归
 * 
 * 主要功能：
 * 1. 性能回归检测 - 对比当前性能与历史基线
 * 2. 趋势分析 - 分析性能指标的长期趋势
 * 3. 异常检测 - 识别性能异常和突变
 * 4. 回归告警 - 当检测到性能回归时发出告警
 * 5. 基线管理 - 管理性能基线数据
 * 6. 回归报告 - 生成详细的回归分析报告
 * 7. 自动基线更新 - 动态维护性能基线
 * 8. 多维度分析 - 多角度性能回归分析
 * 
 * @author Yier Lock Team
 * @version 2.0.0
 */
public class PerformanceRegressionDetector {
    
    private static final Logger logger = LoggerFactory.getLogger(PerformanceRegressionDetector.class);
    
    // 数据存储和缓存
    private final Map<String, PerformanceBaseline> performanceBaselines;
    private final Map<String, List<PerformanceSample>> performanceHistory;
    private final Map<String, RegressionAlert> activeAlerts;
    private final AtomicReference<Map<String, PerformanceTrend>> performanceTrends;
    
    // 检测配置
    private final RegressionDetectionConfiguration detectionConfig;
    
    // 监控和分析服务
    private final PerformanceMonitor performanceMonitor;
    private final LockMetrics lockMetrics;
    private final ScheduledExecutorService monitoringExecutor;
    private final ScheduledExecutorService analysisExecutor;
    
    // 告警和通知
    private final List<RegressionAlertListener> alertListeners;
    private final AtomicBoolean isMonitoring;
    
    /**
     * 回归检测配置类
     */
    public static class RegressionDetectionConfiguration {
        private double regressionThreshold = 0.1; // 10%性能下降认为回归
        private int baselineSampleSize = 100; // 基线样本数量
        private Duration baselineWindow = Duration.ofDays(7); // 基线时间窗口
        private Duration trendAnalysisWindow = Duration.ofHours(24); // 趋势分析窗口
        private Duration sampleRetentionPeriod = Duration.ofDays(30); // 样本保留期
        private int maxAlertsPerMetric = 5; // 每个指标最大告警数
        private boolean enableAutomaticBaselineUpdate = true; // 是否自动更新基线
        private double trendSignificanceThreshold = 0.05; // 趋势显著性阈值
        private boolean enableAnomalyDetection = true; // 是否启用异常检测
        private Duration anomalyDetectionWindow = Duration.ofMinutes(15); // 异常检测窗口
        private double anomalyThreshold = 2.0; // 异常检测阈值（标准差倍数）
        private Map<String, Double> metricSpecificThresholds = new HashMap<>();
        
        // Getters and Setters
        public double getRegressionThreshold() { return regressionThreshold; }
        public void setRegressionThreshold(double regressionThreshold) { 
            this.regressionThreshold = Math.max(0.01, Math.min(1.0, regressionThreshold)); 
        }
        public int getBaselineSampleSize() { return baselineSampleSize; }
        public void setBaselineSampleSize(int baselineSampleSize) { 
            this.baselineSampleSize = Math.max(10, baselineSampleSize); 
        }
        public Duration getBaselineWindow() { return baselineWindow; }
        public void setBaselineWindow(Duration baselineWindow) { 
            this.baselineWindow = baselineWindow; 
        }
        public Duration getTrendAnalysisWindow() { return trendAnalysisWindow; }
        public void setTrendAnalysisWindow(Duration trendAnalysisWindow) { 
            this.trendAnalysisWindow = trendAnalysisWindow; 
        }
        public Duration getSampleRetentionPeriod() { return sampleRetentionPeriod; }
        public void setSampleRetentionPeriod(Duration sampleRetentionPeriod) { 
            this.sampleRetentionPeriod = sampleRetentionPeriod; 
        }
        public int getMaxAlertsPerMetric() { return maxAlertsPerMetric; }
        public void setMaxAlertsPerMetric(int maxAlertsPerMetric) { 
            this.maxAlertsPerMetric = Math.max(1, maxAlertsPerMetric); 
        }
        public boolean isEnableAutomaticBaselineUpdate() { return enableAutomaticBaselineUpdate; }
        public void setEnableAutomaticBaselineUpdate(boolean enableAutomaticBaselineUpdate) { 
            this.enableAutomaticBaselineUpdate = enableAutomaticBaselineUpdate; 
        }
        public double getTrendSignificanceThreshold() { return trendSignificanceThreshold; }
        public void setTrendSignificanceThreshold(double trendSignificanceThreshold) { 
            this.trendSignificanceThreshold = Math.max(0.001, Math.min(0.5, trendSignificanceThreshold)); 
        }
        public boolean isEnableAnomalyDetection() { return enableAnomalyDetection; }
        public void setEnableAnomalyDetection(boolean enableAnomalyDetection) { 
            this.enableAnomalyDetection = enableAnomalyDetection; 
        }
        public Duration getAnomalyDetectionWindow() { return anomalyDetectionWindow; }
        public void setAnomalyDetectionWindow(Duration anomalyDetectionWindow) { 
            this.anomalyDetectionWindow = anomalyDetectionWindow; 
        }
        public double getAnomalyThreshold() { return anomalyThreshold; }
        public void setAnomalyThreshold(double anomalyThreshold) { 
            this.anomalyThreshold = Math.max(1.0, Math.min(5.0, anomalyThreshold)); 
        }
        public Map<String, Double> getMetricSpecificThresholds() { return metricSpecificThresholds; }
        public void setMetricSpecificThresholds(Map<String, Double> metricSpecificThresholds) { 
            this.metricSpecificThresholds = metricSpecificThresholds; 
        }
        public void setMetricThreshold(String metricName, double threshold) {
            this.metricSpecificThresholds.put(metricName, threshold);
        }
    }
    
    /**
     * 性能基线类
     */
    public static class PerformanceBaseline {
        private final String metricName;
        private final double baselineValue;
        private final double standardDeviation;
        private final double confidenceInterval;
        private final int sampleCount;
        private final Instant lastUpdated;
        private final List<PerformanceSample> samples;
        private final BaselineQuality quality;
        private final Map<String, Object> metadata;
        
        public PerformanceBaseline(String metricName, double baselineValue, double standardDeviation,
                                 double confidenceInterval, int sampleCount, List<PerformanceSample> samples) {
            this.metricName = metricName;
            this.baselineValue = baselineValue;
            this.standardDeviation = standardDeviation;
            this.confidenceInterval = confidenceInterval;
            this.sampleCount = sampleCount;
            this.samples = samples;
            this.lastUpdated = Instant.now();
            this.quality = assessBaselineQuality();
            this.metadata = new HashMap<>();
        }
        
        private BaselineQuality assessBaselineQuality() {
            if (sampleCount < 30) {
                return BaselineQuality.POOR;
            } else if (sampleCount < 100) {
                return BaselineQuality.FAIR;
            } else if (standardDeviation / baselineValue < 0.1) {
                return BaselineQuality.GOOD;
            } else {
                return BaselineQuality.ACCEPTABLE;
            }
        }
        
        public PerformanceBaseline addMetadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }
        
        // Getters
        public String getMetricName() { return metricName; }
        public double getBaselineValue() { return baselineValue; }
        public double getStandardDeviation() { return standardDeviation; }
        public double getConfidenceInterval() { return confidenceInterval; }
        public int getSampleCount() { return sampleCount; }
        public Instant getLastUpdated() { return lastUpdated; }
        public List<PerformanceSample> getSamples() { return samples; }
        public BaselineQuality getQuality() { return quality; }
        public Map<String, Object> getMetadata() { return metadata; }
    }
    
    /**
     * 基线质量枚举
     */
    public enum BaselineQuality {
        POOR("质量较差，需要更多数据"),
        FAIR("质量一般，建议增加样本"),
        ACCEPTABLE("质量可接受"),
        GOOD("质量良好");
        
        private final String description;
        
        BaselineQuality(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
    
    /**
     * 性能样本类
     */
    public static class PerformanceSample {
        private final String metricName;
        private final double value;
        private final Instant timestamp;
        private final Map<String, Object> context;
        private final String source;
        
        public PerformanceSample(String metricName, double value, Instant timestamp, 
                               Map<String, Object> context, String source) {
            this.metricName = metricName;
            this.value = value;
            this.timestamp = timestamp;
            this.context = context;
            this.source = source;
        }
        
        public PerformanceSample(String metricName, double value, Instant timestamp) {
            this(metricName, value, timestamp, new HashMap<>(), "unknown");
        }
        
        // Getters
        public String getMetricName() { return metricName; }
        public double getValue() { return value; }
        public Instant getTimestamp() { return timestamp; }
        public Map<String, Object> getContext() { return context; }
        public String getSource() { return source; }
    }
    
    /**
     * 性能趋势类
     */
    public static class PerformanceTrend {
        private final String metricName;
        private final TrendDirection direction;
        private final double slope;
        private final double rSquared;
        private final double pValue;
        private final TrendSignificance significance;
        private final List<PerformanceSample> trendData;
        private final Instant lastAnalyzed;
        private final Map<String, Object> trendMetrics;
        
        public PerformanceTrend(String metricName, TrendDirection direction, double slope,
                              double rSquared, double pValue, List<PerformanceSample> trendData) {
            this.metricName = metricName;
            this.direction = direction;
            this.slope = slope;
            this.rSquared = rSquared;
            this.pValue = pValue;
            this.significance = assessTrendSignificance(pValue);
            this.trendData = trendData;
            this.lastAnalyzed = Instant.now();
            this.trendMetrics = new HashMap<>();
        }
        
        private TrendSignificance assessTrendSignificance(double pValue) {
            if (pValue < 0.001) {
                return TrendSignificance.HIGHLY_SIGNIFICANT;
            } else if (pValue < 0.01) {
                return TrendSignificance.SIGNIFICANT;
            } else if (pValue < 0.05) {
                return TrendSignificance.MARGINAL;
            } else {
                return TrendSignificance.NOT_SIGNIFICANT;
            }
        }
        
        public PerformanceTrend addTrendMetric(String key, Object value) {
            this.trendMetrics.put(key, value);
            return this;
        }
        
        // Getters
        public String getMetricName() { return metricName; }
        public TrendDirection getDirection() { return direction; }
        public double getSlope() { return slope; }
        public double getRSquared() { return rSquared; }
        public double getPValue() { return pValue; }
        public TrendSignificance getSignificance() { return significance; }
        public List<PerformanceSample> getTrendData() { return trendData; }
        public Instant getLastAnalyzed() { return lastAnalyzed; }
        public Map<String, Object> getTrendMetrics() { return trendMetrics; }
    }
    
    /**
     * 趋势方向枚举
     */
    public enum TrendDirection {
        INCREASING("上升趋势"),
        DECREASING("下降趋势"),
        STABLE("稳定趋势"),
        VOLATILE("波动趋势");
        
        private final String description;
        
        TrendDirection(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
    
    /**
     * 趋势显著性枚举
     */
    public enum TrendSignificance {
        NOT_SIGNIFICANT("不显著"),
        MARGINAL("边际显著"),
        SIGNIFICANT("显著"),
        HIGHLY_SIGNIFICANT("高度显著");
        
        private final String description;
        
        TrendSignificance(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
    
    /**
     * 回归告警类
     */
    public static class RegressionAlert {
        private final String alertId;
        private final String metricName;
        private final RegressionType type;
        private final Severity severity;
        private final String description;
        private final double currentValue;
        private final double baselineValue;
        private final double regressionPercentage;
        private final Instant detectedAt;
        private final List<String> possibleCauses;
        private final List<String> recommendedActions;
        private final AlertStatus status;
        private final Map<String, Object> alertData;
        
        public RegressionAlert(String metricName, RegressionType type, Severity severity,
                             String description, double currentValue, double baselineValue, 
                             double regressionPercentage, List<String> possibleCauses,
                             List<String> recommendedActions) {
            this.alertId = UUID.randomUUID().toString();
            this.metricName = metricName;
            this.type = type;
            this.severity = severity;
            this.description = description;
            this.currentValue = currentValue;
            this.baselineValue = baselineValue;
            this.regressionPercentage = regressionPercentage;
            this.detectedAt = Instant.now();
            this.possibleCauses = possibleCauses;
            this.recommendedActions = recommendedActions;
            this.status = AlertStatus.ACTIVE;
            this.alertData = new HashMap<>();
        }
        
        public RegressionAlert markResolved() {
            return new RegressionAlert(metricName, type, severity, description + " (已解决)", 
                                     currentValue, baselineValue, regressionPercentage,
                                     possibleCauses, recommendedActions) {
                @Override
                public AlertStatus getStatus() { return AlertStatus.RESOLVED; }
            };
        }
        
        public RegressionAlert addAlertData(String key, Object value) {
            this.alertData.put(key, value);
            return this;
        }
        
        // Getters
        public String getAlertId() { return alertId; }
        public String getMetricName() { return metricName; }
        public RegressionType getType() { return type; }
        public Severity getSeverity() { return severity; }
        public String getDescription() { return description; }
        public double getCurrentValue() { return currentValue; }
        public double getBaselineValue() { return baselineValue; }
        public double getRegressionPercentage() { return regressionPercentage; }
        public Instant getDetectedAt() { return detectedAt; }
        public List<String> getPossibleCauses() { return possibleCauses; }
        public List<String> getRecommendedActions() { return recommendedActions; }
        public AlertStatus getStatus() { return status; }
        public Map<String, Object> getAlertData() { return alertData; }
    }
    
    /**
     * 回归类型枚举
     */
    public enum RegressionType {
        PERFORMANCE_DEGRADATION("性能下降"),
        THROUGHPUT_REDUCTION("吞吐量下降"),
        LATENCY_INCREASE("延迟增加"),
        ERROR_RATE_INCREASE("错误率增加"),
        RESOURCE_USAGE_SURGE("资源使用激增"),
        ANOMALY_DETECTED("检测到异常");
        
        private final String description;
        
        RegressionType(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
    
    /**
     * 严重程度枚举
     */
    public enum Severity {
        LOW("低"),
        MEDIUM("中"),
        HIGH("高"),
        CRITICAL("严重");
        
        private final String description;
        
        Severity(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
    
    /**
     * 告警状态枚举
     */
    public enum AlertStatus {
        ACTIVE("活跃"),
        ACKNOWLEDGED("已确认"),
        RESOLVED("已解决"),
        SUPPRESSED("已抑制");
        
        private final String description;
        
        AlertStatus(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
    
    /**
     * 回归分析结果类
     */
    public static class RegressionAnalysisResult {
        private final String analysisId;
        private final String metricName;
        private final boolean hasRegression;
        private final List<RegressionAlert> alerts;
        private final PerformanceTrend trend;
        private final PerformanceBaseline baseline;
        private final AnomalyDetectionResult anomalyResult;
        private final double confidenceScore;
        private final Instant analyzedAt;
        private final List<String> insights;
        private final List<String> recommendations;
        
        public RegressionAnalysisResult(String metricName, boolean hasRegression,
                                      List<RegressionAlert> alerts, PerformanceTrend trend,
                                      PerformanceBaseline baseline, AnomalyDetectionResult anomalyResult,
                                      double confidenceScore, List<String> insights, List<String> recommendations) {
            this.analysisId = UUID.randomUUID().toString();
            this.metricName = metricName;
            this.hasRegression = hasRegression;
            this.alerts = alerts;
            this.trend = trend;
            this.baseline = baseline;
            this.anomalyResult = anomalyResult;
            this.confidenceScore = confidenceScore;
            this.analyzedAt = Instant.now();
            this.insights = insights;
            this.recommendations = recommendations;
        }
        
        // Getters
        public String getAnalysisId() { return analysisId; }
        public String getMetricName() { return metricName; }
        public boolean hasRegression() { return hasRegression; }
        public List<RegressionAlert> getAlerts() { return alerts; }
        public PerformanceTrend getTrend() { return trend; }
        public PerformanceBaseline getBaseline() { return baseline; }
        public AnomalyDetectionResult getAnomalyResult() { return anomalyResult; }
        public double getConfidenceScore() { return confidenceScore; }
        public Instant getAnalyzedAt() { return analyzedAt; }
        public List<String> getInsights() { return insights; }
        public List<String> getRecommendations() { return recommendations; }
    }
    
    /**
     * 异常检测结果类
     */
    public static class AnomalyDetectionResult {
        private final boolean hasAnomaly;
        private final double anomalyScore;
        private final List<PerformanceSample> anomalousSamples;
        private final Instant detectionTime;
        private final AnomalyType type;
        private final Map<String, Object> anomalyData;
        
        public AnomalyDetectionResult(boolean hasAnomaly, double anomalyScore, 
                                    List<PerformanceSample> anomalousSamples, AnomalyType type) {
            this.hasAnomaly = hasAnomaly;
            this.anomalyScore = anomalyScore;
            this.anomalousSamples = anomalousSamples;
            this.detectionTime = Instant.now();
            this.type = type;
            this.anomalyData = new HashMap<>();
        }
        
        public AnomalyDetectionResult addAnomalyData(String key, Object value) {
            this.anomalyData.put(key, value);
            return this;
        }
        
        // Getters
        public boolean hasAnomaly() { return hasAnomaly; }
        public double getAnomalyScore() { return anomalyScore; }
        public List<PerformanceSample> getAnomalousSamples() { return anomalousSamples; }
        public Instant getDetectionTime() { return detectionTime; }
        public AnomalyType getType() { return type; }
        public Map<String, Object> getAnomalyData() { return anomalyData; }
    }
    
    /**
     * 异常类型枚举
     */
    public enum AnomalyType {
        SPIKE("突增"),
        DROP("突降"),
        TREND_CHANGE("趋势改变"),
        VOLATILITY_INCREASE("波动性增加"),
        OUTLIER("离群值");
        
        private final String description;
        
        AnomalyType(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
    
    /**
     * 回归告警监听器接口
     */
    public interface RegressionAlertListener {
        void onRegressionDetected(RegressionAlert alert);
        void onAnomalyDetected(AnomalyDetectionResult anomaly);
        void onTrendChanged(PerformanceTrend trend);
        void onBaselineUpdated(PerformanceBaseline baseline);
    }
    
    /**
     * 构造函数
     */
    public PerformanceRegressionDetector(PerformanceMonitor performanceMonitor, 
                                       LockMetrics lockMetrics) {
        this.performanceMonitor = performanceMonitor;
        this.lockMetrics = lockMetrics;
        this.detectionConfig = new RegressionDetectionConfiguration();
        this.performanceBaselines = new ConcurrentHashMap<>();
        this.performanceHistory = new ConcurrentHashMap<>();
        this.activeAlerts = new ConcurrentHashMap<>();
        this.performanceTrends = new AtomicReference<>(new HashMap<>());
        this.alertListeners = new CopyOnWriteArrayList<>();
        this.isMonitoring = new AtomicBoolean(false);
        
        // 创建监控和分析线程池
        int corePoolSize = Runtime.getRuntime().availableProcessors();
        this.monitoringExecutor = Executors.newScheduledThreadPool(2, 
            new ThreadFactoryBuilder().setNameFormat("regression-monitor-%d").build());
        this.analysisExecutor = Executors.newScheduledThreadPool(corePoolSize / 2 + 1,
            new ThreadFactoryBuilder().setNameFormat("regression-analysis-%d").build());
        
        logger.info("性能回归检测器已初始化，CPU核心数: {}", corePoolSize);
    }
    
    /**
     * 启动性能回归检测
     */
    public void startMonitoring() {
        if (isMonitoring.get()) {
            logger.warn("性能回归检测已在运行中");
            return;
        }
        
        logger.info("启动性能回归检测");
        isMonitoring.set(true);
        
        // 定期收集性能样本
        monitoringExecutor.scheduleAtFixedRate(this::collectPerformanceSamples, 
            1, 1, TimeUnit.MINUTES);
        
        // 定期执行回归分析
        monitoringExecutor.scheduleAtFixedRate(this::performRegressionAnalysis,
            5, 5, TimeUnit.MINUTES);
        
        // 定期更新性能基线
        monitoringExecutor.scheduleAtFixedRate(this::updatePerformanceBaselines,
            30, 30, TimeUnit.MINUTES);
        
        // 定期清理过期数据
        monitoringExecutor.scheduleAtFixedRate(this::cleanupExpiredData,
            60, 60, TimeUnit.MINUTES);
        
        logger.info("性能回归检测已启动");
    }
    
    /**
     * 停止性能回归检测
     */
    public void stopMonitoring() {
        if (!isMonitoring.get()) {
            logger.warn("性能回归检测未在运行");
            return;
        }
        
        logger.info("停止性能回归检测");
        isMonitoring.set(false);
        
        monitoringExecutor.shutdown();
        analysisExecutor.shutdown();
        
        try {
            if (!monitoringExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                monitoringExecutor.shutdownNow();
            }
            
            if (!analysisExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                analysisExecutor.shutdownNow();
            }
            
        } catch (InterruptedException e) {
            monitoringExecutor.shutdownNow();
            analysisExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("性能回归检测已停止");
    }
    
    /**
     * 添加性能样本
     */
    public void addPerformanceSample(String metricName, double value, Map<String, Object> context) {
        PerformanceSample sample = new PerformanceSample(metricName, value, Instant.now(), 
            context != null ? context : new HashMap<>(), "manual");
        
        performanceHistory.computeIfAbsent(metricName, k -> new CopyOnWriteArrayList<>())
            .add(sample);
        
        logger.debug("添加性能样本: {} = {}", metricName, value);
    }
    
    /**
     * 执行性能回归分析
     */
    public List<RegressionAnalysisResult> performRegressionAnalysis() {
        if (!isMonitoring.get()) {
            logger.warn("性能回归检测未运行，跳过分析");
            return Collections.emptyList();
        }
        
        logger.info("开始执行性能回归分析");
        List<RegressionAnalysisResult> results = new ArrayList<>();
        
        try {
            // 获取所有监控的性能指标
            Set<String> metricsToAnalyze = getMetricsToAnalyze();
            
            for (String metricName : metricsToAnalyze) {
                CompletableFuture<RegressionAnalysisResult> future = 
                    CompletableFuture.supplyAsync(() -> analyzeMetricRegression(metricName), analysisExecutor);
                
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            
            logger.info("完成性能回归分析，共分析{}个指标", results.size());
            
        } catch (Exception e) {
            logger.error("执行性能回归分析时发生错误", e);
        }
        
        return results;
    }
    
    /**
     * 分析单个指标的性能回归
     */
    private RegressionAnalysisResult analyzeMetricRegression(String metricName) {
        try {
            // 1. 获取历史数据
            List<PerformanceSample> history = getMetricHistory(metricName);
            if (history.size() < 10) {
                logger.debug("指标{}数据不足，跳过分析", metricName);
                return new RegressionAnalysisResult(metricName, false, Collections.emptyList(),
                    null, null, null, 0.0, 
                    Arrays.asList("数据不足，无法进行回归分析"),
                    Collections.emptyList());
            }
            
            // 2. 获取或创建性能基线
            PerformanceBaseline baseline = getOrCreateBaseline(metricName, history);
            
            // 3. 分析性能趋势
            PerformanceTrend trend = analyzeTrend(metricName, history);
            
            // 4. 检测异常
            AnomalyDetectionResult anomalyResult = detectAnomalies(metricName, history);
            
            // 5. 检测回归
            List<RegressionAlert> alerts = detectRegressions(metricName, history, baseline, trend);
            
            // 6. 评估置信度
            double confidenceScore = calculateConfidenceScore(baseline, trend, anomalyResult);
            
            // 7. 生成洞察和建议
            List<String> insights = generateInsights(metricName, baseline, trend, anomalyResult, alerts);
            List<String> recommendations = generateRecommendations(metricName, baseline, trend, alerts);
            
            RegressionAnalysisResult result = new RegressionAnalysisResult(
                metricName, !alerts.isEmpty(), alerts, trend, baseline, anomalyResult,
                confidenceScore, insights, recommendations
            );
            
            // 8. 触发告警监听器
            if (!alerts.isEmpty()) {
                alerts.forEach(alert -> alertListeners.forEach(listener -> 
                    listener.onRegressionDetected(alert)));
            }
            
            if (anomalyResult.hasAnomaly()) {
                alertListeners.forEach(listener -> listener.onAnomalyDetected(anomalyResult));
            }
            
            if (trend != null && isSignificantTrendChange(trend)) {
                alertListeners.forEach(listener -> listener.onTrendChanged(trend));
            }
            
            logger.debug("完成指标{}的回归分析，发现{}个告警", metricName, alerts.size());
            return result;
            
        } catch (Exception e) {
            logger.error("分析指标{}的回归时发生错误", metricName, e);
            return new RegressionAnalysisResult(metricName, false, Collections.emptyList(),
                null, null, null, 0.0,
                Arrays.asList("分析过程中发生错误: " + e.getMessage()),
                Collections.emptyList());
        }
    }
    
    /**
     * 获取或创建性能基线
     */
    private PerformanceBaseline getOrCreateBaseline(String metricName, List<PerformanceSample> history) {
        // 检查是否已有基线
        PerformanceBaseline existingBaseline = performanceBaselines.get(metricName);
        
        if (existingBaseline != null && existingBaseline.getQuality() != BaselineQuality.POOR) {
            return existingBaseline;
        }
        
        // 计算新的基线
        List<PerformanceSample> baselineSamples = getBaselineSamples(history);
        if (baselineSamples.size() < 10) {
            return existingBaseline; // 返回旧基线或null
        }
        
        double baselineValue = baselineSamples.stream()
            .mapToDouble(PerformanceSample::getValue)
            .average()
            .orElse(0.0);
            
        double standardDeviation = calculateStandardDeviation(baselineSamples.stream()
            .mapToDouble(PerformanceSample::getValue)
            .toArray());
            
        double confidenceInterval = 1.96 * standardDeviation / Math.sqrt(baselineSamples.size());
        
        PerformanceBaseline newBaseline = new PerformanceBaseline(metricName, baselineValue, 
            standardDeviation, confidenceInterval, baselineSamples.size(), baselineSamples);
        
        performanceBaselines.put(metricName, newBaseline);
        
        // 通知监听器
        alertListeners.forEach(listener -> listener.onBaselineUpdated(newBaseline));
        
        logger.info("更新指标{}的基线，基线值: {}, 标准差: {}, 样本数: {}", 
                   metricName, baselineValue, standardDeviation, baselineSamples.size());
        
        return newBaseline;
    }
    
    /**
     * 分析性能趋势
     */
    private PerformanceTrend analyzeTrend(String metricName, List<PerformanceSample> history) {
        try {
            // 获取趋势分析时间窗口内的数据
            Instant windowStart = Instant.now().minus(detectionConfig.getTrendAnalysisWindow());
            List<PerformanceSample> trendData = history.stream()
                .filter(sample -> sample.getTimestamp().isAfter(windowStart))
                .sorted(Comparator.comparing(PerformanceSample::getTimestamp))
                .collect(Collectors.toList());
            
            if (trendData.size() < 5) {
                return null;
            }
            
            // 线性回归分析
            double[] x = new double[trendData.size()];
            double[] y = new double[trendData.size()];
            
            long startTime = trendData.get(0).getTimestamp().toEpochMilli();
            for (int i = 0; i < trendData.size(); i++) {
                x[i] = (trendData.get(i).getTimestamp().toEpochMilli() - startTime) / 1000.0; // 转换为秒
                y[i] = trendData.get(i).getValue();
            }
            
            LinearRegressionResult regression = performLinearRegression(x, y);
            
            // 确定趋势方向
            TrendDirection direction;
            if (Math.abs(regression.slope) < 0.001) {
                direction = TrendDirection.STABLE;
            } else if (regression.slope > 0) {
                direction = TrendDirection.INCREASING;
            } else {
                direction = TrendDirection.DECREASING;
            }
            
            // 检测波动性
            double volatility = calculateVolatility(y);
            if (volatility > 0.2) {
                direction = TrendDirection.VOLATILE;
            }
            
            PerformanceTrend trend = new PerformanceTrend(metricName, direction, regression.slope,
                regression.rSquared, regression.pValue, trendData);
            
            // 添加趋势指标
            trend.addTrendMetric("volatility", volatility)
                 .addTrendMetric("mean_value", Arrays.stream(y).average().orElse(0.0))
                 .addTrendMetric("trend_strength", Math.abs(regression.slope))
                 .addTrendMetric("data_points", trendData.size());
            
            // 更新趋势缓存
            performanceTrends.get().put(metricName, trend);
            
            return trend;
            
        } catch (Exception e) {
            logger.warn("分析指标{}的趋势时发生错误", metricName, e);
            return null;
        }
    }
    
    /**
     * 检测异常
     */
    private AnomalyDetectionResult detectAnomalies(String metricName, List<PerformanceSample> history) {
        if (!detectionConfig.isEnableAnomalyDetection()) {
            return new AnomalyDetectionResult(false, 0.0, Collections.emptyList(), null);
        }
        
        try {
            Instant windowStart = Instant.now().minus(detectionConfig.getAnomalyDetectionWindow());
            List<PerformanceSample> recentData = history.stream()
                .filter(sample -> sample.getTimestamp().isAfter(windowStart))
                .sorted(Comparator.comparing(PerformanceSample::getTimestamp))
                .collect(Collectors.toList());
            
            if (recentData.size() < 3) {
                return new AnomalyDetectionResult(false, 0.0, Collections.emptyList(), null);
            }
            
            double[] values = recentData.stream()
                .mapToDouble(PerformanceSample::getValue)
                .toArray();
            
            double mean = Arrays.stream(values).average().orElse(0.0);
            double stdDev = calculateStandardDeviation(values);
            
            List<PerformanceSample> anomalousSamples = new ArrayList<>();
            double maxAnomalyScore = 0.0;
            AnomalyType dominantAnomalyType = null;
            
            for (int i = 0; i < values.length; i++) {
                double zScore = stdDev > 0 ? Math.abs(values[i] - mean) / stdDev : 0;
                
                if (zScore > detectionConfig.getAnomalyThreshold()) {
                    anomalousSamples.add(recentData.get(i));
                    maxAnomalyScore = Math.max(maxAnomalyScore, zScore);
                    
                    // 确定异常类型
                    if (i > 0) {
                        double previousValue = values[i - 1];
                        if (values[i] > previousValue * 1.5) {
                            dominantAnomalyType = AnomalyType.SPIKE;
                        } else if (values[i] < previousValue * 0.5) {
                            dominantAnomalyType = AnomalyType.DROP;
                        }
                    }
                }
            }
            
            boolean hasAnomaly = !anomalousSamples.isEmpty();
            if (dominantAnomalyType == null && hasAnomaly) {
                dominantAnomalyType = AnomalyType.OUTLIER;
            }
            
            AnomalyDetectionResult result = new AnomalyDetectionResult(hasAnomaly, maxAnomalyScore,
                anomalousSamples, dominantAnomalyType);
            
            if (hasAnomaly) {
                result.addAnomalyData("mean_value", mean)
                     .addAnomalyData("std_deviation", stdDev)
                     .addAnomalyData("detection_threshold", detectionConfig.getAnomalyThreshold());
            }
            
            return result;
            
        } catch (Exception e) {
            logger.warn("检测指标{}的异常时发生错误", metricName, e);
            return new AnomalyDetectionResult(false, 0.0, Collections.emptyList(), null);
        }
    }
    
    /**
     * 检测性能回归
     */
    private List<RegressionAlert> detectRegressions(String metricName, List<PerformanceSample> history,
                                                  PerformanceBaseline baseline, PerformanceTrend trend) {
        List<RegressionAlert> alerts = new ArrayList<>();
        
        if (baseline == null) {
            return alerts;
        }
        
        // 获取最近的样本
        List<PerformanceSample> recentSamples = getRecentSamples(history, 10);
        if (recentSamples.isEmpty()) {
            return alerts;
        }
        
        double currentValue = recentSamples.stream()
            .mapToDouble(PerformanceSample::getValue)
            .average()
            .orElse(baseline.getBaselineValue());
        
        // 计算回归程度
        double regressionPercentage;
        if (baseline.getBaselineValue() > 0) {
            regressionPercentage = (baseline.getBaselineValue() - currentValue) / baseline.getBaselineValue();
        } else {
            regressionPercentage = 0;
        }
        
        // 获取指标特定的回归阈值
        double threshold = detectionConfig.getMetricSpecificThresholds()
            .getOrDefault(metricName, detectionConfig.getRegressionThreshold());
        
        // 检查性能回归
        if (regressionPercentage > threshold) {
            // 确定回归类型和严重程度
            RegressionType regressionType = determineRegressionType(metricName, regressionPercentage);
            Severity severity = determineSeverity(regressionPercentage);
            
            // 生成可能的原因和建议
            List<String> possibleCauses = generatePossibleCauses(metricName, regressionType, trend);
            List<String> recommendedActions = generateRecommendedActions(metricName, regressionType);
            
            String description = String.format("检测到性能回归，当前值%.2f相比基线%.2f下降%.1f%%", 
                currentValue, baseline.getBaselineValue(), regressionPercentage * 100);
            
            RegressionAlert alert = new RegressionAlert(metricName, regressionType, severity, description,
                currentValue, baseline.getBaselineValue(), regressionPercentage, possibleCauses, recommendedActions);
            
            alert.addAlertData("threshold", threshold)
                 .addAlertData("baseline_quality", baseline.getQuality().toString())
                 .addAlertData("trend_direction", trend != null ? trend.getDirection().toString() : "unknown");
            
            alerts.add(alert);
            activeAlerts.put(metricName, alert);
            
            logger.warn("检测到性能回归: {} - {}", metricName, description);
        }
        
        // 检查趋势回归
        if (trend != null && trend.getSignificance() == TrendSignificance.HIGHLY_SIGNIFICANT &&
            trend.getDirection() == TrendDirection.DECREASING && trend.getSlope() < -0.01) {
            
            RegressionAlert trendAlert = new RegressionAlert(metricName, RegressionType.PERFORMANCE_DEGRADATION,
                Severity.MEDIUM, "检测到性能下降趋势", currentValue, baseline.getBaselineValue(),
                regressionPercentage, 
                Arrays.asList("持续的下降趋势", "可能存在系统性问题"),
                Arrays.asList("分析根本原因", "检查最近的系统变更"));
            
            alerts.add(trendAlert);
        }
        
        return alerts;
    }
    
    /**
     * 计算置信度分数
     */
    private double calculateConfidenceScore(PerformanceBaseline baseline, PerformanceTrend trend,
                                          AnomalyDetectionResult anomalyResult) {
        double confidence = 0.5; // 基础置信度
        
        // 基线质量影响置信度
        if (baseline != null) {
            switch (baseline.getQuality()) {
                case GOOD:
                    confidence += 0.3;
                    break;
                case ACCEPTABLE:
                    confidence += 0.2;
                    break;
                case FAIR:
                    confidence += 0.1;
                    break;
                case POOR:
                    confidence -= 0.2;
                    break;
            }
        }
        
        // 趋势显著性影响置信度
        if (trend != null) {
            switch (trend.getSignificance()) {
                case HIGHLY_SIGNIFICANT:
                    confidence += 0.2;
                    break;
                case SIGNIFICANT:
                    confidence += 0.1;
                    break;
                case NOT_SIGNIFICANT:
                    confidence -= 0.1;
                    break;
            }
        }
        
        // 异常检测结果影响置信度
        if (anomalyResult.hasAnomaly()) {
            confidence += Math.min(0.2, anomalyResult.getAnomalyScore() * 0.05);
        }
        
        return Math.max(0.0, Math.min(1.0, confidence));
    }
    
    /**
     * 生成洞察
     */
    private List<String> generateInsights(String metricName, PerformanceBaseline baseline,
                                        PerformanceTrend trend, AnomalyDetectionResult anomalyResult,
                                        List<RegressionAlert> alerts) {
        List<String> insights = new ArrayList<>();
        
        if (baseline != null) {
            insights.add(String.format("基线质量：%s（基于%d个样本）", 
                baseline.getQuality().getDescription(), baseline.getSampleCount()));
        }
        
        if (trend != null) {
            insights.add(String.format("趋势分析：%s（R²=%.3f，显著性%s）",
                trend.getDirection().getDescription(), trend.getRSquared(), 
                trend.getSignificance().getDescription()));
        }
        
        if (anomalyResult.hasAnomaly()) {
            insights.add(String.format("异常检测：检测到%s异常（异常分数：%.2f）",
                anomalyResult.getType().getDescription(), anomalyResult.getAnomalyScore()));
        }
        
        if (!alerts.isEmpty()) {
            insights.add(String.format("回归告警：检测到%d个性能回归问题", alerts.size()));
            
            long criticalAlerts = alerts.stream().mapToLong(a -> 
                a.getSeverity() == Severity.CRITICAL ? 1 : 0).sum();
            if (criticalAlerts > 0) {
                insights.add(String.format("严重告警：%d个严重级别的问题需要立即处理", criticalAlerts));
            }
        }
        
        return insights;
    }
    
    /**
     * 生成建议
     */
    private List<String> generateRecommendations(String metricName, PerformanceBaseline baseline,
                                               PerformanceTrend trend, List<RegressionAlert> alerts) {
        List<String> recommendations = new ArrayList<>();
        
        if (baseline != null && baseline.getQuality() == BaselineQuality.POOR) {
            recommendations.add("增加性能数据收集频率以提高基线质量");
        }
        
        if (trend != null && trend.getDirection() == TrendDirection.DECREASING) {
            recommendations.add("分析性能下降的根本原因");
            recommendations.add("检查最近的系统变更和配置更新");
        }
        
        if (trend != null && trend.getDirection() == TrendDirection.VOLATILE) {
            recommendations.add("性能波动较大，建议检查系统稳定性");
            recommendations.add("考虑增加缓存或优化数据库查询");
        }
        
        recommendations.add("定期执行性能基准测试以更新基线数据");
        recommendations.add("设置性能告警阈值以提前发现问题");
        recommendations.add("监控资源使用情况（CPU、内存、网络）");
        
        return recommendations;
    }
    
    /**
     * 收集性能样本
     */
    private void collectPerformanceSamples() {
        try {
            // 从性能监控器获取指标
            Map<String, Double> currentMetrics = performanceMonitor.getCurrentMetrics();
            
            currentMetrics.forEach((metricName, value) -> {
                Map<String, Object> context = new HashMap<>();
                context.put("source", "performance_monitor");
                context.put("timestamp", Instant.now().toEpochMilli());
                
                addPerformanceSample(metricName, value, context);
            });
            
            // 从锁指标获取指标
            Map<String, Object> lockMetricsData = lockMetrics.collectMetrics();
            lockMetricsData.forEach((metricName, value) -> {
                if (value instanceof Number) {
                    Map<String, Object> context = new HashMap<>();
                    context.put("source", "lock_metrics");
                    context.put("timestamp", Instant.now().toEpochMilli());
                    
                    addPerformanceSample("lock_" + metricName, ((Number) value).doubleValue(), context);
                }
            });
            
        } catch (Exception e) {
            logger.error("收集性能样本时发生错误", e);
        }
    }
    
    /**
     * 更新性能基线
     */
    private void updatePerformanceBaselines() {
        if (!detectionConfig.isEnableAutomaticBaselineUpdate()) {
            return;
        }
        
        try {
            for (String metricName : performanceHistory.keySet()) {
                List<PerformanceSample> history = getMetricHistory(metricName);
                if (history.size() > detectionConfig.getBaselineSampleSize()) {
                    getOrCreateBaseline(metricName, history);
                }
            }
        } catch (Exception e) {
            logger.error("更新性能基线时发生错误", e);
        }
    }
    
    /**
     * 清理过期数据
     */
    private void cleanupExpiredData() {
        try {
            Instant cutoff = Instant.now().minus(detectionConfig.getSampleRetentionPeriod());
            
            for (String metricName : performanceHistory.keySet()) {
                List<PerformanceSample> samples = performanceHistory.get(metricName);
                samples.removeIf(sample -> sample.getTimestamp().isBefore(cutoff));
                
                logger.debug("清理指标{}的过期数据，剩余{}个样本", metricName, samples.size());
            }
            
        } catch (Exception e) {
            logger.error("清理过期数据时发生错误", e);
        }
    }
    
    // 私有辅助方法
    
    private Set<String> getMetricsToAnalyze() {
        // 返回需要分析的性能指标
        return new HashSet<>(Arrays.asList(
            "average_latency_ms", "throughput_per_second", "success_rate",
            "error_rate", "cpu_usage_percent", "memory_usage_mb",
            "lock_acquisition_time_ms", "lock_hold_time_ms",
            "concurrent_lock_count", "failed_lock_operations"
        ));
    }
    
    private List<PerformanceSample> getMetricHistory(String metricName) {
        return performanceHistory.getOrDefault(metricName, new ArrayList<>());
    }
    
    private List<PerformanceSample> getBaselineSamples(List<PerformanceSample> history) {
        Instant windowStart = Instant.now().minus(detectionConfig.getBaselineWindow());
        return history.stream()
            .filter(sample -> sample.getTimestamp().isAfter(windowStart))
            .sorted(Comparator.comparing(PerformanceSample::getTimestamp))
            .limit(detectionConfig.getBaselineSampleSize())
            .collect(Collectors.toList());
    }
    
    private List<PerformanceSample> getRecentSamples(List<PerformanceSample> history, int count) {
        return history.stream()
            .sorted(Comparator.comparing(PerformanceSample::getTimestamp).reversed())
            .limit(count)
            .collect(Collectors.toList());
    }
    
    private double calculateStandardDeviation(double[] values) {
        if (values.length == 0) return 0;
        
        double mean = Arrays.stream(values).average().orElse(0);
        double variance = Arrays.stream(values)
            .mapToDouble(value -> Math.pow(value - mean, 2))
            .average()
            .orElse(0);
            
        return Math.sqrt(variance);
    }
    
    private double calculateVolatility(double[] values) {
        if (values.length < 2) return 0;
        
        double mean = Arrays.stream(values).average().orElse(0);
        double stdDev = calculateStandardDeviation(values);
        
        return mean != 0 ? stdDev / Math.abs(mean) : stdDev;
    }
    
    /**
     * 线性回归结果类
     */
    private static class LinearRegressionResult {
        final double slope;
        final double intercept;
        final double rSquared;
        final double pValue;
        
        LinearRegressionResult(double slope, double intercept, double rSquared, double pValue) {
            this.slope = slope;
            this.intercept = intercept;
            this.rSquared = rSquared;
            this.pValue = pValue;
        }
    }
    
    private LinearRegressionResult performLinearRegression(double[] x, double[] y) {
        int n = x.length;
        if (n != y.length || n < 2) {
            return new LinearRegressionResult(0, 0, 0, 1);
        }
        
        double sumX = Arrays.stream(x).sum();
        double sumY = Arrays.stream(y).sum();
        double sumXY = 0;
        double sumX2 = 0;
        
        for (int i = 0; i < n; i++) {
            sumXY += x[i] * y[i];
            sumX2 += x[i] * x[i];
        }
        
        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
        double intercept = (sumY - slope * sumX) / n;
        
        // 计算R²
        double meanY = sumY / n;
        double ssTotal = 0;
        double ssRes = 0;
        
        for (int i = 0; i < n; i++) {
            double predicted = slope * x[i] + intercept;
            ssTotal += Math.pow(y[i] - meanY, 2);
            ssRes += Math.pow(y[i] - predicted, 2);
        }
        
        double rSquared = ssTotal > 0 ? 1 - (ssRes / ssTotal) : 0;
        
        // 简化的p值计算（实际应用中应使用t分布）
        double pValue = rSquared > 0.8 ? 0.001 : (rSquared > 0.5 ? 0.01 : 0.1);
        
        return new LinearRegressionResult(slope, intercept, rSquared, pValue);
    }
    
    private boolean isSignificantTrendChange(PerformanceTrend trend) {
        return trend != null && 
               trend.getSignificance() == TrendSignificance.HIGHLY_SIGNIFICANT &&
               (trend.getDirection() == TrendDirection.INCREASING || trend.getDirection() == TrendDirection.DECREASING);
    }
    
    private RegressionType determineRegressionType(String metricName, double regressionPercentage) {
        if (metricName.contains("latency") || metricName.contains("time")) {
            return RegressionType.LATENCY_INCREASE;
        } else if (metricName.contains("throughput")) {
            return RegressionType.THROUGHPUT_REDUCTION;
        } else if (metricName.contains("error") || metricName.contains("failed")) {
            return RegressionType.ERROR_RATE_INCREASE;
        } else if (metricName.contains("cpu") || metricName.contains("memory")) {
            return RegressionType.RESOURCE_USAGE_SURGE;
        } else {
            return RegressionType.PERFORMANCE_DEGRADATION;
        }
    }
    
    private Severity determineSeverity(double regressionPercentage) {
        if (regressionPercentage > 0.5) {
            return Severity.CRITICAL;
        } else if (regressionPercentage > 0.3) {
            return Severity.HIGH;
        } else if (regressionPercentage > 0.1) {
            return Severity.MEDIUM;
        } else {
            return Severity.LOW;
        }
    }
    
    private List<String> generatePossibleCauses(String metricName, RegressionType regressionType, PerformanceTrend trend) {
        List<String> causes = new ArrayList<>();
        
        switch (regressionType) {
            case LATENCY_INCREASE:
                causes.addAll(Arrays.asList("网络延迟增加", "数据库查询性能下降", "缓存命中率降低"));
                break;
            case THROUGHPUT_REDUCTION:
                causes.addAll(Arrays.asList("系统负载增加", "资源竞争加剧", "代码性能退化"));
                break;
            case ERROR_RATE_INCREASE:
                causes.addAll(Arrays.asList("依赖服务异常", "配置变更错误", "网络连接问题"));
                break;
            case RESOURCE_USAGE_SURGE:
                causes.addAll(Arrays.asList("内存泄漏", "CPU密集型操作增加", "并发线程数过多"));
                break;
            default:
                causes.addAll(Arrays.asList("系统配置变更", "代码部署问题", "环境因素变化"));
        }
        
        if (trend != null && trend.getDirection() == TrendDirection.VOLATILE) {
            causes.add("系统稳定性问题");
        }
        
        return causes;
    }
    
    private List<String> generateRecommendedActions(String metricName, RegressionType regressionType) {
        List<String> actions = new ArrayList<>();
        
        actions.add("立即分析最近的系统变更");
        actions.add("检查相关日志和错误信息");
        actions.add("验证系统配置的正确性");
        
        switch (regressionType) {
            case LATENCY_INCREASE:
                actions.addAll(Arrays.asList("优化数据库查询", "增加缓存层", "检查网络连接"));
                break;
            case THROUGHPUT_REDUCTION:
                actions.addAll(Arrays.asList("增加系统资源", "优化代码逻辑", "检查并发限制"));
                break;
            case ERROR_RATE_INCREASE:
                actions.addAll(Arrays.asList("回滚最近的变更", "检查依赖服务状态", "修复配置错误"));
                break;
            case RESOURCE_USAGE_SURGE:
                actions.addAll(Arrays.asList("内存分析", "优化算法复杂度", "调整线程池配置"));
                break;
        }
        
        return actions;
    }
    
    /**
     * 线程工厂构建器
     */
    private static class ThreadFactoryBuilder {
        private String nameFormat = "pool-%d";
        
        public ThreadFactoryBuilder setNameFormat(String nameFormat) {
            this.nameFormat = nameFormat;
            return this;
        }
        
        public ThreadFactory build() {
            return new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(0);
                
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, String.format(nameFormat, counter.getAndIncrement()));
                    thread.setDaemon(true);
                    return thread;
                }
            };
        }
    }
    
    // Getter方法
    public RegressionDetectionConfiguration getDetectionConfig() { 
        return detectionConfig; 
    }
    
    public Map<String, PerformanceBaseline> getPerformanceBaselines() { 
        return performanceBaselines; 
    }
    
    public Map<String, RegressionAlert> getActiveAlerts() { 
        return activeAlerts; 
    }
    
    public Map<String, PerformanceTrend> getPerformanceTrends() { 
        return performanceTrends.get(); 
    }
    
    public boolean isMonitoring() { 
        return isMonitoring.get(); 
    }
    
    /**
     * 添加回归告警监听器
     */
    public void addAlertListener(RegressionAlertListener listener) {
        alertListeners.add(listener);
        logger.info("已添加回归告警监听器: {}", listener.getClass().getSimpleName());
    }
    
    /**
     * 移除回归告警监听器
     */
    public void removeAlertListener(RegressionAlertListener listener) {
        alertListeners.remove(listener);
        logger.info("已移除回归告警监听器: {}", listener.getClass().getSimpleName());
    }
    
    /**
     * 获取性能回归报告
     */
    public String generateRegressionReport() {
        StringBuilder report = new StringBuilder();
        report.append("# 性能回归检测报告\n\n");
        report.append("生成时间: ").append(Instant.now()).append("\n\n");
        
        // 活跃告警
        report.append("## 活跃告警\n");
        if (activeAlerts.isEmpty()) {
            report.append("当前没有活跃的性能回归告警。\n\n");
        } else {
            activeAlerts.forEach((metric, alert) -> {
                report.append("### ").append(metric).append("\n");
                report.append("- **严重程度**: ").append(alert.getSeverity().getDescription()).append("\n");
                report.append("- **描述**: ").append(alert.getDescription()).append("\n");
                report.append("- **当前值**: ").append(String.format("%.2f", alert.getCurrentValue())).append("\n");
                report.append("- **基线值**: ").append(String.format("%.2f", alert.getBaselineValue())).append("\n");
                report.append("- **回归幅度**: ").append(String.format("%.1f%%", alert.getRegressionPercentage() * 100)).append("\n");
                report.append("- **检测时间**: ").append(alert.getDetectedAt()).append("\n\n");
            });
        }
        
        // 性能基线
        report.append("## 性能基线\n");
        performanceBaselines.forEach((metric, baseline) -> {
            report.append("### ").append(metric).append("\n");
            report.append("- **基线值**: ").append(String.format("%.2f", baseline.getBaselineValue())).append("\n");
            report.append("- **标准差**: ").append(String.format("%.2f", baseline.getStandardDeviation())).append("\n");
            report.append("- **质量**: ").append(baseline.getQuality().getDescription()).append("\n");
            report.append("- **样本数**: ").append(baseline.getSampleCount()).append("\n");
            report.append("- **最后更新**: ").append(baseline.getLastUpdated()).append("\n\n");
        });
        
        // 性能趋势
        report.append("## 性能趋势\n");
        performanceTrends.get().forEach((metric, trend) -> {
            report.append("### ").append(metric).append("\n");
            report.append("- **趋势方向**: ").append(trend.getDirection().getDescription()).append("\n");
            report.append("- **趋势强度**: ").append(String.format("%.3f", Math.abs(trend.getSlope()))).append("\n");
            report.append("- **显著性**: ").append(trend.getSignificance().getDescription()).append("\n");
            report.append("- **R²**: ").append(String.format("%.3f", trend.getRSquared())).append("\n\n");
        });
        
        return report.toString();
    }
}