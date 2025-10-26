package com.mycorp.distributedlock.core.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 优化建议生成器
 * 
 * 功能特性：
 * 1. 基于性能分析生成优化建议
 * 2. 提供具体的配置优化方案
 * 3. 生成实施优先级排序
 * 4. 预估优化效果和ROI
 * 5. 提供实施步骤和风险评估
 * 6. 生成优化跟踪计划
 */
public class OptimizationSuggestions {
    
    private final Logger logger = LoggerFactory.getLogger(OptimizationSuggestions.class);
    
    // 配置参数
    private final SuggestionsConfig config;
    
    // 优化规则库
    private final List<OptimizationRule> optimizationRules;
    private final Map<String, OptimizationTemplate> templates;
    
    // 统计信息
    private final AtomicInteger totalSuggestionsGenerated = new AtomicInteger(0);
    private final AtomicInteger highPrioritySuggestions = new AtomicInteger(0);
    private final AtomicInteger implementedSuggestions = new AtomicInteger(0);
    
    public OptimizationSuggestions(SuggestionsConfig config) {
        this.config = config;
        this.optimizationRules = initializeOptimizationRules();
        this.templates = initializeOptimizationTemplates();
        
        logger.info("优化建议生成器初始化完成 - 规则数: {}, 模板数: {}, 最小影响分数: {}",
                optimizationRules.size(), templates.size(), config.getMinImpactScore());
    }
    
    /**
     * 初始化优化规则
     */
    private List<OptimizationRule> initializeOptimizationRules() {
        List<OptimizationRule> rules = new ArrayList<>();
        
        // 锁性能优化规则
        rules.add(new OptimizationRule(
                "LOCK_LATENCY_HIGH",
                "锁延迟过高",
                Arrays.asList("lock_acquisition_latency", "lock_hold_time"),
                80.0,
                Arrays.asList(
                        "考虑使用更高效的锁算法",
                        "优化锁的范围和粒度",
                        "减少锁持有时间",
                        "使用无锁或乐观锁策略"
                ),
                Arrays.asList(
                        "确保锁逻辑正确性",
                        "避免死锁风险",
                        "监控性能变化"
                )
        ));
        
        rules.add(new OptimizationRule(
                "LOCK_CONTENTION_HIGH",
                "锁竞争激烈",
                Arrays.asList("concurrent_lock_requests", "lock_queue_length"),
                75.0,
                Arrays.asList(
                        "增加锁实例数量减少竞争",
                        "使用读写锁提高并发度",
                        "实施锁分段策略",
                        "优化业务逻辑减少锁依赖"
                ),
                Arrays.asList(
                        "监控系统CPU使用率",
                        "检查死锁检测机制",
                        "验证业务逻辑正确性"
                )
        ));
        
        rules.add(new OptimizationRule(
                "BATCH_OPERATIONS_SLOW",
                "批量操作性能不佳",
                Arrays.asList("batch_operation_latency", "batch_size"),
                70.0,
                Arrays.asList(
                        "增加批量操作大小",
                        "使用pipeline优化网络传输",
                        "并行处理批量操作",
                        "优化批量操作算法"
                ),
                Arrays.asList(
                        "监控内存使用情况",
                        "确保批量操作的原子性",
                        "测试大批量操作的稳定性"
                )
        ));
        
        // 内存优化规则
        rules.add(new OptimizationRule(
                "MEMORY_PRESSURE_HIGH",
                "内存压力过高",
                Arrays.asList("heap_usage", "gc_frequency", "gc_time"),
                85.0,
                Arrays.asList(
                        "优化对象池配置",
                        "增加堆内存大小",
                        "优化数据结构减少内存占用",
                        "启用对象复用机制"
                ),
                Arrays.asList(
                        "监控GC频率和时长",
                        "检查内存泄漏",
                        "分析堆转储文件"
                )
        ));
        
        rules.add(new OptimizationRule(
                "GC_PAUSE_TIME_HIGH",
                "GC暂停时间过长",
                Arrays.asList("gc_pause_time", "gc_frequency"),
                80.0,
                Arrays.asList(
                        "调整GC算法配置",
                        "优化对象生命周期",
                        "减少大对象创建",
                        "使用G1GC替代默认GC"
                ),
                Arrays.asList(
                        "监控GC日志",
                        "验证应用程序暂停时间",
                        "测试不同GC配置"
                )
        ));
        
        // 网络优化规则
        rules.add(new OptimizationRule(
                "NETWORK_LATENCY_HIGH",
                "网络延迟过高",
                Arrays.asList("network_latency", "request_timeout"),
                75.0,
                Arrays.asList(
                        "优化连接池配置",
                        "启用连接复用",
                        "使用HTTP/2或HTTP/3",
                        "优化网络拓扑结构"
                ),
                Arrays.asList(
                        "监控网络错误率",
                        "检查超时配置",
                        "验证连接稳定性"
                )
        ));
        
        rules.add(new OptimizationRule(
                "THROUGHPUT_LOW",
                "系统吞吐量低",
                Arrays.asList("requests_per_second", "concurrent_connections"),
                70.0,
                Arrays.asList(
                        "增加并发线程数",
                        "优化连接池大小",
                        "启用异步处理",
                        "使用负载均衡"
                ),
                Arrays.asList(
                        "监控系统资源使用",
                        "检查网络带宽",
                        "验证应用程序线程池配置"
                )
        ));
        
        // 缓存优化规则
        rules.add(new OptimizationRule(
                "CACHE_HIT_RATE_LOW",
                "缓存命中率低",
                Arrays.asList("cache_hit_rate", "cache_miss_rate"),
                65.0,
                Arrays.asList(
                        "优化缓存策略",
                        "增加缓存大小",
                        "改进缓存预热机制",
                        "使用多级缓存"
                ),
                Arrays.asList(
                        "监控缓存失效模式",
                        "分析热点数据",
                        "测试缓存一致性"
                )
        ));
        
        return rules;
    }
    
