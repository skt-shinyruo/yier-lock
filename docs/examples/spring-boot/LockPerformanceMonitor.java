package com.mycorp.distributedlock.examples.springboot;

import com.mycorp.distributedlock.api.DistributedLockFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 锁性能监控服务
 * 定期收集和报告锁的性能指标
 */
@Service
public class LockPerformanceMonitor {

    private final DistributedLockFactory lockFactory;

    public LockPerformanceMonitor(DistributedLockFactory lockFactory) {
        this.lockFactory = lockFactory;
    }

    /**
     * 每30秒收集一次性能指标
     */
    @Scheduled(fixedRate = 30000)
    public void collectPerformanceMetrics() {
        var stats = lockFactory.getStatistics();

        System.out.println("=== Lock Performance Metrics ===");
        System.out.println("Total Locks: " + stats.getTotalLocks());
        System.out.println("Active Locks: " + stats.getActiveLocks());
        System.out.println("Total Acquisitions: " + stats.getTotalLockAcquisitions());
        System.out.println("Failed Acquisitions: " + stats.getFailedLockAcquisitions());
        System.out.println("Average Acquisition Time: " + stats.getAverageLockAcquisitionTime() + "ms");
        System.out.println("Peak Concurrency: " + stats.getPeakConcurrency());
        System.out.println("Uptime: " + stats.getUptime() + "ms");

        // 计算一些派生指标
        long totalAttempts = stats.getTotalLockAcquisitions() + stats.getFailedLockAcquisitions();
        if (totalAttempts > 0) {
            double successRate = (double) stats.getTotalLockAcquisitions() / totalAttempts * 100.0;
            System.out.println("Success Rate: " + String.format("%.2f%%", successRate));
        }

        if (stats.getMemoryUsage() != null) {
            var memory = stats.getMemoryUsage();
            System.out.println("Memory Usage: " + memory.getUsedMemory() + "/" + memory.getTotalMemory() + " bytes");
            System.out.println("Memory Usage Rate: " + String.format("%.2f%%", memory.getUsageRate() * 100));
        }

        // 检查潜在问题
        checkForIssues(stats);
    }

    /**
     * 检查性能问题
     */
    private void checkForIssues(com.mycorp.distributedlock.api.DistributedLockFactory.FactoryStatistics stats) {
        boolean hasIssues = false;

        // 检查获取时间
        if (stats.getAverageLockAcquisitionTime() > 2000) {
            System.err.println("WARNING: High average lock acquisition time: " + stats.getAverageLockAcquisitionTime() + "ms");
            hasIssues = true;
        }

        // 检查失败率
        long totalAttempts = stats.getTotalLockAcquisitions() + stats.getFailedLockAcquisitions();
        if (totalAttempts > 0) {
            double failureRate = (double) stats.getFailedLockAcquisitions() / totalAttempts;
            if (failureRate > 0.1) { // 10% 失败率
                System.err.println("WARNING: High lock acquisition failure rate: " + String.format("%.2f%%", failureRate * 100));
                hasIssues = true;
            }
        }

        // 检查活跃锁数量
        if (stats.getActiveLocks() > 50) {
            System.err.println("WARNING: High number of active locks: " + stats.getActiveLocks());
            hasIssues = true;
        }

        if (!hasIssues) {
            System.out.println("✓ All metrics within acceptable ranges");
        }
    }

    /**
     * 每5分钟生成性能报告
     */
    @Scheduled(fixedRate = 300000)
    public void generatePerformanceReport() {
        System.out.println("\n=== Performance Report ===");
        var stats = lockFactory.getStatistics();

        // 计算吞吐量 (每秒操作数)
        long uptimeSeconds = stats.getUptime() / 1000;
        if (uptimeSeconds > 0) {
            double throughput = (double) stats.getTotalLockAcquisitions() / uptimeSeconds;
            System.out.println("Throughput: " + String.format("%.2f ops/sec", throughput));
        }

        // 计算资源利用率
        if (stats.getMemoryUsage() != null) {
            var memory = stats.getMemoryUsage();
            System.out.println("Memory Efficiency: " + String.format("%.2f ops/MB",
                (double) stats.getTotalLockAcquisitions() / (memory.getUsedMemory() / (1024.0 * 1024.0))));
        }

        System.out.println("=== End Performance Report ===\n");
    }
}