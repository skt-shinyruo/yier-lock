package com.mycorp.distributedlock.examples;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.redis.RedisClusterFactory;
import com.mycorp.distributedlock.redis.SimpleRedisLock;

import java.util.concurrent.TimeUnit;

/**
 * Redis 后端使用示例
 * 演示如何使用 Redis 作为分布式锁的后端存储
 */
public class RedisExample {

    public static void main(String[] args) {
        System.out.println("=== Redis 单节点示例 ===");
        demonstrateSingleNodeRedis();

        System.out.println("\n=== Redis 集群示例 ===");
        demonstrateRedisCluster();

        System.out.println("\n=== Redis 高级配置示例 ===");
        demonstrateAdvancedRedisConfig();
    }

    /**
     * 演示单节点 Redis 使用
     */
    public static void demonstrateSingleNodeRedis() {
        // 创建单节点 Redis 锁工厂
        DistributedLockFactory lockFactory = RedisClusterFactory.builder()
            .redisUrl("redis://localhost:6379")
            .build();

        try {
            // 获取锁
            DistributedLock lock = lockFactory.getLock("single-node-example");

            // 使用锁
            boolean acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);
            if (acquired) {
                System.out.println("Successfully acquired lock on single-node Redis");

                // 执行业务逻辑
                performBusinessLogic();

                lock.unlock();
                System.out.println("Lock released");
            } else {
                System.out.println("Failed to acquire lock on single-node Redis");
            }

        } finally {
            lockFactory.shutdown();
        }
    }

    /**
     * 演示 Redis 集群使用
     */
    public static void demonstrateRedisCluster() {
        // 创建 Redis 集群锁工厂
        DistributedLockFactory lockFactory = RedisClusterFactory.builder()
            .redisUrl("redis://redis-node1:6379,redis://redis-node2:6379,redis://redis-node3:6379")
            .password("cluster-password")  // 集群密码
            .build();

        try {
            // 获取锁
            DistributedLock lock = lockFactory.getLock("cluster-example");

            // 使用锁
            boolean acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);
            if (acquired) {
                System.out.println("Successfully acquired lock on Redis cluster");

                // 执行业务逻辑
                performBusinessLogic();

                lock.unlock();
                System.out.println("Lock released from cluster");
            } else {
                System.out.println("Failed to acquire lock on Redis cluster");
            }

        } finally {
            lockFactory.shutdown();
        }
    }

    /**
     * 演示高级 Redis 配置
     */
    public static void demonstrateAdvancedRedisConfig() {
        // 使用高级配置的 Redis 锁工厂
        DistributedLockFactory lockFactory = RedisClusterFactory.builder()
            .redisUrl("redis://localhost:6379")
            .password("secure-password")
            .database(1)  // 使用数据库 1
            .clientName("distributed-lock-client")  // 客户端名称
            .poolMaxTotal(20)  // 连接池最大连接数
            .poolMaxIdle(10)   // 连接池最大空闲连接数
            .poolMinIdle(5)    // 连接池最小空闲连接数
            .commandTimeout(2000)  // 命令超时时间（毫秒）
            .build();

        try {
            // 演示不同的锁类型
            demonstrateDifferentLockTypes(lockFactory);

            // 演示连接池监控
            demonstrateConnectionPooling(lockFactory);

        } finally {
            lockFactory.shutdown();
        }
    }

    /**
     * 演示不同的锁类型
     */
    private static void demonstrateDifferentLockTypes(DistributedLockFactory lockFactory) {
        System.out.println("Demonstrating different Redis lock types...");

        // 基础锁
        DistributedLock basicLock = lockFactory.getLock("basic-lock");
        useLock(basicLock, "Basic Lock");

        // 可重入锁（通过配置实现）
        DistributedLock reentrantLock = lockFactory.getLock("reentrant-lock");
        useReentrantLock(reentrantLock, "Reentrant Lock");

        // 公平锁（通过配置实现）
        DistributedLock fairLock = lockFactory.getLock("fair-lock");
        useLock(fairLock, "Fair Lock");
    }

    /**
     * 演示连接池使用
     */
    private static void demonstrateConnectionPooling(DistributedLockFactory lockFactory) {
        System.out.println("Demonstrating connection pooling...");

        // 并发使用多个锁来测试连接池
        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                DistributedLock lock = lockFactory.getLock("pool-test-" + threadId);
                try {
                    boolean acquired = lock.tryLock(2, 5, TimeUnit.SECONDS);
                    if (acquired) {
                        System.out.println("Thread " + threadId + " acquired lock");
                        Thread.sleep(1000); // 模拟工作
                        lock.unlock();
                        System.out.println("Thread " + threadId + " released lock");
                    } else {
                        System.out.println("Thread " + threadId + " failed to acquire lock");
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

        System.out.println("Connection pooling test completed");
    }

    /**
     * 使用锁的通用方法
     */
    private static void useLock(DistributedLock lock, String lockType) {
        try {
            boolean acquired = lock.tryLock(3, 5, TimeUnit.SECONDS);
            if (acquired) {
                System.out.println(lockType + " acquired successfully");

                // 获取锁状态信息
                DistributedLock.LockStateInfo stateInfo = lock.getLockStateInfo();
                System.out.println("  Lock name: " + lock.getName());
                System.out.println("  Is locked: " + stateInfo.isLocked());
                System.out.println("  Held by current thread: " + stateInfo.isHeldByCurrentThread());
                System.out.println("  Reentrant count: " + stateInfo.getReentrantCount());

                lock.unlock();
                System.out.println(lockType + " released");
            } else {
                System.out.println("Failed to acquire " + lockType);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println(lockType + " acquisition was interrupted");
        }
    }

    /**
     * 演示可重入锁使用
     */
    private static void useReentrantLock(DistributedLock lock, String lockType) {
        try {
            // 第一次获取锁
            boolean acquired1 = lock.tryLock(3, 5, TimeUnit.SECONDS);
            if (acquired1) {
                System.out.println(lockType + " acquired (first time)");

                // 第二次获取同一个锁（可重入）
                boolean acquired2 = lock.tryLock(1, 1, TimeUnit.SECONDS);
                if (acquired2) {
                    System.out.println(lockType + " acquired (second time - reentrant)");

                    lock.unlock(); // 释放第二次获取
                    System.out.println(lockType + " released (second time)");
                }

                lock.unlock(); // 释放第一次获取
                System.out.println(lockType + " released (first time)");
            } else {
                System.out.println("Failed to acquire " + lockType);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println(lockType + " acquisition was interrupted");
        }
    }

    /**
     * 执行业务逻辑
     */
    private static void performBusinessLogic() {
        try {
            // 模拟业务处理时间
            Thread.sleep(500);
            System.out.println("Business logic executed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Redis 配置最佳实践示例
     */
    public static void demonstrateRedisBestPractices() {
        System.out.println("=== Redis 配置最佳实践 ===");

        // 生产环境推荐配置
        DistributedLockFactory productionFactory = RedisClusterFactory.builder()
            .redisUrl("redis://prod-redis-1:6379,redis://prod-redis-2:6379,redis://prod-redis-3:6379")
            .password("${REDIS_PASSWORD}")  // 使用环境变量
            .database(0)
            .clientName("distributed-lock-prod")
            .poolMaxTotal(50)     // 根据并发量调整
            .poolMaxIdle(20)
            .poolMinIdle(10)
            .commandTimeout(5000) // 5秒超时
            .build();

        // 使用生产环境配置
        DistributedLock prodLock = productionFactory.getLock("production-example");
        useLock(prodLock, "Production Lock");

        productionFactory.shutdown();
    }

    /**
     * Redis 故障转移示例
     */
    public static void demonstrateRedisFailover() {
        System.out.println("=== Redis 故障转移示例 ===");

        // 配置多个 Redis 节点用于故障转移
        DistributedLockFactory failoverFactory = RedisClusterFactory.builder()
            .redisUrl("redis://primary:6379,redis://secondary:6379,redis://tertiary:6379")
            .password("failover-password")
            .build();

        try {
            // 即使主节点故障，仍然可以获取锁
            DistributedLock failoverLock = failoverFactory.getLock("failover-example");

            // 模拟故障转移场景
            boolean acquired = failoverLock.tryLock(10, 30, TimeUnit.SECONDS);
            if (acquired) {
                System.out.println("Lock acquired with failover support");
                failoverLock.unlock();
            } else {
                System.out.println("Failed to acquire lock even with failover");
            }

        } finally {
            failoverFactory.shutdown();
        }
    }
}