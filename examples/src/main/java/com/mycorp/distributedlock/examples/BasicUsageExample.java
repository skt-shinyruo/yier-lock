package com.mycorp.distributedlock.examples;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.redis.RedisDistributedLockFactory;
import com.mycorp.distributedlock.zookeeper.ZooKeeperDistributedLockFactory;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class BasicUsageExample {
    
    public static void main(String[] args) throws Exception {
        redisExample();
        zookeeperExample();
        readWriteLockExample();
        asyncExample();
    }
    
    private static void redisExample() throws InterruptedException {
        System.out.println("=== Redis Distributed Lock Example ===");
        
        RedisClient redisClient = RedisClient.create(RedisURI.create("redis://localhost:6379"));
        RedisDistributedLockFactory lockFactory = new RedisDistributedLockFactory(redisClient);
        
        DistributedLock lock = lockFactory.getLock("example-resource");
        
        System.out.println("Attempting to acquire lock...");
        lock.lock(30, TimeUnit.SECONDS);
        
        try {
            System.out.println("Lock acquired! Doing some work...");
            Thread.sleep(2000);
            System.out.println("Work completed.");
        } finally {
            lock.unlock();
            System.out.println("Lock released.");
        }
        
        lockFactory.shutdown();
        redisClient.shutdown();
    }
    
    private static void zookeeperExample() throws Exception {
        System.out.println("\n=== ZooKeeper Distributed Lock Example ===");
        
        CuratorFramework curator = CuratorFrameworkFactory.newClient(
            "localhost:2181", 
            new ExponentialBackoffRetry(1000, 3)
        );
        curator.start();
        curator.blockUntilConnected(10, TimeUnit.SECONDS);
        
        ZooKeeperDistributedLockFactory lockFactory = new ZooKeeperDistributedLockFactory(curator);
        
        DistributedLock lock = lockFactory.getLock("example-resource");
        
        System.out.println("Attempting to acquire ZooKeeper lock...");
        if (lock.tryLock(5, 30, TimeUnit.SECONDS)) {
            try {
                System.out.println("ZooKeeper lock acquired! Doing some work...");
                Thread.sleep(2000);
                System.out.println("Work completed.");
            } finally {
                lock.unlock();
                System.out.println("ZooKeeper lock released.");
            }
        } else {
            System.out.println("Failed to acquire ZooKeeper lock within timeout.");
        }
        
        lockFactory.shutdown();
        curator.close();
    }
    
    private static void readWriteLockExample() throws InterruptedException {
        System.out.println("\n=== Read/Write Lock Example ===");
        
        RedisClient redisClient = RedisClient.create(RedisURI.create("redis://localhost:6379"));
        RedisDistributedLockFactory lockFactory = new RedisDistributedLockFactory(redisClient);
        
        DistributedReadWriteLock rwLock = lockFactory.getReadWriteLock("shared-resource");
        
        DistributedLock readLock = rwLock.readLock();
        DistributedLock writeLock = rwLock.writeLock();
        
        System.out.println("Acquiring read lock...");
        readLock.lock(30, TimeUnit.SECONDS);
        try {
            System.out.println("Read lock acquired. Reading data...");
            Thread.sleep(1000);
        } finally {
            readLock.unlock();
            System.out.println("Read lock released.");
        }
        
        System.out.println("Acquiring write lock...");
        writeLock.lock(30, TimeUnit.SECONDS);
        try {
            System.out.println("Write lock acquired. Writing data...");
            Thread.sleep(1000);
        } finally {
            writeLock.unlock();
            System.out.println("Write lock released.");
        }
        
        lockFactory.shutdown();
        redisClient.shutdown();
    }
    
    private static void asyncExample() throws Exception {
        System.out.println("\n=== Async Lock Example ===");
        
        RedisClient redisClient = RedisClient.create(RedisURI.create("redis://localhost:6379"));
        RedisDistributedLockFactory lockFactory = new RedisDistributedLockFactory(redisClient);
        
        DistributedLock lock = lockFactory.getLock("async-resource");
        
        System.out.println("Acquiring lock asynchronously...");
        CompletableFuture<Void> lockFuture = lock.lockAsync(30, TimeUnit.SECONDS);
        
        lockFuture.thenRun(() -> {
            System.out.println("Async lock acquired! Doing async work...");
            try {
                Thread.sleep(1000);
                System.out.println("Async work completed.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).thenCompose(v -> lock.unlockAsync())
          .thenRun(() -> System.out.println("Async lock released."))
          .get(10, TimeUnit.SECONDS);
        
        lockFactory.shutdown();
        redisClient.shutdown();
    }
    
    public static void tryWithResourcesExample() throws InterruptedException {
        System.out.println("\n=== Try-with-Resources Example ===");
        
        RedisClient redisClient = RedisClient.create(RedisURI.create("redis://localhost:6379"));
        RedisDistributedLockFactory lockFactory = new RedisDistributedLockFactory(redisClient);
        
        try (DistributedLock lock = lockFactory.getLock("auto-resource")) {
            lock.lock(30, TimeUnit.SECONDS);
            System.out.println("Lock acquired automatically. Doing work...");
            Thread.sleep(1000);
            System.out.println("Work completed. Lock will be released automatically.");
        }
        
        lockFactory.shutdown();
        redisClient.shutdown();
    }
}