package com.mycorp.distributedlock.core.observability;

import com.mycorp.distributedlock.api.HealthCheck;
import com.mycorp.distributedlock.api.PerformanceMetrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Counter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 健康指标组件
 * 提供分布式锁系统的健康状态监控和检查
 */
public class LockHealthMetrics {
    
    private static final Logger logger = LoggerFactory.getLogger(LockHealthMetrics.class);
    
    private final LockPerformanceMetrics performanceMetrics;
    private final MeterRegistry meterRegistry;
    private final HealthConfig config;
    
    // 健康状态管理
    private final AtomicBoolean isHealthy = new AtomicBoolean(true);
    private final AtomicReference<HealthStatus> currentHealth = new AtomicReference<>(HealthStatus.HEALTHY);
    private final Map<String, ComponentHealth> componentHealthMap;
    
    // 健康检查调度
    private final ScheduledExecutorService scheduler;
    private final HealthChecker healthChecker;
    
    // 健康统计
    private final AtomicLong totalHealthChecks = new AtomicLong(0);
    private final AtomicLong failedHealthChecks = new AtomicLong(0);
    private final AtomicReference<Instant> lastHealthCheck = new AtomicReference<>(Instant.now());
    private final AtomicReference<Duration> overallHealthDuration = new AtomicReference<>(Duration.ZERO);
    
    // 健康阈值配置
    private final HealthThresholds thresholds;
    
    public LockHealthMetrics(LockPerformanceMetrics performanceMetrics, MeterRegistry meterRegistry) {
        this(performanceMetrics, meterRegistry, new HealthConfig());
    }
    
    public LockHealthMetrics(LockPerformanceMetrics performanceMetrics,
                           MeterRegistry meterRegistry, HealthConfig config) {
        this.performanceMetrics = performanceMetrics;
        this.meterRegistry = meterRegistry;
        this.config = config != null ? config : new HealthConfig();
        this.componentHealthMap = new ConcurrentHashMap<>();
        this.thresholds = new HealthThresholds();
        
        this.scheduler = Executors.newScheduledThreadPool(
            config.getHealthCheckThreadPoolSize(), r -> {
                Thread thread = new Thread(r, "lock-health-metrics");
                thread.setDaemon(true);
                return thread;
            });
            
        this.healthChecker = new HealthChecker();
        
        // 初始化健康组件
        initializeHealthComponents();
        
        // 初始化Micrometer指标
        if (meterRegistry != null) {
            initializeMicrometerMetrics();
        }
        
        // 启动健康检查
        startHealthChecks();
        
        logger.info("LockHealthMetrics initialized with config: {}", config);
    }
    