    /**
     * 初始化优化模板
     */
    private Map<String, OptimizationTemplate> initializeOptimizationTemplates() {
        Map<String, OptimizationTemplate> templates = new HashMap<>();
        
        // Redis优化模板
        templates.put("redis_optimization", new OptimizationTemplate(
                "Redis性能优化",
                "config-redis-optimization",
                Arrays.asList("connection_pool_size", "timeout_settings", "pipeline_enabled"),
                Arrays.asList(
                        "jedis.pool.maxTotal=100",
                        "jedis.pool.maxIdle=20", 
                        "jedis.pool.minIdle=5",
                        "jedis.timeout=5000",
                        "jedis.pipeline.enabled=true"
                ),
                "Redis连接池和超时配置的优化建议"
        ));
        
        // ZooKeeper优化模板
        templates.put("zookeeper_optimization", new OptimizationTemplate(
                "ZooKeeper性能优化",
                "config-zookeeper-optimization", 
                Arrays.asList("session_timeout", "connection_timeout", "retry_policy"),
                Arrays.asList(
                        "zookeeper.session.timeout=30000",
                        "zookeeper.connection.timeout=10000",
                        "zookeeper.retry.max=5"
                ),
                "ZooKeeper会话超时和重试策略的优化建议"
        ));
        
        // JVM优化模板
        templates.put("jvm_optimization", new OptimizationTemplate(
                "JVM性能优化",
                "config-jvm-optimization",
                Arrays.asList("heap_size", "gc_algorithm", "thread_pool_size"),
                Arrays.asList(
                        "-Xms2g -Xmx4g",
                        "-XX:+UseG1GC",
                        "-XX:MaxGCPauseMillis=200",
                        "-XX:G1HeapRegionSize=16m"
                ),
                "JVM堆内存和垃圾收集器的优化建议"
        ));
        
        // 连接池优化模板
        templates.put("connection_pool_optimization", new OptimizationTemplate(
                "连接池优化",
                "config-connection-pool",
                Arrays.asList("pool_size", "connection_timeout", "validation_query"),
                Arrays.asList(
                        "pool.max.active=50",
                        "pool.max.idle=20",
                        "pool.min.idle=5",
                        "validation.query=SELECT 1"
                ),
                "数据库连接池配置的优化建议"
        ));
        
        return templates;
    }
    
    /**
     * 生成优化建议
     */
    public OptimizationSuggestionResult generateSuggestions(
            Map<String, Object> performanceMetrics,
            List<BenchmarkResultAnalyzer.BenchmarkResult> benchmarkResults) {
        
        long startTime = System.currentTimeMillis();
        
        try {
            // 分析性能指标
            List<OptimizationIssue> issues = analyzePerformanceIssues(performanceMetrics);
            
            // 分析基准测试结果
            List<OptimizationIssue> benchmarkIssues = analyzeBenchmarkResults(benchmarkResults);
            
            // 合并所有问题
            List<OptimizationIssue> allIssues = new ArrayList<>();
            allIssues.addAll(issues);
            allIssues.addAll(benchmarkIssues);
            
            // 按优先级排序
            List<OptimizationIssue> prioritizedIssues = allIssues.stream()
                    .sorted(Comparator.comparingDouble(OptimizationIssue::getImpactScore).reversed())
                    .collect(Collectors.toList());
            
            // 生成优化建议
            List<OptimizationRecommendation> recommendations = generateOptimizationRecommendations(prioritizedIssues);
            
            // 按优先级排序建议
            List<OptimizationRecommendation> prioritizedRecommendations = recommendations.stream()
                    .sorted(Comparator.comparingDouble(OptimizationRecommendation::getPriorityScore).reversed())
                    .collect(Collectors.toList());
            
            // 生成实施计划
            List<OptimizationPlan> implementationPlans = generateImplementationPlans(prioritizedRecommendations);
            
            totalSuggestionsGenerated.addAndGet(recommendations.size());
            highPrioritySuggestions.addAndGet((int) recommendations.stream()
                    .filter(r -> r.getPriorityScore() >= 80.0).count());
            
            long generationTime = System.currentTimeMillis() - startTime;
            
            return new OptimizationSuggestionResult(
                    prioritizedIssues,
                    prioritizedRecommendations,
                    implementationPlans,
                    generationTime,
                    generateOptimizationSummary(prioritizedIssues, prioritizedRecommendations)
            );
            
        } catch (Exception e) {
            logger.error("生成优化建议异常", e);
            return new OptimizationSuggestionResult("生成优化建议失败: " + e.getMessage());
        }
    }
    
