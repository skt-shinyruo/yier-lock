package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class RedisDistributedLockIntegrationTest {
    
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    
    private RedisClient redisClient;
    private RedisDistributedLockFactory lockFactory;
    
    @BeforeEach
    void setUp() {
        String redisHost = redis.getHost();
        Integer redisPort = redis.getMappedPort(6379);
        
        redisClient = RedisClient.create(RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .build());
        
        lockFactory = new RedisDistributedLockFactory(redisClient);
    }
    
    @AfterEach
    void tearDown() {
        if (lockFactory != null) {
            lockFactory.shutdown();
        }
        if (redisClient != null) {
            redisClient.shutdown();
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
                    if (lock.tryLock(1, 5, TimeUnit.SECONDS)) {
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
        
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        assertEquals(threadCount, counter.get());
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
    
    @Test
    void testAsyncLockOperations() throws ExecutionException, InterruptedException {
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
        
        assertTrue(startLatch.await(5, TimeUnit.SECONDS));
        assertTrue(endLatch.await(10, TimeUnit.SECONDS));
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
    
    @Test
    void testLockExpiration() throws InterruptedException {
        DistributedLock lock = lockFactory.getLock("test-expiration");
        
        lock.lock(500, TimeUnit.MILLISECONDS);
        assertTrue(lock.isHeldByCurrentThread());
        
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .until(() -> !lock.isLocked());
        
        assertFalse(lock.isLocked());
    }
}