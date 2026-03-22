package com.mycorp.distributedlock.springboot.actuator;

import com.mycorp.distributedlock.springboot.health.DistributedLockHealthChecker;
import com.mycorp.distributedlock.springboot.health.DistributedLockHealthStatus;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分布式锁 Actuator 健康检查指示器
 */
public class DistributedLockHealthIndicator implements HealthIndicator {

    private static final long CACHE_DURATION_MS = 30_000;

    private final DistributedLockHealthChecker healthChecker;
    private final Map<String, DistributedLockHealthStatus> cachedHealthStatus = new ConcurrentHashMap<>();

    private long lastCheckTime = 0;

    public DistributedLockHealthIndicator(DistributedLockHealthChecker healthChecker) {
        this.healthChecker = healthChecker;
    }

    @Override
    public Health health() {
        try {
            DistributedLockHealthStatus status = getCachedHealthStatus();
            Health.Builder builder = status.isHealthy() ? Health.up() : Health.down();

            builder.withDetail("provider", status.getProviderType());
            builder.withDetail("timestamp", status.getTimestamp());

            if (status.getPerformanceMetrics() != null) {
                builder.withDetail("performance", status.getPerformanceMetrics());
            }
            if (status.getConnectionStatus() != null) {
                builder.withDetail("connection", status.getConnectionStatus());
            }
            if (status.getErrorMessage() != null) {
                builder.withDetail("error", status.getErrorMessage());
            }
            if (status.getDetails() != null && !status.getDetails().isEmpty()) {
                builder.withDetails(status.getDetails());
            }

            return builder.build();
        } catch (Exception exception) {
            return Health.down()
                .withDetail("error", "Health check failed: " + exception.getMessage())
                .withDetail("timestamp", System.currentTimeMillis())
                .build();
        }
    }

    public Map<String, Object> getDetailedHealthInfo() {
        DistributedLockHealthStatus status = getCachedHealthStatus();
        Map<String, Object> details = new HashMap<>();
        details.put("healthy", status.isHealthy());
        details.put("timestamp", status.getTimestamp());
        details.put("providerType", status.getProviderType());
        details.put("lastCheck", LocalDateTime.ofEpochSecond(status.getTimestamp() / 1000, 0, ZoneOffset.UTC));

        if (status.getErrorMessage() != null) {
            details.put("errorMessage", status.getErrorMessage());
        }
        if (status.getPerformanceMetrics() != null) {
            details.put("performanceMetrics", status.getPerformanceMetrics());
        }
        if (status.getConnectionStatus() != null) {
            details.put("connectionStatus", status.getConnectionStatus());
        }
        if (status.getDetails() != null) {
            details.putAll(status.getDetails());
        }
        return details;
    }

    public void clearCache() {
        cachedHealthStatus.clear();
        lastCheckTime = 0;
    }

    public long getLastCheckTime() {
        return lastCheckTime;
    }

    public boolean isCacheValid() {
        return System.currentTimeMillis() - lastCheckTime <= CACHE_DURATION_MS;
    }

    private DistributedLockHealthStatus getCachedHealthStatus() {
        long now = System.currentTimeMillis();
        if (cachedHealthStatus.isEmpty() || now - lastCheckTime > CACHE_DURATION_MS) {
            DistributedLockHealthStatus status = healthChecker.checkHealth();
            cachedHealthStatus.clear();
            cachedHealthStatus.put("distributed-lock", status);
            lastCheckTime = now;
        }
        return cachedHealthStatus.get("distributed-lock");
    }
}
