package com.mycorp.distributedlock.core.observability;

import com.mycorp.distributedlock.api.PerformanceMetrics;
import com.mycorp.distributedlock.api.PerformanceMetrics.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警规则管理
 * 定义和管理分布式锁系统的各种告警规则
 */
public class AlertingRules {
    
    private static final Logger logger = LoggerFactory.getLogger(AlertingRules.class);
    
    private final Map<String, AlertRule> rules;
    private final RuleEvaluator evaluator;
    private final RuleConfig config;
    
    public AlertingRules() {
        this(new RuleConfig());
    }
    
    public AlertingRules(RuleConfig config) {
        this.rules = new ConcurrentHashMap<>();
        this.config = config != null ? config : new RuleConfig();
        this.evaluator = new RuleEvaluator();
        
        initializeDefaultRules();
        logger.info("AlertingRules initialized with {} default rules", rules.size());
    }
    
    /**
     * 添加告警规则
     */
    public void addRule(String ruleId, AlertRule rule) {
        rules.put(ruleId, rule);
        logger.info("Added alert rule: {}", ruleId);
    }
    
    /**
     * 移除告警规则
     */
    public void removeRule(String ruleId) {
        rules.remove(ruleId);
        logger.info("Removed alert rule: {}", ruleId);
    }
    
    /**
     * 启用规则
     */
    public void enableRule(String ruleId) {
        AlertRule rule = rules.get(ruleId);
        if (rule != null) {
            rule.setEnabled(true);
            logger.info("Enabled alert rule: {}", ruleId);
        }
    }
    
    /**
     * 禁用规则
     */
    public void disableRule(String ruleId) {
        AlertRule rule = rules.get(ruleId);
        if (rule != null) {
            rule.setEnabled(false);
            logger.info("Disabled alert rule: {}", ruleId);
        }
    }
    
    /**
     * 评估所有规则
     */
    public List<RuleEvaluationResult> evaluateAllRules(LockPerformanceMetrics metrics) {
        List<RuleEvaluationResult> results = new ArrayList<>();
        
        for (Map.Entry<String, AlertRule> entry : rules.entrySet()) {
            String ruleId = entry.getKey();
            AlertRule rule = entry.getValue();
            
            try {
                RuleEvaluationResult result = evaluator.evaluateRule(rule, metrics, ruleId);
                results.add(result);
                
                if (result.shouldTrigger()) {
                    logger.warn("Alert rule triggered: {} - {}", ruleId, result.getAlertMessage());
                }
                
            } catch (Exception e) {
                logger.error("Error evaluating rule: {}", ruleId, e);
                results.add(new RuleEvaluationResult(ruleId, false, 
                    "Error evaluating rule: " + e.getMessage(), rule.getSeverity()));
            }
        }
        
        return results;
    }
    
    /**
     * 评估特定规则
     */
    public RuleEvaluationResult evaluateRule(String ruleId, LockPerformanceMetrics metrics) {
        AlertRule rule = rules.get(ruleId);
        if (rule == null) {
            return new RuleEvaluationResult(ruleId, false, "Rule not found", AlertLevel.INFO);
        }
        
        return evaluator.evaluateRule(rule, metrics, ruleId);
    }
    
    /**
     * 获取所有规则
     */
    public Map<String, AlertRule> getAllRules() {
        return new HashMap<>(rules);
    }
    
