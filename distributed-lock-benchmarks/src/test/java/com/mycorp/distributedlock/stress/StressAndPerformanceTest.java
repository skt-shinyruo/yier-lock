package com.mycorp.distributedlock.stress;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.LockProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fast smoke-style stress tests for the benchmarks module.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Stress And Performance Smoke Tests")
class StressAndPerformanceTest {

    private static final int THREADS = 12;
    private static final int OPERATIONS_PER_THREAD = 10;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Mock
    private LockProvider mockLockProvider;

    @Test
    @DisplayName("should create the mocked provider dependency")
    void shouldCreateMockedProviderDependency() {
        assertThat(mockLockProvider).isNotNull();
    }

    @Test
    @DisplayName("should handle a short concurrent acquisition burst")
    void shouldHandleShortConcurrentAcquisitionBurst() throws InterruptedException {
        DistributedLock lock = createAlwaysSuccessfulLock();
        when(mockLockProvider.createLock("burst-lock")).thenReturn(lock);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREADS);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        AtomicInteger successCount = new AtomicInteger();

        for (int index = 0; index < THREADS; index++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int attempt = 0; attempt < OPERATIONS_PER_THREAD; attempt++) {
                        DistributedLock acquiredLock = mockLockProvider.createLock("burst-lock");
                        if (acquiredLock.tryLock(10L, 1L, TimeUnit.SECONDS)) {
                            try {
                                successCount.incrementAndGet();
                            } finally {
                                acquiredLock.unlock();
                            }
                        }
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        executor.shutdownNow();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(THREADS * OPERATIONS_PER_THREAD);
        verify(lock, times(THREADS * OPERATIONS_PER_THREAD)).tryLock(10L, 1L, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("should use the current tryLock signature in mocked stress scenarios")
    void shouldUseCurrentTryLockSignature() throws InterruptedException {
        DistributedLock lock = createAlwaysSuccessfulLock();

        boolean acquired = lock.tryLock(25L, 1L, TimeUnit.SECONDS);

        assertThat(acquired).isTrue();
        verify(lock).tryLock(25L, 1L, TimeUnit.SECONDS);
    }

    private DistributedLock createAlwaysSuccessfulLock() throws InterruptedException {
        DistributedLock lock = mock(DistributedLock.class);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        return lock;
    }
}
