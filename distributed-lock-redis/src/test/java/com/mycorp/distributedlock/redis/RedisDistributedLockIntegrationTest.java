package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.typesafe.config.ConfigFactory;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@Testcontainers
class RedisDistributedLockIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private SimpleRedisLockProvider lockProvider;

    @BeforeEach
    void setUp() {
        String config = """
                distributed-lock {
                  default-lease-time = 30s
                  redis {
                    hosts = "%s:%d"
                    database = 0
                    ssl = false
                  }
                }
                """.formatted(redis.getHost(), redis.getMappedPort(6379));

        lockProvider = new SimpleRedisLockProvider(new LockConfiguration(ConfigFactory.parseString(config)));
    }

    @AfterEach
    void tearDown() {
        if (lockProvider != null) {
            lockProvider.close();
        }
    }

    @Test
    void shouldAcquireAndReleaseLock() throws InterruptedException {
        DistributedLock lock = lockProvider.createLock("test-lock");

        assertFalse(lock.isLocked());
        assertFalse(lock.isHeldByCurrentThread());

        lock.lock(5, TimeUnit.SECONDS);

        assertTrue(lock.isLocked());
        assertTrue(lock.isHeldByCurrentThread());

        lock.unlock();

        assertFalse(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldRespectLockContentionAcrossHandles() throws InterruptedException {
        DistributedLock firstHandle = lockProvider.createLock("test-trylock");
        DistributedLock secondHandle = lockProvider.createLock("test-trylock");

        assertTrue(firstHandle.tryLock(1, 5, TimeUnit.SECONDS));
        assertTrue(firstHandle.isHeldByCurrentThread());

        assertFalse(secondHandle.tryLock(100, 5, TimeUnit.MILLISECONDS));

        firstHandle.unlock();

        assertTrue(secondHandle.tryLock(1, 5, TimeUnit.SECONDS));
        secondHandle.unlock();
    }

    @Test
    void shouldSupportReentrantLockingPerHandle() throws InterruptedException {
        DistributedLock lock = lockProvider.createLock("test-reentrant");

        lock.lock(5, TimeUnit.SECONDS);
        lock.lock(5, TimeUnit.SECONDS);

        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(2, lock.getReentrantCount());

        lock.unlock();
        assertTrue(lock.isHeldByCurrentThread());

        lock.unlock();
        assertFalse(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldSerializeConcurrentLockContention() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger(0);
        int threadCount = 6;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                DistributedLock handle = lockProvider.createLock("test-contention");
                try {
                    if (handle.tryLock(2, 5, TimeUnit.SECONDS)) {
                        try {
                            counter.incrementAndGet();
                            Thread.sleep(50);
                        } finally {
                            handle.unlock();
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
    void shouldEnforceReadWriteExclusion() throws InterruptedException {
        DistributedReadWriteLock readWriteLock = lockProvider.createReadWriteLock("test-rw-lock");
        DistributedLock readLock = readWriteLock.readLock();
        DistributedLock writeLock = lockProvider.createReadWriteLock("test-rw-lock").writeLock();

        readLock.lock(5, TimeUnit.SECONDS);
        assertTrue(readLock.isHeldByCurrentThread());

        assertFalse(writeLock.tryLock(100, 5, TimeUnit.MILLISECONDS));

        readLock.unlock();
        assertFalse(readLock.isHeldByCurrentThread());

        assertTrue(writeLock.tryLock(1, 5, TimeUnit.SECONDS));
        assertTrue(writeLock.isHeldByCurrentThread());

        DistributedLock secondReadHandle = lockProvider.createReadWriteLock("test-rw-lock").readLock();
        assertFalse(secondReadHandle.tryLock(100, 5, TimeUnit.MILLISECONDS));

        writeLock.unlock();
        assertFalse(writeLock.isHeldByCurrentThread());
    }

    @Test
    void shouldAllowMultipleReadersFromIndependentHandles() throws InterruptedException {
        DistributedLock readLock1 = lockProvider.createReadWriteLock("test-multiple-readers").readLock();
        DistributedLock readLock2 = lockProvider.createReadWriteLock("test-multiple-readers").readLock();

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
    void shouldExpireLocksAfterLeaseTime() throws InterruptedException {
        DistributedLock lock = lockProvider.createLock("test-expiration");

        lock.lock(500, TimeUnit.MILLISECONDS);
        assertTrue(lock.isHeldByCurrentThread());

        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .until(lock::isExpired);
    }
}
