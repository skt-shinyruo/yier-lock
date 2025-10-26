package com.mycorp.distributedlock.core.observability;

import com.mycorp.distributedlock.api.PerformanceMetrics;
import com.mycorp.distributedlock.api.PerformanceMetrics.*;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JMX管理器
 * 提供分布式锁的JMX管理和监控接口
 */
public class LockJMXManager {
    
    private static final Logger logger = LoggerFactory.getLogger(LockJMXManager.class);
    
    private final LockPerformanceMetrics performanceMetrics;
    private final MeterRegistry meterRegistry;
    private final MBeanServer mbeanServer;
    private final JMXConfig config;
    
    // JMX状态管理
    private final AtomicBoolean isEnabled = new AtomicBoolean(false);
    private final LockManagementMBean mbean;
    private final AtomicReference<Instant> lastUpdateTime = new AtomicReference<>(Instant.now());
    
    // JMX统计
    private final AtomicLong totalJmxOperations = new AtomicLong(0);
    private final AtomicLong jmxErrors = new AtomicLong(0);
    private final AtomicReference<Instant> jmxStartTime = new AtomicReference<>(Instant.now());
    
    public LockJMXManager(LockPerformanceMetrics performanceMetrics, MeterRegistry meterRegistry) {
        this(performanceMetrics, meterRegistry, new JMXConfig());
    }
    
    public LockJMXManager(LockPerformanceMetrics performanceMetrics,
                        MeterRegistry meterRegistry, JMXConfig config) {
        this.performanceMetrics = performanceMetrics;
        this.meterRegistry = meterRegistry;
        this.config = config != null ? config : new JMXConfig();
        this.mbeanServer = ManagementFactory.getPlatformMBeanServer();
        this.mbean = new LockManagementMBean();
        
        logger.info("LockJMXManager initialized with config: {}", config);
    }
    
    /**
     * 启动JMX管理服务
     */
    public void start() throws MalformedObjectNameException, InstanceAlreadyExistsException, 
                               MBeanRegistrationException, NotCompliantMBeanException {
        if (isEnabled.compareAndSet(false, true)) {
            // 注册MBean
            ObjectName objectName = new ObjectName(config.getMBeanDomainName() + ":type=LockManagement");
            mbeanServer.registerMBean(mbean, objectName);
            
            jmxStartTime.set(Instant.now());
            logger.info("JMX Lock Management service started with MBean: {}", objectName);
        } else {
            logger.warn("JMX Lock Management service is already running");
        }
    }
    
    /**
     * 停止JMX管理服务
     */
    public void stop() {
        if (isEnabled.compareAndSet(true, false)) {
            try {
                // 注销MBean
                ObjectName objectName = new ObjectName(config.getMBeanDomainName() + ":type=LockManagement");
                if (mbeanServer.isRegistered(objectName)) {
                    mbeanServer.unregisterMBean(objectName);
                }
                
                logger.info("JMX Lock Management service stopped");
            } catch (Exception e) {
                logger.error("Error stopping JMX service", e);
            }
        }
    }
    
    /**
     * 获取当前系统指标
     */
    public LockSystemMetrics getCurrentSystemMetrics() {
        SystemPerformanceMetrics metrics = performanceMetrics.getSystemMetrics();
        return new LockSystemMetricsImpl(metrics);
    }
    
    /**
     * 获取锁级指标
     */
    public List<LockInfo> getLockMetrics() {
        // 这里需要从实际的锁注册表获取锁列表
        // 现在返回示例数据
        List<LockInfo> lockInfoList = new ArrayList<>();
        
        // 示例锁信息
        LockInfo lockInfo = new LockInfoImpl("example-lock", "ACTIVE", 1, Duration.ofMillis(100));
        lockInfoList.add(lockInfo);
        
        return lockInfoList;
    }
    
    /**
     * 重置指标
     */
    public void resetMetrics() {
        performanceMetrics.resetMetrics();
        jmxErrors.set(0);
        totalJmxOperations.set(0);
        lastUpdateTime.set(Instant.now());
        logger.info("JMX metrics reset");
    }
    