    /**
     * 分析性能问题
     */
    private List<OptimizationIssue> analyzePerformanceIssues(Map<String, Object> performanceMetrics) {
        List<OptimizationIssue> issues = new ArrayList<>();
        
        for (OptimizationRule rule : optimizationRules) {
            double issueScore = calculateIssueScore(rule, performanceMetrics);
            
            if (issueScore >= config.getMinIssueScore()) {
                issues.add(new OptimizationIssue(
                        rule.getRuleId(),
                        rule.getDescription(),
                        rule.getSeverity(),
                        issueScore,
                        getAffectedMetrics(rule, performanceMetrics),
                        getCurrentValues(rule, performanceMetrics),
                        rule.getOptimizationActions(),
                        rule.getRiskMitigationActions()
                ));
            }
        }
        
        return issues;
    }
    
    /**
     * 计算问题严重程度分数
     */
    private double calculateIssueScore(OptimizationRule rule, Map<String, Object> performanceMetrics) {
        double totalScore = 0.0;
        double weightSum = 0.0;
        
        for (String metricName : rule.getAffectedMetrics()) {
            Object metricValue = performanceMetrics.get(metricName);
            if (metricValue instanceof Number) {
                double value = ((Number) metricValue).doubleValue();
                double normalizedValue = normalizeMetricValue(metricName, value);
                double weight = getMetricWeight(metricName);
                
                totalScore += normalizedValue * weight;
                weightSum += weight;
            }
        }
        
        return weightSum > 0 ? (totalScore / weightSum) * 100 : 0.0;
    }
    
    /**
     * 标准化指标值
     */
    private double normalizeMetricValue(String metricName, double value) {
        // 根据不同指标类型进行标准化
        switch (metricName) {
            case "lock_acquisition_latency":
                return Math.min(value / 100.0, 1.0); // 超过100ms认为有问题
            case "heap_usage":
                return Math.min(value / 90.0, 1.0); // 超过90%认为有问题
            case "gc_frequency":
                return Math.min(value / 10.0, 1.0); // 每分钟超过10次认为有问题
            case "cache_hit_rate":
                return 1.0 - Math.min(value / 70.0, 1.0); // 低于70%认为有问题（反转）
            case "network_latency":
                return Math.min(value / 50.0, 1.0); // 超过50ms认为有问题
            case "cpu_usage":
                return Math.min(value / 80.0, 1.0); // 超过80%认为有问题
            default:
                return Math.min(value / 100.0, 1.0); // 默认标准化
        }
    }
    
    /**
     * 获取指标权重
     */
    private double getMetricWeight(String metricName) {
        // 根据指标重要性分配权重
        Map<String, Double> weights = Map.of(
                "lock_acquisition_latency", 1.0,
                "heap_usage", 0.9,
                "gc_frequency", 0.8,
                "cache_hit_rate", 0.7,
                "network_latency", 0.9,
                "cpu_usage", 0.8,
                "requests_per_second", 1.0
        );
        
        return weights.getOrDefault(metricName, 0.5);
    }
    
    /**
     * 分析基准测试结果
     */
    private List<OptimizationIssue> analyzeBenchmarkResults(List<BenchmarkResultAnalyzer.BenchmarkResult> benchmarkResults) {
        List<OptimizationIssue> issues = new ArrayList<>();
        
        // 按后端类型分组分析
        Map<String, List<BenchmarkResultAnalyzer.BenchmarkResult>> groupedResults = 
                benchmarkResults.stream()
                .collect(Collectors.groupingBy(BenchmarkResultAnalyzer.BenchmarkResult::getTestType));
        
        for (Map.Entry<String, List<BenchmarkResultAnalyzer.BenchmarkResult>> entry : groupedResults.entrySet()) {
            String backendType = entry.getKey();
            List<BenchmarkResultAnalyzer.BenchmarkResult> results = entry.getValue();
            
            // 分析延迟问题
            double avgLatency = results.stream()
                    .filter(r -> r.getUnit().contains("ops/sec"))
                    .mapToDouble(r -> 1000.0 / r.getScore()) // 转换为延迟
                    .average()
                    .orElse(0);
            
            if (avgLatency > 10.0) { // 平均延迟超过10ms
                issues.add(new OptimizationIssue(
                        "BENCHMARK_" + backendType.toUpperCase() + "_LATENCY",
                        backendType + "基准测试延迟过高",
                        "HIGH",
                        75.0,
                        Map.of("average_latency", avgLatency),
                        Map.of("average_latency", avgLatency),
                        Arrays.asList(
                                "优化" + backendType + "连接池配置",
                                "调整" + backendType + "超时设置",
                                "优化" + backendType + "网络配置"
                        ),
                        Arrays.asList(
                                "监控" + backendType + "连接状态",
                                "验证网络稳定性"
                        )
                ));
            }
            
            // 分析错误率问题
            double avgError = results.stream()
                    .mapToDouble(BenchmarkResultAnalyzer.BenchmarkResult::getError)
                    .average()
                    .orElse(0);
            
            double avgScore = results.stream()
                    .mapToDouble(BenchmarkResultAnalyzer.BenchmarkResult::getScore)
                    .average()
                    .orElse(0);
            
            if (avgScore > 0 && avgError / avgScore > 0.1) { // 错误率超过10%
                issues.add(new OptimizationIssue(
                        "BENCHMARK_" + backendType.toUpperCase() + "_VARIABILITY",
                        backendType + "基准测试变异性过高",
                        "MEDIUM",
                        65.0,
                        Map.of("error_rate", avgError / avgScore),
                        Map.of("error_rate", avgError / avgScore),
                        Arrays.asList(
                                "稳定" + backendType + "环境配置",
                                "优化" + backendType + "负载均衡",
                                "改进" + backendType + "监控"
                        ),
                        Arrays.asList(
                                "检查环境稳定性",
                                "验证负载测试方法"
                        )
                ));
            }
        }
        
        return issues;
    }
    
