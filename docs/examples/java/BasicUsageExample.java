package com.mycorp.distributedlock.examples;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.redis.RedisClusterFactory;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁基础使用示例
 * 演示最简单的分布式锁使用方式
 */
public class BasicUsageExample {

    public static void main(String[] args) {
        // 1. 创建锁工厂
        DistributedLockFactory lockFactory = createLockFactory();

        // 2. 获取分布式锁
        DistributedLock lock = lockFactory.getLock("example-lock");

        try {
            // 3. 获取锁（同步方式）
            System.out.println("Attempting to acquire lock...");
            boolean acquired = lock.tryLock(10, 30, TimeUnit.SECONDS);

            if (acquired) {
                System.out.println("Lock acquired successfully!");

                // 4. 执行业务逻辑
                performBusinessLogic();

                System.out.println("Business logic completed.");
            } else {
                System.out.println("Failed to acquire lock within timeout.");
            }

        } catch (InterruptedException e) {
            System.out.println("Lock acquisition was interrupted.");
            Thread.currentThread().interrupt();
        } finally {
            // 5. 释放锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                System.out.println("Lock released.");
            }
        }

        // 6. 关闭工厂
        lockFactory.shutdown();
    }

    /**
     * 创建锁工厂
     * 这里使用 Redis 作为后端存储
     */
    private static DistributedLockFactory createLockFactory() {
        // 在实际使用中，应该从配置文件或环境变量读取连接信息
        String redisUrl = "redis://localhost:6379";

        return RedisClusterFactory.builder()
            .redisUrl(redisUrl)
            .build();
    }

    /**
     * 模拟业务逻辑处理
     */
    private static void performBusinessLogic() {
        try {
            // 模拟一些处理时间
            Thread.sleep(2000);
            System.out.println("Processing business logic...");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Business logic was interrupted.");
        }
    }
}