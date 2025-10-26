package com.mycorp.distributedlock.springboot.actuator;

import com.mycorp.distributedlock.springboot.health.DistributedLockHealthChecker;
import com.mycorp.distributedlock.springboot.health.DistributedLockHealthStatus;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.StatusAggregator;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分布式锁Actuator健康检查指示器
 * 提供 /actuator/health/distributed-lock 端点
 * 
 * @since Spring Boot 3.x
 */
@Component("distributedLock")
public class DistributedLockHealthIndicator implements HealthIndicator {

    private final DistributedLockHealthChecker healthChecker;
    private final StatusAggregator statusAggregator;
    
    // 缓存健康检查结果，避免频繁检查
    private final Map<String, DistributedLockHealthStatus> cachedHealthStatus = new ConcurrentHashMap<>();
    private long lastCheckTime = 0;
    private static final long CACHE_DURATION_MS = 30_000; // 30秒缓存

    public DistributedLockHealthIndicator(DistributedLockHealthChecker healthChecker, StatusAggregator statusAggregator) {
        this.healthChecker = healthChecker;
        this.statusAggregator = statusAggregator;
    }

    @Override
    public Health health() {
        try {
            // 检查缓存
            DistributedLockHealthStatus status = getCachedHealthStatus();
            
            // 构建健康状态
            Health.Builder builder = new Health.Builder();
            
            if (status.isHealthy()) {
                builder.up();
            } else {
                builder.down();
            }
            
            // 添加详细健康信息
            builder.withDetail("provider", status.getProviderType());
            builder.withDetail("timestamp", status.getTimestamp());
            builder.withDetail("status", status.isHealthy() ? "UP" : "DOWN");
            
            // 添加性能指标
            if (status.getPerformanceMetrics() != null) {
                DistributedLockHealthStatus.PerformanceMetrics metrics = status.getPerformanceMetrics();
                builder.withDetail("performance", metrics);
            }
            
            // 添加连接状态
            if (status.getConnectionStatus() != null) {
                DistributedLockHealthStatus.ConnectionStatus connection = status.getConnectionStatus();
                builder.withDetail("connection", connection);
            }
            
            // 添加错误信息（如果有）
            if (!status.isHealthy() && status.getErrorMessage() != null) {
                builder.withDetail("error", status.getErrorMessage());
            }
            
            // 添加详细信息
            if (status.getDetails() != null && !status.getDetails().isEmpty()) {
                builder.withDetails(status.getDetails());
            }
            
            return builder.build();
            
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", "Health check failed: " + e.getMessage())
                    .withDetail("timestamp", System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * 获取缓存的健康状态
     */
    private DistributedLockHealthStatus getCachedHealthStatus() {
        long now = System.currentTimeMillis();
        
        // 检查是否需要重新检查
        if (now - lastCheckTime > CACHE_DURATION_MS || cachedHealthStatus.isEmpty()) {
            try {
                DistributedLockHealthStatus status = healthChecker.checkHealth();
                cachedHealthStatus.clear();
                cachedHealthStatus.put("distributed-lock", status);
                lastCheckTime = now;
                return status;
            } catch (Exception e) {
                // 如果健康检查失败，返回缓存的结果或创建一个错误状态
                DistributedLockHealthStatus cachedStatus = cachedHealthStatus.get("distributed-lock");
                if (cachedStatus == null) {
                    DistributedLockHealthStatus errorStatus = new DistributedLockHealthStatus();
                    errorStatus.setHealthy(false);
                    errorStatus.setTimestamp(now);
                    errorStatus.setErrorMessage("Initial health check failed: " + e.getMessage());
                    cachedHealthStatus.put("distributed-lock", errorStatus);
                    return errorStatus;
                }
                return cachedStatus;
            }
        }
        
        return cachedHealthStatus.get("distributed-lock");
    }

    /**
     * 获取详细健康信息
     */
    public Map<String, Object> getDetailedHealthInfo() {
        DistributedLockHealthStatus status = getCachedHealthStatus();
        
        Map<String, Object> details = new HashMap<>();
        details.put("healthy", status.isHealthy());
        details.put("timestamp", status.getTimestamp());
        details.put("providerType", status.getProviderType());
        details.put("lastCheck", LocalDateTime.ofEpochSecond(status.getTimestamp() / 1000, 0, 
            java.time.ZoneOffset.UTC));
        
        if (!status.isHealthy()) {
            details.put("errorMessage", status.getErrorMessage());
        }
        
        if (status.getPerformanceMetrics() != null) {
            Map<String, Object> metrics = new HashMap<>();
            DistributedLockHealthStatus.PerformanceMetrics pm = status.getPerformanceMetrics();
            metrics.put("averageWaitTime", pm.getAverageWaitTime());
            metrics.put("successRate", pm.getSuccessRate());
            metrics.put("errorRate", pm.getErrorRate());
            metrics.put("totalOperations", pm.getTotalOperations());
            details.put("performanceMetrics", metrics);
        }
        
        if (status.getConnectionStatus() != null) {
            Map<String, Object> connection = new HashMap<>();
            DistributedLockHealthStatus.ConnectionStatus cs = status.getConnectionStatus();
            connection.put("connected", cs.isConnected());
            connection.put("provider", cs.getProvider());
            connection.put("lastCheckTimestamp", cs.getLastCheckTimestamp());
            details.put("connectionStatus", connection);
        }
        
        if (status.getDetails() != null) {
            details.putAll(status.getDetails());
        }
        
        return details;
    }

    /**
     * 清理缓存
     */
    public void clearCache() {
        cachedHealthStatus.clear();
        lastCheckTime = 0;
    }

    /**
     * 获取缓存时间戳
     */
    public long getLastCheckTime() {
        return lastCheckTime;
    }

    /**
     * 检查缓存是否有效
     */
    public boolean isCacheValid() {
        return System.currentTimeMillis() - lastCheckTime <= CACHE_DURATION_MS;
    }
}