    /**
     * 获取受影响的指标
     */
    private Map<String, Object> getAffectedMetrics(OptimizationRule rule, Map<String, Object> performanceMetrics) {
        Map<String, Object> affectedMetrics = new HashMap<>();
        
        for (String metricName : rule.getAffectedMetrics()) {
            if (performanceMetrics.containsKey(metricName)) {
                affectedMetrics.put(metricName, performanceMetrics.get(metricName));
            }
        }
        
        return affectedMetrics;
    }
    
    /**
     * 获取当前值
     */
    private Map<String, Object> getCurrentValues(OptimizationRule rule, Map<String, Object> performanceMetrics) {
        return getAffectedMetrics(rule, performanceMetrics);
    }
    
    /**
     * 生成优化建议
     */
    private List<OptimizationRecommendation> generateOptimizationRecommendations(List<OptimizationIssue> issues) {
        List<OptimizationRecommendation> recommendations = new ArrayList<>();
        
        for (OptimizationIssue issue : issues) {
            OptimizationRule matchingRule = findMatchingRule(issue.getIssueId());
            
            if (matchingRule != null) {
                // 生成具体建议
                for (String action : matchingRule.getOptimizationActions()) {
                    OptimizationRecommendation recommendation = createOptimizationRecommendation(
                            issue, action, matchingRule);
                    recommendations.add(recommendation);
                }
                
                // 添加模板建议
                String templateKey = findTemplateKey(issue.getIssueId());
                if (templateKey != null) {
                    OptimizationTemplate template = templates.get(templateKey);
                    if (template != null) {
                        recommendations.add(createTemplateRecommendation(issue, template));
                    }
                }
            }
        }
        
        return recommendations;
    }
    
    /**
     * 创建优化建议
     */
    private OptimizationRecommendation createOptimizationRecommendation(
            OptimizationIssue issue, String action, OptimizationRule rule) {
        
        // 计算优先级分数
        double priorityScore = calculatePriorityScore(issue, rule);
        
        // 估算实施难度
        ImplementationDifficulty difficulty = estimateImplementationDifficulty(action);
        
        // 估算预期效果
        double expectedImpact = estimateExpectedImpact(issue, action);
        
        // 生成配置建议
        List<String> configChanges = generateConfigChanges(action, issue);
        
        return new OptimizationRecommendation(
                rule.getRuleId(),
                action,
                String.format("针对问题 '%s' 的优化建议", issue.getDescription()),
                priorityScore,
                difficulty,
                expectedImpact,
                action,
                configChanges,
                generateImplementationSteps(action),
                estimateImplementationTime(action),
                identifyRisks(action, issue),
                generateValidationMethods(action)
        );
    }
    
    /**
     * 创建模板建议
     */
    private OptimizationRecommendation createTemplateRecommendation(
            OptimizationIssue issue, OptimizationTemplate template) {
        
        return new OptimizationRecommendation(
                "TEMPLATE_" + template.getTemplateId(),
                template.getTitle(),
                template.getDescription(),
                70.0, // 默认中等优先级
                ImplementationDifficulty.MEDIUM,
                15.0, // 默认中等效果
                "配置优化",
                template.getConfigChanges(),
                generateTemplateImplementationSteps(template),
                estimateTemplateImplementationTime(template),
                Arrays.asList("配置错误风险", "测试不充分风险"),
                Arrays.asList("验证配置生效", "监控性能指标")
        );
    }
    
    /**
     * 计算优先级分数
     */
    private double calculatePriorityScore(OptimizationIssue issue, OptimizationRule rule) {
        double baseScore = issue.getImpactScore();
        double severityMultiplier = getSeverityMultiplier(issue.getSeverity());
        double urgencyMultiplier = getUrgencyMultiplier(rule.getRuleId());
        
        return baseScore * severityMultiplier * urgencyMultiplier;
    }
    
