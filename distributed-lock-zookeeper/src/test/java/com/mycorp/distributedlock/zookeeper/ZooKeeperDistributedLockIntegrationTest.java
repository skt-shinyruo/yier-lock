package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ZooKeeperDistributedLockIntegrationTest {
    
    private TestingServer testingServer;
    private CuratorFramework curatorFramework;
    private ZooKeeperDistributedLockFactory lockFactory;
    
    @BeforeEach
    void setUp() throws Exception {
        testingServer = new TestingServer();
        testingServer.start();
        
        curatorFramework = CuratorFrameworkFactory.newClient(
                testingServer.getConnectString(),
                new ExponentialBackoffRetry(1000, 3)
        );
        curatorFramework.start();
        curatorFramework.blockUntilConnected(10, TimeUnit.SECONDS);
        
        lockFactory = new ZooKeeperDistributedLockFactory(curatorFramework);
    }
    
    @AfterEach
    void tearDown() throws Exception {
        if (lockFactory != null) {
            lockFactory.shutdown();
        }
        if (curatorFramework != null) {
            curatorFramework.close();
        }
        if (testingServer != null) {
            testingServer.close();
        }
    }
    
    @Test
    void testBasicLockAcquisitionAndRelease() throws InterruptedException {
        DistributedLock lock = lockFactory.getLock("test-lock");
        
        assertFalse(lock.isLocked());
        assertFalse(lock.isHeldByCurrentThread());
        
        lock.lock(5, TimeUnit.SECONDS);
        
        assertTrue(lock.isLocked());
        assertTrue(lock.isHeldByCurrentThread());
        
        lock.unlock();
        
        assertFalse(lock.isHeldByCurrentThread());
    }
    
    @Test
    void testTryLockWithTimeout() throws InterruptedException {
        DistributedLock lock = lockFactory.getLock("test-trylock");
        
        assertTrue(lock.tryLock(1, 5, TimeUnit.SECONDS));
        assertTrue(lock.isHeldByCurrentThread());
        
        assertFalse(lock.tryLock(100, 5, TimeUnit.MILLISECONDS));
        
        lock.unlock();
        assertFalse(lock.isHeldByCurrentThread());
    }
    
    @Test
    void testReentrantLock() throws InterruptedException {
        DistributedLock lock = lockFactory.getLock("test-reentrant");
        
        lock.lock(5, TimeUnit.SECONDS);
        assertTrue(lock.isHeldByCurrentThread());
        
        lock.lock(5, TimeUnit.SECONDS);
        assertTrue(lock.isHeldByCurrentThread());
        
        lock.unlock();
        assertTrue(lock.isHeldByCurrentThread());
        
        lock.unlock();
        assertFalse(lock.isHeldByCurrentThread());
    }
    
    @Test
    void testConcurrentLockContention() throws InterruptedException {
        DistributedLock lock = lockFactory.getLock("test-contention");
        AtomicInteger counter = new AtomicInteger(0);
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    if (lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                        try {
                            counter.incrementAndGet();
                            Thread.sleep(50);
                        } finally {
                            lock.unlock();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(60, TimeUnit.SECONDS));
        assertEquals(threadCount, counter.get());
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
    
    @Test
    void testAsyncLockOperations() throws ExecutionException, InterruptedException, TimeoutException {
        DistributedLock lock = lockFactory.getLock("test-async");
        
        CompletableFuture<Void> lockFuture = lock.lockAsync(5, TimeUnit.SECONDS);
        lockFuture.get(10, TimeUnit.SECONDS);
        
        assertTrue(lock.isHeldByCurrentThread());
        
        CompletableFuture<Void> unlockFuture = lock.unlockAsync();
        unlockFuture.get(5, TimeUnit.SECONDS);
        
        assertFalse(lock.isHeldByCurrentThread());
    }
    
    @Test
    void testTryWithResources() throws InterruptedException {
        DistributedLock lock = lockFactory.getLock("test-try-with-resources");
        
        try (DistributedLock autoLock = lock) {
            autoLock.lock(5, TimeUnit.SECONDS);
            assertTrue(autoLock.isHeldByCurrentThread());
        }
        
        assertFalse(lock.isHeldByCurrentThread());
    }
    
    @Test
    void testReadWriteLock() throws InterruptedException {
        DistributedReadWriteLock readWriteLock = lockFactory.getReadWriteLock("test-rw-lock");
        DistributedLock readLock = readWriteLock.readLock();
        DistributedLock writeLock = readWriteLock.writeLock();
        
        readLock.lock(5, TimeUnit.SECONDS);
        assertTrue(readLock.isHeldByCurrentThread());
        
        assertFalse(writeLock.tryLock(100, 5, TimeUnit.MILLISECONDS));
        
        readLock.unlock();
        assertFalse(readLock.isHeldByCurrentThread());
        
        assertTrue(writeLock.tryLock(1, 5, TimeUnit.SECONDS));
        assertTrue(writeLock.isHeldByCurrentThread());
        
        assertFalse(readLock.tryLock(100, 5, TimeUnit.MILLISECONDS));
        
        writeLock.unlock();
        assertFalse(writeLock.isHeldByCurrentThread());
    }
    
    @Test
    void testMultipleReadersAllowed() throws InterruptedException {
        DistributedReadWriteLock readWriteLock = lockFactory.getReadWriteLock("test-multiple-readers");
        DistributedLock readLock1 = readWriteLock.readLock();
        DistributedLock readLock2 = readWriteLock.readLock();
        
        CountDownLatch startLatch = new CountDownLatch(2);
        CountDownLatch endLatch = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        
        executor.submit(() -> {
            try {
                readLock1.lock(5, TimeUnit.SECONDS);
                startLatch.countDown();
                Thread.sleep(200);
                readLock1.unlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                endLatch.countDown();
            }
        });
        
        executor.submit(() -> {
            try {
                readLock2.lock(5, TimeUnit.SECONDS);
                startLatch.countDown();
                Thread.sleep(200);
                readLock2.unlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                endLatch.countDown();
            }
        });
        
        assertTrue(startLatch.await(10, TimeUnit.SECONDS));
        assertTrue(endLatch.await(15, TimeUnit.SECONDS));
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
}