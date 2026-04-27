package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SessionBoundLockLeaseConcurrencyTest {

    @Test
    void concurrentReleaseShouldRunDelegateOnceAndBlockSecondCaller() throws Exception {
        ThreadMXBean threadMxBean = ManagementFactory.getThreadMXBean();
        assumeTrue(threadMxBean.isThreadCpuTimeSupported(), "thread CPU time is required for this regression");
        threadMxBean.setThreadCpuTimeEnabled(true);

        BlockingLease delegate = new BlockingLease();
        AtomicInteger unregisterCount = new AtomicInteger();
        AtomicLong secondThreadId = new AtomicLong(-1L);
        SessionBoundLockLease lease = new SessionBoundLockLease(delegate, ignored -> unregisterCount.incrementAndGet());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(lease::release);
            assertThat(delegate.awaitReleaseStarted()).isTrue();

            Future<?> second = executor.submit(() -> {
                secondThreadId.set(Thread.currentThread().getId());
                lease.release();
            });
            while (secondThreadId.get() < 0L) {
                Thread.sleep(10L);
            }
            long beforeCpuNanos = threadMxBean.getThreadCpuTime(secondThreadId.get());
            Thread.sleep(100L);
            long afterCpuNanos = threadMxBean.getThreadCpuTime(secondThreadId.get());
            assertThat(second.isDone()).isFalse();
            assertThat(afterCpuNanos - beforeCpuNanos).isLessThan(TimeUnit.MILLISECONDS.toNanos(25L));

            delegate.finishRelease();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);

            assertThat(delegate.releaseCount()).isEqualTo(1);
            assertThat(unregisterCount.get()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class BlockingLease implements BackendLockLease {
        private final CountDownLatch releaseStarted = new CountDownLatch(1);
        private final CountDownLatch releaseMayFinish = new CountDownLatch(1);
        private final AtomicInteger releaseCount = new AtomicInteger();
        private volatile LeaseState state = LeaseState.ACTIVE;

        @Override
        public LockKey key() {
            return new LockKey("core:release");
        }

        @Override
        public LockMode mode() {
            return LockMode.MUTEX;
        }

        @Override
        public FencingToken fencingToken() {
            return new FencingToken(1L);
        }

        @Override
        public LeaseState state() {
            return state;
        }

        @Override
        public boolean isValid() {
            return state == LeaseState.ACTIVE;
        }

        @Override
        public void release() {
            releaseCount.incrementAndGet();
            releaseStarted.countDown();
            try {
                if (!releaseMayFinish.await(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("release was not allowed to finish");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            state = LeaseState.RELEASED;
        }

        boolean awaitReleaseStarted() throws InterruptedException {
            return releaseStarted.await(1, TimeUnit.SECONDS);
        }

        void finishRelease() {
            releaseMayFinish.countDown();
        }

        int releaseCount() {
            return releaseCount.get();
        }
    }
}