    /**
     * 获取严重程度乘数
     */
    private double getSeverityMultiplier(String severity) {
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> 1.5;
            case "HIGH" -> 1.2;
            case "MEDIUM" -> 1.0;
            case "LOW" -> 0.8;
            default -> 1.0;
        };
    }
    
    /**
     * 获取紧急程度乘数
     */
    private double getUrgencyMultiplier(String ruleId) {
        // 根据规则ID确定紧急程度
        if (ruleId.contains("MEMORY_PRESSURE") || ruleId.contains("LOCK_CONTENTION")) {
            return 1.3; // 这些问题比较紧急
        }
        return 1.0;
    }
    
    /**
     * 估算实施难度
     */
    private ImplementationDifficulty estimateImplementationDifficulty(String action) {
        // 基于操作类型估算难度
        if (action.contains("配置") || action.contains("参数")) {
            return ImplementationDifficulty.EASY;
        } else if (action.contains("算法") || action.contains("策略")) {
            return ImplementationDifficulty.HARD;
        } else {
            return ImplementationDifficulty.MEDIUM;
        }
    }
    
    /**
     * 估算预期效果
     */
    private double estimateExpectedImpact(OptimizationIssue issue, String action) {
        double baseImpact = issue.getImpactScore() * 0.3; // 基础影响为问题分数的30%
        
        // 根据操作类型调整
        if (action.contains("连接池")) {
            return baseImpact * 1.2; // 连接池优化通常效果明显
        } else if (action.contains("内存")) {
            return baseImpact * 1.1;
        } else if (action.contains("缓存")) {
            return baseImpact * 1.3; // 缓存优化效果通常很好
        } else {
            return baseImpact;
        }
    }
    
    /**
     * 生成配置变更
     */
    private List<String> generateConfigChanges(String action, OptimizationIssue issue) {
        List<String> changes = new ArrayList<>();
        
        if (action.contains("连接池")) {
            changes.add("# 连接池优化");
            changes.add("connection.pool.max.size=100");
            changes.add("connection.pool.min.size=10");
            changes.add("connection.pool.timeout=5000");
        } else if (action.contains("内存")) {
            changes.add("# JVM内存优化");
            changes.add("-Xms2g -Xmx4g");
            changes.add("-XX:+UseG1GC");
        } else if (action.contains("缓存")) {
            changes.add("# 缓存配置");
            changes.add("cache.size=10000");
            changes.add("cache.ttl=3600");
        }
        
        return changes;
    }
    
    /**
     * 生成实施步骤
     */
    private List<String> generateImplementationSteps(String action) {
        List<String> steps = new ArrayList<>();
        
        steps.add("1. 备份当前配置");
        steps.add("2. 测试环境验证");
        
        if (action.contains("配置")) {
            steps.add("3. 应用配置变更");
            steps.add("4. 验证配置生效");
        } else if (action.contains("算法")) {
            steps.add("3. 实现新的算法逻辑");
            steps.add("4. 编写单元测试");
            steps.add("5. 集成测试验证");
        } else {
            steps.add("3. 实施建议的变更");
            steps.add("4. 功能验证");
        }
        
        steps.add("5. 性能监控");
        steps.add("6. 回滚准备（如需要）");
        
        return steps;
    }
    
    /**
     * 估算实施时间
     */
    private Duration estimateImplementationTime(String action) {
        if (action.contains("配置") || action.contains("参数")) {
            return Duration.ofHours(1); // 配置变更通常1小时内完成
        } else if (action.contains("算法") || action.contains("策略")) {
            return Duration.ofDays(5); // 算法变更需要5天
        } else {
            return Duration.ofHours(4); // 其他变更4小时
        }
    }
    
    /**
     * 识别风险
     */
    private List<String> identifyRisks(String action, OptimizationIssue issue) {
        List<String> risks = new ArrayList<>();
        
        risks.add("配置错误导致服务异常");
        risks.add("性能改善不明显");
        risks.add("需要额外的监控和维护");
        
        if (action.contains("内存")) {
            risks.add("内存使用可能增加");
        }
        
        if (action.contains("锁")) {
            risks.add("可能引入新的并发问题");
        }
        
        return risks;
    }
    
    /**
     * 生成验证方法
     */
    private List<String> generateValidationMethods(String action) {
        List<String> methods = new ArrayList<>();
        
        methods.add("监控关键性能指标");
        methods.add("执行回归测试");
        methods.add("检查错误日志");
        
        if (action.contains("连接池")) {
            methods.add("监控连接池使用率");
            methods.add("检查连接超时情况");
        }
        
        if (action.contains("内存")) {
            methods.add("监控GC频率和暂停时间");
            methods.add("检查内存使用趋势");
        }
        
        return methods;
    }
    
    /**
     * 生成实施计划
     */
    private List<OptimizationPlan> generateImplementationPlans(List<OptimizationRecommendation> recommendations) {
        List<OptimizationPlan> plans = new ArrayList<>();
        
        // 按优先级分组
        Map<ImplementationPhase, List<OptimizationRecommendation>> groupedRecommendations = recommendations.stream()
                .collect(Collectors.groupingBy(this::determineImplementationPhase));
        
        for (Map.Entry<ImplementationPhase, List<OptimizationRecommendation>> entry : groupedRecommendations.entrySet()) {
            ImplementationPhase phase = entry.getKey();
            List<OptimizationRecommendation> phaseRecommendations = entry.getValue();
            
            OptimizationPlan plan = new OptimizationPlan(
                    phase,
                    phaseRecommendations,
                    estimatePhaseDuration(phaseRecommendations),
                    identifyPhaseDependencies(phaseRecommendations),
                    generatePhaseMilestones(phaseRecommendations)
            );
            
            plans.add(plan);
        }
        
        return plans;
    }
    
    /**
     * 确定实施阶段
     */
    private ImplementationPhase determineImplementationPhase(OptimizationRecommendation recommendation) {
        if (recommendation.getPriorityScore() >= 80.0) {
            return ImplementationPhase.IMMEDIATE;
        } else if (recommendation.getPriorityScore() >= 60.0) {
            return ImplementationPhase.SHORT_TERM;
        } else if (recommendation.getDifficulty() == ImplementationDifficulty.EASY) {
            return ImplementationPhase.QUICK_WIN;
        } else {
            return ImplementationPhase.LONG_TERM;
        }
    }
    
    /**
     * 估算阶段持续时间
     */
    private Duration estimatePhaseDuration(List<OptimizationRecommendation> recommendations) {
        long totalHours = recommendations.stream()
                .mapToLong(r -> r.getEstimatedTime().toHours())
                .sum();
        
        // 添加20%的缓冲时间
        totalHours = (long) (totalHours * 1.2);
        
        return Duration.ofHours(totalHours);
    }
    
    /**
     * 识别阶段依赖关系
     */
    private List<String> identifyPhaseDependencies(List<OptimizationRecommendation> recommendations) {
        List<String> dependencies = new ArrayList<>();
        
        // 基础依赖
        dependencies.add("确保基础架构稳定");
        dependencies.add("完成相关团队培训");
        
        // 特定依赖
        if (recommendations.stream().anyMatch(r -> r.getAction().contains("连接池"))) {
            dependencies.add("完成网络架构评估");
        }
        
        if (recommendations.stream().anyMatch(r -> r.getAction().contains("内存"))) {
            dependencies.add("完成JVM配置审查");
        }
        
        return dependencies;
    }
    
    /**
     * 生成阶段里程碑
     */
    private List<String> generatePhaseMilestones(List<OptimizationRecommendation> recommendations) {
        List<String> milestones = new ArrayList<>();
        
        milestones.add("阶段开始");
        
        for (int i = 0; i < recommendations.size(); i += 2) {
            milestones.add(String.format("完成 %d 个优化项", Math.min(i + 2, recommendations.size())));
        }
        
        milestones.add("阶段完成");
        
        return milestones;
    }
    
    /**
     * 生成优化摘要
     */
    private Map<String, Object> generateOptimizationSummary(
            List<OptimizationIssue> issues,
            List<OptimizationRecommendation> recommendations) {
        
        Map<String, Object> summary = new HashMap<>();
        
        // 基本统计
        summary.put("totalIssues", issues.size());
        summary.put("totalRecommendations", recommendations.size());
        
        // 按严重程度统计
        Map<String, Long> severityStats = issues.stream()
                .collect(Collectors.groupingBy(
                        OptimizationIssue::getSeverity,
                        Collectors.counting()
                ));
        summary.put("issuesBySeverity", severityStats);
        
        // 按实施难度统计
        Map<String, Long> difficultyStats = recommendations.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getDifficulty().name(),
                        Collectors.counting()
                ));
        summary.put("recommendationsByDifficulty", difficultyStats);
        
        // 优先级分布
        long highPriority = recommendations.stream()
                .mapToDouble(OptimizationRecommendation::getPriorityScore)
                .filter(score -> score >= 80.0)
                .count();
        long mediumPriority = recommendations.stream()
                .mapToDouble(OptimizationRecommendation::getPriorityScore)
                .filter(score -> score >= 60.0 && score < 80.0)
                .count();
        long lowPriority = recommendations.stream()
                .mapToDouble(OptimizationRecommendation::getPriorityScore)
                .filter(score -> score < 60.0)
                .count();
        
        summary.put("highPriorityCount", highPriority);
        summary.put("mediumPriorityCount", mediumPriority);
        summary.put("lowPriorityCount", lowPriority);
        
        // 预期效果估算
        double totalExpectedImpact = recommendations.stream()
                .mapToDouble(OptimizationRecommendation::getExpectedImpact)
                .sum();
        summary.put("totalExpectedImpact", totalExpectedImpact);
        
        // 实施时间估算
        long totalImplementationTime = recommendations.stream()
                .mapToLong(r -> r.getEstimatedTime().toHours())
                .sum();
        summary.put("totalImplementationTimeHours", totalImplementationTime);
        
        return summary;
    }
    
    /**
     * 查找匹配的规则
     */
    private OptimizationRule findMatchingRule(String issueId) {
        return optimizationRules.stream()
                .filter(rule -> rule.getRuleId().equals(issueId))
                .findFirst()
                .orElse(null);
    }
    
    /**
     * 查找模板键
     */
    private String findTemplateKey(String issueId) {
        if (issueId.contains("REDIS")) {
            return "redis_optimization";
        } else if (issueId.contains("ZOOKEEPER")) {
            return "zookeeper_optimization";
        } else if (issueId.contains("MEMORY") || issueId.contains("GC")) {
            return "jvm_optimization";
        } else if (issueId.contains("CONNECTION") || issueId.contains("POOL")) {
            return "connection_pool_optimization";
        }
        return null;
    }
    
    /**
     * 生成模板实施步骤
     */
    private List<String> generateTemplateImplementationSteps(OptimizationTemplate template) {
        List<String> steps = new ArrayList<>();
        
        steps.add("1. 备份当前配置文件");
        steps.add("2. 应用模板配置");
        steps.add("3. 重启服务");
        steps.add("4. 验证配置生效");
        steps.add("5. 监控性能变化");
        
        return steps;
    }
    
    /**
     * 估算模板实施时间
     */
    private Duration estimateTemplateImplementationTime(OptimizationTemplate template) {
        return Duration.ofHours(2); // 模板实施通常2小时
    }
    
    /**
     * 获取分析统计信息
     */
    public OptimizationSuggestionsStatistics getStatistics() {
        return new OptimizationSuggestionsStatistics(
                totalSuggestionsGenerated.get(),
                highPrioritySuggestions.get(),
                implementedSuggestions.get(),
                optimizationRules.size(),
                templates.size(),
                System.currentTimeMillis()
        );
    }
    
    /**
     * 标记建议为已实施
     */
    public void markSuggestionAsImplemented(String recommendationId) {
        implementedSuggestions.incrementAndGet();
        logger.info("标记优化建议为已实施: {}", recommendationId);
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 建议配置
     */
    public static class SuggestionsConfig {
        private double minIssueScore = 50.0;
        private double minImpactScore = 10.0;
        private boolean enableTemplates = true;
        private boolean enableRiskAnalysis = true;
        private boolean generateImplementationPlans = true;
        private String outputDirectory;
        
        // Getters and Setters
        public double getMinIssueScore() { return minIssueScore; }
        public void setMinIssueScore(double minIssueScore) { this.minIssueScore = minIssueScore; }
        public double getMinImpactScore() { return minImpactScore; }
        public void setMinImpactScore(double minImpactScore) { this.minImpactScore = minImpactScore; }
        public boolean isEnableTemplates() { return enableTemplates; }
        public void setEnableTemplates(boolean enableTemplates) { this.enableTemplates = enableTemplates; }
        public boolean isEnableRiskAnalysis() { return enableRiskAnalysis; }
        public void setEnableRiskAnalysis(boolean enableRiskAnalysis) { this.enableRiskAnalysis = enableRiskAnalysis; }
        public boolean isGenerateImplementationPlans() { return generateImplementationPlans; }
        public void setGenerateImplementationPlans(boolean generateImplementationPlans) { this.generateImplementationPlans = generateImplementationPlans; }
        public String getOutputDirectory() { return outputDirectory; }
        public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; }
    }
    
    /**
     * 优化规则
     */
    public static class OptimizationRule {
        private final String ruleId;
        private final String description;
        private final List<String> affectedMetrics;
        private final double baseScore;
        private final List<String> optimizationActions;
        private final List<String> riskMitigationActions;
        private final String severity;
        
        public OptimizationRule(String ruleId, String description, List<String> affectedMetrics,
                              double baseScore, List<String> optimizationActions,
                              List<String> riskMitigationActions) {
            this.ruleId = ruleId;
            this.description = description;
            this.affectedMetrics = affectedMetrics;
            this.baseScore = baseScore;
            this.optimizationActions = optimizationActions;
            this.riskMitigationActions = riskMitigationActions;
            this.severity = determineSeverity(baseScore);
        }
        
        private String determineSeverity(double score) {
            if (score >= 80.0) return "HIGH";
            if (score >= 60.0) return "MEDIUM";
            return "LOW";
        }
        
        // Getters
        public String getRuleId() { return ruleId; }
        public String getDescription() { return description; }
        public List<String> getAffectedMetrics() { return affectedMetrics; }
        public double getBaseScore() { return baseScore; }
        public List<String> getOptimizationActions() { return optimizationActions; }
        public List<String> getRiskMitigationActions() { return riskMitigationActions; }
        public String getSeverity() { return severity; }
    }
    
    /**
     * 优化模板
     */
    public static class OptimizationTemplate {
        private final String title;
        private final String templateId;
        private final List<String> configParameters;
        private final List<String> configChanges;
        private final String description;
        
        public OptimizationTemplate(String title, String templateId, List<String> configParameters,
                                  List<String> configChanges, String description) {
            this.title = title;
            this.templateId = templateId;
            this.configParameters = configParameters;
            this.configChanges = configChanges;
            this.description = description;
        }
        
        // Getters
        public String getTitle() { return title; }
        public String getTemplateId() { return templateId; }
        public List<String> getConfigParameters() { return configParameters; }
        public List<String> getConfigChanges() { return configChanges; }
        public String getDescription() { return description; }
    }
    
    /**
     * 优化问题
     */
    public static class OptimizationIssue {
        private final String issueId;
        private final String description;
        private final String severity;
        private final double impactScore;
        private final Map<String, Object> affectedMetrics;
        private final Map<String, Object> currentValues;
        private final List<String> optimizationActions;
        private final List<String> riskMitigationActions;
        
        public OptimizationIssue(String issueId, String description, String severity,
                               double impactScore, Map<String, Object> affectedMetrics,
                               Map<String, Object> currentValues, List<String> optimizationActions,
                               List<String> riskMitigationActions) {
            this.issueId = issueId;
            this.description = description;
            this.severity = severity;
            this.impactScore = impactScore;
            this.affectedMetrics = affectedMetrics;
            this.currentValues = currentValues;
            this.optimizationActions = optimizationActions;
            this.riskMitigationActions = riskMitigationActions;
        }
        
        // Getters
        public String getIssueId() { return issueId; }
        public String getDescription() { return description; }
        public String getSeverity() { return severity; }
        public double getImpactScore() { return impactScore; }
        public Map<String, Object> getAffectedMetrics() { return affectedMetrics; }
        public Map<String, Object> getCurrentValues() { return currentValues; }
        public List<String> getOptimizationActions() { return optimizationActions; }
        public List<String> getRiskMitigationActions() { return riskMitigationActions; }
    }
    
    /**
     * 优化建议
     */
    public static class OptimizationRecommendation {
        private final String recommendationId;
        private final String title;
        private final String description;
        private final double priorityScore;
        private final ImplementationDifficulty difficulty;
        private final double expectedImpact;
        private final String action;
        private final List<String> configChanges;
        private final List<String> implementationSteps;
        private final Duration estimatedTime;
        private final List<String> risks;
        private final List<String> validationMethods;
        
        public OptimizationRecommendation(String recommendationId, String title, String description,
                                        double priorityScore, ImplementationDifficulty difficulty,
                                        double expectedImpact, String action, List<String> configChanges,
                                        List<String> implementationSteps, Duration estimatedTime,
                                        List<String> risks, List<String> validationMethods) {
            this.recommendationId = recommendationId;
            this.title = title;
            this.description = description;
            this.priorityScore = priorityScore;
            this.difficulty = difficulty;
            this.expectedImpact = expectedImpact;
            this.action = action;
            this.configChanges = configChanges;
            this.implementationSteps = implementationSteps;
            this.estimatedTime = estimatedTime;
            this.risks = risks;
            this.validationMethods = validationMethods;
        }
        
        // Getters
        public String getRecommendationId() { return recommendationId; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public double getPriorityScore() { return priorityScore; }
        public ImplementationDifficulty getDifficulty() { return difficulty; }
        public double getExpectedImpact() { return expectedImpact; }
        public String getAction() { return action; }
        public List<String> getConfigChanges() { return configChanges; }
        public List<String> getImplementationSteps() { return implementationSteps; }
        public Duration getEstimatedTime() { return estimatedTime; }
        public List<String> getRisks() { return risks; }
        public List<String> getValidationMethods() { return validationMethods; }
    }
    
    /**
     * 优化计划
     */
    public static class OptimizationPlan {
        private final ImplementationPhase phase;
        private final List<OptimizationRecommendation> recommendations;
        private final Duration estimatedDuration;
        private final List<String> dependencies;
        private final List<String> milestones;
        
        public OptimizationPlan(ImplementationPhase phase, List<OptimizationRecommendation> recommendations,
                              Duration estimatedDuration, List<String> dependencies,
                              List<String> milestones) {
            this.phase = phase;
            this.recommendations = recommendations;
            this.estimatedDuration = estimatedDuration;
            this.dependencies = dependencies;
            this.milestones = milestones;
        }
        
        // Getters
        public ImplementationPhase getPhase() { return phase; }
        public List<OptimizationRecommendation> getRecommendations() { return recommendations; }
        public Duration getEstimatedDuration() { return estimatedDuration; }
        public List<String> getDependencies() { return dependencies; }
        public List<String> getMilestones() { return milestones; }
    }
    
    /**
     * 优化建议结果
     */
    public static class OptimizationSuggestionResult {
        private final List<OptimizationIssue> issues;
        private final List<OptimizationRecommendation> recommendations;
        private final List<OptimizationPlan> implementationPlans;
        private final long generationTimeMs;
        private final Map<String, Object> summary;
        private final String message;
        
        public OptimizationSuggestionResult(String message) {
            this.message = message;
            this.issues = new ArrayList<>();
            this.recommendations = new ArrayList<>();
            this.implementationPlans = new ArrayList<>();
            this.generationTimeMs = 0;
            this.summary = new HashMap<>();
        }
        
        public OptimizationSuggestionResult(List<OptimizationIssue> issues,
                                          List<OptimizationRecommendation> recommendations,
                                          List<OptimizationPlan> implementationPlans,
                                          long generationTimeMs,
                                          Map<String, Object> summary) {
            this.issues = issues;
            this.recommendations = recommendations;
            this.implementationPlans = implementationPlans;
            this.generationTimeMs = generationTimeMs;
            this.summary = summary;
            this.message = "优化建议生成完成";
        }
        
        // Getters
        public List<OptimizationIssue> getIssues() { return issues; }
        public List<OptimizationRecommendation> getRecommendations() { return recommendations; }
        public List<OptimizationPlan> getImplementationPlans() { return implementationPlans; }
        public long getGenerationTimeMs() { return generationTimeMs; }
        public Map<String, Object> getSummary() { return summary; }
        public String getMessage() { return message; }
    }
    
    /**
     * 实施难度
     */
    public enum ImplementationDifficulty {
        EASY, MEDIUM, HARD
    }
    
    /**
     * 实施阶段
     */
    public enum ImplementationPhase {
        IMMEDIATE,    // 立即执行
        SHORT_TERM,   // 短期计划（1-2周）
        QUICK_WIN,    // 快速胜利（1-3天）
        LONG_TERM     // 长期计划（1个月以上）
    }
    
    /**
     * 优化建议统计信息
     */
    public static class OptimizationSuggestionsStatistics {
        private final long totalSuggestionsGenerated;
        private final long highPrioritySuggestions;
        private final long implementedSuggestions;
        private final int optimizationRulesCount;
        private final int templatesCount;
        private final long uptime;
        
        public OptimizationSuggestionsStatistics(long totalSuggestionsGenerated,
                                               long highPrioritySuggestions,
                                               long implementedSuggestions,
                                               int optimizationRulesCount,
                                               int templatesCount,
                                               long uptime) {
            this.totalSuggestionsGenerated = totalSuggestionsGenerated;
            this.highPrioritySuggestions = highPrioritySuggestions;
            this.implementedSuggestions = implementedSuggestions;
            this.optimizationRulesCount = optimizationRulesCount;
            this.templatesCount = templatesCount;
            this.uptime = uptime;
        }
        
        // Getters
        public long getTotalSuggestionsGenerated() { return totalSuggestionsGenerated; }
        public long getHighPrioritySuggestions() { return highPrioritySuggestions; }
        public long getImplementedSuggestions() { return implementedSuggestions; }
        public int getOptimizationRulesCount() { return optimizationRulesCount; }
        public int getTemplatesCount() { return templatesCount; }
        public long getUptime() { return uptime; }
    }
}