    /**
     * 手动触发告警
     */
    public void triggerTestAlert(String lockName, String alertType, String message) {
        try {
            // 这里可以触发测试告警
            logger.info("JMX Test Alert triggered: {} - {} - {}", lockName, alertType, message);
            totalJmxOperations.incrementAndGet();
        } catch (Exception e) {
            jmxErrors.incrementAndGet();
            logger.error("Error triggering test alert", e);
        }
    }
    
    /**
     * 获取JMX统计信息
     */
    public JMXStatistics getStatistics() {
        return new JMXStatisticsImpl();
    }
    
    /**
     * 执行维护操作
     */
    public void performMaintenanceOperation(String operation) {
        try {
            logger.info("Performing maintenance operation: {}", operation);
            
            switch (operation.toLowerCase()) {
                case "cleanup_expired_locks":
                    cleanupExpiredLocks();
                    break;
                case "reset_counters":
                    performanceMetrics.resetMetrics();
                    break;
                case "force_garbage_collection":
                    System.gc();
                    break;
                default:
                    logger.warn("Unknown maintenance operation: {}", operation);
                    return;
            }
            
            totalJmxOperations.incrementAndGet();
            lastUpdateTime.set(Instant.now());
            
        } catch (Exception e) {
            jmxErrors.incrementAndGet();
            logger.error("Error performing maintenance operation: {}", operation, e);
        }
    }
    
    /**
     * 获取性能报告
     */
    public String generatePerformanceReport() {
        try {
            SystemPerformanceMetrics systemMetrics = performanceMetrics.getSystemMetrics();
            
            StringBuilder report = new StringBuilder();
            report.append("=== 分布式锁性能报告 ===\n")
                  .append("生成时间: ").append(Instant.now()).append("\n\n")
                  .append("系统指标:\n")
                  .append("- 总操作数: ").append(systemMetrics.getTotalOperations()).append("\n")
                  .append("- 成功操作数: ").append(systemMetrics.getSuccessfulOperations()).append("\n")
                  .append("- 失败操作数: ").append(systemMetrics.getFailedOperations()).append("\n")
                  .append("- 当前并发数: ").append(systemMetrics.getCurrentConcurrency()).append("\n")
                  .append("- 峰值并发数: ").append(systemMetrics.getPeakConcurrency()).append("\n")
                  .append("- 错误率: ").append(String.format("%.2f%%", systemMetrics.getErrorRate() * 100)).append("\n")
                  .append("- 吞吐量: ").append(String.format("%.2f ops/sec", systemMetrics.getThroughput())).append("\n\n");
            
            // 添加告警统计
            report.append("JMX统计:\n")
                  .append("- JMX操作总数: ").append(totalJmxOperations.get()).append("\n")
                  .append("- JMX错误数: ").append(jmxErrors.get()).append("\n")
                  .append("- 运行时长: ").append(getRuntimeDuration()).append("\n\n");
            
            return report.toString();
            
        } catch (Exception e) {
            jmxErrors.incrementAndGet();
            logger.error("Error generating performance report", e);
            return "Error generating report: " + e.getMessage();
        }
    }
    
    // 私有方法
    
    private void cleanupExpiredLocks() {
        // 这里实现过期锁清理逻辑
        // 简化实现
        logger.info("Cleaning up expired locks");
    }
    
    private Duration getRuntimeDuration() {
        return Duration.between(jmxStartTime.get(), Instant.now());
    }
    
    // JMX MBean接口
    
    /**
     * 锁管理MBean
     */
    public interface LockManagementMBean {
        String getSystemStatus();
        long getTotalOperations();
        long getActiveLocks();
        double getCurrentErrorRate();
        void resetMetrics();
        String generateReport();
        void performMaintenance(String operation);
        void triggerAlert(String lockName, String alertType, String message);
    }
    
    /**
     * 锁管理MBean实现
     */
    private class LockManagementMBean implements LockManagementMBean {
        @Override
        public String getSystemStatus() {
            try {
                SystemPerformanceMetrics metrics = performanceMetrics.getSystemMetrics();
                if (metrics.getErrorRate() > 0.1) {
                    return "WARNING";
                } else if (metrics.getCurrentConcurrency() > 1000) {
                    return "HIGH_LOAD";
                } else {
                    return "HEALTHY";
                }
            } catch (Exception e) {
                logger.error("Error getting system status", e);
                return "ERROR";
            }
        }
        
