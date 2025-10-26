package com.mycorp.distributedlock.core.observability;

import com.mycorp.distributedlock.api.PerformanceMetrics;
import com.mycorp.distributedlock.api.PerformanceMetrics.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 告警服务
 * 负责告警规则管理、阈值监控和通知发送
 */
public class LockAlertingService {
    
    private static final Logger logger = LoggerFactory.getLogger(LockAlertingService.class);
    
    private final LockPerformanceMetrics performanceMetrics;
    private final MeterRegistry meterRegistry;
    private final AlertingConfig config;
    
    // 告警状态管理
    private final AtomicBoolean isAlertingEnabled = new AtomicBoolean(true);
    private final Map<String, LockAlertState> lockAlertStates;
    private final Map<AlertType, List<AlertRule>> alertRules;
    
    // 告警统计
    private final AtomicLong totalAlertsTriggered = new AtomicLong(0);
    private final AtomicLong totalAlertsResolved = new AtomicLong(0);
    private final AtomicLong suppressedAlerts = new AtomicLong(0);
    private final AtomicReference<Instant> lastAlertTime = new AtomicReference<>(Instant.now());
    
    // 告警回调和通知器
    private PerformanceAlertCallback alertCallback;
    private final List<AlertNotifier> notifiers;
    private final ScheduledExecutorService scheduler;
    
    // 告警降噪和聚合
    private final AlertAggregation aggregation;
    private final AlertNoiseReduction noiseReduction;
    
    public LockAlertingService(LockPerformanceMetrics performanceMetrics, MeterRegistry meterRegistry) {
        this(performanceMetrics, meterRegistry, new AlertingConfig());
    }
    
