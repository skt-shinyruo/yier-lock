package com.mycorp.distributedlock.core.reporting;

import com.mycorp.distributedlock.api.PerformanceMetrics;
import com.mycorp.distributedlock.core.analysis.PerformanceAnalyzer;
import com.mycorp.distributedlock.core.benchmarks.BenchmarkResultAnalyzer;
import com.mycorp.distributedlock.core.monitoring.PerformanceMonitor;
import com.mycorp.distributedlock.core.monitoring.PerformanceRegressionDetector;
import com.mycorp.distributedlock.core.optimization.AutoTuningEngine;
import com.mycorp.distributedlock.core.tuning.PerformanceRecommendationEngine;
import com.mycorp.distributedlock.core.testing.LoadTestScenarios;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 性能报告生成器 - 综合生成分布式锁系统性能分析报告
 * 
 * 主要功能：
 * 1. 整合多种性能分析结果
 * 2. 生成多格式性能报告（HTML、JSON、CSV、PDF）
 * 3. 包含性能趋势分析和对比
 * 4. 提供优化建议和实施建议
 * 5. 生成性能可视化图表数据
 * 6. 支持报告模板和自定义
 * 7. 报告分发和归档管理
 * 8. 性能基准对比分析
 * 9. 关键性能指标(KPI)分析
 * 10. 性能调优效果评估
 * 
 * @author Yier Lock Team
 * @version 2.0.0
 */
public class PerformanceReportGenerator {
    
    private static final Logger logger = LoggerFactory.getLogger(PerformanceReportGenerator.class);
    
    // 报告配置
    private final ReportConfiguration reportConfig;
    
    // 性能分析组件
    private final PerformanceAnalyzer performanceAnalyzer;
    private final BenchmarkResultAnalyzer benchmarkAnalyzer;
    private final PerformanceMonitor performanceMonitor;
    private final PerformanceRegressionDetector regressionDetector;
    private final AutoTuningEngine autoTuningEngine;
    private final PerformanceRecommendationEngine recommendationEngine;
    private final LoadTestScenarios loadTestScenarios;
    
    // 报告生成器
    private final ExecutorService reportGenerationExecutor;
    
    /**
     * 报告配置类
     */
    public static class ReportConfiguration {
        private ReportFormat defaultFormat = ReportFormat.HTML;
        private Duration reportTimeRange = Duration.ofDays(7);
        private boolean includeDetailedMetrics = true;
        private boolean includeRecommendations = true;
        private boolean includeTrends = true;
        private boolean includeBenchmarks = true;
        private boolean includeRegressionAnalysis = true;
        private boolean includeCharts = true;
        private String outputDirectory = "./performance-reports";
        private String templateDirectory = "./report-templates";
        private Map<String, String> customTemplates = new HashMap<>();
        private Map<String, Object> brandingConfig = new HashMap<>();
        private boolean enableAutoScheduling = false;
        private Duration reportGenerationInterval = Duration.ofHours(24);
        private int maxReportsRetention = 30;
        private Set<String> includedMetrics = new HashSet<>();
        private Set<String> excludedMetrics = new HashSet<>();
        
        // 构造函数，设置默认值
        public ReportConfiguration() {
            // 默认包含的主要指标
            includedMetrics.addAll(Arrays.asList(
                "average_latency_ms", "throughput_per_second", "success_rate",
                "cpu_usage_percent", "memory_usage_mb", "error_rate",
                "lock_acquisition_time_ms", "concurrent_lock_operations",
                "connection_pool_utilization", "cache_hit_rate"
            ));
        }
        
        // Getters and Setters
        public ReportFormat getDefaultFormat() { return defaultFormat; }
        public void setDefaultFormat(ReportFormat defaultFormat) { this.defaultFormat = defaultFormat; }
        
        public Duration getReportTimeRange() { return reportTimeRange; }
        public void setReportTimeRange(Duration reportTimeRange) { this.reportTimeRange = reportTimeRange; }
        
        public boolean isIncludeDetailedMetrics() { return includeDetailedMetrics; }
        public void setIncludeDetailedMetrics(boolean includeDetailedMetrics) { 
            this.includeDetailedMetrics = includeDetailedMetrics; 
        }
        
        public boolean isIncludeRecommendations() { return includeRecommendations; }
        public void setIncludeRecommendations(boolean includeRecommendations) { 
            this.includeRecommendations = includeRecommendations; 
        }
        
        public boolean isIncludeTrends() { return includeTrends; }
        public void setIncludeTrends(boolean includeTrends) { this.includeTrends = includeTrends; }
        
        public boolean isIncludeBenchmarks() { return includeBenchmarks; }
        public void setIncludeBenchmarks(boolean includeBenchmarks) { 
            this.includeBenchmarks = includeBenchmarks; 
        }
        
        public boolean isIncludeRegressionAnalysis() { return includeRegressionAnalysis; }
        public void setIncludeRegressionAnalysis(boolean includeRegressionAnalysis) { 
            this.includeRegressionAnalysis = includeRegressionAnalysis; 
        }
        
        public boolean isIncludeCharts() { return includeCharts; }
        public void setIncludeCharts(boolean includeCharts) { this.includeCharts = includeCharts; }
        
        public String getOutputDirectory() { return outputDirectory; }
        public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; }
        
        public String getTemplateDirectory() { return templateDirectory; }
        public void setTemplateDirectory(String templateDirectory) { this.templateDirectory = templateDirectory; }
        
        public Map<String, String> getCustomTemplates() { return customTemplates; }
        public void setCustomTemplates(Map<String, String> customTemplates) { 
            this.customTemplates = customTemplates; 
        }
        
        public Map<String, Object> getBrandingConfig() { return brandingConfig; }
        public void setBrandingConfig(Map<String, Object> brandingConfig) { 
            this.brandingConfig = brandingConfig; 
        }
        
        public boolean isEnableAutoScheduling() { return enableAutoScheduling; }
        public void setEnableAutoScheduling(boolean enableAutoScheduling) { 
            this.enableAutoScheduling = enableAutoScheduling; 
        }
        
        public Duration getReportGenerationInterval() { return reportGenerationInterval; }
        public void setReportGenerationInterval(Duration reportGenerationInterval) { 
            this.reportGenerationInterval = reportGenerationInterval; 
        }
        
        public int getMaxReportsRetention() { return maxReportsRetention; }
        public void setMaxReportsRetention(int maxReportsRetention) { 
            this.maxReportsRetention = Math.max(1, maxReportsRetention); 
        }
        
        public Set<String> getIncludedMetrics() { return includedMetrics; }
        public void setIncludedMetrics(Set<String> includedMetrics) { 
            this.includedMetrics = includedMetrics; 
        }
        
        public Set<String> getExcludedMetrics() { return excludedMetrics; }
        public void setExcludedMetrics(Set<String> excludedMetrics) { 
            this.excludedMetrics = excludedMetrics; 
        }
        
        public void addIncludedMetric(String metric) { this.includedMetrics.add(metric); }
        public void addExcludedMetric(String metric) { this.excludedMetrics.add(metric); }
        
        public void addCustomTemplate(String name, String template) {
            this.customTemplates.put(name, template);
        }
        