        @Override
        public long getTotalOperations() {
            try {
                SystemPerformanceMetrics metrics = performanceMetrics.getSystemMetrics();
                return metrics.getTotalOperations();
            } catch (Exception e) {
                logger.error("Error getting total operations", e);
                return -1;
            }
        }
        
        @Override
        public long getActiveLocks() {
            try {
                SystemPerformanceMetrics metrics = performanceMetrics.getSystemMetrics();
                return metrics.getActiveLockCount();
            } catch (Exception e) {
                logger.error("Error getting active locks", e);
                return -1;
            }
        }
        
        @Override
        public double getCurrentErrorRate() {
            try {
                SystemPerformanceMetrics metrics = performanceMetrics.getSystemMetrics();
                return metrics.getErrorRate();
            } catch (Exception e) {
                logger.error("Error getting error rate", e);
                return -1;
            }
        }
        
        @Override
        public void resetMetrics() {
            try {
                LockJMXManager.this.resetMetrics();
            } catch (Exception e) {
                logger.error("Error resetting metrics via JMX", e);
            }
        }
        
        @Override
        public String generateReport() {
            try {
                totalJmxOperations.incrementAndGet();
                return LockJMXManager.this.generatePerformanceReport();
            } catch (Exception e) {
                jmxErrors.incrementAndGet();
                logger.error("Error generating report via JMX", e);
                return "Error generating report: " + e.getMessage();
            }
        }
        
        @Override
        public void performMaintenance(String operation) {
            try {
                totalJmxOperations.incrementAndGet();
                LockJMXManager.this.performMaintenanceOperation(operation);
            } catch (Exception e) {
                jmxErrors.incrementAndGet();
                logger.error("Error performing maintenance via JMX", e);
            }
        }
        
        @Override
        public void triggerAlert(String lockName, String alertType, String message) {
            try {
                totalJmxOperations.incrementAndGet();
                LockJMXManager.this.triggerTestAlert(lockName, alertType, message);
            } catch (Exception e) {
                jmxErrors.incrementAndGet();
                logger.error("Error triggering alert via JMX", e);
            }
        }
    }
    
    /**
     * JMX配置
     */
    public static class JMXConfig {
        private String mBeanDomainName = "com.mycorp.distributedlock";
        private boolean autoRegister = true;
        private boolean enableRemoteAccess = true;
        private int rmiRegistryPort = 9999;
        private int rmiServerPort = 10000;
        private Duration updateInterval = Duration.ofSeconds(30);
        private boolean enableDetailedMetrics = true;
        
        // Getters and setters
        public String getMBeanDomainName() { return mBeanDomainName; }
        public void setMBeanDomainName(String mBeanDomainName) { 
            this.mBeanDomainName = mBeanDomainName; 
        }
        
        public boolean isAutoRegister() { return autoRegister; }
        public void setAutoRegister(boolean autoRegister) { 
            this.autoRegister = autoRegister; 
        }
        
        public boolean isEnableRemoteAccess() { return enableRemoteAccess; }
        public void setEnableRemoteAccess(boolean enableRemoteAccess) { 
            this.enableRemoteAccess = enableRemoteAccess; 
        }
        
        public int getRmiRegistryPort() { return rmiRegistryPort; }
        public void setRmiRegistryPort(int rmiRegistryPort) { 
            this.rmiRegistryPort = rmiRegistryPort; 
        }
        
        public int getRmiServerPort() { return rmiServerPort; }
        public void setRmiServerPort(int rmiServerPort) { 
            this.rmiServerPort = rmiServerPort; 
        }
        
        public Duration getUpdateInterval() { return updateInterval; }
        public void setUpdateInterval(Duration updateInterval) { 
            this.updateInterval = updateInterval; 
        }
        
        public boolean isEnableDetailedMetrics() { return enableDetailedMetrics; }
        public void setEnableDetailedMetrics(boolean enableDetailedMetrics) { 
            this.enableDetailedMetrics = enableDetailedMetrics; 
        }
    }
    