    public LockAlertingService(LockPerformanceMetrics performanceMetrics,
                             MeterRegistry meterRegistry, AlertingConfig config) {
        this.performanceMetrics = performanceMetrics;
        this.meterRegistry = meterRegistry;
        this.config = config != null ? config : new AlertingConfig();
        this.lockAlertStates = new ConcurrentHashMap<>();
        this.alertRules = new ConcurrentHashMap<>();
        this.notifiers = new ArrayList<>();
        
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread thread = new Thread(r, "lock-alerting-service");
            thread.setDaemon(true);
            return thread;
        });
        
        this.aggregation = new AlertAggregation();
        this.noiseReduction = new AlertNoiseReduction();
        
        // 初始化默认告警规则
        initializeDefaultAlertRules();
        
        // 初始化通知器
        initializeNotifiers();
        
        // 启动告警检查任务
        startAlertChecking();
        
        logger.info("LockAlertingService initialized with config: {}", config);
    }
    
    /**
     * 启用告警服务
     */
    public void enableAlerting() {
        if (isAlertingEnabled.compareAndSet(false, true)) {
            startAlertChecking();
            logger.info("Lock alerting service enabled");
        }
    }
    
    /**
     * 禁用告警服务
     */
    public void disableAlerting() {
        if (isAlertingEnabled.compareAndSet(true, false)) {
            scheduler.shutdown();
            logger.info("Lock alerting service disabled");
        }
    }
    
    /**
     * 添加告警规则
     */
    public void addAlertRule(AlertType type, AlertRule rule) {
        alertRules.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(rule);
        logger.info("Added alert rule for type: {}", type);
    }
    
    /**
     * 移除告警规则
     */
    public void removeAlertRule(AlertType type, AlertRule rule) {
        List<AlertRule> rules = alertRules.get(type);
        if (rules != null) {
            rules.remove(rule);
            logger.info("Removed alert rule for type: {}", type);
        }
    }
    
    /**
     * 检查告警条件
     */
    public void checkAlertConditions(String lockName, LockOperation operation,
                                   Duration duration, boolean success) {
        if (!isAlertingEnabled.get()) return;
        
        try {
            // 获取锁级告警状态
            LockAlertState alertState = lockAlertStates.computeIfAbsent(lockName,
                k -> new LockAlertState(lockName));
            
            // 检查操作级告警
            checkOperationAlerts(alertState, operation, duration, success);
            
            // 检查锁级告警
            checkLockAlerts(alertState);
            
        } catch (Exception e) {
            logger.error("Error checking alert conditions for lock: {}", lockName, e);
        }
    }
    
    /**
     * 触发告警
     */
    public void triggerAlert(PerformanceAlert alert) {
        if (!isAlertingEnabled.get()) return;
        
        try {
            // 应用告警降噪
            if (!noiseReduction.shouldFire(alert)) {
                suppressedAlerts.incrementAndGet();
                logger.debug("Alert suppressed by noise reduction: {}", alert.getMessage());
                return;
            }
            
            // 检查告警抑制
            String alertKey = generateAlertKey(alert);
            if (isAlertSuppressed(alertKey)) {
                suppressedAlerts.incrementAndGet();
                logger.debug("Alert suppressed: {}", alert.getMessage());
                return;
            }
            
            // 聚合相关告警
            List<PerformanceAlert> aggregatedAlerts = aggregation.aggregateAlerts(alert);
            
            for (PerformanceAlert aggregatedAlert : aggregatedAlerts) {
                // 触发告警通知
                sendNotification(aggregatedAlert);
                
                // 更新统计信息
                updateAlertStatistics(aggregatedAlert, true);
                
                // 记录告警状态
                recordAlertState(aggregatedAlert);
            }
            
            lastAlertTime.set(Instant.now());
            totalAlertsTriggered.incrementAndGet();
            
            logger.warn("Alert triggered: {} - {}", alert.getAlertType(), alert.getMessage());
            
        } catch (Exception e) {
            logger.error("Error triggering alert", e);
        }
    }
    
    /**
     * 解析告警
     */
    public void resolveAlert(PerformanceAlert alert) {
        try {
            // 更新告警状态
            String alertKey = generateAlertKey(alert);
            LockAlertState alertState = lockAlertStates.get(alert.getLockName());
            if (alertState != null) {
                alertState.markResolved(alert.getAlertType(), alert.getAlertLevel());
            }
            
            // 发送解析通知
            sendResolutionNotification(alert);
            
            // 更新统计信息
            updateAlertStatistics(alert, false);
            totalAlertsResolved.incrementAndGet();
            
            logger.info("Alert resolved: {} - {}", alert.getAlertType(), alert.getMessage());
            
        } catch (Exception e) {
            logger.error("Error resolving alert", e);
        }
    }
    
    /**
     * 设置告警回调
     */
    public void setCallback(PerformanceAlertCallback callback) {
        this.alertCallback = callback;
    }
    
    /**
     * 添加通知器
     */
    public void addNotifier(AlertNotifier notifier) {
        notifiers.add(notifier);
        logger.info("Added alert notifier: {}", notifier.getName());
    }
    
    /**
     * 移除通知器
     */
    public void removeNotifier(AlertNotifier notifier) {
        notifiers.remove(notifier);
        logger.info("Removed alert notifier: {}", notifier.getName());
    }
    
    /**
     * 获取告警统计
     */
    public AlertingStatistics getStatistics() {
        return new AlertingStatisticsImpl();
    }
    
    /**
     * 获取活跃告警
     */
    public List<PerformanceAlert> getActiveAlerts() {
        return lockAlertStates.values().stream()
            .flatMap(state -> state.getActiveAlerts().stream())
            .collect(Collectors.toList());
    }
    
    /**
     * 获取告警历史
     */
    public List<PerformanceAlert> getAlertHistory(Duration timeRange) {
        // 这里应该查询告警历史存储
        // 现在返回空列表作为示例
        return new ArrayList<>();
    }
    
    /**
     * 测试告警
     */
    public void testAlert(AlertType type, AlertLevel level, String message) {
        PerformanceAlert testAlert = new PerformanceAlertImpl(
            type, level, "TEST: " + message, "test-lock", System.currentTimeMillis()
        );
        triggerAlert(testAlert);
    }
    
    // 私有方法
    
    private void initializeDefaultAlertRules() {
        // 添加默认告警规则
        alertRules.put(AlertType.ERROR_RATE, Arrays.asList(
            new ThresholdAlertRule("Error rate > 5%", AlertLevel.WARNING, 0.05),
            new ThresholdAlertRule("Error rate > 10%", AlertLevel.CRITICAL, 0.10)
        ));
        
        alertRules.put(AlertType.RESPONSE_TIME, Arrays.asList(
            new ThresholdAlertRule("Response time > 1s", AlertLevel.WARNING, 1000.0),
            new ThresholdAlertRule("Response time > 5s", AlertLevel.CRITICAL, 5000.0)
        ));
        
        alertRules.put(AlertType.CONCURRENCY, Arrays.asList(
            new ThresholdAlertRule("Concurrency > 100", AlertLevel.WARNING, 100.0),
            new ThresholdAlertRule("Concurrency > 500", AlertLevel.CRITICAL, 500.0)
        ));
        
        alertRules.put(AlertType.THROUGHPUT, Arrays.asList(
            new ThresholdAlertRule("Success rate < 95%", AlertLevel.WARNING, 0.95, true)
        ));
    }
    
    private void initializeNotifiers() {
        // 添加默认通知器（如果配置了）
        if (config.isLogNotificationEnabled()) {
            notifiers.add(new LogAlertNotifier());
        }
        
        if (config.isMicrometerNotificationEnabled() && meterRegistry != null) {
            notifiers.add(new MicrometerAlertNotifier(meterRegistry));
        }
        
        // 可以添加更多的通知器，如邮件、短信、钉钉等
    }
    
    private void startAlertChecking() {
        if (scheduler.isShutdown()) {
            // 重新创建调度器（如果之前已关闭）
            // 这里简化处理，实际使用时需要注意线程安全问题
        }
        
        scheduler.scheduleWithFixedDelay(this::performPeriodicAlertCheck,
                                       config.getCheckInterval().toSeconds(),
                                       config.getCheckInterval().toSeconds(),
                                       TimeUnit.SECONDS);
    }
    
    private void performPeriodicAlertCheck() {
        try {
            // 清理过期的告警抑制
            cleanupAlertSuppressions();
            
            // 检查规则匹配
            for (LockAlertState alertState : lockAlertStates.values()) {
                checkAlertRules(alertState);
            }
            
            // 发送定期告警摘要（如果启用）
            if (config.isPeriodicSummaryEnabled()) {
                sendPeriodicSummary();
            }
            
        } catch (Exception e) {
            logger.error("Error in periodic alert check", e);
        }
    }
    
    private void checkOperationAlerts(LockAlertState alertState, LockOperation operation,
                                    Duration duration, boolean success) {
        // 检查操作级告警
        if (!success) {
            PerformanceAlert alert = new PerformanceAlertImpl(
                AlertType.ERROR_RATE,
                AlertLevel.CRITICAL,
                "Operation failed: " + operation,
                alertState.getLockName(),
                System.currentTimeMillis()
            );
            triggerAlert(alert);
        }
        
        // 检查超时告警
        if (duration.compareTo(config.getOperationTimeoutThreshold()) > 0) {
            PerformanceAlert alert = new PerformanceAlertImpl(
                AlertType.RESPONSE_TIME,
                AlertLevel.WARNING,
                "Operation timeout: " + operation + " took " + duration,
                alertState.getLockName(),
                System.currentTimeMillis()
            );
            triggerAlert(alert);
        }
    }
    
    private void checkLockAlerts(LockAlertState alertState) {
        // 获取锁性能指标
        LockPerformanceMetrics lockMetrics = performanceMetrics.getLockMetrics(alertState.getLockName());
        
        // 检查获取成功率
        double successRate = lockMetrics.getAcquisitionSuccessRate();
        if (successRate < config.getMinSuccessRateThreshold()) {
            PerformanceAlert alert = new PerformanceAlertImpl(
                AlertType.THROUGHPUT,
                AlertLevel.WARNING,
                "Low acquisition success rate: " + (successRate * 100) + "%",
                alertState.getLockName(),
                System.currentTimeMillis()
            );
            triggerAlert(alert);
        }
        
        // 检查竞争度
        double contentionLevel = lockMetrics.getContentionLevel();
        if (contentionLevel > config.getMaxContentionThreshold()) {
            PerformanceAlert alert = new PerformanceAlertImpl(
                AlertType.CONCURRENCY,
                AlertLevel.CRITICAL,
                "High contention level: " + contentionLevel,
                alertState.getLockName(),
                System.currentTimeMillis()
            );
            triggerAlert(alert);
        }
        
        // 检查持有时间
        Duration avgHoldTime = lockMetrics.getAverageHoldTime();
        if (avgHoldTime.compareTo(config.getMaxHoldTimeThreshold()) > 0) {
            PerformanceAlert alert = new PerformanceAlertImpl(
                AlertType.RESPONSE_TIME,
                AlertLevel.WARNING,
                "Long hold time: " + avgHoldTime,
                alertState.getLockName(),
                System.currentTimeMillis()
            );
            triggerAlert(alert);
        }
    }
    
    private void checkAlertRules(LockAlertState alertState) {
        for (Map.Entry<AlertType, List<AlertRule>> entry : alertRules.entrySet()) {
            AlertType alertType = entry.getKey();
            List<AlertRule> rules = entry.getValue();
            
            for (AlertRule rule : rules) {
                if (rule.shouldTrigger(alertState)) {
                    PerformanceAlert alert = rule.getAlert();
                    triggerAlert(alert);
                }
            }
        }
    }
    
    private void sendNotification(PerformanceAlert alert) {
        // 发送到所有通知器
        for (AlertNotifier notifier : notifiers) {
            try {
                notifier.send(alert);
            } catch (Exception e) {
                logger.error("Error sending alert via notifier: {}", notifier.getName(), e);
            }
        }
        
        // 调用回调
        if (alertCallback != null) {
            try {
                alertCallback.onPerformanceAlert(alert);
            } catch (Exception e) {
                logger.error("Error in alert callback", e);
            }
        }
    }
    
    private void sendResolutionNotification(PerformanceAlert alert) {
        for (AlertNotifier notifier : notifiers) {
            try {
                notifier.resolve(alert);
            } catch (Exception e) {
                logger.error("Error sending resolution notification via notifier: {}", notifier.getName(), e);
            }
        }
    }
    
    private void sendPeriodicSummary() {
        List<PerformanceAlert> activeAlerts = getActiveAlerts();
        if (!activeAlerts.isEmpty()) {
            logger.info("Periodic Alert Summary - Active alerts: {}", activeAlerts.size());
        }
    }
    
    private void cleanupAlertSuppressions() {
        Instant cutoffTime = Instant.now().minus(config.getAlertSuppressionDuration());
        // 清理过期的告警抑制记录
        // 这里简化实现
    }
    
    private void updateAlertStatistics(PerformanceAlert alert, boolean triggered) {
        if (meterRegistry != null) {
            String type = alert.getAlertType().name().toLowerCase();
            String level = alert.getAlertLevel().name().toLowerCase();
            
            Counter.builder("lock.alerts.total")
                .description("Total number of alerts")
                .tag("type", type)
                .tag("level", level)
                .register(meterRegistry)
                .increment();
                
            if (triggered) {
                Counter.builder("lock.alerts.triggered")
                    .description("Number of alerts triggered")
                    .tag("type", type)
                    .tag("level", level)
                    .register(meterRegistry)
                    .increment();
            }
        }
    }
    
    private void recordAlertState(PerformanceAlert alert) {
        String lockName = alert.getLockName();
        if (lockName != null) {
            LockAlertState alertState = lockAlertStates.computeIfAbsent(lockName,
                k -> new LockAlertState(lockName));
            alertState.recordAlert(alert);
        }
    }
    
    private String generateAlertKey(PerformanceAlert alert) {
        return alert.getAlertType() + ":" + alert.getLockName() + ":" + alert.getAlertLevel();
    }
    
    private boolean isAlertSuppressed(String alertKey) {
        // 检查告警是否在抑制期内
        // 这里简化实现，实际需要维护抑制状态
        return false;
    }
    
    // 内部类
    
    /**
     * 锁告警状态
     */
    private static class LockAlertState {
        private final String lockName;
        private final Map<AlertType, AlertState> alertTypeStates;
        private final List<PerformanceAlert> activeAlerts;
        
        public LockAlertState(String lockName) {
            this.lockName = lockName;
            this.alertTypeStates = new HashMap<>();
            this.activeAlerts = new ArrayList<>();
        }
        
        public void recordAlert(PerformanceAlert alert) {
            alertTypeStates.put(alert.getAlertType(), new AlertState(alert));
            activeAlerts.removeIf(a -> a.getAlertType() == alert.getAlertType());
            activeAlerts.add(alert);
        }
        
        public void markResolved(AlertType alertType, AlertLevel alertLevel) {
            alertTypeStates</tool_call>(alertType, new AlertState(null));
            activeAlerts.removeIf(a -> a.getAlertType() == alertType);
        }
        
        public String getLockName() { return lockName; }
        public List<PerformanceAlert> getActiveAlerts() { return new ArrayList<>(activeAlerts); }
    }
    
    /**
     * 单个告警状态
     */
    private static class AlertState {
        private final PerformanceAlert currentAlert;
        private final AtomicReference<Instant></tool_call>Time = new AtomicReference<>(Instant.now());
        
        public AlertState(PerformanceAlert alert) {
            this.currentAlert = alert;
        }
        
        public boolean isActive() { return currentAlert != null; }
        public PerformanceAlert getCurrentAlert() { return currentAlert; }
        public Instant getResolveTime() { return resolveTime.get(); }
    }
    
    /**
     * 告警降噪器
     */
    private static class AlertNoiseReduction {
        private final Map<String, Instant> lastAlertTimes = new ConcurrentHashMap<>();
        
        public boolean shouldFire(PerformanceAlert alert) {
            String key = generateAlertKey(alert);
            Instant now = Instant.now();
            Instant lastTime = lastAlertTimes.get(key);
            
            if (lastTime == null) {
                lastAlertTimes.put(key, now);
                return true;
            }
            
            Duration interval = Duration.between(lastTime, now);
            // 如果在抑制期内，抑制告警
            if (interval.toMinutes() < 5) { // 5分钟抑制期
                return false;
            }
            
            lastAlertTimes.put(key, now);
            return true;
        }
        
        private String generateAlertKey(PerformanceAlert alert) {
            return alert.getAlertType() + ":" + alert.getLockName() + ":" + alert.getAlertLevel();
        }
    }
    
    /**
     * 告警聚合器
     */
    private static class AlertAggregation {
        public List<PerformanceAlert> aggregateAlerts(PerformanceAlert alert) {
            // 简化实现：直接返回原始告警
            // 实际实现可以聚合相关告警
            return Collections.singletonList(alert);
        }
    }
    
    /**
     * 阈值告警规则
     */
    private static class ThresholdAlertRule implements AlertRule {
        private final String description;
        private final AlertLevel level;
        private final double threshold;
        private final boolean inverted; // true表示小于阈值告警，false表示大于阈值告警
        
        public ThresholdAlertRule(String description, AlertLevel level, double threshold) {
            this(description, level, threshold, false);
        }
        
        public ThresholdAlertRule(String description, AlertLevel level, double threshold, boolean inverted) {
            this.description = description;
            this.level = level;
            this.threshold = threshold;
            this.inverted = inverted;
        }
        
        @Override
        public boolean shouldTrigger(LockAlertState alertState) {
            // 这里需要根据具体的阈值逻辑实现
            // 简化返回false
            return false;
        }
        
        @Override
        public PerformanceAlert getAlert() {
            return new PerformanceAlertImpl(
                AlertType.RESPONSE_TIME,
                level,
                description,
                "unknown-lock",
                System.currentTimeMillis()
            );
        }
    }
    
    /**
     * 告警通知器接口
     */
    public interface AlertNotifier {
        String getName();
        void send(PerformanceAlert alert);
        void resolve(PerformanceAlert alert);
    }
    
    /**
     * 日志通知器
     */
    private static class LogAlertNotifier implements AlertNotifier {
        @Override
        public String getName() {
            return "Log Alert Notifier";
        }
        
        @Override
        public void send(PerformanceAlert alert) {
            logger.warn("ALERT: {} - {}", alert.getAlertType(), alert.getMessage());
        }
        
        @Override
        public void resolve(PerformanceAlert alert) {
            logger.info("RESOLVED: {} - {}", alert.getAlertType(), alert.getMessage());
        }
    }
    
    /**
     * Micrometer通知器
     */
    private static class MicrometerAlertNotifier implements AlertNotifier {
        private final MeterRegistry meterRegistry;
        
        public MicrometerAlertNotifier(MeterRegistry meterRegistry) {
            this.meterRegistry = meterRegistry;
        }
        
        @Override
        public String getName() {
            return "Micrometer Alert Notifier";
        }
        
        @Override
        public void send(PerformanceAlert alert) {
            // 记录到Micrometer
            Counter.builder("lock.alerts.notified")
                .description("Alerts sent via notification")
                .</tool_call>("type", alert.getAlertType().name().toLowerCase())
                .tag("level", alert.getAlertLevel().name().toLowerCase())
                .register(meterRegistry)
                .increment();
        }
        
        @Override
        public void resolve(PerformanceAlert alert) {
            // 记录解析通知
            Counter.builder("lock.alerts.resolved")
                .description("Alert resolutions sent")
                .tag("type", alert.getAlertType().name().toLowerCase())
                .tag("level", alert.getAlertLevel().name().toLowerCase())
                .register(meterRegistry)
                .increment();
        }
    }
    
    // 配置类
    
    /**
     * 告警配置
     */
    public static class AlertingConfig {
        private Duration checkInterval = Duration.ofSeconds(30);
        private Duration operationTimeoutThreshold = Duration.ofSeconds(10);
        private Duration maxHoldTimeThreshold = Duration.ofMinutes(5);
        private Duration alertSuppressionDuration = Duration.ofMinutes(5);
        private boolean logNotificationEnabled = true;
        private boolean micrometerNotificationEnabled = true;
        private boolean periodicSummaryEnabled = true;
        private double minSuccessRateThreshold = 0.95;
        private double maxContentionThreshold = 0.1;
        
        // Getters and setters
        public Duration getCheckInterval() { return checkInterval; }
        public void setCheckInterval(Duration checkInterval) { 
            this.checkInterval = checkInterval; 
        }
        
        public Duration getOperationTimeoutThreshold() { return operationTimeoutThreshold; }
        public void setOperationTimeoutThreshold(Duration operationTimeoutThreshold) { 
            this.operationTimeoutThreshold = operationTimeoutThreshold; 
        }
        
        public Duration getMaxHoldTimeThreshold() { return maxHoldTimeThreshold; }
        public void setMaxHoldTimeThreshold(Duration maxHoldTimeThreshold) { 
            this.maxHoldTimeThreshold = maxHoldTimeThreshold; 
        }
        
        public Duration getAlertSuppressionDuration() { return alertSuppressionDuration; }
        public void setAlertSuppressionDuration(Duration alertSuppressionDuration) { 
            this.alertSuppressionDuration = alertSuppressionDuration; 
        }
        
        public boolean isLogNotificationEnabled() { return logNotificationEnabled; }
        public void setLogNotificationEnabled(boolean logNotificationEnabled) { 
            this.logNotificationEnabled = logNotificationEnabled; 
        }
        
        public boolean isMicrometerNotificationEnabled() { return micrometerNotificationEnabled; }
        public void setMicrometerNotificationEnabled(boolean micrometerNotificationEnabled) { 
            this.micrometerNotificationEnabled = micrometerNotificationEnabled; 
        }
        
        public boolean isPeriodicSummaryEnabled() { return periodicSummaryEnabled; }
        public void setPeriodicSummaryEnabled(boolean periodicSummaryEnabled) { 
            this.periodicSummaryEnabled = periodicSummaryEnabled; 
        }
        
        public double getMinSuccessRateThreshold() { return minSuccessRateThreshold; }
        public void setMinSuccessRateThreshold(double minSuccessRateThreshold) { 
            this.minSuccessRateThreshold = minSuccessRateThreshold; 
        }
        
        public double getMaxContentionThreshold() { return maxContentionThreshold; }
        public void setMaxContentionThreshold(double maxContentionThreshold) { 
            this.maxContentionThreshold = maxContentionThreshold; 
        }
    }
    
    /**
     * 告警统计
     */
    public interface AlertingStatistics {
        long getTotalAlertsTriggered();
        long getTotalAlertsResolved();
        long getSuppressedAlerts();
        Instant getLastAlertTime();
        Duration getUptime();
        Map<String, Long> getAlertsByType();
        Map<String, Long> getAlertsByLevel();
        double getAlertResolutionRate();
    }
    
    private class AlertingStatisticsImpl implements AlertingStatistics {
        @Override
        public long getTotalAlertsTriggered() {
            return totalAlertsTriggered.get();
        }
        
        @Override
        public long getTotalAlertsResolved() {
            return totalAlertsResolved.get();
        }
        
        @Override
        public long getSuppressedAlerts() {
            return suppressedAlerts.get();
        }
        
        @Override
        public Instant getLastAlertTime() {
            return lastAlertTime.get();
        }
        
        @Override
        public Duration getUptime() {
            return Duration.of</tool_call>(30); // 示例值
        }
        
        @Override
        public Map<String, Long> getAlertsByType() {
            Map<String, Long> alertsByType = new HashMap<>();
            for (AlertType type : AlertType.values()) {
                alertsByType.put(type.name().toLowerCase(), 0L);
            }
            return alertsByType;
        }
        
        @Override
        public Map<String, Long> getAlertsByLevel() {
            Map<String, Long> alertsByLevel = new HashMap<>();
            for (AlertLevel level : AlertLevel.values()) {
                alertsByLevel.put(level.name().toLowerCase(), 0L);
            }
            return alertsByLevel;
        }
        
        @Override
        public double getAlertResolutionRate() {
            long triggered = totalAlertsTriggered.get();
            long resolved = totalAlertsResolved.get();
            return triggered > 0 ? (double) resolved / triggered : 0.0;
        }
    }
}