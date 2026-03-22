package com.mycorp.distributedlock.springboot.health;

import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.springboot.SpringDistributedLockFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 分布式锁健康检查器
 */
public class DistributedLockHealthChecker {

    private static final Logger logger = LoggerFactory.getLogger(DistributedLockHealthChecker.class);

    private final DistributedLockFactory lockFactory;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public DistributedLockHealthChecker(DistributedLockFactory lockFactory) {
        this.lockFactory = lockFactory;
    }

    /**
     * 检查分布式锁系统健康状态
     */
    public DistributedLockHealthStatus checkHealth() {
        long now = System.currentTimeMillis();
        try {
            DistributedLockFactory.FactoryHealthStatus factoryStatus = lockFactory.healthCheck();

            DistributedLockHealthStatus status = new DistributedLockHealthStatus();
            status.setHealthy(factoryStatus != null && factoryStatus.isHealthy());
            status.setTimestamp(factoryStatus != null ? factoryStatus.getCheckTime() : now);
            status.setProviderType(determineProviderType());
            status.setPerformanceMetrics(extractPerformanceMetrics(factoryStatus));
            status.setConnectionStatus(extractConnectionStatus(factoryStatus, now));
            status.setDetails(extractDetails(factoryStatus));

            if (factoryStatus != null && factoryStatus.getErrorMessage() != null) {
                status.setErrorMessage(factoryStatus.getErrorMessage());
            }

            return status;
        } catch (Exception exception) {
            logger.error("Health check failed with exception", exception);
            DistributedLockHealthStatus status = new DistributedLockHealthStatus();
            status.setHealthy(false);
            status.setTimestamp(now);
            status.setProviderType(determineProviderType());
            status.setErrorMessage("Health check exception: " + exception.getMessage());
            status.setConnectionStatus(extractConnectionStatus(null, now));
            return status;
        }
    }

    public CompletableFuture<DistributedLockHealthStatus> checkHealthAsync() {
        return CompletableFuture.supplyAsync(this::checkHealth, executor);
    }

    public void shutdown() {
        logger.info("Shutting down DistributedLockHealthChecker");
        executor.shutdown();
    }

    private String determineProviderType() {
        if (lockFactory instanceof SpringDistributedLockFactory springDistributedLockFactory) {
            return springDistributedLockFactory.getDelegate().getType();
        }
        return lockFactory.getFactoryName();
    }

    private DistributedLockHealthStatus.PerformanceMetrics extractPerformanceMetrics(
        DistributedLockFactory.FactoryHealthStatus factoryStatus
    ) {
        if (factoryStatus == null || factoryStatus.getPerformanceMetrics() == null) {
            return null;
        }

        DistributedLockFactory.FactoryHealthStatus.PerformanceMetrics source = factoryStatus.getPerformanceMetrics();
        DistributedLockHealthStatus.PerformanceMetrics metrics = new DistributedLockHealthStatus.PerformanceMetrics();
        metrics.setAverageWaitTime(source.getResponseTimeMs() + "ms");
        metrics.setSuccessRate(String.format("%.2f%%", Math.max(0, (1.0 - source.getErrorRate()) * 100.0)));
        metrics.setErrorRate(String.format("%.2f%%", Math.max(0, source.getErrorRate()) * 100.0));
        metrics.setTotalOperations(0L);
        return metrics;
    }

    private DistributedLockHealthStatus.ConnectionStatus extractConnectionStatus(
        DistributedLockFactory.FactoryHealthStatus factoryStatus,
        long now
    ) {
        DistributedLockHealthStatus.ConnectionStatus status = new DistributedLockHealthStatus.ConnectionStatus();
        status.setConnected(factoryStatus == null || factoryStatus.isHealthy());
        status.setProvider(determineProviderType());
        status.setLastCheckTimestamp(now);
        return status;
    }

    private Map<String, Object> extractDetails(DistributedLockFactory.FactoryHealthStatus factoryStatus) {
        Map<String, Object> details = new HashMap<>();
        if (factoryStatus == null) {
            return details;
        }

        if (factoryStatus.getDetails() != null && !factoryStatus.getDetails().isBlank()) {
            details.put("details", factoryStatus.getDetails());
        }
        if (factoryStatus.getPerformanceMetrics() != null) {
            details.put("throughput", factoryStatus.getPerformanceMetrics().getThroughput());
            details.put("activeConnections", factoryStatus.getPerformanceMetrics().getActiveConnections());
        }
        return details;
    }
}