    /**
     * 锁系统指标
     */
    public interface LockSystemMetrics {
        long getTotalOperations();
        long getSuccessfulOperations();
        long getFailedOperations();
        int getCurrentConcurrency();
        int getPeakConcurrency();
        double getAverageResponseTime();
        double getErrorRate();
        double getThroughput();
        int getActiveLockCount();
        long getLastUpdateTime();
    }
    
    /**
     * 锁信息
     */
    public interface LockInfo {
        String getLockName();
        String getStatus();
        int getActiveHolders();
        Duration getAverageHoldTime();
        double getContentionLevel();
        long getAcquisitionCount();
        long getFailureCount();
    }
    
    /**
     * JMX统计信息
     */
    public interface JMXStatistics {
        long getTotalJmxOperations();
        long getJmxErrors();
        Duration getRuntime();
        Instant getStartTime();
        Instant getLastUpdateTime();
        boolean isEnabled();
        String getJmxVersion();
    }
    
    // 实现类
    
    private class LockSystemMetricsImpl implements LockSystemMetrics {
        private final SystemPerformanceMetrics metrics;
        
        public LockSystemMetricsImpl(SystemPerformanceMetrics metrics) {
            this.metrics = metrics;
        }
        
        @Override
        public long getTotalOperations() {
            return metrics.getTotalOperations();
        }
        
        @Override
        public long getSuccessfulOperations() {
            return metrics.getSuccessfulOperations();
        }
        
        @Override
        public long getFailedOperations() {
            return metrics.getFailedOperations();
        }
        
        @Override
        public int getCurrentConcurrency() {
            return metrics.getCurrentConcurrency();
        }
        
        @Override
        public int getPeakConcurrency() {
            return metrics.getPeakConcurrency();
        }
        
        @Override
        public double getAverageResponseTime() {
            return metrics.getAverageResponseTime().toMillis();
        }
        
        @Override
        public double getErrorRate() {
            return metrics.getErrorRate();
        }
        
        @Override
        public double getThroughput() {
            return metrics.getThroughput();
        }
        
        @Override
        public int getActiveLockCount() {
            return metrics.getActiveLockCount();
        }
        
        @Override
        public long getLastUpdateTime() {
            return metrics.getUpdateTime();
        }
    }
    
    private class LockInfoImpl implements LockInfo {
        private final String lockName;
        private final String status;
        private final int activeHolders;
        private final Duration averageHoldTime;
        
        public LockInfoImpl(String lockName, String status, int activeHolders, Duration averageHoldTime) {
            this.lockName = lockName;
            this.status = status;
            this.activeHolders = activeHolders;
            this.averageHoldTime = averageHoldTime;
        }
        
        @Override
        public String getLockName() { return lockName; }
        
        @Override
        public String getStatus() { return status; }
        
        @Override
        public int getActiveHolders() { return activeHolders; }
        
        @Override
        public Duration getAverageHoldTime() { return averageHoldTime; }
        
        @Override
        public double getContentionLevel() {
            // 这里需要从实际锁指标获取
            return Math.random() * 0.1; // 示例值
        }
        
        @Override
        public long getAcquisitionCount() {
            // 这里需要从实际锁指标获取
            return (long) (Math.random() * 1000); // 示例值
        }
        
        @Override
        public long getFailureCount() {
            // 这里需要从实际锁指标获取
            return (long) (Math.random() * 50); // 示例值
        }
    }
    
    private class JMXStatisticsImpl implements JMXStatistics {
        @Override
        public long getTotalJmxOperations() {
            return totalJmxOperations.get();
        }
        
        @Override
        public long getJmxErrors() {
            return jmxErrors.get();
        }
        
        @Override
        public Duration getRuntime() {
            return Duration.between(jmxStartTime.get(), Instant.now());
        }
        
        @Override
        public Instant getStartTime() {
            return jmxStartTime.get();
        }
        
        @Override
        public Instant getLastUpdateTime() {
            return lastUpdateTime.get();
        }
        
        @Override
        public boolean isEnabled() {
            return isEnabled.get();
        }
        
        @Override
        public String getJmxVersion() {
            return "1.0.0"; // JMX管理器版本
        }
    }
}