package com.mycorp.distributedlock.core.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 基准测试结果分析器
 * 
 * 功能特性：
 * 1. JMH基准测试结果解析
 * 2. 性能指标对比分析
 * 3. 性能趋势分析
 * 4. 性能瓶颈识别
 * 5. 基准测试历史记录管理
 * 6. 性能回归检测
 * 7. 性能报告生成
 */
public class BenchmarkResultAnalyzer {
    
    private final Logger logger = LoggerFactory.getLogger(BenchmarkResultAnalyzer.class);
    
    // 配置参数
    private final AnalyzerConfig config;
    
    // 历史结果存储
    private final Map<String, BenchmarkResult> historicalResults;
    private final Map<String, PerformanceTrend> performanceTrends;
    
    // 分析统计
    private final LongAdder totalAnalyzedBenchmarks = new LongAdder();
    private final LongAdder totalRegressionsDetected = new LongAdder();
    private final LongAdder totalImprovementsDetected = new LongAdder();
    private final AtomicInteger analysisCycle = new AtomicInteger(0);
    
    // 基准测试结果缓存
    private final Map<String, CachedBenchmarkResult> resultCache;
    
    public BenchmarkResultAnalyzer(AnalyzerConfig config) {
        this.config = config;
        this.historicalResults = new ConcurrentHashMap<>();
        this.performanceTrends = new ConcurrentHashMap<>();
        this.resultCache = new ConcurrentHashMap<>();
        
        loadHistoricalData();
        
        logger.info("基准测试结果分析器初始化完成 - 历史记录: {} 条, 分析周期: {}s",
                historicalResults.size(), config.getAnalysisIntervalSeconds());
    }
    
    /**
     * 加载历史数据
     */
    private void loadHistoricalData() {
        if (config.getHistoricalDataDirectory() == null) {
            return;
        }
        
        try {
            Path dataDir = Paths.get(config.getHistoricalDataDirectory());
            if (Files.exists(dataDir) && Files.isDirectory(dataDir)) {
                Files.list(dataDir)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .forEach(this::loadBenchmarkResultFile);
            }
        } catch (Exception e) {
            logger.warn("加载历史数据失败", e);
        }
    }
    
    /**
     * 加载基准测试结果文件
     */
    private void loadBenchmarkResultFile(Path filePath) {
        try {
            String content = Files.readString(filePath);
            BenchmarkResult result = parseBenchmarkResult(content);
            
            if (result != null) {
                historicalResults.put(result.getBenchmarkId(), result);
                
                // 更新性能趋势
                updatePerformanceTrend(result);
            }
            
        } catch (Exception e) {
            logger.warn("加载基准测试结果文件失败: {}", filePath, e);
        }
    }
    
    /**
     * 解析基准测试结果
     */
    private BenchmarkResult parseBenchmarkResult(String jsonContent) {
        try {
            // 简化的JSON解析（实际实现中应使用Jackson或Gson）
            // 这里只是示例逻辑
            if (jsonContent.contains("\"benchmark\"")) {
                String benchmarkId = extractJsonField(jsonContent, "benchmark");
                double score = Double.parseDouble(extractJsonField(jsonContent, "score"));
                double scoreError = Double.parseDouble(extractJsonField(jsonContent, "scoreError"));
                String unit = extractJsonField(jsonContent, "unit");
                int sampleCount = Integer.parseInt(extractJsonField(jsonContent, "sampleCount"));
                
                return new BenchmarkResult(
                        benchmarkId,
                        score,
                        scoreError,
                        unit,
                        sampleCount,
                        System.currentTimeMillis(),
                        extractJsonField(jsonContent, "mode"),
                        extractJsonField(jsonContent, "threads")
                );
            }
        } catch (Exception e) {
            logger.warn("解析基准测试结果失败", e);
        }
        
        return null;
    }
    
