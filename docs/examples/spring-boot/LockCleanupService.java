package com.mycorp.distributedlock.examples.springboot;

import com.mycorp.distributedlock.api.DistributedLockFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 锁清理服务
 * 定期清理过期的锁和释放资源
 */
@Service
public class LockCleanupService {

    private final DistributedLockFactory lockFactory;

    public LockCleanupService(DistributedLockFactory lockFactory) {
        this.lockFactory = lockFactory;
    }

    /**
     * 每5分钟清理一次过期的锁
     */
    @Scheduled(fixedRate = 300000) // 5分钟
    public void cleanupExpiredLocks() {
        System.out.println("Starting expired lock cleanup...");

        try {
            int cleanedCount = lockFactory.cleanupExpiredLocks();
            if (cleanedCount > 0) {
                System.out.println("Cleaned up " + cleanedCount + " expired locks");
            } else {
                System.out.println("No expired locks found");
            }
        } catch (Exception e) {
            System.err.println("Error during lock cleanup: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 每小时进行深度清理
     */
    @Scheduled(fixedRate = 3600000) // 1小时
    public void deepCleanup() {
        System.out.println("Starting deep lock cleanup...");

        try {
            // 获取所有活跃锁
            var activeLocks = lockFactory.getActiveLocks();
            System.out.println("Currently active locks: " + activeLocks.size());

            int totalCleaned = 0;

            // 检查每个锁的状态
            for (String lockName : activeLocks) {
                try {
                    var lock = lockFactory.getLock(lockName);
                    var stateInfo = lock.getLockStateInfo();

                    // 检查是否过期
                    if (stateInfo.getExpirationTime() != null &&
                        stateInfo.getExpirationTime().isBefore(java.time.Instant.now())) {

                        // 强制释放过期锁
                        if (lockFactory.releaseLock(lockName)) {
                            System.out.println("Force released expired lock: " + lockName);
                            totalCleaned++;
                        }
                    }

                    // 检查锁是否被遗弃（长时间无人持有但标记为锁定状态）
                    if (stateInfo.isLocked() && stateInfo.getHolder() == null) {
                        System.out.println("Found abandoned lock: " + lockName + ", attempting cleanup");
                        // 这里可以实现更复杂的清理逻辑
                    }

                } catch (Exception e) {
                    System.err.println("Error checking lock " + lockName + ": " + e.getMessage());
                }
            }

            System.out.println("Deep cleanup completed. Cleaned: " + totalCleaned + " locks");

        } catch (Exception e) {
            System.err.println("Error during deep cleanup: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 内存清理和优化
     */
    @Scheduled(fixedRate = 600000) // 10分钟
    public void memoryOptimization() {
        System.out.println("Starting memory optimization...");

        try {
            // 强制垃圾回收（生产环境慎用）
            System.gc();

            // 输出内存信息
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;

            System.out.println("Memory status:");
            System.out.println("  Total: " + (totalMemory / 1024 / 1024) + " MB");
            System.out.println("  Used: " + (usedMemory / 1024 / 1024) + " MB");
            System.out.println("  Free: " + (freeMemory / 1024 / 1024) + " MB");
            System.out.println("  Usage: " + String.format("%.2f%%", (double) usedMemory / totalMemory * 100));

        } catch (Exception e) {
            System.err.println("Error during memory optimization: " + e.getMessage());
        }
    }

    /**
     * 健康状态检查
     */
    @Scheduled(fixedRate = 60000) // 1分钟
    public void healthCheck() {
        try {
            var healthStatus = lockFactory.healthCheck();

            if (!healthStatus.isHealthy()) {
                System.err.println("Lock factory health check failed: " + healthStatus.getDetails());

                // 可以在这里添加告警逻辑
                // alertService.sendAlert("Lock factory unhealthy: " + healthStatus.getDetails());
            } else {
                System.out.println("Lock factory health check passed");
            }

        } catch (Exception e) {
            System.err.println("Error during health check: " + e.getMessage());
            e.printStackTrace();
        }
    }
}