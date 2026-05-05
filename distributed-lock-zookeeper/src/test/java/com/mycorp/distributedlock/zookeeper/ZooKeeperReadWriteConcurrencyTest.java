package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.spi.BackendLease;
import com.mycorp.distributedlock.spi.BackendSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZooKeeperReadWriteConcurrencyTest {

    @Test
    void writerShouldTimeOutWhileReaderIsHeld() throws Exception {
        try (ZooKeeperTestSupport support = new ZooKeeperTestSupport();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(support.configuration());
             BackendSession readerSession = backend.openSession();
             BackendLease ignored = readerSession.acquire(new LockRequest(
                 new LockKey("zk:rw"),
                 LockMode.READ,
                 WaitPolicy.indefinite()
             ));
             BackendSession writerSession = backend.openSession()) {
            assertThatThrownBy(() -> writerSession.acquire(new LockRequest(
                new LockKey("zk:rw"),
                LockMode.WRITE,
                WaitPolicy.timed(Duration.ofMillis(100))
            ))).isInstanceOf(LockAcquisitionTimeoutException.class);
        }
    }

    @Test
    void readerAndWriterShouldNeverOverlapUnderContention() throws Exception {
        try (ZooKeeperTestSupport support = new ZooKeeperTestSupport();
             ZooKeeperLockBackend backend = new ZooKeeperLockBackend(support.configuration())) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                for (int round = 0; round < 25; round++) {
                    CountDownLatch start = new CountDownLatch(1);
                    AtomicInteger activeReaders = new AtomicInteger();
                    AtomicInteger activeWriters = new AtomicInteger();
                    AtomicBoolean overlap = new AtomicBoolean();

                    Future<Boolean> readResult = executor.submit(() -> tryAcquire(
                        backend,
                        LockMode.READ,
                        start,
                        activeReaders,
                        activeWriters,
                        overlap
                    ));
                    Future<Boolean> writeResult = executor.submit(() -> tryAcquire(
                        backend,
                        LockMode.WRITE,
                        start,
                        activeReaders,
                        activeWriters,
                        overlap
                    ));

                    start.countDown();
                    readResult.get();
                    writeResult.get();

                    assertThat(overlap.get()).isFalse();
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private boolean tryAcquire(
        ZooKeeperLockBackend backend,
        LockMode mode,
        CountDownLatch start,
        AtomicInteger activeReaders,
        AtomicInteger activeWriters,
        AtomicBoolean overlap
    ) throws Exception {
        start.await();
        try (BackendSession session = backend.openSession();
             BackendLease lease = session.acquire(new LockRequest(
                 new LockKey("zk:rw-race"),
                 mode,
                 WaitPolicy.timed(Duration.ofMillis(200))
             ))) {
            if (mode == LockMode.READ) {
                if (activeWriters.get() > 0) {
                    overlap.set(true);
                }
                activeReaders.incrementAndGet();
                try {
                    Thread.sleep(20L);
                } finally {
                    activeReaders.decrementAndGet();
                }
            } else {
                if (activeReaders.get() > 0 || activeWriters.get() > 0) {
                    overlap.set(true);
                }
                activeWriters.incrementAndGet();
                try {
                    Thread.sleep(20L);
                } finally {
                    activeWriters.decrementAndGet();
                }
            }
            return true;
        } catch (LockAcquisitionTimeoutException exception) {
            return false;
        }
    }
}