    /**
     * 提取JSON字段值
     */
    private String extractJsonField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*\"?([^\",}]+)\"?");
        var matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }
    
    /**
     * 分析JMH基准测试输出
     */
    public BenchmarkAnalysisResult analyzeJMHOutput(String jmhOutput) {
        long startTime = System.currentTimeMillis();
        analysisCycle.incrementAndGet();
        
        try {
            List<BenchmarkResult> results = parseJMHOutput(jmhOutput);
            
            if (results.isEmpty()) {
                return new BenchmarkAnalysisResult("未找到有效的基准测试结果");
            }
            
            // 对每个基准测试结果进行分析
            List<BenchmarkComparison> comparisons = new ArrayList<>();
            List<PerformanceIssue> issues = new ArrayList<>();
            List<PerformanceTrend> trends = new ArrayList<>();
            
            for (BenchmarkResult result : results) {
                BenchmarkComparison comparison = compareWithHistorical(result);
                if (comparison != null) {
                    comparisons.add(comparison);
                }
                
                // 检测性能问题
                List<PerformanceIssue> resultIssues = detectPerformanceIssues(result);
                issues.addAll(resultIssues);
                
                // 分析性能趋势
                PerformanceTrend trend = analyzePerformanceTrend(result);
                if (trend != null) {
                    trends.add(trend);
                }
                
                // 缓存结果
                cacheBenchmarkResult(result);
            }
            
            // 生成建议
            List<PerformanceRecommendation> recommendations = generatePerformanceRecommendations(
                    comparisons, issues, trends);
            
            totalAnalyzedBenchmarks.add(results.size());
            
            long analysisTime = System.currentTimeMillis() - startTime;
            
            return new BenchmarkAnalysisResult(
                    "基准测试分析完成",
                    results,
                    comparisons,
                    issues,
                    trends,
                    recommendations,
                    analysisTime,
                    generateSummaryStatistics(results)
            );
            
        } catch (Exception e) {
            logger.error("分析JMH基准测试输出异常", e);
            return new BenchmarkAnalysisResult("分析失败: " + e.getMessage());
        }
    }
    
    /**
     * 解析JMH输出
     */
    private List<BenchmarkResult> parseJMHOutput(String jmhOutput) {
        List<BenchmarkResult> results = new ArrayList<>();
        
        // 解析JMH的CSV格式输出
        String[] lines = jmhOutput.split("\n");
        
        for (String line : lines) {
            line = line.trim();
            
            // 跳过标题行和空行
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("Benchmark")) {
                continue;
            }
            
            // 解析CSV格式: Benchmark, Mode, Cnt, Score, Error, Unit
            try {
                String[] parts = line.split(",");
                if (parts.length >= 6) {
                    String benchmarkName = parts[0].trim();
                    String mode = parts[1].trim();
                    String count = parts[2].trim();
                    double score = Double.parseDouble(parts[3].trim());
                    double error = parseErrorValue(parts[4].trim());
                    String unit = parts[5].trim();
                    
                    // 提取线程数
                    int threads = extractThreadCount(benchmarkName);
                    
                    // 提取测试类型
                    String testType = extractTestType(benchmarkName);
                    
                    BenchmarkResult result = new BenchmarkResult(
                            benchmarkName,
                            score,
                            error,
                            unit,
                            threads,
                            System.currentTimeMillis(),
                            mode,
                            testType
                    );
                    
                    results.add(result);
                }
            } catch (NumberFormatException e) {
                logger.debug("解析基准测试行失败: {}", line, e);
            }
        }
        
        return results;
    }
    
    /**
     * 解析错误值
     */
    private double parseErrorValue(String errorStr) {
        if (errorStr.equals("±") || errorStr.isEmpty()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(errorStr);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
    
    /**
     * 提取线程数
     */
    private int extractThreadCount(String benchmarkName) {
        Pattern pattern = Pattern.compile("(\\d+)threads?");
        var matcher = pattern.matcher(benchmarkName);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 1; // 默认单线程
    }
    
    /**
     * 提取测试类型
     */
    private String extractTestType(String benchmarkName) {
        if (benchmarkName.contains("redis")) {
            return "redis";
        } else if (benchmarkName.contains("zookeeper") || benchmarkName.contains("zk")) {
            return "zookeeper";
        } else if (benchmarkName.contains("readwrite")) {
            return "readwrite";
        } else if (benchmarkName.contains("batch")) {
            return "batch";
        } else if (benchmarkName.contains("fair")) {
            return "fair";
        } else if (benchmarkName.contains("concurrent")) {
            return "concurrent";
        }
        return "unknown";
    }
    
    /**
     * 与历史数据对比
     */
    private BenchmarkComparison compareWithHistorical(BenchmarkResult currentResult) {
        BenchmarkResult historicalResult = historicalResults.get(currentResult.getBenchmarkId());
        
        if (historicalResult == null) {
            // 没有历史数据，创建基准
            historicalResults.put(currentResult.getBenchmarkId(), currentResult);
            return new BenchmarkComparison(
                    currentResult,
                    null,
                    0.0,
                    "基准建立",
                    "首次运行，建立性能基线"
            );
        }
        
        // 计算性能变化
        double percentChange = ((currentResult.getScore() - historicalResult.getScore()) 
                / historicalResult.getScore()) * 100;
        
        String status = determineComparisonStatus(percentChange, currentResult.getError());
        String description = generateComparisonDescription(percentChange, currentResult.getTestType());
        
        // 更新历史记录
        historicalResults.put(currentResult.getBenchmarkId(), currentResult);
        
        // 更新统计
        if (percentChange < -5.0) { // 性能下降超过5%
            totalRegressionsDetected.incrementAndGet();
        } else if (percentChange > 5.0) { // 性能提升超过5%
            totalImprovementsDetected.incrementAndGet();
        }
        
        return new BenchmarkComparison(
                currentResult,
                historicalResult,
                percentChange,
                status,
                description
        );
    }
    
    /**
     * 确定对比状态
     */
    private String determineComparisonStatus(double percentChange, double error) {
        double threshold = config.getRegressionThreshold();
        double improvementThreshold = config.getImprovementThreshold();
        
        if (Math.abs(percentChange) <= error) {
            return "稳定"; // 变化在误差范围内
        } else if (percentChange < -threshold) {
            return "回归"; // 性能下降
        } else if (percentChange > improvementThreshold) {
            return "改进"; // 性能提升
        } else {
            return "正常"; // 正常波动
        }
    }
    
    /**
     * 生成对比描述
     */
    private String generateComparisonDescription(double percentChange, String testType) {
        StringBuilder description = new StringBuilder();
        
        description.append(String.format("性能变化: %.2f%%", percentChange));
        
        if (Math.abs(percentChange) > 10) {
            description.append(" (显著变化)");
        } else if (Math.abs(percentChange) > 5) {
            description.append(" (明显变化)");
        }
        
        description.append(String.format(" [%s]", testType));
        
        return description.toString();
    }
    
    /**
     * 检测性能问题
     */
    private List<PerformanceIssue> detectPerformanceIssues(BenchmarkResult result) {
        List<PerformanceIssue> issues = new ArrayList<>();
        
        // 检查延迟是否过高
        if (result.getUnit().contains("ops/sec") && result.getScore() < config.getMinAcceptableThroughput()) {
            issues.add(new PerformanceIssue(
                    "低吞吐量",
                    String.format("基准测试 %s 吞吐量过低: %.2f %s", 
                            result.getBenchmarkId(), result.getScore(), result.getUnit()),
                    "HIGH",
                    "throughput",
                    result.getScore(),
                    config.getMinAcceptableThroughput()
            ));
        }
        
        // 检查错误率是否过高
        if (result.getError() > result.getScore() * config.getMaxErrorRate()) {
            issues.add(new PerformanceIssue(
                    "高错误率",
                    String.format("基准测试 %s 错误率过高: ±%.2f", 
                            result.getBenchmarkId(), result.getError()),
                    "MEDIUM",
                    "error_rate",
                    result.getError(),
                    result.getScore() * config.getMaxErrorRate()
            ));
        }
        
        // 检查性能是否大幅波动
        BenchmarkResult historicalResult = historicalResults.get(result.getBenchmarkId());
        if (historicalResult != null) {
            double coefficientOfVariation = Math.abs(result.getError() / result.getScore());
            if (coefficientOfVariation > config.getMaxCoefficientOfVariation()) {
                issues.add(new PerformanceIssue(
                        "高变异性",
                        String.format("基准测试 %s 性能变异性过高: %.2f%%", 
                                result.getBenchmarkId(), coefficientOfVariation * 100),
                        "MEDIUM",
                        "coefficient_of_variation",
                        coefficientOfVariation,
                        config.getMaxCoefficientOfVariation()
                ));
            }
        }
        
        return issues;
    }
    
    /**
     * 分析性能趋势
     */
    private PerformanceTrend analyzePerformanceTrend(BenchmarkResult currentResult) {
        String benchmarkId = currentResult.getBenchmarkId();
        
        PerformanceTrend trend = performanceTrends.computeIfAbsent(benchmarkId, 
                PerformanceTrend::new);
        
        trend.addDataPoint(currentResult);
        
        // 分析趋势方向
        String trendDirection = trend.analyzeTrend();
        
        // 计算趋势强度
        double trendStrength = Math.abs(trend.calculateTrendStrength());
        
        return new PerformanceTrend(
                benchmarkId,
                trendDirection,
                trendStrength,
                trend.getDataPoints().size(),
                trend.getLatestScore(),
                trend.getAverageScore(),
                trend.getStandardDeviation()
        );
    }
    
    /**
     * 更新性能趋势
     */
    private void updatePerformanceTrend(BenchmarkResult result) {
        String benchmarkId = result.getBenchmarkId();
        PerformanceTrend trend = performanceTrends.computeIfAbsent(benchmarkId, 
                PerformanceTrend::new);
        trend.addDataPoint(result);
    }
    
    /**
     * 生成性能建议
     */
    private List<PerformanceRecommendation> generatePerformanceRecommendations(
            List<BenchmarkComparison> comparisons,
            List<PerformanceIssue> issues,
            List<PerformanceTrend> trends) {
        
        List<PerformanceRecommendation> recommendations = new ArrayList<>();
        
        // 基于性能回归的建议
        for (BenchmarkComparison comparison : comparisons) {
            if ("回归".equals(comparison.getStatus())) {
                recommendations.add(new PerformanceRecommendation(
                        "性能优化",
                        "检测到性能回归",
                        String.format("基准测试 %s 性能下降 %.2f%%", 
                                comparison.getCurrentResult().getBenchmarkId(),
                                Math.abs(comparison.getPercentChange())),
                        "检查最近的代码变更和系统配置",
                        calculateExpectedImprovement(comparison)
                ));
            }
        }
        
        // 基于性能问题的建议
        for (PerformanceIssue issue : issues) {
            switch (issue.getType()) {
                case "低吞吐量":
                    recommendations.add(new PerformanceRecommendation(
                            "性能调优",
                            "优化吞吐量",
                            issue.getDescription(),
                            "检查系统资源使用和并发设置",
                            15.0
                    ));
                    break;
                case "高错误率":
                    recommendations.add(new PerformanceRecommendation(
                            "稳定性改进",
                            "降低错误率",
                            issue.getDescription(),
                            "优化算法实现和错误处理",
                            10.0
                    ));
                    break;
            }
        }
        
        // 基于趋势的建议
        for (PerformanceTrend trend : trends) {
            if ("下降".equals(trend.getTrendDirection()) && trend.getTrendStrength() > 0.5) {
                recommendations.add(new PerformanceRecommendation(
                        "趋势监控",
                        "监控性能下降趋势",
                        String.format("基准测试 %s 呈下降趋势", trend.getBenchmarkId()),
                        "分析根本原因并采取预防措施",
                        5.0
                ));
            }
        }
        
        return recommendations;
    }
    
    /**
     * 计算预期改进
     */
    private double calculateExpectedImprovement(BenchmarkComparison comparison) {
        if (comparison.getHistoricalResult() != null) {
            return Math.abs(comparison.getPercentChange());
        }
        return 0.0;
    }
    
    /**
     * 缓存基准测试结果
     */
    private void cacheBenchmarkResult(BenchmarkResult result) {
        String cacheKey = result.getBenchmarkId() + "_" + System.currentTimeMillis() / 3600000; // 按小时缓存
        
        resultCache.put(cacheKey, new CachedBenchmarkResult(
                cacheKey,
                result,
                System.currentTimeMillis()
        ));
        
        // 清理过期缓存
        if (resultCache.size() > config.getMaxCacheSize()) {
            cleanupExpiredCache();
        }
    }
    
    /**
     * 清理过期缓存
     */
    private void cleanupExpiredCache() {
        long expiryTime = System.currentTimeMillis() - config.getCacheExpiryTime();
        
        resultCache.entrySet().removeIf(entry -> 
                entry.getValue().getTimestamp() < expiryTime);
    }
    
    /**
     * 生成摘要统计信息
     */
    private Map<String, Object> generateSummaryStatistics(List<BenchmarkResult> results) {
        Map<String, Object> summary = new HashMap<>();
        
        if (results.isEmpty()) {
            return summary;
        }
        
        // 基本统计
        double totalScore = results.stream().mapToDouble(BenchmarkResult::getScore).sum();
        double avgScore = totalScore / results.size();
        double maxScore = results.stream().mapToDouble(BenchmarkResult::getScore).max().orElse(0);
        double minScore = results.stream().mapToDouble(BenchmarkResult::getScore).min().orElse(0);
        
        summary.put("totalBenchmarks", results.size());
        summary.put("averageScore", avgScore);
        summary.put("maximumScore", maxScore);
        summary.put("minimumScore", minScore);
        
        // 按后端分类统计
        Map<String, Long> backendStats = results.stream()
                .collect(Collectors.groupingBy(
                        BenchmarkResult::getTestType,
                        Collectors.counting()
                ));
        summary.put("backendDistribution", backendStats);
        
        // 性能分类统计
        long goodPerformance = results.stream()
                .mapToLong(r -> r.getScore() > avgScore ? 1 : 0)
                .sum();
        summary.put("goodPerformanceCount", goodPerformance);
        summary.put("poorPerformanceCount", results.size() - goodPerformance);
        
        return summary;
    }
    
    /**
     * 保存分析结果
     */
    public void saveAnalysisResult(BenchmarkAnalysisResult result) {
        if (config.getOutputDirectory() == null) {
            return;
        }
        
        try {
            Path outputDir = Paths.get(config.getOutputDirectory());
            Files.createDirectories(outputDir);
            
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String filename = String.format("benchmark_analysis_%s.json", timestamp);
            Path outputFile = outputDir.resolve(filename);
            
            String jsonContent = serializeAnalysisResult(result);
            Files.writeString(outputFile, jsonContent);
            
            logger.info("基准测试分析结果已保存: {}", outputFile);
            
        } catch (Exception e) {
            logger.error("保存分析结果失败", e);
        }
    }
    
    /**
     * 序列化分析结果
     */
    private String serializeAnalysisResult(BenchmarkAnalysisResult result) {
        // 简化的序列化实现
        return String.format("""
            {
                "timestamp": %d,
                "analysisCycle": %d,
                "summary": %s,
                "comparisons": %d,
                "issues": %d,
                "trends": %d,
                "recommendations": %d,
                "analysisTimeMs": %d,
                "message": "%s"
            }
            """,
            System.currentTimeMillis(),
            analysisCycle.get(),
            result.getSummaryStatistics().toString(),
            result.getComparisons().size(),
            result.getIssues().size(),
            result.getTrends().size(),
            result.getRecommendations().size(),
            result.getAnalysisTimeMs(),
            result.getMessage().replace("\"", "\\\"")
        );
    }
    
    /**
     * 获取分析统计信息
     */
    public AnalyzerStatistics getStatistics() {
        return new AnalyzerStatistics(
                totalAnalyzedBenchmarks.sum(),
                totalRegressionsDetected.sum(),
                totalImprovementsDetected.sum(),
                historicalResults.size(),
                performanceTrends.size(),
                resultCache.size(),
                analysisCycle.get(),
                System.currentTimeMillis()
        );
    }
    
    /**
     * 关闭分析器
     */
    public void shutdown() {
        logger.info("关闭基准测试结果分析器");
        
        // 保存当前状态
        saveCurrentState();
    }
    
    /**
     * 保存当前状态
     */
    private void saveCurrentState() {
        if (config.getHistoricalDataDirectory() == null) {
            return;
        }
        
        try {
            Path dataDir = Paths.get(config.getHistoricalDataDirectory());
            Files.createDirectories(dataDir);
            
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String filename = String.format("benchmark_results_%s.json", timestamp);
            Path outputFile = dataDir.resolve(filename);
            
            // 保存历史结果
            StringBuilder json = new StringBuilder("{\n  \"results\": [\n");
            
            List<BenchmarkResult> results = new ArrayList<>(historicalResults.values());
            for (int i = 0; i < results.size(); i++) {
                BenchmarkResult result = results.get(i);
                json.append(String.format("    {\"benchmark\": \"%s\", \"score\": %.2f, \"error\": %.2f, \"unit\": \"%s\"}%s\n",
                        result.getBenchmarkId(),
                        result.getScore(),
                        result.getError(),
                        result.getUnit(),
                        i < results.size() - 1 ? "," : ""
                ));
            }
            
            json.append("  ]\n}");
            Files.writeString(outputFile, json.toString());
            
            logger.debug("历史基准测试结果已保存: {}", outputFile);
            
        } catch (Exception e) {
            logger.warn("保存当前状态失败", e);
        }
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 分析器配置
     */
    public static class AnalyzerConfig {
        private String outputDirectory;
        private String historicalDataDirectory;
        private int analysisIntervalSeconds = 3600; // 1小时
        private double regressionThreshold = 5.0; // 5%
        private double improvementThreshold = 5.0; // 5%
        private double minAcceptableThroughput = 100.0;
        private double maxErrorRate = 0.1; // 10%
        private double maxCoefficientOfVariation = 0.2; // 20%
        private int maxCacheSize = 1000;
        private long cacheExpiryTime = 86400000; // 24小时
        
        // Getters and Setters
        public String getOutputDirectory() { return outputDirectory; }
        public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; }
        public String getHistoricalDataDirectory() { return historicalDataDirectory; }
        public void setHistoricalDataDirectory(String historicalDataDirectory) { this.historicalDataDirectory = historicalDataDirectory; }
        public int getAnalysisIntervalSeconds() { return analysisIntervalSeconds; }
        public void setAnalysisIntervalSeconds(int analysisIntervalSeconds) { this.analysisIntervalSeconds = analysisIntervalSeconds; }
        public double getRegressionThreshold() { return regressionThreshold; }
        public void setRegressionThreshold(double regressionThreshold) { this.regressionThreshold = regressionThreshold; }
        public double getImprovementThreshold() { return improvementThreshold; }
        public void setImprovementThreshold(double improvementThreshold) { this.improvementThreshold = improvementThreshold; }
        public double getMinAcceptableThroughput() { return minAcceptableThroughput; }
        public void setMinAcceptableThroughput(double minAcceptableThroughput) { this.minAcceptableThroughput = minAcceptableThroughput; }
        public double getMaxErrorRate() { return maxErrorRate; }
        public void setMaxErrorRate(double maxErrorRate) { this.maxErrorRate = maxErrorRate; }
        public double getMaxCoefficientOfVariation() { return maxCoefficientOfVariation; }
        public void setMaxCoefficientOfVariation(double maxCoefficientOfVariation) { this.maxCoefficientOfVariation = maxCoefficientOfVariation; }
        public int getMaxCacheSize() { return maxCacheSize; }
        public void setMaxCacheSize(int maxCacheSize) { this.maxCacheSize = maxCacheSize; }
        public long getCacheExpiryTime() { return cacheExpiryTime; }
        public void setCacheExpiryTime(long cacheExpiryTime) { this.cacheExpiryTime = cacheExpiryTime; }
    }
    
    /**
     * 基准测试结果
     */
    public static class BenchmarkResult {
        private final String benchmarkId;
        private final double score;
        private final double error;
        private final String unit;
        private final int threads;
        private final long timestamp;
        private final String mode;
        private final String testType;
        
        public BenchmarkResult(String benchmarkId, double score, double error, String unit, 
                             int threads, long timestamp, String mode, String testType) {
            this.benchmarkId = benchmarkId;
            this.score = score;
            this.error = error;
            this.unit = unit;
            this.threads = threads;
            this.timestamp = timestamp;
            this.mode = mode;
            this.testType = testType;
        }
        
        // Getters
        public String getBenchmarkId() { return benchmarkId; }
        public double getScore() { return score; }
        public double getError() { return error; }
        public String getUnit() { return unit; }
        public int getThreads() { return threads; }
        public long getTimestamp() { return timestamp; }
        public String getMode() { return mode; }
        public String getTestType() { return testType; }
    }
    
    /**
     * 基准测试对比
     */
    public static class BenchmarkComparison {
        private final BenchmarkResult currentResult;
        private final BenchmarkResult historicalResult;
        private final double percentChange;
        private final String status;
        private final String description;
        
        public BenchmarkComparison(BenchmarkResult currentResult, BenchmarkResult historicalResult,
                                double percentChange, String status, String description) {
            this.currentResult = currentResult;
            this.historicalResult = historicalResult;
            this.percentChange = percentChange;
            this.status = status;
            this.description = description;
        }
        
        // Getters
        public BenchmarkResult getCurrentResult() { return currentResult; }
        public BenchmarkResult getHistoricalResult() { return historicalResult; }
        public double getPercentChange() { return percentChange; }
        public String getStatus() { return status; }
        public String getDescription() { return description; }
    }
    
    /**
     * 性能趋势
     */
    public static class PerformanceTrend {
        private final String benchmarkId;
        private final List<BenchmarkResult> dataPoints;
        
        public PerformanceTrend(String benchmarkId) {
            this.benchmarkId = benchmarkId;
            this.dataPoints = new ArrayList<>();
        }
        
        public void addDataPoint(BenchmarkResult result) {
            dataPoints.add(result);
        }
        
        public String analyzeTrend() {
            if (dataPoints.size() < 2) {
                return "不足数据";
            }
            
            // 简单的线性趋势分析
            double slope = calculateSlope();
            if (Math.abs(slope) < 0.01) {
                return "稳定";
            } else if (slope > 0) {
                return "上升";
            } else {
                return "下降";
            }
        }
        
        public double calculateTrendStrength() {
            if (dataPoints.size() < 2) {
                return 0.0;
            }
            
            double correlation = calculateCorrelation();
            return Math.abs(correlation);
        }
        
        private double calculateSlope() {
            int n = dataPoints.size();
            double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
            
            for (int i = 0; i < n; i++) {
                sumX += i;
                sumY += dataPoints.get(i).getScore();
                sumXY += i * dataPoints.get(i).getScore();
                sumX2 += i * i;
            }
            
            return (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
        }
        
        private double calculateCorrelation() {
            int n = dataPoints.size();
            double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
            
            for (int i = 0; i < n; i++) {
                sumX += i;
                sumY += dataPoints.get(i).getScore();
                sumXY += i * dataPoints.get(i).getScore();
                sumX2 += i * i;
                sumY2 += dataPoints.get(i).getScore() * dataPoints.get(i).getScore();
            }
            
            double correlation = (n * sumXY - sumX * sumY) / 
                    Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
            
            return correlation;
        }
        
        public List<BenchmarkResult> getDataPoints() { return dataPoints; }
        public double getLatestScore() { 
            return dataPoints.isEmpty() ? 0 : dataPoints.get(dataPoints.size() - 1).getScore(); 
        }
        public double getAverageScore() {
            return dataPoints.stream().mapToDouble(BenchmarkResult::getScore).average().orElse(0);
        }
        public double getStandardDeviation() {
            double avg = getAverageScore();
            return Math.sqrt(dataPoints.stream()
                    .mapToDouble(r -> Math.pow(r.getScore() - avg, 2))
                    .average()
                    .orElse(0));
        }
    }
    
    /**
     * 基准测试分析结果
     */
    public static class BenchmarkAnalysisResult {
        private final String message;
        private final List<BenchmarkResult> results;
        private final List<BenchmarkComparison> comparisons;
        private final List<PerformanceIssue> issues;
        private final List<PerformanceTrend> trends;
        private final List<PerformanceRecommendation> recommendations;
        private final long analysisTimeMs;
        private final Map<String, Object> summaryStatistics;
        
        public BenchmarkAnalysisResult(String message) {
            this.message = message;
            this.results = new ArrayList<>();
            this.comparisons = new ArrayList<>();
            this.issues = new ArrayList<>();
            this.trends = new ArrayList<>();
            this.recommendations = new ArrayList<>();
            this.analysisTimeMs = 0;
            this.summaryStatistics = new HashMap<>();
        }
        
        public BenchmarkAnalysisResult(String message, List<BenchmarkResult> results,
                                     List<BenchmarkComparison> comparisons,
                                     List<PerformanceIssue> issues,
                                     List<PerformanceTrend> trends,
                                     List<PerformanceRecommendation> recommendations,
                                     long analysisTimeMs,
                                     Map<String, Object> summaryStatistics) {
            this.message = message;
            this.results = results;
            this.comparisons = comparisons;
            this.issues = issues;
            this.trends = trends;
            this.recommendations = recommendations;
            this.analysisTimeMs = analysisTimeMs;
            this.summaryStatistics = summaryStatistics;
        }
        
        // Getters
        public String getMessage() { return message; }
        public List<BenchmarkResult> getResults() { return results; }
        public List<BenchmarkComparison> getComparisons() { return comparisons; }
        public List<PerformanceIssue> getIssues() { return issues; }
        public List<PerformanceTrend> getTrends() { return trends; }
        public List<PerformanceRecommendation> getRecommendations() { return recommendations; }
        public long getAnalysisTimeMs() { return analysisTimeMs; }
        public Map<String, Object> getSummaryStatistics() { return summaryStatistics; }
    }
    
    /**
     * 缓存的基准测试结果
     */
    private static class CachedBenchmarkResult {
        private final String cacheKey;
        private final BenchmarkResult result;
        private final long timestamp;
        
        public CachedBenchmarkResult(String cacheKey, BenchmarkResult result, long timestamp) {
            this.cacheKey = cacheKey;
            this.result = result;
            this.timestamp = timestamp;
        }
        
        public String getCacheKey() { return cacheKey; }
        public BenchmarkResult getResult() { return result; }
        public long getTimestamp() { return timestamp; }
    }
    
    /**
     * 分析器统计信息
     */
    public static class AnalyzerStatistics {
        private final long totalAnalyzedBenchmarks;
        private final long totalRegressionsDetected;
        private final long totalImprovementsDetected;
        private final int historicalResultsCount;
        private final int performanceTrendsCount;
        private final int cachedResultsCount;
        private final int analysisCycle;
        private final long uptime;
        
        public AnalyzerStatistics(long totalAnalyzedBenchmarks, long totalRegressionsDetected,
                                long totalImprovementsDetected, int historicalResultsCount,
                                int performanceTrendsCount, int cachedResultsCount,
                                int analysisCycle, long uptime) {
            this.totalAnalyzedBenchmarks = totalAnalyzedBenchmarks;
            this.totalRegressionsDetected = totalRegressionsDetected;
            this.totalImprovementsDetected = totalImprovementsDetected;
            this.historicalResultsCount = historicalResultsCount;
            this.performanceTrendsCount = performanceTrendsCount;
            this.cachedResultsCount = cachedResultsCount;
            this.analysisCycle = analysisCycle;
            this.uptime = uptime;
        }
        
        // Getters
        public long getTotalAnalyzedBenchmarks() { return totalAnalyzedBenchmarks; }
        public long getTotalRegressionsDetected() { return totalRegressionsDetected; }
        public long getTotalImprovementsDetected() { return totalImprovementsDetected; }
        public int getHistoricalResultsCount() { return historicalResultsCount; }
        public int getPerformanceTrendsCount() { return performanceTrendsCount; }
        public int getCachedResultsCount() { return cachedResultsCount; }
        public int getAnalysisCycle() { return analysisCycle; }
        public long getUptime() { return uptime; }
    }
    
    // 复用PerformanceProfiler中的类
    private static class PerformanceIssue {
        private final String type;
        private final String description;
        private final String severity;
        private final String metricName;
        private final Object currentValue;
        private final Object thresholdValue;
        
        public PerformanceIssue(String type, String description, String severity,
                              String metricName, Object currentValue, Object thresholdValue) {
            this.type = type;
            this.description = description;
            this.severity = severity;
            this.metricName = metricName;
            this.currentValue = currentValue;
            this.thresholdValue = thresholdValue;
        }
        
        // Getters
        public String getType() { return type; }
        public String getDescription() { return description; }
        public String getSeverity() { return severity; }
        public String getMetricName() { return metricName; }
        public Object getCurrentValue() { return currentValue; }
        public Object getThresholdValue() { return thresholdValue; }
    }
    
    private static class PerformanceRecommendation {
        private final String category;
        private final String title;
        private final String description;
        private final String action;
        private final double expectedImprovement;
        
        public PerformanceRecommendation(String category, String title, String description,
                                      String action, double expectedImprovement) {
            this.category = category;
            this.title = title;
            this.description = description;
            this.action = action;
            this.expectedImprovement = expectedImprovement;
        }
        
        // Getters
        public String getCategory() { return category; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public String getAction() { return action; }
        public double getExpectedImprovement() { return expectedImprovement; }
    }
}