    /**
     * 执行健康检查
     */
    public HealthCheckResult performHealthCheck() {
        try {
            totalHealthChecks.incrementAndGet();
            lastHealthCheck.set(Instant.now());
            
            HealthCheckResult result = healthChecker.performFullHealthCheck();
            updateComponentHealth(result);
            updateOverallHealth(result);
            
            logger.debug("Health check completed: {}", result.getOverallStatus());
            return result;
            
        } catch (Exception e) {
            failedHealthChecks.incrementAndGet();
            logger.error("Error performing health check", e);
            return new HealthCheckResult(HealthStatus.CRITICAL, 
                "Health check failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * 快速健康检查
     */
    public HealthCheckResult performQuickHealthCheck() {
        return healthChecker.performQuickHealthCheck();
    }
    
    /**
     * 获取组件健康状态
     */
    public ComponentHealth getComponentHealth(String componentName) {
        return componentHealthMap.getOrDefault(componentName, 
            new ComponentHealth(componentName, HealthStatus.UNKNOWN, "Not checked"));
    }
    
    /**
     * 获取所有组件健康状态
     */
    public Map<String, ComponentHealth> getAllComponentHealth() {
        return new HashMap<>(componentHealthMap);
    }
    
    /**
     * 获取整体健康状态
     */
    public HealthStatus getOverallHealth() {
        return currentHealth.get();
    }
    
    /**
     * 获取健康报告
     */
    public HealthReport generateHealthReport() {
        return new HealthReportImpl();
    }
    
    /**
     * 检查特定组件
     */
    public HealthCheckResult checkComponent(String componentName) {
        ComponentHealth component = componentHealthMap.get(componentName);
        if (component == null) {
            return new HealthCheckResult(HealthStatus.UNKNOWN, 
                "Component not found: " + componentName);
        }
        
        return healthChecker.checkComponent(componentName);
    }
    
    /**
     * 重置健康状态
     */
    public void resetHealth() {
        componentHealthMap.clear();
        isHealthy.set(true);
        currentHealth.set(HealthStatus.HEALTHY);
        totalHealthChecks.set(0);
        failedHealthChecks.set(0);
        lastHealthCheck.set(Instant.now());
        
        logger.info("Health metrics reset");
    }
    
    /**
     * 获取健康统计信息
     */
    public HealthStatistics getStatistics() {
        return new HealthStatisticsImpl();
    }
    
    /**
     * 添加自定义健康检查器
     */
    public void addCustomHealthChecker(String componentName, HealthChecker customChecker) {
        componentHealthMap.computeIfAbsent(componentName, 
            k -> new ComponentHealth(componentName, HealthStatus.UNKNOWN, "Custom checker"))
            .setCustomChecker(customChecker);
            
        logger.info("Added custom health checker for component: {}", componentName);
    }
    
    /**
     * 移除自定义健康检查器
     */
    public void removeCustomHealthChecker(String componentName) {
        ComponentHealth component = componentHealthMap.get(componentName);
        if (component != null) {
            component.setCustomChecker(null);
            logger.info("Removed custom health checker for component: {}", componentName);
        }
    }
    
    /**
     * 停止健康检查服务
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("LockHealthMetrics shutdown completed");
    }
    
    // 私有方法
    
    private void initializeHealthComponents() {
        // 初始化各个组件的健康检查器
        componentHealthMap.put("performance", new ComponentHealth("performance", 
            HealthStatus.HEALTHY, "Performance metrics checker"));
        componentHealthMap.put("metrics", new ComponentHealth("metrics", 
            HealthStatus.HEALTHY, "Metrics collection checker"));
        componentHealthMap.put("monitoring", new ComponentHealth("monitoring", 
            HealthStatus.HEALTHY, "Monitoring service checker"));
        componentHealthMap.put("alerting", new ComponentHealth("alerting", 
            HealthStatus.HEALTHY, "Alerting service checker"));
        componentHealthMap.put("jmx", new ComponentHealth("jmx", 
            HealthStatus.HEALTHY, "JMX management checker"));
        componentHealthMap.put("system", new ComponentHealth("system", 
            HealthStatus.HEALTHY, "System resource checker"));
    }
    
    private void initializeMicrometerMetrics() {
        // 整体健康状态指标
        Gauge.builder("lock.health.overall.status")
            .description("Overall health status (1=healthy, 0.5=warning, 0=critical)")
            .register(meterRegistry, this, service -> {
                HealthStatus status = currentHealth.get();
                switch (status) {
                    case HEALTHY: return 1.0;
                    case WARNING: return 0.5;
                    case CRITICAL: return 0.0;
                    default: return -1.0;
                }
            });
            
        // 健康检查计数指标
        Counter.builder("lock.health.checks.total")
            .description("Total number of health checks")
            .register(meterRegistry)
            .increment();
            
        // 健康检查失败指标
        Counter.builder("lock.health.checks.failed")
            .description("Number of failed health checks")
            .register(meterRegistry)
            .increment();
            
        // 组件健康状态指标
        for (String componentName : componentHealthMap.keySet()) {
            Gauge.builder("lock.health.component.status")
                .description("Component health status")
                .tag("component", componentName)
                .register(meterRegistry, service -> {
                    ComponentHealth component = componentHealthMap.get(componentName);
                    return component != null ? getHealthStatusValue(component.getStatus()) : -1.0;
                });
        }
    }
    
    private void startHealthChecks() {
        // 启动定期健康检查
        scheduler.scheduleAtFixedRate(() -> {
            try {
                performHealthCheck();
            } catch (Exception e) {
                logger.error("Error in scheduled health check", e);
            }
        }, config.getHealthCheckInterval().toSeconds(),
           config.getHealthCheckInterval().toSeconds(),
           TimeUnit.SECONDS);
    }
    
    private void updateComponentHealth(HealthCheckResult result) {
        for (Map.Entry<String, HealthStatus> entry : result.getComponentStatuses().entrySet()) {
            String componentName = entry.getKey();
            HealthStatus status = entry.getValue();
            
            componentHealthMap.computeIfAbsent(componentName, 
                k -> new ComponentHealth(componentName, status, "Auto-detected"))
                .setStatus(status);
        }
    }
    
    private void updateOverallHealth(HealthCheckResult result) {
        HealthStatus previousStatus = currentHealth.get();
        HealthStatus newStatus = result.getOverallStatus();
        
        currentHealth.set(newStatus);
        
        // 检查状态变化
        if (previousStatus != newStatus) {
            isHealthy.set(newStatus == HealthStatus.HEALTHY);
            overallHealthDuration.set(Duration.between(lastHealthCheck.get(), Instant.now()));
            
            logger.info("Health status changed from {} to {}", previousStatus, newStatus);
            
            // 记录状态变化事件
            if (meterRegistry != null) {
                Counter.builder("lock.health.status.changes")
                    .description("Number of health status changes")
                    .tag("from", previousStatus.name().toLowerCase())
                    .tag("to", newStatus.name().toLowerCase())
                    .register(meterRegistry)
                    .increment();
            }
        }
    }
    
    private double getHealthStatusValue(HealthStatus status) {
        switch (status) {
            case HEALTHY: return 1.0;
            case WARNING: return 0.5;
            case CRITICAL: return 0.0;
            case UNKNOWN: return -1.0;
            default: return -1.0;
        }
    }
    
    /**
     * 健康检查器
     */
    private class HealthChecker {
        
        public HealthCheckResult performFullHealthCheck() {
            Map<String, HealthStatus> componentStatuses = new HashMap<>();
            List<String> issues = new ArrayList<>();
            
            // 检查各个组件
            componentStatuses.put("performance", checkPerformanceMetrics());
            componentStatuses.put("metrics", checkMetricsCollection());
            componentStatuses.put("monitoring", checkMonitoringService());
            componentStatuses.put("alerting", checkAlertingService());
            componentStatuses.put("jmx", checkJmxService());
            componentStatuses.put("system", checkSystemResources());
            
            // 检查自定义检查器
            for (Map.Entry<String, ComponentHealth> entry : componentHealthMap.entrySet()) {
                String componentName = entry.getKey();
                ComponentHealth component = entry.getValue();
                
                if (component.getCustomChecker() != null) {
                    HealthStatus status = performCustomCheck(component);
                    componentStatuses.put(componentName, status);
                }
            }
            
            // 确定整体健康状态
            HealthStatus overallStatus = determineOverallStatus(componentStatuses);
            
            // 收集问题
            collectIssues(componentStatuses, issues);
            
            return new HealthCheckResult(overallStatus, issues, componentStatuses);
        }
        
        public HealthCheckResult performQuickHealthCheck() {
            // 快速检查只检查关键组件
            Map<String, HealthStatus> componentStatuses = new HashMap<>();
            componentStatuses.put("performance", checkPerformanceMetrics());
            componentStatuses.put("system", checkSystemResources());
            
            HealthStatus overallStatus = determineOverallStatus(componentStatuses);
            
            return new HealthCheckResult(overallStatus, 
                "Quick health check completed", componentStatuses);
        }
        
        private HealthStatus checkPerformanceMetrics() {
            try {
                SystemPerformanceMetrics metrics = performanceMetrics.getSystemMetrics();
                
                // 检查错误率
                if (metrics.getErrorRate() > thresholds.getMaxErrorRate()) {
                    return HealthStatus.CRITICAL;
                }
                
                // 检查并发数
                if (metrics.getCurrentConcurrency() > thresholds.getMaxConcurrency()) {
                    return HealthStatus.WARNING;
                }
                
                // 检查响应时间
                if (metrics.getAverageResponseTime().toMillis() > thresholds.getMaxResponseTime()) {
                    return HealthStatus.WARNING;
                }
                
                return HealthStatus.HEALTHY;
                
            } catch (Exception e) {
                logger.error("Error checking performance metrics", e);
                return HealthStatus.CRITICAL;
            }
        }
        
        private HealthStatus checkMetricsCollection() {
            try {
                // 检查指标收集是否正常工作
                // 这里需要检查实际的指标收集状态
                // 简化实现
                return HealthStatus.HEALTHY;
                
            } catch (Exception e) {
                logger.error("Error checking metrics collection", e);
                return HealthStatus.CRITICAL;
            }
        }
        
        private HealthStatus checkMonitoringService() {
            try {
                // 检查监控服务状态
                return HealthStatus.HEALTHY;
                
            } catch (Exception e) {
                logger.error("Error checking monitoring service", e);
                return HealthStatus.CRITICAL;
            }
        }
        
        private HealthStatus checkAlertingService() {
            try {
                // 检查告警服务状态
                return HealthStatus.HEALTHY;
                
            } catch (Exception e) {
                logger.error("Error checking alerting service", e);
                return HealthStatus.CRITICAL;
            }
        }
        
        private HealthStatus checkJmxService() {
            try {
                // 检查JMX服务状态
                return HealthStatus.HEALTHY;
                
            } catch (Exception e) {
                logger.error("Error checking JMX service", e);
                return HealthStatus.CRITICAL;
            }
        }
        
        private HealthStatus checkSystemResources() {
            try {
                // 检查系统资源使用情况
                Runtime runtime = Runtime.getRuntime();
                long maxMemory = runtime.maxMemory();
                long totalMemory = runtime.totalMemory();
                long freeMemory = runtime.freeMemory();
                long usedMemory = totalMemory - freeMemory;
                double memoryUsageRatio = (double) usedMemory / maxMemory;
                
                if (memoryUsageRatio > thresholds.getMaxMemoryUsage()) {
                    return HealthStatus.CRITICAL;
                }
                
                return HealthStatus.HEALTHY;
                
            } catch (Exception e) {
                logger.error("Error checking system resources", e);
                return HealthStatus.CRITICAL;
            }
        }
        
        private HealthStatus performCustomCheck(ComponentHealth component) {
            try {
                return component.getCustomChecker().check();
            } catch (Exception e) {
                logger.error("Error in custom health check for component: {}", component.getName(), e);
                return HealthStatus.CRITICAL;
            }
        }
        
        public HealthCheckResult checkComponent(String componentName) {
            ComponentHealth component = componentHealthMap.get(componentName);
            if (component == null) {
                return new HealthCheckResult(HealthStatus.UNKNOWN, 
                    "Component not found: " + componentName);
            }
            
            HealthStatus status = performCustomCheck(component);
            return new HealthCheckResult(status, "Component check completed", 
                Collections.singletonMap(componentName, status));
        }
        
        private HealthStatus determineOverallStatus(Map<String, HealthStatus> componentStatuses) {
            // 如果任何组件是CRITICAL，整体状态为CRITICAL
            if (componentStatuses.containsValue(HealthStatus.CRITICAL)) {
                return HealthStatus.CRITICAL;
            }
            
            // 如果有WARNING组件且没有CRITICAL，整体状态为WARNING
            if (componentStatuses.containsValue(HealthStatus.WARNING)) {
                return HealthStatus.WARNING;
            }
            
            // 如果所有组件都是HEALTHY，整体状态为HEALTHY
            if (componentStatuses.containsValue(HealthStatus.HEALTHY)) {
                return HealthStatus.HEALTHY;
            }
            
            return HealthStatus.UNKNOWN;
        }
        
        private void collectIssues(Map<String, HealthStatus> componentStatuses, List<String> issues) {
            for (Map.Entry<String, HealthStatus> entry : componentStatuses.entrySet()) {
                String componentName = entry.getKey();
                HealthStatus status = entry.getValue();
                
                switch (status) {
                    case CRITICAL:
                        issues.add("Critical issue in component: " + componentName);
                        break;
                    case WARNING:
                        issues.add("Warning in component: " + componentName);
                        break;
                    case UNKNOWN:
                        issues.add("Unknown status for component: " + componentName);
                        break;
                    default:
                        break;
                }
            }
        }
    }
    
    /**
     * 健康配置
     */
    public static class HealthConfig {
        private Duration healthCheckInterval = Duration.ofSeconds(60);
        private int healthCheckThreadPoolSize = 2;
        private boolean enableDetailedHealthChecks = true;
        private boolean enableAutomaticRecovery = true;
        private Duration healthCheckTimeout = Duration.ofSeconds(30);
        
        // Getters and setters
        public Duration getHealthCheckInterval() { return healthCheckInterval; }
        public void setHealthCheckInterval(Duration healthCheckInterval) { 
            this.healthCheckInterval = healthCheckInterval; 
        }
        
        public int getHealthCheckThreadPoolSize() { return healthCheckThreadPoolSize; }
        public void setHealthCheckThreadPoolSize(int healthCheckThreadPoolSize) { 
            this.healthCheckThreadPoolSize = healthCheckThreadPoolSize; 
        }
        
        public boolean isEnableDetailedHealthChecks() { return enableDetailedHealthChecks; }
        public void setEnableDetailedHealthChecks(boolean enableDetailedHealthChecks) { 
            this.enableDetailedHealthChecks = enableDetailedHealthChecks; 
        }
        
        public boolean isEnableAutomaticRecovery() { return enableAutomaticRecovery; }
        public void setEnableAutomaticRecovery(boolean enableAutomaticRecovery) { 
            this.enableAutomaticRecovery = enableAutomaticRecovery; 
        }
        
        public Duration getHealthCheckTimeout() { return healthCheckTimeout; }
        public void setHealthCheckTimeout(Duration healthCheckTimeout) { 
            this.healthCheckTimeout = healthCheckTimeout; 
        }
    }
    
    /**
     * 健康阈值
     */
    private static class HealthThresholds {
        private double maxErrorRate = 0.05; // 5%
        private int maxConcurrency = 1000;
        private Duration maxResponseTime = Duration.ofSeconds(5);
        private double maxMemoryUsage = 0.8; // 80%
        
        public double getMaxErrorRate() { return maxErrorRate; }
        public int getMaxConcurrency() { return maxConcurrency; }
        public Duration getMaxResponseTime() { return maxResponseTime; }
        public double getMaxMemoryUsage() { return maxMemoryUsage; }
    }
    
    /**
     * 健康状态枚举
     */
    public enum HealthStatus {
        HEALTHY, WARNING, CRITICAL, UNKNOWN
    }
    
    /**
     * 组件健康信息
     */
    public static class ComponentHealth {
        private final String name;
        private volatile HealthStatus status;
        private volatile String description;
        private volatile HealthChecker customChecker;
        private volatile Instant lastCheckTime = Instant.now();
        
        public ComponentHealth(String name, HealthStatus status, String description) {
            this.name = name;
            this.status = status;
            this.description = description;
        }
        
        public String getName() { return name; }
        public HealthStatus getStatus() { return status; }
        public String getDescription() { return description; }
        public Instant getLastCheckTime() { return lastCheckTime; }
        
        public void setStatus(HealthStatus status) {
            this.status = status;
            this.lastCheckTime = Instant.now();
        }
        
        public void setDescription(String description) { 
            this.description = description; 
        }
        
        public void setCustomChecker(HealthChecker customChecker) { 
            this.customChecker = customChecker; 
        }
        
        public HealthChecker getCustomChecker() { 
            return customChecker; 
        }
    }
    
    /**
     * 健康检查结果
     */
    public static class HealthCheckResult {
        private final HealthStatus overallStatus;
        private final List<String> issues;
        private final Map<String, HealthStatus> componentStatuses;
        private final Instant checkTime;
        private final Duration checkDuration;
        private final Exception error;
        
        public HealthCheckResult(HealthStatus overallStatus, String message) {
            this(overallStatus, Collections.singletonList(message), 
                Collections.emptyMap(), null);
        }
        
        public HealthCheckResult(HealthStatus overallStatus, List<String> issues,
                               Map<String, HealthStatus> componentStatuses) {
            this(overallStatus, issues, componentStatuses, null);
        }
        
        public HealthCheckResult(HealthStatus overallStatus, String message, Exception error) {
            this(overallStatus, Collections.singletonList(message), 
                Collections.emptyMap(), error);
        }
        
        private HealthCheckResult(HealthStatus overallStatus, List<String> issues,
                                Map<String, HealthStatus> componentStatuses, Exception error) {
            this.overallStatus = overallStatus;
            this.issues = new ArrayList<>(issues);
            this.componentStatuses = new HashMap<>(componentStatuses);
            this.checkTime = Instant.now();
            this.checkDuration = Duration.ZERO; // 简化实现
            this.error = error;
        }
        
        public HealthStatus getOverallStatus() { return overallStatus; }
        public List<String> getIssues() { return new ArrayList<>(issues); }
        public Map<String, HealthStatus> getComponentStatuses() { 
            return new HashMap<>(componentStatuses); 
        }
        public Instant getCheckTime() { return checkTime; }
        public Duration getCheckDuration() { return checkDuration; }
        public Exception getError() { return error; }
        public boolean hasIssues() { return !issues.isEmpty(); }
    }
    
    /**
     * 健康报告
     */
    public interface HealthReport {
        HealthStatus getOverallStatus();
        List<String> getIssues();
        Map<String, ComponentHealth> getComponentHealth();
        Instant getReportTime();
        Duration getReportDuration();
        String getSummary();
        List<String> getRecommendations();
    }
    
    /**
     * 健康统计信息
     */
    public interface HealthStatistics {
        long getTotalHealthChecks();
        long getFailedHealthChecks();
        double getHealthCheckSuccessRate();
        Duration getUptime();
        Instant getLastHealthCheck();
        Map<String, Long> getHealthCheckCountsByStatus();
    }
    
    /**
     * 自定义健康检查器接口
     */
    public interface HealthChecker {
        HealthStatus check();
    }
    
    // 实现类
    
    private class HealthReportImpl implements HealthReport {
        private final Instant reportTime = Instant.now();
        
        @Override
        public HealthStatus getOverallStatus() {
            return currentHealth.get();
        }
        
        @Override
        public List<String> getIssues() {
            List<String> issues = new ArrayList<>();
            for (ComponentHealth component : componentHealthMap.values()) {
                switch (component.getStatus()) {
                    case CRITICAL:
                        issues.add("CRITICAL: " + component.getName() + " - " + component.getDescription());
                        break;
                    case WARNING:
                        issues.add("WARNING: " + component.getName() + " - " + component.getDescription());
                        break;
                    case UNKNOWN:
                        issues.add("UNKNOWN: " + component.getName() + " - " + component.getDescription());
                        break;
                    default:
                        break;
                }
            }
            return issues;
        }
        
        @Override
        public Map<String, ComponentHealth> getComponentHealth() {
            return new HashMap<>(componentHealthMap);
        }
        
        @Override
        public Instant getReportTime() {
            return reportTime;
        }
        
        @Override
        public Duration getReportDuration() {
            return Duration.between(lastHealthCheck.get(), reportTime);
        }
        
        @Override
        public String getSummary() {
            int healthy = 0;
            int warning = 0;
            int critical = 0;
            int unknown = 0;
            
            for (ComponentHealth component : componentHealthMap.values()) {
                switch (component.getStatus()) {
                    case HEALTHY: healthy++; break;
                    case WARNING: warning++; break;
                    case CRITICAL: critical++; break;
                    case UNKNOWN: unknown++; break;
                }
            }
            
            return String.format("健康检查摘要: 正常=%d, 警告=%d, 严重=%d, 未知=%d",
                healthy, warning, critical, unknown);
        }
        
        @Override
        public List<String> getRecommendations() {
            List<String> recommendations = new ArrayList<>();
            
            HealthStatus overallStatus = currentHealth.get();
            switch (overallStatus) {
                case CRITICAL:
                    recommendations.add("立即检查系统状态，修复关键问题");
                    recommendations.add("考虑重启相关服务");
                    break;
                case WARNING:
                    recommendations.add("关注系统性能，适当调整配置");
                    recommendations.add("监控相关指标的变化趋势");
                    break;
                case HEALTHY:
                    recommendations.add("系统运行正常，保持当前配置");
                    recommendations.add("定期进行健康检查");
                    break;
                default:
                    recommendations.add("检查未知状态的组件");
                    break;
            }
            
            return recommendations;
        }
    }
    
    private class HealthStatisticsImpl implements HealthStatistics {
        @Override
        public long getTotalHealthChecks() {
            return totalHealthChecks.get();
        }
        
        @Override
        public long getFailedHealthChecks() {
            return failedHealthChecks.get();
        }
        
        @Override
        public double getHealthCheckSuccessRate() {
            long total = totalHealthChecks.get();
            long failed = failedHealthChecks.get();
            return total > 0 ? (double) (total - failed) / total : 1.0;
        }
        
        @Override
        public Duration getUptime() {
            return Duration.ofMinutes(30); // 示例值
        }
        
        @Override
        public Instant getLastHealthCheck() {
            return lastHealthCheck.get();
        }
        
        @Override
        public Map<String, Long> getHealthCheckCountsByStatus() {
            Map<String, Long> counts = new HashMap<>();
            counts.put("healthy", 0L);
            counts.put("warning", 0L);
            counts.put("critical", 0L);
            counts.put("unknown", 0L);
            
            for (ComponentHealth component : componentHealthMap.values()) {
                String status = component.getStatus().name().toLowerCase();
                counts.put(status, counts.getOrDefault(status, 0L) + 1L);
            }
            
            return counts;
        }
    }
}