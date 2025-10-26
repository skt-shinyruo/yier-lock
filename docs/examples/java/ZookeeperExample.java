package com.mycorp.distributedlock.examples;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.zookeeper.ZooKeeperDistributedLockFactory;

import java.util.concurrent.TimeUnit;

/**
 * ZooKeeper 后端使用示例
 * 演示如何使用 ZooKeeper 作为分布式锁的后端存储
 */
public class ZookeeperExample {

    public static void main(String[] args) {
        System.out.println("=== ZooKeeper 单节点示例 ===");
        demonstrateSingleNodeZooKeeper();

        System.out.println("\n=== ZooKeeper 集群示例 ===");
        demonstrateZooKeeperCluster();

        System.out.println("\n=== ZooKeeper 高级配置示例 ===");
        demonstrateAdvancedZooKeeperConfig();
    }

    /**
     * 演示单节点 ZooKeeper 使用
     */
    public static void demonstrateSingleNodeZooKeeper() {
        // 创建单节点 ZooKeeper 锁工厂
        DistributedLockFactory lockFactory = new ZooKeeperDistributedLockFactory(
            "localhost:2181",  // ZooKeeper 连接字符串
            "/distributed-locks"  // 基础路径
        );

        try {
            // 获取锁
            DistributedLock lock = lockFactory.getLock("zk-single-node-example");

            // 使用锁
            boolean acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);
            if (acquired) {
                System.out.println("Successfully acquired lock on single-node ZooKeeper");

                // 执行业务逻辑
                performBusinessLogic();

                lock.unlock();
                System.out.println("Lock released");
            } else {
                System.out.println("Failed to acquire lock on single-node ZooKeeper");
            }

        } finally {
            lockFactory.shutdown();
        }
    }

    /**
     * 演示 ZooKeeper 集群使用
     */
    public static void demonstrateZooKeeperCluster() {
        // 创建 ZooKeeper 集群锁工厂
        DistributedLockFactory lockFactory = new ZooKeeperDistributedLockFactory(
            "zk1:2181,zk2:2181,zk3:2181",  // 集群连接字符串
            "/distributed-locks"
        );

        try {
            // 获取锁
            DistributedLock lock = lockFactory.getLock("zk-cluster-example");

            // 使用锁
            boolean acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);
            if (acquired) {
                System.out.println("Successfully acquired lock on ZooKeeper cluster");

                // 执行业务逻辑
                performBusinessLogic();

                lock.unlock();
                System.out.println("Lock released from cluster");
            } else {
                System.out.println("Failed to acquire lock on ZooKeeper cluster");
            }

        } finally {
            lockFactory.shutdown();
        }
    }

    /**
     * 演示高级 ZooKeeper 配置
     */
    public static void demonstrateAdvancedZooKeeperConfig() {
        // 使用 ZooKeeperDistributedLockFactory 的构造器进行高级配置
        DistributedLockFactory lockFactory = new ZooKeeperDistributedLockFactory(
            "localhost:2181",           // 连接字符串
            "/distributed-locks",       // 基础路径
            60000,                      // 会话超时 (60秒)
            15000,                      // 连接超时 (15秒)
            "digest",                   // 认证方案
            "user:password"             // 认证信息
        );

        try {
            // 演示不同的锁类型
            demonstrateDifferentLockTypes(lockFactory);

            // 演示监听机制
            demonstrateZooKeeperWatchers(lockFactory);

        } finally {
            lockFactory.shutdown();
        }
    }

    /**
     * 演示不同的锁类型
     */
    private static void demonstrateDifferentLockTypes(DistributedLockFactory lockFactory) {
        System.out.println("Demonstrating different ZooKeeper lock types...");

        // 基础锁
        DistributedLock basicLock = lockFactory.getLock("zk-basic-lock");
        useLock(basicLock, "ZooKeeper Basic Lock");

        // 可重入锁
        DistributedLock reentrantLock = lockFactory.getLock("zk-reentrant-lock");
        useReentrantLock(reentrantLock, "ZooKeeper Reentrant Lock");

        // 公平锁（ZooKeeper 天然支持公平性）
        DistributedLock fairLock = lockFactory.getLock("zk-fair-lock");
        useLock(fairLock, "ZooKeeper Fair Lock");
    }

    /**
     * 演示 ZooKeeper 监听机制
     */
    private static void demonstrateZooKeeperWatchers(DistributedLockFactory lockFactory) {
        System.out.println("Demonstrating ZooKeeper watchers...");

        // ZooKeeper 天然支持监听机制，用于实现公平锁和事件通知
        DistributedLock lock1 = lockFactory.getLock("watcher-test-1");
        DistributedLock lock2 = lockFactory.getLock("watcher-test-2");

        Thread thread1 = new Thread(() -> {
            try {
                System.out.println("Thread 1 trying to acquire lock...");
                boolean acquired = lock1.tryLock(10, 30, TimeUnit.SECONDS);
                if (acquired) {
                    System.out.println("Thread 1 acquired lock, holding for 5 seconds...");
                    Thread.sleep(5000);
                    lock1.unlock();
                    System.out.println("Thread 1 released lock");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread thread2 = new Thread(() -> {
            try {
                // 等待一秒后尝试获取锁
                Thread.sleep(1000);
                System.out.println("Thread 2 trying to acquire lock...");
                boolean acquired = lock2.tryLock(10, 30, TimeUnit.SECONDS);
                if (acquired) {
                    System.out.println("Thread 2 acquired lock after Thread 1");
                    lock2.unlock();
                    System.out.println("Thread 2 released lock");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        thread1.start();
        thread2.start();

        try {
            thread1.join();
            thread2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("ZooKeeper watcher demonstration completed");
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
     * ZooKeeper 配置最佳实践示例
     */
    public static void demonstrateZooKeeperBestPractices() {
        System.out.println("=== ZooKeeper 配置最佳实践 ===");

        // 生产环境推荐配置
        DistributedLockFactory productionFactory = new ZooKeeperDistributedLockFactory(
            "zk-prod-1:2181,zk-prod-2:2181,zk-prod-3:2181",  // 生产集群
            "/prod/distributed-locks",     // 生产环境路径
            90000,                         // 较长的会话超时 (90秒)
            30000,                         // 连接超时 (30秒)
            "digest",                      // 认证方案
            System.getenv("ZK_AUTH")       // 从环境变量获取认证信息
        );

        // 使用生产环境配置
        DistributedLock prodLock = productionFactory.getLock("zk-production-example");
        useLock(prodLock, "ZooKeeper Production Lock");

        productionFactory.shutdown();
    }

    /**
     * ZooKeeper 故障转移示例
     */
    public static void demonstrateZooKeeperFailover() {
        System.out.println("=== ZooKeeper 故障转移示例 ===");

        // 配置 ZooKeeper 集群用于故障转移
        DistributedLockFactory failoverFactory = new ZooKeeperDistributedLockFactory(
            "zk-primary:2181,zk-secondary:2181,zk-tertiary:2181",
            "/distributed-locks",
            60000,
            15000
        );

        try {
            // 即使部分节点故障，仍然可以获取锁（由 ZooKeeper 客户端处理）
            DistributedLock failoverLock = failoverFactory.getLock("zk-failover-example");

            boolean acquired = failoverLock.tryLock(10, 30, TimeUnit.SECONDS);
            if (acquired) {
                System.out.println("Lock acquired with ZooKeeper failover support");
                failoverLock.unlock();
            } else {
                System.out.println("Failed to acquire lock even with ZooKeeper failover");
            }

        } finally {
            failoverFactory.shutdown();
        }
    }

    /**
     * 演示 ZooKeeper 的临时节点特性
     */
    public static void demonstrateEphemeralNodes() {
        System.out.println("=== ZooKeeper 临时节点特性 ===");

        DistributedLockFactory lockFactory = new ZooKeeperDistributedLockFactory(
            "localhost:2181",
            "/ephemeral-locks"
        );

        try {
            DistributedLock lock = lockFactory.getLock("ephemeral-example");

            System.out.println("Acquiring lock with ephemeral node backing...");
            boolean acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);

            if (acquired) {
                System.out.println("Lock acquired. In ZooKeeper, this creates a temporary znode.");
                System.out.println("If this JVM crashes, the znode will be automatically deleted,");
                System.out.println("allowing other processes to acquire the lock.");

                // 持有锁一段时间
                Thread.sleep(3000);

                lock.unlock();
                System.out.println("Lock released and znode deleted.");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lockFactory.shutdown();
        }
    }
}