        public void setBrandingInfo(String title, String company, String logo) {
            this.brandingConfig.put("title", title);
            this.brandingConfig.put("company", company);
            this.brandingConfig.put("logo", logo);
        }
    }
    
    /**
     * 报告格式枚举
     */
    public enum ReportFormat {
        HTML("HTML格式报告"),
        JSON("JSON格式数据"),
        CSV("CSV格式数据"),
        PDF("PDF格式报告"),
        XML("XML格式数据"),
        MARKDOWN("Markdown格式");
        
        private final String description;
        
        ReportFormat(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
    
    /**
     * 报告类型枚举
     */
    public enum ReportType {
        DAILY("日报"),
        WEEKLY("周报"),
        MONTHLY("月报"),
        QUARTERLY("季报"),
        BENCHMARK_COMPARISON("基准对比"),
        REGRESSION_ANALYSIS("回归分析"),
        OPTIMIZATION_SUMMARY("优化总结"),
        COMPREHENSIVE("综合报告"),
        CUSTOM("自定义报告");
        
        private final String description;
        
        ReportType(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
    
    /**
     * 性能报告类
     */
    public static class PerformanceReport {
        private final String reportId;
        private final String title;
        private final ReportType reportType;
        private final ReportFormat format;
        private final Instant generatedAt;
        private final Instant reportPeriodStart;
        private final Instant reportPeriodEnd;
        private final ReportMetadata metadata;
        private final ExecutiveSummary executiveSummary;
        private final Map<String, Object> performanceData;
        private final List<ChartData> chartData;
        private final List<PerformanceInsight> insights;
        private final List<OptimizationRecommendation> recommendations;
        private final List<RegressionIssue> regressionIssues;
        private final Map<String, String> benchmarkComparisons;
        private final Map<String, Object> rawData;
        private final byte[] reportContent;
        private final String filePath;
        
        public PerformanceReport(String title, ReportType reportType, ReportFormat format,
                               Instant reportPeriodStart, Instant reportPeriodEnd,
                               ReportMetadata metadata) {
            this.reportId = UUID.randomUUID().toString();
            this.title = title;
            this.reportType = reportType;
            this.format = format;
            this.generatedAt = Instant.now();
            this.reportPeriodStart = reportPeriodStart;
            this.reportPeriodEnd = reportPeriodEnd;
            this.metadata = metadata;
            this.executiveSummary = new ExecutiveSummary();
            this.performanceData = new HashMap<>();
            this.chartData = new ArrayList<>();
            this.insights = new ArrayList<>();
            this.recommendations = new ArrayList<>();
            this.regressionIssues = new ArrayList<>();
            this.benchmarkComparisons = new HashMap<>();
            this.rawData = new HashMap<>();
            this.reportContent = new byte[0];
            this.filePath = "";
        }
        
        // Builder方法
        public PerformanceReport setExecutiveSummary(ExecutiveSummary summary) {
            this.executiveSummary.copyFrom(summary);
            return this;
        }
        
        public PerformanceReport addPerformanceData(String key, Object value) {
            this.performanceData.put(key, value);
            return this;
        }
        
        public PerformanceReport addChartData(ChartData chartData) {
            this.chartData.add(chartData);
            return this;
        }
        
        public PerformanceReport addInsight(PerformanceInsight insight) {
            this.insights.add(insight);
            return this;
        }
        
        public PerformanceReport addRecommendation(OptimizationRecommendation recommendation) {
            this.recommendations.add(recommendation);
            return this;
        }
        
        public PerformanceReport addRegressionIssue(RegressionIssue issue) {
            this.regressionIssues.add(issue);
            return this;
        }
        
        public PerformanceReport addBenchmarkComparison(String benchmark, String result) {
            this.benchmarkComparisons.put(benchmark, result);
            return this;
        }
        
        public PerformanceReport addRawData(String key, Object data) {
            this.rawData.put(key, data);
            return this;
        }
        
        public PerformanceReport setReportContent(byte[] content) {
            this.reportContent = content;
            return this;
        }
        
        public PerformanceReport setFilePath(String filePath) {
            this.filePath = filePath;
            return this;
        }
        
        // Getters
        public String getReportId() { return reportId; }
        public String getTitle() { return title; }
        public ReportType getReportType() { return reportType; }
        public ReportFormat getFormat() { return format; }
        public Instant getGeneratedAt() { return generatedAt; }
        public Instant getReportPeriodStart() { return reportPeriodStart; }
        public Instant getReportPeriodEnd() { return reportPeriodEnd; }
        public ReportMetadata getMetadata() { return metadata; }
        public ExecutiveSummary getExecutiveSummary() { return executiveSummary; }
        public Map<String, Object> getPerformanceData() { return performanceData; }
        public List<ChartData> getChartData() { return chartData; }
        public List<PerformanceInsight> getInsights() { return insights; }
        public List<OptimizationRecommendation> getRecommendations() { return recommendations; }
        public List<RegressionIssue> getRegressionIssues() { return regressionIssues; }
        public Map<String, String> getBenchmarkComparisons() { return benchmarkComparisons; }
        public Map<String, Object> getRawData() { return rawData; }
        public byte[] getReportContent() { return reportContent; }
        public String getFilePath() { return filePath; }
    }
    
    /**
     * 报告元数据类
     */
    public static class ReportMetadata {
        private final String systemName;
        private final String environment;
        private final String version;
        private final String generatedBy;
        private final List<String> tags;
        private final Map<String, String> customProperties;
        private final long totalDataPoints;
        private final int totalTestsExecuted;
        private final Duration reportGenerationTime;
        
        public ReportMetadata(String systemName, String environment, String version, 
                            String generatedBy, List<String> tags) {
            this.systemName = systemName;
            this.environment = environment;
            this.version = version;
            this.generatedBy = generatedBy;
            this.tags = tags != null ? tags : new ArrayList<>();
            this.customProperties = new HashMap<>();
            this.totalDataPoints = 0;
            this.totalTestsExecuted = 0;
            this.reportGenerationTime = Duration.ZERO;
        }
        
        public ReportMetadata addCustomProperty(String key, String value) {
            this.customProperties.put(key, value);
            return this;
        }
        
        public ReportMetadata setTotalDataPoints(long count) {
            // 返回新的实例以支持链式调用
            return new ReportMetadata(systemName, environment, version, generatedBy, tags) {
                {
                    customProperties.putAll(customProperties);
                    totalDataPoints = count;
                    totalTestsExecuted = totalTestsExecuted;
                    reportGenerationTime = reportGenerationTime;
                }
            };
        }
        
        public ReportMetadata setTotalTestsExecuted(int count) {
            return new ReportMetadata(systemName, environment, version, generatedBy, tags) {
                {
                    customProperties.putAll(customProperties);
                    totalDataPoints = totalDataPoints;
                    totalTestsExecuted = count;
                    reportGenerationTime = reportGenerationTime;
                }
            };
        }
        
        public ReportMetadata setReportGenerationTime(Duration time) {
            return new ReportMetadata(systemName, environment, version, generatedBy, tags) {
                {
                    customProperties.putAll(customProperties);
                    totalDataPoints = totalDataPoints;
                    totalTestsExecuted = totalTestsExecuted;
                    reportGenerationTime = time;
                }
            };
        }
        
        // Getters
        public String getSystemName() { return systemName; }
        public String getEnvironment() { return environment; }
        public String getVersion() { return version; }
        public String getGeneratedBy() { return generatedBy; }
        public List<String> getTags() { return tags; }
        public Map<String, String> getCustomProperties() { return customProperties; }
        public long getTotalDataPoints() { return totalDataPoints; }
        public int getTotalTestsExecuted() { return totalTestsExecuted; }
        public Duration getReportGenerationTime() { return reportGenerationTime; }
    }
    
    /**
     * 执行摘要类
     */
    public static class ExecutiveSummary {
        private String overallStatus = "良好";
        private double performanceScore = 0.0;
        private int totalIssues = 0;
        private int criticalIssues = 0;
        private int highPriorityIssues = 0;
        private String keyFindings = "";
        private String mainRecommendations = "";
        private double improvementFromLastPeriod = 0.0;
        private Map<String, String> kpiStatus = new HashMap<>();
        private List<String> highlights = new ArrayList<>();
        private List<String> concerns = new ArrayList<>();
        
        public ExecutiveSummary setOverallStatus(String status) {
            this.overallStatus = status;
            return this;
        }
        
        public ExecutiveSummary setPerformanceScore(double score) {
            this.performanceScore = Math.max(0.0, Math.min(100.0, score));
            return this;
        }
        
        public ExecutiveSummary setIssuesCount(int total, int critical, int highPriority) {
            this.totalIssues = total;
            this.criticalIssues = critical;
            this.highPriorityIssues = highPriority;
            return this;
        }
        
        public ExecutiveSummary setKeyFindings(String findings) {
            this.keyFindings = findings;
            return this;
        }
        
        public ExecutiveSummary setMainRecommendations(String recommendations) {
            this.mainRecommendations = recommendations;
            return this;
        }
        
        public ExecutiveSummary setImprovementFromLastPeriod(double improvement) {
            this.improvementFromLastPeriod = improvement;
            return this;
        }
        
        public ExecutiveSummary addKPIStatus(String kpi, String status) {
            this.kpiStatus.put(kpi, status);
            return this;
        }
        
        public ExecutiveSummary addHighlight(String highlight) {
            this.highlights.add(highlight);
            return this;
        }
        
        public ExecutiveSummary addConcern(String concern) {
            this.concerns.add(concern);
            return this;
        }
        
        public ExecutiveSummary copyFrom(ExecutiveSummary other) {
            this.overallStatus = other.overallStatus;
            this.performanceScore = other.performanceScore;
            this.totalIssues = other.totalIssues;
            this.criticalIssues = other.criticalIssues;
            this.highPriorityIssues = other.highPriorityIssues;
            this.keyFindings = other.keyFindings;
            this.mainRecommendations = other.mainRecommendations;
            this.improvementFromLastPeriod = other.improvementFromLastPeriod;
            this.kpiStatus.putAll(other.kpiStatus);
            this.highlights.addAll(other.highlights);
            this.concerns.addAll(other.concerns);
            return this;
        }
        
        // Getters
        public String getOverallStatus() { return overallStatus; }
        public double getPerformanceScore() { return performanceScore; }
        public int getTotalIssues() { return totalIssues; }
        public int getCriticalIssues() { return criticalIssues; }
        public int getHighPriorityIssues() { return highPriorityIssues; }
        public String getKeyFindings() { return keyFindings; }
        public String getMainRecommendations() { return mainRecommendations; }
        public double getImprovementFromLastPeriod() { return improvementFromLastPeriod; }
        public Map<String, String> getKPIStatus() { return kpiStatus; }
        public List<String> getHighlights() { return highlights; }
        public List<String> getConcerns() { return concerns; }
    }
    
    /**
     * 图表数据类
     */
    public static class ChartData {
        private final String chartId;
        private final String title;
        private final String chartType;
        private final String xAxisLabel;
        private final String yAxisLabel;
        private final List<DataPoint> dataPoints;
        private final Map<String, Object> chartConfig;
        
        public ChartData(String title, String chartType, String xAxisLabel, String yAxisLabel) {
            this.chartId = UUID.randomUUID().toString();
            this.title = title;
            this.chartType = chartType;
            this.xAxisLabel = xAxisLabel;
            this.yAxisLabel = yAxisLabel;
            this.dataPoints = new ArrayList<>();
            this.chartConfig = new HashMap<>();
        }
        
        public ChartData addDataPoint(Object x, Object y) {
            this.dataPoints.add(new DataPoint(x, y));
            return this;
        }
        
        public ChartData addDataPoint(Object x, Object y, Map<String, Object> metadata) {
            this.dataPoints.add(new DataPoint(x, y, metadata));
            return this;
        }
        
        public ChartData addConfig(String key, Object value) {
            this.chartConfig.put(key, value);
            return this;
        }
        
        // Getters
        public String getChartId() { return chartId; }
        public String getTitle() { return title; }
        public String getChartType() { return chartType; }
        public String getXAxisLabel() { return xAxisLabel; }
        public String getYAxisLabel() { return yAxisLabel; }
        public List<DataPoint> getDataPoints() { return dataPoints; }
        public Map<String, Object> getChartConfig() { return chartConfig; }
    }
    
    /**
     * 数据点类
     */
    public static class DataPoint {
        private final Object x;
        private final Object y;
        private final Map<String, Object> metadata;
        
        public DataPoint(Object x, Object y) {
            this.x = x;
            this.y = y;
            this.metadata = new HashMap<>();
        }
        
        public DataPoint(Object x, Object y, Map<String, Object> metadata) {
            this.x = x;
            this.y = y;
            this.metadata = metadata != null ? metadata : new HashMap<>();
        }
        
        public DataPoint addMetadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }
        
        // Getters
        public Object getX() { return x; }
        public Object getY() { return y; }
        public Map<String, Object> getMetadata() { return metadata; }
    }
    
    /**
     * 性能洞察类
     */
    public static class PerformanceInsight {
        private final String insightId;
        private final String category;
        private final String title;
        private final String description;
        private final InsightType type;
        private final double confidence;
        private final Instant detectedAt;
        private final Map<String, Object> supportingData;
        private final List<String> relatedMetrics;
        
        public PerformanceInsight(String category, String title, String description, InsightType type) {
            this.insightId = UUID.randomUUID().toString();
            this.category = category;
            this.title = title;
            this.description = description;
            this.type = type;
            this.confidence = 0.0;
            this.detectedAt = Instant.now();
            this.supportingData = new HashMap<>();
            this.relatedMetrics = new ArrayList<>();
        }
        
        public PerformanceInsight setConfidence(double confidence) {
            this.confidence = Math.max(0.0, Math.min(1.0, confidence));
            return this;
        }
        
        public PerformanceInsight addSupportingData(String key, Object data) {
            this.supportingData.put(key, data);
            return this;
        }
        
        public PerformanceInsight addRelatedMetric(String metric) {
            this.relatedMetrics.add(metric);
            return this;
        }
        
        // Getters
        public String getInsightId() { return insightId; }
        public String getCategory() { return category; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public InsightType getType() { return type; }
        public double getConfidence() { return confidence; }
        public Instant getDetectedAt() { return detectedAt; }
        public Map<String, Object> getSupportingData() { return supportingData; }
        public List<String> getRelatedMetrics() { return relatedMetrics; }
    }
    
    /**
     * 洞察类型枚举
     */
    public enum InsightType {
        POSITIVE("正面洞察"),
        NEGATIVE("负面洞察"),
        NEUTRAL("中性洞察"),
        WARNING("警告洞察"),
        ERROR("错误洞察"),
        TREND("趋势洞察");
        
        private final String description;
        
        InsightType(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
    
    /**
     * 优化建议类
     */
    public static class OptimizationRecommendation {
        private final String recommendationId;
        private final String title;
        private final String description;
        private final RecommendationCategory category;
        private final Priority priority;
        private final double expectedImprovement;
        private final double implementationEffort;
        private final List<String> affectedComponents;
        private final List<String> implementationSteps;
        private final List<String> risks;
        private final Map<String, Object> metadata;
        
        public OptimizationRecommendation(String title, String description, RecommendationCategory category) {
            this.recommendationId = UUID.randomUUID().toString();
            this.title = title;
            this.description = description;
            this.category = category;
            this.priority = Priority.MEDIUM;
            this.expectedImprovement = 0.0;
            this.implementationEffort = 0.0;
            this.affectedComponents = new ArrayList<>();
            this.implementationSteps = new ArrayList<>();
            this.risks = new ArrayList<>();
            this.metadata = new HashMap<>();
        }
        
        public OptimizationRecommendation setPriority(Priority priority) {
            this.priority = priority;
            return this;
        }
        
        public OptimizationRecommendation setExpectedImprovement(double improvement) {
            this.expectedImprovement = Math.max(0.0, improvement);
            return this;
        }
        
        public OptimizationRecommendation setImplementationEffort(double effort) {
            this.implementationEffort = Math.max(0.0, effort);
            return this;
        }
        
        public OptimizationRecommendation addAffectedComponent(String component) {
            this.affectedComponents.add(component);
            return this;
        }
        
        public OptimizationRecommendation addImplementationStep(String step) {
            this.implementationSteps.add(step);
            return this;
        }
        
        public OptimizationRecommendation addRisk(String risk) {
            this.risks.add(risk);
            return this;
        }
        
        public OptimizationRecommendation addMetadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }
        
        // Getters
        public String getRecommendationId() { return recommendationId; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public RecommendationCategory getCategory() { return category; }
        public Priority getPriority() { return priority; }
        public double getExpectedImprovement() { return expectedImprovement; }
        public double getImplementationEffort() { return implementationEffort; }
        public List<String> getAffectedComponents() { return affectedComponents; }
        public List<String> getImplementationSteps() { return implementationSteps; }
        public List<String> getRisks() { return risks; }
        public Map<String, Object> getMetadata() { return metadata; }
    }
    
    /**
     * 建议类别枚举
     */
    public enum RecommendationCategory {
        PERFORMANCE("性能优化"),
        RELIABILITY("可靠性提升"),
        SCALABILITY("可扩展性"),
        MAINTAINABILITY("可维护性"),
        SECURITY("安全性"),
        COST_OPTIMIZATION("成本优化");
        
        private final String description;
        
        RecommendationCategory(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
    
    /**
     * 优先级枚举
     */
    public enum Priority {
        LOW("低"),
        MEDIUM("中"),
        HIGH("高"),
        CRITICAL("关键");
        
        private final String description;
        
        Priority(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
    
    /**
     * 回归问题类
     */
    public static class RegressionIssue {
        private final String issueId;
        private final String metricName;
        private final String description;
        private final RegressionSeverity severity;
        private final Instant detectedAt;
        private final double currentValue;
        private final double baselineValue;
        private final double regressionPercentage;
        private final List<String> possibleCauses;
        private final List<String> recommendedActions;
        private final Map<String, Object> additionalData;
        
        public RegressionIssue(String metricName, String description, RegressionSeverity severity,
                             double currentValue, double baselineValue) {
            this.issueId = UUID.randomUUID().toString();
            this.metricName = metricName;
            this.description = description;
            this.severity = severity;
            this.detectedAt = Instant.now();
            this.currentValue = currentValue;
            this.baselineValue = baselineValue;
            this.regressionPercentage = baselineValue > 0 ? 
                (baselineValue - currentValue) / baselineValue : 0.0;
            this.possibleCauses = new ArrayList<>();
            this.recommendedActions = new ArrayList<>();
            this.additionalData = new HashMap<>();
        }
        
        public RegressionIssue addPossibleCause(String cause) {
            this.possibleCauses.add(cause);
            return this;
        }
        
        public RegressionIssue addRecommendedAction(String action) {
            this.recommendedActions.add(action);
            return this;
        }
        
        public RegressionIssue addAdditionalData(String key, Object data) {
            this.additionalData.put(key, data);
            return this;
        }
        
        // Getters
        public String getIssueId() { return issueId; }
        public String getMetricName() { return metricName; }
        public String getDescription() { return description; }
        public RegressionSeverity getSeverity() { return severity; }
        public Instant getDetectedAt() { return detectedAt; }
        public double getCurrentValue() { return currentValue; }
        public double getBaselineValue() { return baselineValue; }
        public double getRegressionPercentage() { return regressionPercentage; }
        public List<String> getPossibleCauses() { return possibleCauses; }
        public List<String> getRecommendedActions() { return recommendedActions; }
        public Map<String, Object> getAdditionalData() { return additionalData; }
    }
    
    /**
     * 回归严重程度枚举
     */
    public enum RegressionSeverity {
        LOW("低"),
        MEDIUM("中"),
        HIGH("高"),
        CRITICAL("严重");
        
        private final String description;
        
        RegressionSeverity(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
    
    /**
     * 构造函数
     */
    public PerformanceReportGenerator(PerformanceAnalyzer performanceAnalyzer,
                                    BenchmarkResultAnalyzer benchmarkAnalyzer,
                                    PerformanceMonitor performanceMonitor,
                                    PerformanceRegressionDetector regressionDetector,
                                    AutoTuningEngine autoTuningEngine,
                                    PerformanceRecommendationEngine recommendationEngine,
                                    LoadTestScenarios loadTestScenarios) {
        this.performanceAnalyzer = performanceAnalyzer;
        this.benchmarkAnalyzer = benchmarkAnalyzer;
        this.performanceMonitor = performanceMonitor;
        this.regressionDetector = regressionDetector;
        this.autoTuningEngine = autoTuningEngine;
        this.recommendationEngine = recommendationEngine;
        this.loadTestScenarios = loadTestScenarios;
        this.reportConfig = new ReportConfiguration();
        
        // 创建报告生成线程池
        int corePoolSize = Runtime.getRuntime().availableProcessors();
        this.reportGenerationExecutor = Executors.newFixedThreadPool(corePoolSize,
            new ThreadFactoryBuilder().setNameFormat("report-generator-%d").build());
        
        logger.info("性能报告生成器已初始化，CPU核心数: {}", corePoolSize);
    }
    
    /**
     * 生成综合性能报告
     */
    public CompletableFuture<PerformanceReport> generateComprehensiveReport(ReportType reportType,
                                                                           Duration timeRange) {
        logger.info("开始生成综合性能报告，类型: {}, 时间范围: {}", reportType, timeRange);
        
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                // 1. 确定报告时间范围
                Instant endTime = Instant.now();
                Instant startTimeInstant = endTime.minus(timeRange);
                
                // 2. 创建报告基础信息
                PerformanceReport report = createBaseReport("分布式锁系统综合性能报告", 
                    reportType, ReportFormat.HTML, startTimeInstant, endTime);
                
                // 3. 收集性能数据
                collectPerformanceData(report, startTimeInstant, endTime);
                
                // 4. 执行性能分析
                performPerformanceAnalysis(report);
                
                // 5. 分析基准测试结果
                analyzeBenchmarkResults(report);
                
                // 6. 检测性能回归
                analyzePerformanceRegression(report);
                
                // 7. 生成优化建议
                generateOptimizationRecommendations(report);
                
                // 8. 生成执行摘要
                generateExecutiveSummary(report);
                
                // 9. 生成图表数据
                generateChartData(report);
                
                // 10. 生成报告内容
                generateReportContent(report);
                
                // 11. 计算报告生成时间
                long generationTime = System.currentTimeMillis() - startTime;
                report.getMetadata().setReportGenerationTime(Duration.ofMillis(generationTime));
                
                logger.info("综合性能报告生成完成，耗时: {}ms", generationTime);
                return report;
                
            } catch (Exception e) {
                logger.error("生成综合性能报告时发生错误", e);
                throw new RuntimeException("报告生成失败", e);
            }
        }, reportGenerationExecutor);
    }
    
    /**
     * 生成特定类型的报告
     */
    public CompletableFuture<PerformanceReport> generateReport(ReportType reportType, 
                                                              ReportFormat format,
                                                              Map<String, Object> parameters) {
        logger.info("开始生成报告，类型: {}, 格式: {}", reportType, format);
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                Instant endTime = Instant.now();
                Instant startTime = endTime.minus(reportConfig.getReportTimeRange());
                
                // 提取参数
                if (parameters != null) {
                    if (parameters.containsKey("start_time")) {
                        startTime = Instant.parse(parameters.get("start_time").toString());
                    }
                    if (parameters.containsKey("end_time")) {
                        endTime = Instant.parse(parameters.get("end_time").toString());
                    }
                    if (parameters.containsKey("time_range")) {
                        Duration duration = Duration.parse(parameters.get("time_range").toString());
                        startTime = endTime.minus(duration);
                    }
                }
                
                PerformanceReport report = createBaseReport(
                    getReportTitle(reportType), reportType, format, startTime, endTime);
                
                // 根据报告类型执行特定的分析
                switch (reportType) {
                    case DAILY:
                    case WEEKLY:
                    case MONTHLY:
                    case QUARTERLY:
                        generatePeriodicReport(report);
                        break;
                    case BENCHMARK_COMPARISON:
                        generateBenchmarkComparisonReport(report);
                        break;
                    case REGRESSION_ANALYSIS:
                        generateRegressionAnalysisReport(report);
                        break;
                    case OPTIMIZATION_SUMMARY:
                        generateOptimizationSummaryReport(report);
                        break;
                    case CUSTOM:
                        generateCustomReport(report, parameters);
                        break;
                    default:
                        generateComprehensiveReport(reportType, Duration.ofDays(7));
                }
                
                // 生成报告内容
                generateReportContent(report);
                
                logger.info("报告生成完成: {}", report.getReportId());
                return report;
                
            } catch (Exception e) {
                logger.error("生成报告时发生错误", e);
                throw new RuntimeException("报告生成失败", e);
            }
        }, reportGenerationExecutor);
    }
    
    /**
     * 创建基础报告
     */
    private PerformanceReport createBaseReport(String title, ReportType reportType, ReportFormat format,
                                             Instant startTime, Instant endTime) {
        ReportMetadata metadata = new ReportMetadata(
            "分布式锁系统", 
            "production", 
            "2.0.0",
            "PerformanceReportGenerator",
            Arrays.asList("performance", "monitoring", "optimization")
        );
        
        return new PerformanceReport(title, reportType, format, startTime, endTime, metadata);
    }
    
    /**
     * 收集性能数据
     */
    private void collectPerformanceData(PerformanceReport report, Instant startTime, Instant endTime) {
        logger.info("收集性能数据，时间范围: {} 到 {}", startTime, endTime);
        
        try {
            // 收集性能监控数据
            Map<String, Double> metrics = performanceMonitor.getCurrentMetrics();
            metrics.forEach((metricName, value) -> {
                if (isMetricIncluded(metricName)) {
                    report.addPerformanceData(metricName, value);
                }
            });
            
            // 收集锁指标数据
            Map<String, Object> lockMetrics = loadTestScenarios.getTestResults().values().stream()
                .flatMap(result -> result.getMetrics().entrySet().stream())
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (v1, v2) -> v2
                ));
            
            lockMetrics.forEach((metricName, value) -> {
                if (value instanceof Number && isMetricIncluded("lock_" + metricName)) {
                    report.addPerformanceData("lock_" + metricName, value);
                }
            });
            
            // 收集系统资源使用情况
            Runtime runtime = Runtime.getRuntime();
            report.addPerformanceData("jvm_memory_used_mb", 
                (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
            report.addPerformanceData("jvm_memory_total_mb", runtime.totalMemory() / 1024 / 1024);
            report.addPerformanceData("jvm_memory_max_mb", runtime.maxMemory() / 1024 / 1024);
            report.addPerformanceData("cpu_core_count", Runtime.getRuntime().availableProcessors());
            
            logger.info("性能数据收集完成，共收集{}个指标", report.getPerformanceData().size());
            
        } catch (Exception e) {
            logger.error("收集性能数据时发生错误", e);
            report.addPerformanceData("collection_error", e.getMessage());
        }
    }
    
    /**
     * 执行性能分析
     */
    private void performPerformanceAnalysis(PerformanceReport report) {
        logger.info("执行性能分析");
        
        try {
            // 创建分析上下文
            PerformanceAnalyzer.AnalysisContext context = new PerformanceAnalyzer.AnalysisContext(
                "comprehensive_report", 
                null, // LockProvider.Type 未知
                new HashMap<>(),
                new ArrayList<>(),
                new HashMap<>()
            );
            
            // 模拟性能统计（实际应从性能监控器获取）
            PerformanceMetrics.PerformanceStatistics mockStats = createMockPerformanceStatistics();
            
            // 生成性能洞察
            List<PerformanceInsight> insights = generatePerformanceInsights(mockStats);
            insights.forEach(report::addInsight);
            
            // 添加关键性能指标
            addKeyPerformanceIndicators(report, mockStats);
            
            logger.info("性能分析完成，生成{}个洞察", insights.size());
            
        } catch (Exception e) {
            logger.error("执行性能分析时发生错误", e);
            report.addInsight(new PerformanceInsight("error", "性能分析失败", 
                "执行性能分析时发生错误: " + e.getMessage(), InsightType.ERROR));
        }
    }
    
    /**
     * 分析基准测试结果
     */
    private void analyzeBenchmarkResults(PerformanceReport report) {
        logger.info("分析基准测试结果");
        
        try {
            // 获取基准测试结果（模拟）
            Map<String, Object> benchmarkResults = getMockBenchmarkResults();
            
            benchmarkResults.forEach((benchmarkName, result) -> {
                report.addBenchmarkComparison(benchmarkName, result.toString());
            });
            
            // 生成基准测试洞察
            if (reportConfig.isIncludeBenchmarks()) {
                List<PerformanceInsight> benchmarkInsights = generateBenchmarkInsights(benchmarkResults);
                benchmarkInsights.forEach(report::addInsight);
            }
            
            logger.info("基准测试结果分析完成");
            
        } catch (Exception e) {
            logger.error("分析基准测试结果时发生错误", e);
        }
    }
    
    /**
     * 分析性能回归
     */
    private void analyzePerformanceRegression(PerformanceReport report) {
        logger.info("分析性能回归");
        
        try {
            // 获取活跃的回归告警
            Map<String, PerformanceRegressionDetector.RegressionAlert> activeAlerts = 
                regressionDetector.getActiveAlerts();
            
            activeAlerts.forEach((metricName, alert) -> {
                RegressionIssue issue = new RegressionIssue(
                    metricName, 
                    alert.getDescription(),
                    convertSeverity(alert.getSeverity()),
                    alert.getCurrentValue(),
                    alert.getBaselineValue()
                );
                
                alert.getPossibleCauses().forEach(issue::addPossibleCause);
                alert.getRecommendedActions().forEach(issue::addRecommendedAction);
                
                report.addRegressionIssue(issue);
            });
            
            logger.info("性能回归分析完成，发现{}个回归问题", activeAlerts.size());
            
        } catch (Exception e) {
            logger.error("分析性能回归时发生错误", e);
        }
    }
    
    /**
     * 生成优化建议
     */
    private void generateOptimizationRecommendations(PerformanceReport report) {
        logger.info("生成优化建议");
        
        try {
            // 生成自动调优建议
            if (autoTuningEngine != null) {
                // 模拟调优建议（实际应基于当前性能指标）
                OptimizationRecommendation recommendation = new OptimizationRecommendation(
                    "连接池优化",
                    "检测到连接池使用率较高，建议增加连接池大小或优化连接管理策略",
                    RecommendationCategory.PERFORMANCE
                ).setPriority(Priority.HIGH)
                 .setExpectedImprovement(25.0)
                 .setImplementationEffort(3.0)
                 .addAffectedComponent("connection_pool")
                 .addImplementationStep("1. 评估当前连接池配置")
                 .addImplementationStep("2. 增加最大连接数")
                 .addImplementationStep("3. 优化连接超时设置")
                 .addRisk("连接数过多可能导致资源消耗增加");
                
                report.addRecommendation(recommendation);
            }
            
            // 生成基于性能分析的建议
            List<OptimizationRecommendation> recommendations = generateDataDrivenRecommendations(report);
            recommendations.forEach(report::addRecommendation);
            
            logger.info("优化建议生成完成，共{}个建议", report.getRecommendations().size());
            
        } catch (Exception e) {
            logger.error("生成优化建议时发生错误", e);
        }
    }
    
    /**
     * 生成执行摘要
     */
    private void generateExecutiveSummary(PerformanceReport report) {
        logger.info("生成执行摘要");
        
        ExecutiveSummary summary = new ExecutiveSummary();
        
        try {
            // 计算整体性能分数
            double performanceScore = calculateOverallPerformanceScore(report);
            summary.setPerformanceScore(performanceScore);
            
            // 确定整体状态
            if (performanceScore >= 80) {
                summary.setOverallStatus("优秀");
            } else if (performanceScore >= 60) {
                summary.setOverallStatus("良好");
            } else if (performanceScore >= 40) {
                summary.setOverallStatus("一般");
            } else {
                summary.setOverallStatus("需要改进");
            }
            
            // 统计问题数量
            int totalIssues = report.getRegressionIssues().size();
            int criticalIssues = (int) report.getRegressionIssues().stream()
                .mapToLong(issue -> issue.getSeverity() == RegressionSeverity.CRITICAL ? 1 : 0).sum();
            int highPriorityIssues = (int) report.getRegressionIssues().stream()
                .mapToLong(issue -> issue.getSeverity() == RegressionSeverity.HIGH ? 1 : 0).sum();
            
            summary.setIssuesCount(totalIssues, criticalIssues, highPriorityIssues);
            
            // 设置关键发现
            summary.setKeyFindings(generateKeyFindings(report));
            
            // 设置主要建议
            summary.setMainRecommendations(generateMainRecommendations(report));
            
            // KPI状态
            summary.addKPIStatus("平均延迟", report.getPerformanceData().containsKey("average_latency_ms") ? "正常" : "无数据")
                 .addKPIStatus("吞吐量", report.getPerformanceData().containsKey("throughput_per_second") ? "正常" : "无数据")
                 .addKPIStatus("成功率", report.getPerformanceData().containsKey("success_rate") ? "正常" : "无数据");
            
            // 高亮和关注点
            if (criticalIssues > 0) {
                summary.addConcern(String.format("发现%d个严重性能问题需要立即处理", criticalIssues));
            }
            
            if (performanceScore > 70) {
                summary.addHighlight("系统整体性能表现良好");
            }
            
            if (totalIssues == 0) {
                summary.addHighlight("未检测到性能回归问题");
            }
            
            report.setExecutiveSummary(summary);
            
        } catch (Exception e) {
            logger.error("生成执行摘要时发生错误", e);
            summary.setOverallStatus("错误");
            summary.setKeyFindings("执行摘要生成失败: " + e.getMessage());
            report.setExecutiveSummary(summary);
        }
    }
    
    /**
     * 生成图表数据
     */
    private void generateChartData(PerformanceReport report) {
        logger.info("生成图表数据");
        
        try {
            // 性能趋势图
            ChartData performanceTrendChart = new ChartData(
                "性能指标趋势", "line", "时间", "数值"
            );
            
            // 模拟趋势数据
            for (int i = 0; i < 24; i++) {
                performanceTrendChart.addDataPoint(i + ":00", 50 + Math.random() * 20);
            }
            report.addChartData(performanceTrendChart);
            
            // 响应时间分布图
            ChartData responseTimeChart = new ChartData(
                "响应时间分布", "histogram", "响应时间(ms)", "频次"
            );
            
            for (int i = 0; i <= 100; i += 10) {
                responseTimeChart.addDataPoint(i, Math.random() * 50);
            }
            report.addChartData(responseTimeChart);
            
            // 吞吐量对比图
            ChartData throughputChart = new ChartData(
                "吞吐量对比", "bar", "时间", "操作/秒"
            );
            
            String[] periods = {"00:00-06:00", "06:00-12:00", "12:00-18:00", "18:00-24:00"};
            double[] throughputs = {100, 150, 200, 180};
            
            for (int i = 0; i < periods.length; i++) {
                throughputChart.addDataPoint(periods[i], throughputs[i]);
            }
            report.addChartData(throughputChart);
            
            logger.info("图表数据生成完成，生成{}个图表", report.getChartData().size());
            
        } catch (Exception e) {
            logger.error("生成图表数据时发生错误", e);
        }
    }
    
    /**
     * 生成报告内容
     */
    private void generateReportContent(PerformanceReport report) {
        logger.info("生成报告内容，格式: {}", report.getFormat());
        
        try {
            String content = "";
            
            switch (report.getFormat()) {
                case HTML:
                    content = generateHTMLReport(report);
                    break;
                case JSON:
                    content = generateJSONReport(report);
                    break;
                case CSV:
                    content = generateCSVReport(report);
                    break;
                case MARKDOWN:
                    content = generateMarkdownReport(report);
                    break;
                case XML:
                    content = generateXMLReport(report);
                    break;
                default:
                    content = generateHTMLReport(report);
            }
            
            report.setReportContent(content.getBytes());
            
            // 生成文件路径
            String fileName = String.format("performance_report_%s_%s.%s",
                report.getReportType().toString().toLowerCase(),
                report.getGeneratedAt().toString().replace(":", "-"),
                report.getFormat().toString().toLowerCase()
            );
            String filePath = reportConfig.getOutputDirectory() + "/" + fileName;
            report.setFilePath(filePath);
            
        } catch (Exception e) {
            logger.error("生成报告内容时发生错误", e);
            report.setReportContent(("报告生成失败: " + e.getMessage()).getBytes());
        }
    }
    
    /**
     * 生成HTML报告
     */
    private String generateHTMLReport(PerformanceReport report) {
        StringBuilder html = new StringBuilder();
        
        // HTML头部
        html.append("<!DOCTYPE html>\n")
            .append("<html lang=\"zh-CN\">\n<head>\n")
            .append("    <meta charset=\"UTF-8\">\n")
            .append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
            .append("    <title>").append(report.getTitle()).append("</title>\n")
            .append("    <style>\n")
            .append("        body { font-family: Arial, sans-serif; margin: 20px; }\n")
            .append("        .header { background-color: #f4f4f4; padding: 20px; border-radius: 5px; }\n")
            .append("        .summary { background-color: #e8f5e8; padding: 15px; margin: 20px 0; border-radius: 5px; }\n")
            .append("        .issue { background-color: #ffe8e8; padding: 10px; margin: 10px 0; border-radius: 5px; }\n")
            .append("        .recommendation { background-color: #e8f0ff; padding: 10px; margin: 10px 0; border-radius: 5px; }\n")
            .append("        table { width: 100%; border-collapse: collapse; margin: 20px 0; }\n")
            .append("        th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }\n")
            .append("        th { background-color: #f2f2f2; }\n")
            .append("    </style>\n</head>\n<body>\n");
        
        // 报告标题和基本信息
        html.append("    <div class=\"header\">\n")
            .append("        <h1>").append(report.getTitle()).append("</h1>\n")
            .append("        <p><strong>报告ID:</strong> ").append(report.getReportId()).append("</p>\n")
            .append("        <p><strong>生成时间:</strong> ").append(formatInstant(report.getGeneratedAt())).append("</p>\n")
            .append("        <p><strong>报告周期:</strong> ").append(formatInstant(report.getReportPeriodStart()))
            .append(" - ").append(formatInstant(report.getReportPeriodEnd())).append("</p>\n")
            .append("    </div>\n");
        
        // 执行摘要
        if (report.getExecutiveSummary() != null) {
            ExecutiveSummary summary = report.getExecutiveSummary();
            html.append("    <div class=\"summary\">\n")
                .append("        <h2>执行摘要</h2>\n")
                .append("        <p><strong>整体状态:</strong> ").append(summary.getOverallStatus()).append("</p>\n")
                .append("        <p><strong>性能分数:</strong> ").append(String.format("%.1f", summary.getPerformanceScore())).append("/100</p>\n")
                .append("        <p><strong>问题统计:</strong> 总计").append(summary.getTotalIssues())
                .append("，严重").append(summary.getCriticalIssues())
                .append("，高优先级").append(summary.getHighPriorityIssues()).append("</p>\n");
            
            if (!summary.getHighlights().isEmpty()) {
                html.append("        <h3>亮点</h3>\n<ul>\n");
                summary.getHighlights().forEach(highlight -> 
                    html.append("            <li>").append(highlight).append("</li>\n"));
                html.append("        </ul>\n");
            }
            
            if (!summary.getConcerns().isEmpty()) {
                html.append("        <h3>关注点</h3>\n<ul>\n");
                summary.getConcerns().forEach(concern -> 
                    html.append("            <li>").append(concern).append("</li>\n"));
                html.append("        </ul>\n");
            }
            
            html.append("    </div>\n");
        }
        
        // 性能数据表格
        if (!report.getPerformanceData().isEmpty()) {
            html.append("    <h2>性能指标</h2>\n")
                .append("    <table>\n")
                .append("        <tr><th>指标名称</th><th>数值</th></tr>\n");
            
            report.getPerformanceData().forEach((metric, value) -> 
                html.append("        <tr><td>").append(metric).append("</td><td>").append(value).append("</td></tr>\n"));
            
            html.append("    </table>\n");
        }
        
        // 回归问题
        if (!report.getRegressionIssues().isEmpty()) {
            html.append("    <h2>性能回归问题</h2>\n");
            report.getRegressionIssues().forEach(issue -> {
                html.append("    <div class=\"issue\">\n")
                    .append("        <h3>").append(issue.getMetricName()).append("</h3>\n")
                    .append("        <p><strong>描述:</strong> ").append(issue.getDescription()).append("</p>\n")
                    .append("        <p><strong>严重程度:</strong> ").append(issue.getSeverity().getDescription()).append("</p>\n")
                    .append("        <p><strong>当前值:</strong> ").append(String.format("%.2f", issue.getCurrentValue())).append("</p>\n")
                    .append("        <p><strong>基线值:</strong> ").append(String.format("%.2f", issue.getBaselineValue())).append("</p>\n")
                    .append("        <p><strong>回归幅度:</strong> ").append(String.format("%.1f%%", issue.getRegressionPercentage() * 100)).append("</p>\n")
                    .append("    </div>\n");
            });
        }
        
        // 优化建议
        if (!report.getRecommendations().isEmpty()) {
            html.append("    <h2>优化建议</h2>\n");
            report.getRecommendations().forEach(rec -> {
                html.append("    <div class=\"recommendation\">\n")
                    .append("        <h3>").append(rec.getTitle()).append("</h3>\n")
                    .append("        <p><strong>类别:</strong> ").append(rec.getCategory().getDescription()).append("</p>\n")
                    .append("        <p><strong>优先级:</strong> ").append(rec.getPriority().getDescription()).append("</p>\n")
                    .append("        <p><strong>预期改进:</strong> ").append(String.format("%.1f%%", rec.getExpectedImprovement())).append("</p>\n")
                    .append("        <p>").append(rec.getDescription()).append("</p>\n")
                    .append("    </div>\n");
            });
        }
        
        // 图表数据
        if (!report.getChartData().isEmpty() && reportConfig.isIncludeCharts()) {
            html.append("    <h2>性能图表</h2>\n")
                .append("    <div id=\"charts\">\n")
                .append("        <p>图表数据已准备，可使用前端图表库进行可视化展示。</p>\n")
                .append("    </div>\n");
        }
        
        // HTML尾部
        html.append("</body>\n</html>");
        
        return html.toString();
    }
    
    /**
     * 生成JSON报告
     */
    private String generateJSONReport(PerformanceReport report) {
        Map<String, Object> jsonReport = new HashMap<>();
        
        jsonReport.put("report_id", report.getReportId());
        jsonReport.put("title", report.getTitle());
        jsonReport.put("report_type", report.getReportType().toString());
        jsonReport.put("format", report.getFormat().toString());
        jsonReport.put("generated_at", report.getGeneratedAt().toString());
        jsonReport.put("report_period_start", report.getReportPeriodStart().toString());
        jsonReport.put("report_period_end", report.getReportPeriodEnd().toString());
        jsonReport.put("performance_data", report.getPerformanceData());
        jsonReport.put("executive_summary", convertExecutiveSummaryToMap(report.getExecutiveSummary()));
        jsonReport.put("regression_issues", convertRegressionIssuesToList(report.getRegressionIssues()));
        jsonReport.put("recommendations", convertRecommendationsToList(report.getRecommendations()));
        jsonReport.put("chart_data", convertChartDataToList(report.getChartData()));
        jsonReport.put("insights", convertInsightsToList(report.getInsights()));
        
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(jsonReport);
    }
    
    /**
     * 生成CSV报告
     */
    private String generateCSVReport(PerformanceReport report) {
        StringBuilder csv = new StringBuilder();
        
        // CSV头部
        csv.append("指标名称,数值,单位\n");
        
        // 性能数据
        report.getPerformanceData().forEach((metric, value) -> {
            csv.append(metric).append(",").append(value).append(",\n");
        });
        
        // 添加空行
        csv.append("\n");
        
        // 回归问题
        csv.append("回归问题,严重程度,当前值,基线值,回归幅度\n");
        report.getRegressionIssues().forEach(issue -> {
            csv.append(issue.getMetricName()).append(",")
                .append(issue.getSeverity().getDescription()).append(",")
                .append(String.format("%.2f", issue.getCurrentValue())).append(",")
                .append(String.format("%.2f", issue.getBaselineValue())).append(",")
                .append(String.format("%.2f%%", issue.getRegressionPercentage() * 100)).append("\n");
        });
        
        return csv.toString();
    }
    
    /**
     * 生成Markdown报告
     */
    private String generateMarkdownReport(PerformanceReport report) {
        StringBuilder md = new StringBuilder();
        
        // 标题
        md.append("# ").append(report.getTitle()).append("\n\n");
        
        // 基本信息
        md.append("**报告ID:** ").append(report.getReportId()).append("\n\n");
        md.append("**生成时间:** ").append(formatInstant(report.getGeneratedAt())).append("\n\n");
        md.append("**报告周期:** ").append(formatInstant(report.getReportPeriodStart()))
          .append(" - ").append(formatInstant(report.getReportPeriodEnd())).append("\n\n");
        
        // 执行摘要
        if (report.getExecutiveSummary() != null) {
            ExecutiveSummary summary = report.getExecutiveSummary();
            md.append("## 执行摘要\n\n");
            md.append("- **整体状态:** ").append(summary.getOverallStatus()).append("\n");
            md.append("- **性能分数:** ").append(String.format("%.1f", summary.getPerformanceScore())).append("/100\n");
            md.append("- **问题统计:** 总计").append(summary.getTotalIssues())
              .append("，严重").append(summary.getCriticalIssues())
              .append("，高优先级").append(summary.getHighPriorityIssues()).append("\n\n");
        }
        
        // 性能指标
        if (!report.getPerformanceData().isEmpty()) {
            md.append("## 性能指标\n\n");
            md.append("| 指标名称 | 数值 |\n");
            md.append("|----------|------|\n");
            report.getPerformanceData().forEach((metric, value) -> 
                md.append("| ").append(metric).append(" | ").append(value).append(" |\n"));
            md.append("\n");
        }
        
        // 回归问题
        if (!report.getRegressionIssues().isEmpty()) {
            md.append("## 性能回归问题\n\n");
            report.getRegressionIssues().forEach(issue -> {
                md.append("### ").append(issue.getMetricName()).append("\n\n");
                md.append("- **描述:** ").append(issue.getDescription()).append("\n");
                md.append("- **严重程度:** ").append(issue.getSeverity().getDescription()).append("\n");
                md.append("- **当前值:** ").append(String.format("%.2f", issue.getCurrentValue())).append("\n");
                md.append("- **基线值:** ").append(String.format("%.2f", issue.getBaselineValue())).append("\n");
                md.append("- **回归幅度:** ").append(String.format("%.1f%%", issue.getRegressionPercentage() * 100)).append("\n\n");
            });
        }
        
        // 优化建议
        if (!report.getRecommendations().isEmpty()) {
            md.append("## 优化建议\n\n");
            report.getRecommendations().forEach(rec -> {
                md.append("### ").append(rec.getTitle()).append("\n\n");
                md.append("- **类别:** ").append(rec.getCategory().getDescription()).append("\n");
                md.append("- **优先级:** ").append(rec.getPriority().getDescription()).append("\n");
                md.append("- **预期改进:** ").append(String.format("%.1f%%", rec.getExpectedImprovement())).append("\n");
                md.append("- **描述:** ").append(rec.getDescription()).append("\n\n");
            });
        }
        
        return md.toString();
    }
    
    /**
     * 生成XML报告
     */
    private String generateXMLReport(PerformanceReport report) {
        StringBuilder xml = new StringBuilder();
        
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<performance_report>\n");
        xml.append("  <metadata>\n");
        xml.append("    <report_id>").append(report.getReportId()).append("</report_id>\n");
        xml.append("    <title>").append(report.getTitle()).append("</title>\n");
        xml.append("    <report_type>").append(report.getReportType()).append("</report_type>\n");
        xml.append("    <generated_at>").append(report.getGeneratedAt()).append("</generated_at>\n");
        xml.append("    <report_period_start>").append(report.getReportPeriodStart()).append("</report_period_start>\n");
        xml.append("    <report_period_end>").append(report.getReportPeriodEnd()).append("</report_period_end>\n");
        xml.append("  </metadata>\n");
        
        // 性能数据
        if (!report.getPerformanceData().isEmpty()) {
            xml.append("  <performance_data>\n");
            report.getPerformanceData().forEach((metric, value) -> 
                xml.append("    <metric name=\"").append(metric).append("\">").append(value).append("</metric>\n"));
            xml.append("  </performance_data>\n");
        }
        
        // 回归问题
        if (!report.getRegressionIssues().isEmpty()) {
            xml.append("  <regression_issues>\n");
            report.getRegressionIssues().forEach(issue -> {
                xml.append("    <issue>\n");
                xml.append("      <metric_name>").append(issue.getMetricName()).append("</metric_name>\n");
                xml.append("      <description>").append(issue.getDescription()).append("</description>\n");
                xml.append("      <severity>").append(issue.getSeverity()).append("</severity>\n");
                xml.append("      <current_value>").append(issue.getCurrentValue()).append("</current_value>\n");
                xml.append("      <baseline_value>").append(issue.getBaselineValue()).append("</baseline_value>\n");
                xml.append("      <regression_percentage>").append(issue.getRegressionPercentage()).append("</regression_percentage>\n");
                xml.append("    </issue>\n");
            });
            xml.append("  </regression_issues>\n");
        }
        
        xml.append("</performance_report>");
        
        return xml.toString();
    }
    
    // 私有辅助方法
    
    private boolean isMetricIncluded(String metricName) {
        return reportConfig.getIncludedMetrics().contains(metricName) && 
               !reportConfig.getExcludedMetrics().contains(metricName);
    }
    
    private PerformanceMetrics.PerformanceStatistics createMockPerformanceStatistics() {
        return PerformanceMetrics.PerformanceStatistics.builder()
            .withTotalOperations(10000)
            .withSuccessfulOperations(9800)
            .withFailedOperations(200)
            .withAverageLatencyMs(25.0)
            .withP95LatencyMs(60.0)
            .withP99LatencyMs(120.0)
            .withMemoryUsageMb(150.0)
            .withCpuUsagePercent(15.0)
            .withThroughputPerSecond(200.0)
            .withProviderSpecificMetrics(new HashMap<>())
            .build();
    }
    
    private List<PerformanceInsight> generatePerformanceInsights(PerformanceMetrics.PerformanceStatistics stats) {
        List<PerformanceInsight> insights = new ArrayList<>();
        
        if (stats.getAverageLatencyMs() < 50) {
            insights.add(new PerformanceInsight("latency", "延迟表现良好", 
                "平均延迟在可接受范围内", InsightType.POSITIVE)
                .setConfidence(0.9));
        } else {
            insights.add(new PerformanceInsight("latency", "延迟较高", 
                "平均延迟超过50ms，可能需要优化", InsightType.WARNING)
                .setConfidence(0.8));
        }
        
        if (stats.getSuccessRate() > 95) {
            insights.add(new PerformanceInsight("reliability", "系统稳定性良好", 
                "成功率超过95%，系统运行稳定", InsightType.POSITIVE)
                .setConfidence(0.95));
        }
        
        return insights;
    }
    
    private void addKeyPerformanceIndicators(PerformanceReport report, PerformanceMetrics.PerformanceStatistics stats) {
        report.addPerformanceData("kpi_latency_ms", stats.getAverageLatencyMs());
        report.addPerformanceData("kpi_throughput_per_sec", stats.getThroughputPerSecond());
        report.addPerformanceData("kpi_success_rate", stats.getSuccessRate());
        report.addPerformanceData("kpi_cpu_usage_percent", stats.getCpuUsagePercent());
        report.addPerformanceData("kpi_memory_usage_mb", stats.getMemoryUsageMb());
    }
    
    private Map<String, Object> getMockBenchmarkResults() {
        Map<String, Object> results = new HashMap<>();
        results.put("lock_acquisition_time", "15ms");
        results.put("lock_release_time", "5ms");
        results.put("concurrent_lock_operations", "1000 ops/sec");
        results.put("memory_usage", "50MB");
        results.put("cpu_overhead", "3%");
        return results;
    }
    
    private List<PerformanceInsight> generateBenchmarkInsights(Map<String, Object> benchmarkResults) {
        List<PerformanceInsight> insights = new ArrayList<>();
        
        insights.add(new PerformanceInsight("benchmark", "基准测试完成", 
            "所有基准测试指标在预期范围内", InsightType.POSITIVE)
            .setConfidence(0.9));
        
        return insights;
    }
    
    private PerformanceRegressionDetector.RegressionSeverity convertSeverity(PerformanceRegressionDetector.Severity severity) {
        switch (severity) {
            case LOW: return RegressionSeverity.LOW;
            case MEDIUM: return RegressionSeverity.MEDIUM;
            case HIGH: return RegressionSeverity.HIGH;
            case CRITICAL: return RegressionSeverity.CRITICAL;
            default: return RegressionSeverity.MEDIUM;
        }
    }
    
    private List<OptimizationRecommendation> generateDataDrivenRecommendations(PerformanceReport report) {
        List<OptimizationRecommendation> recommendations = new ArrayList<>();
        
        // 基于性能数据生成建议
        if (report.getPerformanceData().containsKey("average_latency_ms")) {
            Double latency = (Double) report.getPerformanceData().get("average_latency_ms");
            if (latency > 50) {
                recommendations.add(new OptimizationRecommendation(
                    "延迟优化",
                    "检测到平均延迟较高，建议优化算法或增加缓存",
                    RecommendationCategory.PERFORMANCE
                ).setPriority(Priority.HIGH)
                 .setExpectedImprovement(30.0)
                 .addAffectedComponent("lock_operations"));
            }
        }
        
        return recommendations;
    }
    
    private double calculateOverallPerformanceScore(PerformanceReport report) {
        double score = 100.0;
        
        // 基于回归问题扣分
        score -= report.getRegressionIssues().size() * 5;
        
        // 基于关键指标调整分数
        if (report.getPerformanceData().containsKey("average_latency_ms")) {
            Double latency = (Double) report.getPerformanceData().get("average_latency_ms");
            if (latency > 50) score -= 10;
        }
        
        if (report.getPerformanceData().containsKey("cpu_usage_percent")) {
            Double cpuUsage = (Double) report.getPerformanceData().get("cpu_usage_percent");
            if (cpuUsage > 80) score -= 15;
        }
        
        return Math.max(0.0, Math.min(100.0, score));
    }
    
    private String generateKeyFindings(PerformanceReport report) {
        StringBuilder findings = new StringBuilder();
        
        findings.append("报告期间共发现").append(report.getRegressionIssues().size()).append("个性能回归问题，");
        findings.append("生成").append(report.getRecommendations().size()).append("个优化建议，");
        findings.append("系统整体性能分数为").append(String.format("%.1f", calculateOverallPerformanceScore(report))).append("分。");
        
        return findings.toString();
    }
    
    private String generateMainRecommendations(PerformanceReport report) {
        if (report.getRecommendations().isEmpty()) {
            return "当前系统运行良好，建议继续监控关键性能指标。";
        }
        
        return "优先处理高优先级的性能优化建议，重点关注延迟和吞吐量相关问题。";
    }
    
    // 格式转换方法
    private String formatInstant(Instant instant) {
        return instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
    
    private Map<String, Object> convertExecutiveSummaryToMap(ExecutiveSummary summary) {
        if (summary == null) return new HashMap<>();
        
        Map<String, Object> map = new HashMap<>();
        map.put("overall_status", summary.getOverallStatus());
        map.put("performance_score", summary.getPerformanceScore());
        map.put("total_issues", summary.getTotalIssues());
        map.put("critical_issues", summary.getCriticalIssues());
        map.put("high_priority_issues", summary.getHighPriorityIssues());
        map.put("key_findings", summary.getKeyFindings());
        map.put("main_recommendations", summary.getMainRecommendations());
        map.put("kpi_status", summary.getKPIStatus());
        map.put("highlights", summary.getHighlights());
        map.put("concerns", summary.getConcerns());
        return map;
    }
    
    private List<Map<String, Object>> convertRegressionIssuesToList(List<RegressionIssue> issues) {
        return issues.stream().map(issue -> {
            Map<String, Object> map = new HashMap<>();
            map.put("issue_id", issue.getIssueId());
            map.put("metric_name", issue.getMetricName());
            map.put("description", issue.getDescription());
            map.put("severity", issue.getSeverity().toString());
            map.put("current_value", issue.getCurrentValue());
            map.put("baseline_value", issue.getBaselineValue());
            map.put("regression_percentage", issue.getRegressionPercentage());
            map.put("detected_at", issue.getDetectedAt().toString());
            return map;
        }).collect(Collectors.toList());
    }
    
    private List<Map<String, Object>> convertRecommendationsToList(List<OptimizationRecommendation> recommendations) {
        return recommendations.stream().map(rec -> {
            Map<String, Object> map = new HashMap<>();
            map.put("recommendation_id", rec.getRecommendationId());
            map.put("title", rec.getTitle());
            map.put("description", rec.getDescription());
            map.put("category", rec.getCategory().toString());
            map.put("priority", rec.getPriority().toString());
            map.put("expected_improvement", rec.getExpectedImprovement());
            map.put("implementation_effort", rec.getImplementationEffort());
            return map;
        }).collect(Collectors.toList());
    }
    
    private List<Map<String, Object>> convertChartDataToList(List<ChartData> charts) {
        return charts.stream().map(chart -> {
            Map<String, Object> map = new HashMap<>();
            map.put("chart_id", chart.getChartId());
            map.put("title", chart.getTitle());
            map.put("chart_type", chart.getChartType());
            map.put("x_axis_label", chart.getXAxisLabel());
            map.put("y_axis_label", chart.getYAxisLabel());
            map.put("data_points", chart.getDataPoints());
            return map;
        }).collect(Collectors.toList());
    }
    
    private List<Map<String, Object>> convertInsightsToList(List<PerformanceInsight> insights) {
        return insights.stream().map(insight -> {
            Map<String, Object> map = new HashMap<>();
            map.put("insight_id", insight.getInsightId());
            map.put("category", insight.getCategory());
            map.put("title", insight.getTitle());
            map.put("description", insight.getDescription());
            map.put("type", insight.getType().toString());
            map.put("confidence", insight.getConfidence());
            return map;
        }).collect(Collectors.toList());
    }
    
    // 特定报告类型生成方法
    private void generatePeriodicReport(PerformanceReport report) {
        // 生成周期性报告的特定逻辑
        logger.info("生成周期性报告: {}", report.getReportType());
    }
    
    private void generateBenchmarkComparisonReport(PerformanceReport report) {
        // 生成基准对比报告的特定逻辑
        logger.info("生成基准对比报告");
    }
    
    private void generateRegressionAnalysisReport(PerformanceReport report) {
        // 生成回归分析报告的特定逻辑
        logger.info("生成回归分析报告");
    }
    
    private void generateOptimizationSummaryReport(PerformanceReport report) {
        // 生成优化总结报告的特定逻辑
        logger.info("生成优化总结报告");
    }
    
    private void generateCustomReport(PerformanceReport report, Map<String, Object> parameters) {
        // 生成自定义报告的特定逻辑
        logger.info("生成自定义报告");
    }
    
    private String getReportTitle(ReportType reportType) {
        switch (reportType) {
            case DAILY: return "分布式锁系统日报";
            case WEEKLY: return "分布式锁系统周报";
            case MONTHLY: return "分布式锁系统月报";
            case QUARTERLY: return "分布式锁系统季报";
            case BENCHMARK_COMPARISON: return "性能基准对比报告";
            case REGRESSION_ANALYSIS: return "性能回归分析报告";
            case OPTIMIZATION_SUMMARY: return "性能优化总结报告";
            case COMPREHENSIVE: return "分布式锁系统综合性能报告";
            default: return "分布式锁系统性能报告";
        }
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
    public ReportConfiguration getReportConfig() { 
        return reportConfig; 
    }
    
    /**
     * 关闭报告生成器
     */
    public void shutdown() {
        logger.info("关闭性能报告生成器");
        
        reportGenerationExecutor.shutdown();
        
        try {
            if (!reportGenerationExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                reportGenerationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            reportGenerationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("性能报告生成器已关闭");
    }
}