package com.mycorp.distributedlock.examples.springboot;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.api.annotation.DistributedLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁示例控制器
 * 演示各种分布式锁的使用方式
 */
@RestController
@RequestMapping("/api/locks")
public class Controller {

    @Autowired
    private DistributedLockFactory lockFactory;

    @Autowired
    private OrderService orderService;

    @Autowired
    private InventoryService inventoryService;

    /**
     * 基础锁使用示例
     */
    @GetMapping("/basic/{resourceId}")
    public String basicLock(@PathVariable String resourceId) {
        DistributedLock lock = lockFactory.getLock("basic:" + resourceId);

        try {
            boolean acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);
            if (acquired) {
                // 模拟业务处理
                Thread.sleep(1000);
                return "Resource " + resourceId + " processed successfully";
            } else {
                return "Failed to acquire lock for resource " + resourceId;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Operation interrupted for resource " + resourceId;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 注解方式使用示例
     */
    @PostMapping("/orders/{orderId}/process")
    public String processOrder(@PathVariable String orderId) {
        return orderService.processOrder(orderId);
    }

    /**
     * 批量操作示例
     */
    @PostMapping("/batch")
    public Map<String, Object> batchOperation(@RequestBody List<String> resourceIds) {
        return inventoryService.updateInventoryBatch(resourceIds);
    }

    /**
     * 异步操作示例
     */
    @PostMapping("/async/{resourceId}")
    public CompletableFuture<String> asyncOperation(@PathVariable String resourceId) {
        return CompletableFuture.supplyAsync(() -> {
            DistributedLock lock = lockFactory.getLock("async:" + resourceId);
            try {
                boolean acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);
                if (acquired) {
                    // 模拟异步处理
                    Thread.sleep(2000);
                    return "Async processing completed for " + resourceId;
                } else {
                    return "Failed to acquire lock for async operation on " + resourceId;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Async operation interrupted for " + resourceId;
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        });
    }

    /**
     * 锁状态查询示例
     */
    @GetMapping("/status/{lockName}")
    public Map<String, Object> getLockStatus(@PathVariable String lockName) {
        DistributedLock lock = lockFactory.getLock(lockName);
        DistributedLock.LockStateInfo stateInfo = lock.getLockStateInfo();

        return Map.of(
            "lockName", lock.getName(),
            "isLocked", stateInfo.isLocked(),
            "isHeldByCurrentThread", stateInfo.isHeldByCurrentThread(),
            "holder", stateInfo.getHolder(),
            "remainingTime", stateInfo.getRemainingTime(TimeUnit.SECONDS),
            "reentrantCount", stateInfo.getReentrantCount(),
            "lockType", stateInfo.getLockType()
        );
    }

    /**
     * 健康检查示例
     */
    @GetMapping("/health/{lockName}")
    public Map<String, Object> healthCheck(@PathVariable String lockName) {
        DistributedLock lock = lockFactory.getLock(lockName);
        DistributedLock.HealthCheckResult health = lock.healthCheck();

        return Map.of(
            "healthy", health.isHealthy(),
            "details", health.getDetails(),
            "checkTime", health.getCheckTime()
        );
    }

    /**
     * 工厂统计信息示例
     */
    @GetMapping("/stats")
    public Map<String, Object> getFactoryStats() {
        var stats = lockFactory.getStatistics();

        return Map.of(
            "totalLocks", stats.getTotalLocks(),
            "activeLocks", stats.getActiveLocks(),
            "totalAcquisitions", stats.getTotalLockAcquisitions(),
            "failedAcquisitions", stats.getFailedLockAcquisitions(),
            "averageAcquisitionTime", stats.getAverageLockAcquisitionTime(),
            "uptime", stats.getUptime()
        );
    }

    /**
     * 手动续期示例
     */
    @PostMapping("/renew/{lockName}")
    public String renewLock(@PathVariable String lockName) {
        DistributedLock lock = lockFactory.getLock(lockName);

        try {
            boolean renewed = lock.renewLock(60, TimeUnit.SECONDS);
            return renewed ? "Lock renewed successfully" : "Failed to renew lock";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Lock renewal interrupted";
        }
    }

    /**
     * 自动续期示例
     */
    @PostMapping("/auto-renew/{lockName}")
    public String startAutoRenewal(@PathVariable String lockName) {
        DistributedLock lock = lockFactory.getLock(lockName);

        // 首先获取锁
        try {
            boolean acquired = lock.tryLock(5, 30, TimeUnit.SECONDS);
            if (!acquired) {
                return "Failed to acquire lock for auto-renewal";
            }

            // 设置自动续期
            lock.scheduleAutoRenewal(10, TimeUnit.SECONDS, result -> {
                if (result.isSuccess()) {
                    System.out.println("Lock " + lockName + " renewed at " + result.getRenewalTime());
                } else {
                    System.err.println("Failed to renew lock " + lockName + ": " + result.getFailureCause().getMessage());
                }
            });

            return "Auto-renewal started for lock " + lockName;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Auto-renewal setup interrupted";
        }
    }
}