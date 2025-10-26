package com.mycorp.distributedlock.examples;

import com.mycorp.distributedlock.api.*;
import com.mycorp.distributedlock.api.exception.DistributedLockException;
import com.mycorp.distributedlock.redis.RedisClusterFactory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;

/**
 * 高级特性使用示例
 * 演示分布式锁的各种高级功能
 */
public class AdvancedFeaturesExample {

    private final DistributedLockFactory lockFactory;

    public AdvancedFeaturesExample() {
        this.lockFactory = createLockFactory();
    }

    public static void main(String[] args) {
        AdvancedFeaturesExample example = new AdvancedFeaturesExample();

        try {
            System.out.println("=== 演示自动续期功能 ===");
            example.demonstrateAutoRenewal();

            System.out.println("\n=== 演示异步操作 ===");
            example.demonstrateAsyncOperations();

            System.out.println("\n=== 演示批量操作 ===");
            example.demonstrateBatchOperations();

            System.out.println("\n=== 演示读写锁 ===");
            example.demonstrateReadWriteLock();

            System.out.println("\n=== 演示事件监听 ===");
            example.demonstrateEventListening();

        } catch (Exception e) {
            System.err.println("Example execution failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            example.shutdown();
        }
    }

    /**
     * 演示自动续期功能
     */
    public void demonstrateAutoRenewal() {
        DistributedLock lock = lockFactory.getLock("renewal-example");

        try {
            // 获取锁
            boolean acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);
            if (!acquired) {
                System.out.println("Failed to acquire lock for renewal demo");
                return;
            }

            System.out.println("Lock acquired, starting auto-renewal...");

            // 设置自动续期 (每5秒续期一次)
            ScheduledFuture<?> renewalTask = lock.scheduleAutoRenewal(5, TimeUnit.SECONDS,
                result -> {
                    if (result.isSuccess()) {
                        System.out.println("Lock renewed successfully at " + result.getRenewalTime());
                    } else {
                        System.err.println("Lock renewal failed: " + result.getFailureCause().getMessage());
                    }
                });

            // 模拟长时间运行的任务
            Thread.sleep(15000); // 15秒

            // 取消自动续期
            lock.cancelAutoRenewal(renewalTask);
            System.out.println("Auto-renewal cancelled");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 演示异步操作
     */
    public void demonstrateAsyncOperations() {
        try {
            // 异步获取锁
            CompletableFuture<DistributedLock> lockFuture = lockFactory.getLockAsync("async-example");

            lockFuture.thenCompose(lock -> {
                System.out.println("Async lock obtained, attempting to acquire...");

                // 异步尝试获取锁
                return lock.tryLockAsync(5, 10, TimeUnit.SECONDS)
                    .thenCompose(acquired -> {
                        if (acquired) {
                            System.out.println("Lock acquired asynchronously");

                            // 异步执行业务逻辑
                            return performAsyncWork()
                                .thenRun(() -> System.out.println("Async work completed"))
                                .thenCompose(v -> lock.unlockAsync())
                                .thenRun(() -> System.out.println("Lock released asynchronously"));
                        } else {
                            System.out.println("Failed to acquire lock asynchronously");
                            return CompletableFuture.completedFuture(null);
                        }
                    });
            }).get(); // 等待完成

        } catch (InterruptedException | ExecutionException e) {
            System.err.println("Async operation failed: " + e.getMessage());
        }
    }

    /**
     * 演示批量操作
     */
    public void demonstrateBatchOperations() {
        List<String> resourceIds = Arrays.asList("resource-1", "resource-2", "resource-3");
        List<String> lockNames = resourceIds.stream()
            .map(id -> "batch:" + id)
            .sorted() // 排序避免死锁
            .toList();

        BatchLockOperations<DistributedLock> batchOps =
            lockFactory.createBatchLockOperations(lockNames, locks -> {
                System.out.println("Processing batch with " + locks.size() + " locks");

                // 批量处理资源
                for (int i = 0; i < resourceIds.size(); i++) {
                    String resourceId = resourceIds.get(i);
                    DistributedLock lock = locks.get(i);

                    System.out.println("Processing " + resourceId + " with lock " + lock.getName());
                    // 模拟处理
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                return resourceIds.size();
            });

        try {
            Integer processedCount = batchOps.execute();
            System.out.println("Batch operation completed, processed " + processedCount + " resources");
        } catch (BatchLockException e) {
            System.err.println("Batch operation failed: " + e.getMessage());
        }
    }

    /**
     * 演示读写锁
     */
    public void demonstrateReadWriteLock() {
        DistributedReadWriteLock rwLock = lockFactory.getReadWriteLock("rw-example");

        // 模拟读操作
        Runnable readTask = () -> {
            DistributedLock readLock = rwLock.readLock();
            try {
                readLock.lock();
                System.out.println(Thread.currentThread().getName() + " acquired read lock");
                Thread.sleep(1000);
                System.out.println(Thread.currentThread().getName() + " releasing read lock");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                readLock.unlock();
            }
        };

        // 模拟写操作
        Runnable writeTask = () -> {
            DistributedLock writeLock = rwLock.writeLock();
            try {
                writeLock.lock();
                System.out.println(Thread.currentThread().getName() + " acquired write lock");
                Thread.sleep(2000);
                System.out.println(Thread.currentThread().getName() + " releasing write lock");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                writeLock.unlock();
            }
        };

        // 启动多个读线程和一个写线程
        Thread readThread1 = new Thread(readTask, "Reader-1");
        Thread readThread2 = new Thread(readTask, "Reader-2");
        Thread writeThread = new Thread(writeTask, "Writer");

        readThread1.start();
        readThread2.start();

        try {
            Thread.sleep(500); // 让读线程先运行
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        writeThread.start();

        // 等待所有线程完成
        try {
            readThread1.join();
            readThread2.join();
            writeThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 演示事件监听
     */
    public void demonstrateEventListening() {
        // 注册事件监听器
        lockFactory.registerEventListener(new LockEventListener<DistributedLock>() {
            @Override
            public void onLockEvent(LockEvent<DistributedLock> event) {
                System.out.println("Lock event: " + event.getEventType() +
                                 " for lock " + event.getLock().getName() +
                                 " at " + event.getTimestamp());
            }

            @Override
            public void onDeadlockDetected(DeadlockCycleInfo deadlockInfo) {
                System.err.println("Deadlock detected in cycle: " +
                                 deadlockInfo.getCycle().stream()
                                     .map(lock -> lock.getName())
                                     .toList());
            }
        });

        // 执行一些锁操作来触发事件
        DistributedLock lock1 = lockFactory.getLock("event-test-1");
        DistributedLock lock2 = lockFactory.getLock("event-test-2");

        try {
            lock1.lock();
            Thread.sleep(500);

            // 在另一个线程中尝试获取锁2，然后锁1（可能导致死锁）
            Thread deadlockThread = new Thread(() -> {
                try {
                    DistributedLock l2 = lockFactory.getLock("event-test-2");
                    DistributedLock l1 = lockFactory.getLock("event-test-1");

                    l2.lock();
                    Thread.sleep(100);
                    // 这里不会真正获取到锁1，因为它被主线程持有
                    boolean acquired = l1.tryLock(1, 1, TimeUnit.SECONDS);
                    if (acquired) {
                        l1.unlock();
                    }
                    l2.unlock();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            deadlockThread.start();
            deadlockThread.join();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock1.unlock();
        }
    }

    /**
     * 执行异步工作
     */
    private CompletableFuture<Void> performAsyncWork() {
        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
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
}