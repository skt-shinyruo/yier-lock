package com.mycorp.distributedlock.examples;

import com.mycorp.distributedlock.api.*;
import com.mycorp.distributedlock.api.exception.DistributedLockException;
import com.mycorp.distributedlock.redis.RedisClusterFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 监控示例
 * 演示如何监控分布式锁的使用情况和性能指标
 */
public class MonitoringExample {

    private final DistributedLockFactory lockFactory;
    private final MeterRegistry meterRegistry;
    private final AtomicLong operationCount = new AtomicLong(0);

    public MonitoringExample() {
        this.lockFactory = createLockFactory();
        this.meterRegistry = new SimpleMeterRegistry();

        // 注册自定义指标
        registerMetrics();
    }

    public static void main(String[] args) {
        MonitoringExample example = new MonitoringExample();

        try {
            System.out.println("=== 演示性能监控 ===");
            example.demonstratePerformanceMonitoring();

            System.out.println("\n=== 演示健康检查 ===");
            example.demonstrateHealthChecks();

            System.out.println("\n=== 演示统计信息收集 ===");
            example.demonstrateStatisticsCollection();

            System.out.println("\n=== 演示告警监控 ===");
            example.demonstrateAlertMonitoring();

        } catch (Exception e) {
            System.err.println("Monitoring example failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            example.shutdown();
        }
    }

    /**
     * 演示性能监控
     */
    public void demonstratePerformanceMonitoring() {
        System.out.println("Starting performance monitoring demo...");

        // 执行一系列锁操作来生成监控数据
        for (int i = 0; i < 10; i++) {
            String lockName = "perf-test-" + i;

            long startTime = System.nanoTime();
            DistributedLock lock = lockFactory.getLock(lockName);

            try {
                boolean acquired = lock.tryLock(2, 5, TimeUnit.SECONDS);
                long duration = System.nanoTime() - startTime;

                if (acquired) {
                    // 记录成功获取锁的指标
                    recordLockAcquisition(lockName, true, duration);

                    // 模拟持有锁的时间
                    Thread.sleep(100 + (int)(Math.random() * 200));

                    // 记录锁持有时间
                    recordLockHoldTime(lockName, System.nanoTime() - startTime);

                } else {
                    // 记录获取锁失败的指标
                    recordLockAcquisition(lockName, false, duration);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recordLockAcquisition(lockName, false, System.nanoTime() - startTime);
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                    recordLockRelease(lockName);
                }
            }
        }

        // 输出监控指标
        printMetrics();
    }

    /**
     * 演示健康检查
     */
    public void demonstrateHealthChecks() {
        System.out.println("Performing health checks...");

        // 执行工厂健康检查
        FactoryHealthStatus healthStatus = lockFactory.healthCheck();
        System.out.println("Factory Health Status:");
        System.out.println("  Healthy: " + healthStatus.isHealthy());
        System.out.println("  Details: " + healthStatus.getDetails());
        System.out.println("  Check Time: " + healthStatus.getCheckTime());

        if (healthStatus.getPerformanceMetrics() != null) {
            FactoryHealthStatus.PerformanceMetrics metrics = healthStatus.getPerformanceMetrics();
            System.out.println("  Response Time: " + metrics.getResponseTimeMs() + "ms");
            System.out.println("  Throughput: " + metrics.getThroughput());
            System.out.println("  Error Rate: " + metrics.getErrorRate());
            System.out.println("  Active Connections: " + metrics.getActiveConnections());
        }

        // 检查特定锁的可用性
        String testLockName = "health-check-lock";
        boolean available = lockFactory.isLockAvailable(testLockName);
        System.out.println("Lock '" + testLockName + "' available: " + available);

        // 执行锁健康检查
        DistributedLock testLock = lockFactory.getLock(testLockName);
        DistributedLock.HealthCheckResult lockHealth = testLock.healthCheck();
        System.out.println("Lock Health Status:");
        System.out.println("  Healthy: " + lockHealth.isHealthy());
        System.out.println("  Details: " + lockHealth.getDetails());
        System.out.println("  Check Time: " + lockHealth.getCheckTime());
    }

    /**
     * 演示统计信息收集
     */
    public void demonstrateStatisticsCollection() {
        System.out.println("Collecting factory statistics...");

        FactoryStatistics stats = lockFactory.getStatistics();
        System.out.println("Factory Statistics:");
        System.out.println("  Total Locks: " + stats.getTotalLocks());
        System.out.println("  Active Locks: " + stats.getActiveLocks());
        System.out.println("  Total Acquisitions: " + stats.getTotalLockAcquisitions());
        System.out.println("  Failed Acquisitions: " + stats.getFailedLockAcquisitions());
        System.out.println("  Total Releases: " + stats.getTotalLockReleases());
        System.out.println("  Average Acquisition Time: " + stats.getAverageLockAcquisitionTime() + "ms");
        System.out.println("  Peak Concurrency: " + stats.getPeakConcurrency());
        System.out.println("  Uptime: " + stats.getUptime() + "ms");

        if (stats.getMemoryUsage() != null) {
            FactoryStatistics.MemoryUsage memory = stats.getMemoryUsage();
            System.out.println("  Memory Usage:");
            System.out.println("    Used: " + memory.getUsedMemory() + " bytes");
            System.out.println("    Total: " + memory.getTotalMemory() + " bytes");
            System.out.println("    Available: " + memory.getAvailableMemory() + " bytes");
            System.out.println("    Usage Rate: " + String.format("%.2f%%", memory.getUsageRate() * 100));
        }

        // 显示活跃锁列表
        System.out.println("  Active Lock Names: " + lockFactory.getActiveLocks());
    }

    /**
     * 演示告警监控
     */
    public void demonstrateAlertMonitoring() {
        System.out.println("Setting up alert monitoring...");

        // 模拟一些可能触发告警的情况
        AlertMonitor alertMonitor = new AlertMonitor();

        // 执行高竞争的锁操作
        simulateHighContention(alertMonitor);

        // 执行慢操作
        simulateSlowOperations(alertMonitor);

        // 检查连接池使用率
        simulateConnectionPoolPressure(alertMonitor);

        System.out.println("Alert monitoring demo completed. Check alerts above.");
    }

    /**
     * 注册监控指标
     */
    private void registerMetrics() {
        // 锁操作计数器
        meterRegistry.counter("distributed_lock.operations.total", "type", "acquisition");
        meterRegistry.counter("distributed_lock.operations.total", "type", "release");
        meterRegistry.counter("distributed_lock.operations.failures");

        // 锁获取时间直方图
        meterRegistry.timer("distributed_lock.acquisition.duration");

        // 锁持有时间直方图
        meterRegistry.timer("distributed_lock.hold.duration");

        // 活跃锁数量
        meterRegistry.gauge("distributed_lock.active.count", operationCount);

        // 竞争率
        meterRegistry.gauge("distributed_lock.contention.rate", this::calculateContentionRate);
    }

    /**
     * 记录锁获取操作
     */
    private void recordLockAcquisition(String lockName, boolean success, long durationNanos) {
        meterRegistry.counter("distributed_lock.operations.total", "type", "acquisition", "result", success ? "success" : "failure")
            .increment();

        if (!success) {
            meterRegistry.counter("distributed_lock.operations.failures").increment();
        }

        meterRegistry.timer("distributed_lock.acquisition.duration")
            .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 记录锁持有时间
     */
    private void recordLockHoldTime(String lockName, long durationNanos) {
        meterRegistry.timer("distributed_lock.hold.duration")
            .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 记录锁释放操作
     */
    private void recordLockRelease(String lockName) {
        meterRegistry.counter("distributed_lock.operations.total", "type", "release")
            .increment();
    }

    /**
     * 计算竞争率
     */
    private double calculateContentionRate() {
        // 简化的竞争率计算
        return Math.random() * 0.1; // 0-10% 的随机竞争率
    }

    /**
     * 输出监控指标
     */
    private void printMetrics() {
        System.out.println("Current Metrics:");
        System.out.println("  Total Operations: " + operationCount.get());
        System.out.println("  Contention Rate: " + String.format("%.2f%%", calculateContentionRate() * 100));
    }

    /**
     * 模拟高竞争场景
     */
    private void simulateHighContention(AlertMonitor alertMonitor) {
        System.out.println("Simulating high contention scenario...");

        String contendedLock = "contended-lock";

        // 多个线程竞争同一个锁
        Thread[] threads = new Thread[5];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                DistributedLock lock = lockFactory.getLock(contendedLock);
                try {
                    boolean acquired = lock.tryLock(1, 2, TimeUnit.SECONDS);
                    if (acquired) {
                        Thread.sleep(500); // 持有锁一段时间
                        lock.unlock();
                    } else {
                        alertMonitor.onLockAcquisitionFailure(contendedLock);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads[i].start();
        }

        // 等待所有线程完成
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 模拟慢操作
     */
    private void simulateSlowOperations(AlertMonitor alertMonitor) {
        System.out.println("Simulating slow operations...");

        DistributedLock lock = lockFactory.getLock("slow-lock");
        try {
            long startTime = System.currentTimeMillis();
            boolean acquired = lock.tryLock(10, 30, TimeUnit.SECONDS);

            if (acquired) {
                // 模拟很慢的操作
                Thread.sleep(5000);
                lock.unlock();

                long duration = System.currentTimeMillis() - startTime;
                if (duration > 3000) { // 超过3秒
                    alertMonitor.onSlowOperation("slow-lock", duration);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 模拟连接池压力
     */
    private void simulateConnectionPoolPressure(AlertMonitor alertMonitor) {
        System.out.println("Simulating connection pool pressure...");

        // 模拟高连接使用率
        double simulatedPoolUsage = 0.95; // 95%
        if (simulatedPoolUsage > 0.9) {
            alertMonitor.onHighConnectionPoolUsage(simulatedPoolUsage);
        }
    }

    /**
     * 创建锁工厂
     */
    private DistributedLockFactory createLockFactory() {
        return RedisClusterFactory.builder()
            .redisUrl("redis://localhost:6379")
            .build();
    }

    /**
     * 关闭资源
     */
    public void shutdown() {
        if (lockFactory != null) {
            lockFactory.shutdown();
        }
    }

    /**
     * 告警监控器
     */
    static class AlertMonitor {

        public void onLockAcquisitionFailure(String lockName) {
            System.err.println("ALERT: Failed to acquire lock '" + lockName + "'");
        }

        public void onSlowOperation(String lockName, long durationMs) {
            System.err.println("ALERT: Slow operation on lock '" + lockName +
                             "', duration: " + durationMs + "ms");
        }

        public void onHighConnectionPoolUsage(double usage) {
            System.err.println("ALERT: High connection pool usage: " +
                             String.format("%.1f%%", usage * 100));
        }
    }
}