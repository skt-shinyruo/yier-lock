package com.mycorp.distributedlock.springboot.health;

import java.util.HashMap;
import java.util.Map;

/**
 * 分布式锁健康状态结果
 * 
 * @since Spring Boot 3.x
 */
public class DistributedLockHealthStatus {

    private boolean healthy;
    private long timestamp;
    private String providerType;
    private String errorMessage;
    private Map<String, Object> details;
    private PerformanceMetrics performanceMetrics;
    private ConnectionStatus connectionStatus;

    public DistributedLockHealthStatus() {
        this.details = new HashMap<>();
    }

    public boolean isHealthy() {
        return healthy;
    }

    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }

    public PerformanceMetrics getPerformanceMetrics() {
        return performanceMetrics;
    }

    public void setPerformanceMetrics(PerformanceMetrics performanceMetrics) {
        this.performanceMetrics = performanceMetrics;
    }

    public ConnectionStatus getConnectionStatus() {
        return connectionStatus;
    }

    public void setConnectionStatus(ConnectionStatus connectionStatus) {
        this.connectionStatus = connectionStatus;
    }

    /**
     * 性能指标
     */
    public static class PerformanceMetrics {
        private String averageWaitTime;
        private String successRate;
        private String errorRate;
        private long totalOperations;

        public String getAverageWaitTime() {
            return averageWaitTime;
        }

        public void setAverageWaitTime(String averageWaitTime) {
            this.averageWaitTime = averageWaitTime;
        }

        public String getSuccessRate() {
            return successRate;
        }

        public void setSuccessRate(String successRate) {
            this.successRate = successRate;
        }

        public String getErrorRate() {
            return errorRate;
        }

        public void setErrorRate(String errorRate) {
            this.errorRate = errorRate;
        }

        public long getTotalOperations() {
            return totalOperations;
        }

        public void setTotalOperations(long totalOperations) {
            this.totalOperations = totalOperations;
        }
    }

    /**
     * 连接状态
     */
    public static class ConnectionStatus {
        private boolean connected;
        private String provider;
        private long lastCheckTimestamp;

        public boolean isConnected() {
            return connected;
        }

        public void setConnected(boolean connected) {
            this.connected = connected;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public long getLastCheckTimestamp() {
            return lastCheckTimestamp;
        }

        public void setLastCheckTimestamp(long lastCheckTimestamp) {
            this.lastCheckTimestamp = lastCheckTimestamp;
        }
    }
}