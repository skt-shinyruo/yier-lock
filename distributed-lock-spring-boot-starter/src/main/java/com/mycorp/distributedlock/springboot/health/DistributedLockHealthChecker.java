package com.mycorp.distributedlock.springboot.health;

import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.api.HealthCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 分布式锁健康检查器
 * 
 * @since Spring Boot 3.x
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
        logger.debug("Checking distributed lock health status");
        
        try {
            // 执行健康检查
            HealthCheck.HealthCheckResult healthResult = performHealthCheck();
            
            // 根据健康检查结果构建状态
            DistributedLockHealthStatus status = new DistributedLockHealthStatus();
            status.setHealthy(healthResult.isHealthy());
            status.setTimestamp(System.currentTimeMillis());
            status.setProviderType(determineProviderType());
            status.setPerformanceMetrics(extractPerformanceMetrics(healthResult));
            status.setConnectionStatus(extractConnectionStatus(healthResult));
            
            if (!healthResult.isHealthy()) {
                status.setErrorMessage(healthResult.getErrorMessage());
                status.setDetails(healthResult.getDiagnosticInfo());
            }
            
            logger.debug("Health check completed. Status: {}", status);
            return status;
            
        } catch (Exception e) {
            logger.error("Health check failed with exception", e);
            DistributedLockHealthStatus errorStatus = new DistributedLockHealthStatus();
            errorStatus.setHealthy(false);
            errorStatus.setTimestamp(System.currentTimeMillis());
            errorStatus.setErrorMessage("Health check exception: " + e.getMessage());
            errorStatus.setProviderType(determineProviderType());
            return errorStatus;
        }
    }

    /**
     * 执行实际健康检查
     */
    private HealthCheck.HealthCheckResult performHealthCheck() {
        try {
            // 创建一个测试锁
            String testLockKey = "health-check-" + System.currentTimeMillis();
            var lock = lockFactory.getLock(testLockKey);
            
            // 尝试获取锁
            boolean lockAcquired = lock.tryLock(Duration.ofSeconds(2), Duration.ofSeconds(5));
            
            if (!lockAcquired) {
                return HealthCheck.HealthCheckResult.unhealthy("Failed to acquire test lock");
            }
            
            try {
                // 检查锁工厂状态
                HealthCheck.FactoryHealthStatus factoryStatus = lockFactory.getFactoryHealthStatus();
                
                if (factoryStatus.getState() == HealthCheck.FactoryState.ERROR) {
                    return HealthCheck.HealthCheckResult.unhealthy(
                        "Lock factory in error state: " + factoryStatus.getErrorMessage());
                }
                
                if (factoryStatus.getState() == HealthCheck.FactoryState.DEGRADED) {
                    return HealthCheck.HealthCheckResult.degraded(
                        "Lock factory in degraded state", factoryStatus.getPerformanceMetrics());
                }
                
                // 模拟一些业务操作时间
                Thread.sleep(100);
                
                return HealthCheck.HealthCheckResult.healthy("All systems operational");
                
            } finally {
                // 释放锁
                try {
                    lock.unlock();
                } catch (Exception e) {
                    logger.warn("Failed to release test lock", e);
                }
            }
            
        } catch (Exception e) {
            return HealthCheck.HealthCheckResult.unhealthy("Health check failed: " + e.getMessage());
        }
    }

    /**
     * 确定锁提供者类型
     */
    private String determineProviderType() {
        if (lockFactory.getClass().getName().contains("Redis")) {
            return "redis";
        } else if (lockFactory.getClass().getName().contains("Zookeeper") || 
                   lockFactory.getClass().getName().contains("ZooKeeper")) {
            return "zookeeper";
        }
        return "unknown";
    }

    /**
     * 提取性能指标
     */
    private DistributedLockHealthStatus.PerformanceMetrics extractPerformanceMetrics(HealthCheck.HealthCheckResult healthResult) {
        try {
            var factoryStatus = lockFactory.getFactoryHealthStatus();
            if (factoryStatus != null && factoryStatus.getStatistics() != null) {
                var stats = factoryStatus.getStatistics();
                
                DistributedLockHealthStatus.PerformanceMetrics metrics = new DistributedLockHealthStatus.PerformanceMetrics();
                metrics.setAverageWaitTime(stats.getAverageWaitTime().toMillis() + "ms");
                metrics.setSuccessRate(stats.getSuccessRate() * 100 + "%");
                metrics.setErrorRate(stats.getErrorRate() * 100 + "%");
                metrics.setTotalOperations(stats.getTotalOperations());
                
                return metrics;
            }
        } catch (Exception e) {
            logger.warn("Failed to extract performance metrics", e);
        }
        
        return null;
    }

    /**
     * 提取连接状态
     */
    private DistributedLockHealthStatus.ConnectionStatus extractConnectionStatus(HealthCheck.HealthCheckResult healthResult) {
        DistributedLockHealthStatus.ConnectionStatus status = new DistributedLockHealthStatus.ConnectionStatus();
        status.setConnected(true);
        status.setProvider(determineProviderType());
        status.setLastCheckTimestamp(System.currentTimeMillis());
        return status;
    }

    /**
     * 异步健康检查
     */
    public CompletableFuture<DistributedLockHealthStatus> checkHealthAsync() {
        return CompletableFuture.supplyAsync(this::checkHealth, executor);
    }

    /**
     * 关闭健康检查器
     */
    public void shutdown() {
        logger.info("Shutting down DistributedLockHealthChecker");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}