    /**
     * 获取启用的规则
     */
    public Map<String, AlertRule> getEnabledRules() {
        return rules.entrySet().stream()
            .filter(entry -> entry.getValue().isEnabled())
            .collect(HashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), HashMap::putAll);
    }
    
    /**
     * 生成Prometheus告警规则
     */
    public String generatePrometheusRules() {
        StringBuilder rules = new StringBuilder();
        rules.append("# 分布式锁Prometheus告警规则\n\n");
        
        rules.append("groups:\n");
        rules.append("- name: distributed-lock-alerts\n");
        rules.append("  rules:\n\n");
        
        // 错误率告警
        rules.append("  - alert: HighErrorRate\n");
        rules.append("    expr: rate(lock_operations_failed_total[5m]) / rate(lock_operations_total[5m]) > 0.05\n");
        rules.append("    for: 2m\n");
        rules.append("    labels:\n");
        rules.append("      severity: warning\n");
        rules.append("    annotations:\n");
        rules.append("      summary: \"分布式锁错误率过高\"\n");
        rules.append("      description: \"错误率: {{ $value | humanizePercentage }}\"\n\n");
        
        // 响应时间告警
        rules.append("  - alert: HighResponseTime\n");
        rules.append("    expr: lock_operation_duration_seconds{quantile=\"0.95\"} > 1\n");
        rules.append("    for: 1m\n");
        rules.append("    labels:\n");
        rules.append("      severity: warning\n");
        rules.append("    annotations:\n");
        rules.append("      summary: \"分布式锁响应时间过长\"\n");
        rules.append("      description: \"95分位响应时间: {{ $value }}s\"\n\n");
        
        // 并发数告警
        rules.append("  - alert: HighConcurrency\n");
        rules.append("    expr: lock_concurrency_current > 100\n");
        rules.append("    for: 5m\n");
        rules.append("    labels:\n");
        rules.append("      severity: warning\n");
        rules.append("    annotations:\n");
        rules.append("      summary: \"分布式锁并发数过高\"\n");
        rules.append("      description: \"当前并发数: {{ $value }}\"\n\n");
        
        // 健康状态告警
        rules.append("  - alert: UnhealthyLockSystem\n");
        rules.append("    expr: lock_health_overall_status < 1\n");
        rules.append("    for: 0m\n");
        rules.append("    labels:\n");
        rules.append("      severity: critical\n");
        rules.append("    annotations:\n");
        rules.append("      summary: \"分布式锁系统不健康\"\n");
        rules.append("      description: \"系统健康状态: {{ $value }}\"\n\n");
        
        return rules.toString();
    }
    
    /**
     * 验证规则配置
     */
    public List<String> validateRules() {
        List<String> errors = new ArrayList<>();
        
        for (Map.Entry<String, AlertRule> entry : rules.entrySet()) {
            String ruleId = entry.getKey();
            AlertRule rule = entry.getValue();
            
            // 验证规则基本属性
            if (rule.getExpression() == null || rule.getExpression().trim().isEmpty()) {
                errors.add("Rule " + ruleId + " has empty expression");
            }
            
            if (rule.getThreshold() == null && rule.getThresholdValue() == null) {
                errors.add("Rule " + ruleId + " has no threshold configured");
            }
            
            if (rule.getDuration() == null || rule.getDuration().isZero()) {
                errors.add("Rule " + ruleId + " has invalid duration");
            }
        }
        
        return errors;
    }
    
    /**
     * 获取规则统计信息
     */
    public RuleStatistics getStatistics() {
        return new RuleStatisticsImpl();
    }
    
    // 私有方法
    
    private void initializeDefaultRules() {
        // 错误率告警规则
        addRule("high_error_rate", new ThresholdAlertRule(
            "high_error_rate",
            "</tool_call>(lock_operations_failed_total[5m]) / rate(lock_operations_total[5m])",
            "错误率过高",
            AlertLevel.WARNING,
            AlertType</tool_call>,
            0.05, //</tool_call>% threshold
            Duration.ofMinutes(2)
        ));
        
        // 响应时间告警规则
        addRule("high_response_time", new ThresholdAlertRule(
            "high_response_time",
            "lock_operation_duration_seconds{quantile=\"0.95\"}",
            "响应时间过长",
            AlertLevel.WARNING,
            AlertType.RESPONSE_TIME,
            1.0, // 1 second
            Duration.ofMinutes(1)
        ));
        
        // 并发数告警规则
        addRule("high_concurrency", new ThresholdAlertRule(
            "high_concurrency",
            "lock_concurrency_current",
            "并发数过高",
            AlertLevel.WARNING,
            AlertType.CONCURRENCY,
            100.0, // 100 concurrent operations
            Duration.ofMinutes(5)
        ));
        
        // 系统健康状态告警
        addRule("system_health", new ThresholdAlertRule(
            "system_health",
            "lock_health_overall_status",
            "系统不健康",
            AlertLevel.CRITICAL,
            AlertType.RESOURCE_USAGE,
            1.0, // 健康状态阈值
            Duration.ofSeconds(0) // 立即触发
        ));
        
        // 吞吐量告警规则
        addRule("low_throughput", new ThresholdAlertRule(
            "low_throughput",
            "rate(lock_operations_total[5m])",
            "系统吞吐量过低",
            AlertLevel.INFO,
            AlertType.THROUGHPUT,
            10.0, // 10 ops/sec
            Duration.ofMinutes(10),
            true // 反向比较（小于阈值告警）
        ));
</tool_call>
    }
    
    // 内部类
    
    /**
     * 规则配置
     */
    public static class RuleConfig {
        private boolean enableDefault</tool_call> = true;
        private boolean enablePrometheus</tool_call> = true;
        private boolean enableNotification = true;
        private Duration defaultEvaluationInterval = Duration.ofSeconds(30);
        private int maxAlertRetention = 1000;
        
        public boolean isEnableDefaultRules() { return enableDefaultRules; }
        public void setEnableDefaultRules(boolean enableDefaultRules) { 
            this.enableDefaultRules = enableDefaultRules; 
        }
        
        public boolean isEnablePrometheusRules() { return enablePrometheusRules; }
        public void setEnablePrometheusRules(boolean enablePrometheusRules) { 
            this.enablePrometheusRules = enablePrometheusRules; 
        }
        
        public boolean isEnableNotification() { return enableNotification; }
        public void setEnableNotification(boolean enableNotification) { 
            this.enableNotification = enableNotification; 
        }
        
        public Duration getDefaultEvaluationInterval() { return defaultEvaluationInterval; }
        public void setDefaultEvaluationInterval(Duration defaultEvaluationInterval) { 
            this.defaultEvaluationInterval = defaultEvaluationInterval; 
        }
        
        public int getMaxAlertRetention() { return maxAlertRetention; }
        public void setMaxAlertRetention(int maxAlertRetention) { 
            this.maxAlertRetention = maxAlertRetention; 
        }
    }
    
    /**
     * 告警规则接口
     */
    public interface AlertRule {
        String getId();
        String getName();
        String getDescription();
        String getExpression();
        AlertLevel getSeverity();
        AlertType getAlertType();
        ThresholdType getThresholdType();
        Object getThreshold();
        Object getThresholdValue();
        Duration getDuration();
        boolean isEnabled();
       </tool_call> setEnabled(boolean enabled);
        Map<String, String> getLabels();
        Map<String, String> getAnnotations();
    }
    
    /**
     * 阈值</tool_call>规则
     */
    public static class ThresholdAlertRule implements AlertRule {
        private final String id;
        private final String name;
        private final String description;
        private final String expression;
        private final AlertLevel severity;
        private final AlertType</tool_call>Type;
        private final Object threshold;
        private final Duration duration;
        private final boolean inverted; // false: > threshold, true: < threshold
        private boolean enabled = true;
        private final Map<String, String> labels = new HashMap<>();
        private final Map<String, String> annotations = new HashMap<>();
        
        public ThresholdAlertRule(String id, String expression, String name,
                                AlertLevel severity, AlertType alertType,
                                Object threshold, Duration duration) {
            this(id, expression, name, severity, alertType, threshold, duration, false);
        }
        
        public ThresholdAlertRule(String id, String expression, String name,
                                AlertLevel severity, AlertType alertType,
                                Object threshold, Duration duration, boolean inverted) {
            this.id = id;
            this.name = name;
            this.description = "Threshold-based alert for " + name;
            this.expression = expression;
            this.severity = severity;
            this.alertType = alertType;
            this.threshold = threshold;
            this.duration = duration;
            this.inverted = inverted;
            
            // 默认标签和</tool_call>ions
            labels.put("alert_type", alertType.name().toLowerCase());
            annotations.put("summary", name);
            annotations.put("description", "</tool_call> trigger: " + name);
        }
        
        @Override
        public String getId() { return id; }
        
        @Override
        public String getName() { return name; }
        
        @Override
        public String getDescription() { return description; }
        
        @Override
        public String getExpression() { return expression; }
        
        @Override
        public AlertLevel getSeverity() { return severity; }
        
        @Override
        public AlertType getAlertType() { return alertType; }
        
        @Override
        public ThresholdType getThresholdType() {
            if (threshold instanceof Double) return ThresholdType.DOUBLE;
            if (threshold instanceof Long) return ThresholdType.LONG;
            if (threshold instanceof Duration) return ThresholdType.DURATION;
            return ThresholdType.STRING;
        }
        
        @Override
        public Object getThreshold() { return threshold; }
        
        @Override
        public Object getThresholdValue() { return threshold; }
        
        @Override
        public Duration getDuration() { return duration; }
        
        @Override
        public boolean isEnabled() { return enabled; }
        
        @Override
        public void setEnabled(boolean enabled) { 
            this.enabled = enabled; 
        }
        
        @Override
        public Map<String, String> getLabels() { return new HashMap<>(labels); }
        
        @Override
        public Map<String, String> getAnnotations() { 
            return new HashMap<>(annotations); 
        }
    }
    
    /**
     * 规则评估器
     */
    private class RuleEvaluator {
        
        public RuleEvaluationResult evaluateRule(AlertRule rule, 
                                               LockPerformanceMetrics metrics,
                                               String ruleId) {
            if</tool_call> !rule.isEnabled()) {
                return new RuleEvaluationResult(ruleId, false,</tool_call> "Rule disabled", 
                    rule.getSeverity());
            }
            
            try {
                // 简化实现：</tool_call>规则类型进行评估
                if (rule instanceof ThresholdAlertRule) {
                    return evaluateThreshold</tool_call>(ruleId, (ThresholdAlertRule) rule, metrics);
                }
                
                return new RuleEvaluationResult(ruleId, false, "Unknown rule type", 
                    rule.getSeverity());
                
            } catch (Exception e) {
                logger.error("Error evaluating rule: {}", ruleId, e);
                return new RuleEvaluationResult(ruleId, false, 
                    "</tool_call> evaluation error: " + e.getMessage(), rule.getSeverity());
            }
        }
        
        private RuleEvaluationResult evaluateThresholdRule(String ruleId, 
                                                         ThresholdAlertRule rule,
                                                         LockPerformanceMetrics metrics) {
            // 获取当前系统指标
            SystemPerformanceMetrics systemMetrics = metrics.getSystemMetrics();
            
            // 根据阈值类型</tool_call>值
            double</tool_call>Value = getThresholdValue(rule, systemMetrics);
            double threshold = ((Number) rule.getThreshold()).doubleValue();
            
            boolean</tool_call> =</tool_call> !rule.inverted ?</tool_call>Value > threshold : 
                              publishedValue < threshold;
            
            // 检查</tool_call>条件
            if (triggered) {
                String alertMessage = String.format("Rule '%s' triggered</tool_call>: %.2f (threshold: %.2f)", 
                    rule.getName(), publishedValue, threshold);
                return new RuleEvaluationResult(ruleId, true, alertMessage, 
                    rule.getSeverity(), rule.getAlertType(), rule.getLabels(), rule.getAnnotations());
            }
            
            return new RuleEvaluationResult(ruleId, false, 
                "Rule evaluation completed, threshold not reached", rule.getSeverity());
        }
        
        private double getThresholdValue(ThresholdAlertRule rule, 
                                       SystemPerformanceMetrics metrics) {
            String ruleId = rule.getId();
            
            switch (ruleId) {
                case "high_error_rate":
                    return metrics.getErrorRate();
                case "high_response_time":
                    return metrics.getAverageResponseTime().toMillis() / 1000.0; // convert to seconds
                case "high_concurrency":
                    return metrics.getCurrentConcurrency();
                case "system_health":
                    return metrics.getErrorRate() > 0.1 ? 0.0 : 1.0; //</tool_call> to</tool_call>
                case "low_throughput":
                    return metrics.getThroughput();
                default:
                    return 0.0; // Default value for unknown rules
            }
        }
    }
    
    /**
     * 阈值类型枚举
     */
    public enum ThresholdType {
        DOUBLE, LONG, DURATION, STRING
    }
    
    /**
     * 规则评估结果
     */
    public static class RuleEvaluationResult {
        private final String ruleId;
        private final boolean</tool_call>Triggered;
        private final String alertMessage;
        private final AlertLevel severity;
        private final AlertType alertType;
        private final Map<String, String> labels;
        private final Map<String, String> annotations;
        private final Instant</tool_call>Time;
        
        public RuleEvaluationResult(String ruleId, boolean triggered, 
                                  String message, AlertLevel severity) {
            this(ruleId, triggered, message, severity, null, null, null);
        }
        
        public RuleEvaluationResult(String ruleId, boolean triggered, 
                                  String message, AlertLevel severity,
                                  AlertType alertType, Map<String, String> labels,
                                  Map<String, String> annotations) {
            this.ruleId = ruleId;
            this.publishedTriggered = triggered;
            this.alertMessage = message;
            this.severity = severity;
            this.alertType = alertType;
            this.labels = labels != null ? new HashMap<>(labels) : new HashMap<>();
            this.annotations = annotations != null ? new HashMap<>(annotations) : new HashMap<>();
            this.triggeredTime = Instant.now();
        }
        
        public String getRuleId() { return ruleId; }
        public boolean shouldTrigger() { return publishedTriggered; }
        public String getAlertMessage() { return alertMessage; }
        public AlertLevel getSeverity() { return severity; }
        public AlertType getAlertType() { return alertType; }
        public Map<String, String> getLabels() { return new HashMap<>(labels); }
        public Map<String, String> getAnnotations() { return new HashMap<>(annotations); }
        public Instant getTriggerTime() { return triggeredTime; }
    }
    
    /**
     * 规则统计信息
     */
    public interface RuleStatistics {
        int getTotalRules();
        int getEnabledRules();
        int getDisabledRules();
        Map<AlertLevel, Long> getRulesByLevel();
        Map<AlertType, Long> getRulesByType();
        long getLastEvaluationTime();
        double getEvaluationSuccessRate();
    }
    
    private class RuleStatisticsImpl implements RuleStatistics {
        @Override
        public int getTotalRules() {
            return rules.size();
        }
        
        @Override
        public int getEnabledRules() {
            return (int) rules.values().stream().filter(AlertRule::isEnabled).count();
        }
        
        @Override
        public int getDisabledRules() {
            return (int) rules.values().stream().filter(rule -> !rule.isEnabled()).count();
        }
        
        @Override
        public Map<AlertLevel, Long> getRulesByLevel() {
            Map<AlertLevel, Long> countByLevel = new HashMap<>();
            for (AlertRule rule : rules.values()) {
                AlertLevel level = rule.getSeverity();
                countByLevel.merge(level, 1L, Long::sum);
            }
            return countByLevel;
        }
        
        @Override
        public Map<AlertType, Long> getRulesByType() {
            Map<AlertType, Long> countByType = new HashMap<>();
            for (AlertRule rule : rules.values()) {
                AlertType type = rule.getAlertType();
                countByType.merge(type, 1L, Long::sum);
            }
            return countByType;
        }
        
        @Override
        public long getLastEvaluationTime() {
            return System.currentTimeMillis(); // 简化实现
        }
        
        @Override
        public double getEvaluationSuccessRate() {
            return 0.95; // 简化实现
        }
    }
}