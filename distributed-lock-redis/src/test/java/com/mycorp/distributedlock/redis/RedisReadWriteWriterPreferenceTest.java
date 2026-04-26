package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RedisReadWriteWriterPreferenceTest {

    private static final String WRITER_PROGRESS_KEY = "redis:rw:writer-progress";
    private static final String WRITER_TIMEOUT_KEY = "redis:rw:writer-timeout";
    private static final String MANUAL_PENDING_KEY = "redis:rw:manual-pending";

    private static RedisTestSupport.RunningRedis redis;

    @BeforeAll
    static void startRedis() throws Exception {
        redis = RedisTestSupport.startRedis();
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (redis != null) {
            redis.close();
        }
    }

    @BeforeEach
    void resetRedis() {
        redis.flushAll();
    }

    @Test
    void pendingWriterShouldBlockLaterReadersAndAcquireAfterReadersDrain() throws Exception {
        try (RedisLockBackend backend = redis.newBackend(30L)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            BackendSession readerSession = backend.openSession();
            BackendLockLease readerLease = readerSession.acquire(readRequest(WRITER_PROGRESS_KEY, Duration.ofSeconds(1)));
            BackendSession writerSession = backend.openSession();
            Future<BackendLockLease> writerAttempt = executor.submit(() ->
                writerSession.acquire(writeRequest(WRITER_PROGRESS_KEY, Duration.ofSeconds(5)))
            );
            BackendLockLease writerLease = null;

            try {
                assertThat(awaitPendingWriterIntent(WRITER_PROGRESS_KEY)).isTrue();

                assertThat(tryAcquireRead(backend, WRITER_PROGRESS_KEY, Duration.ofMillis(150))).isFalse();
                assertThat(tryAcquireRead(backend, WRITER_PROGRESS_KEY, Duration.ofMillis(150))).isFalse();

                readerLease.release();
                writerLease = writerAttempt.get(2, TimeUnit.SECONDS);

                assertThat(writerLease.isValid()).isTrue();
                assertThat(pendingWriterCount(WRITER_PROGRESS_KEY)).isZero();
            } finally {
                closeQuietly(writerLease);
                writerAttempt.cancel(true);
                closeQuietly(writerSession);
                closeQuietly(readerLease);
                closeQuietly(readerSession);
                executor.shutdownNow();
            }
        }
    }

    @Test
    void writerTimeoutShouldRemovePendingIntent() throws Exception {
        try (RedisLockBackend backend = redis.newBackend(30L);
             BackendSession readerSession = backend.openSession();
             BackendLockLease ignored = readerSession.acquire(readRequest(WRITER_TIMEOUT_KEY, Duration.ofSeconds(1)))) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Boolean> writerAttempt = executor.submit(() ->
                tryAcquireWrite(backend, WRITER_TIMEOUT_KEY, Duration.ofMillis(300))
            );

            try {
                assertThat(awaitPendingWriterIntent(WRITER_TIMEOUT_KEY)).isTrue();
                assertThat(writerAttempt.get(2, TimeUnit.SECONDS)).isFalse();
                assertThat(pendingWriterCount(WRITER_TIMEOUT_KEY)).isZero();
            } finally {
                writerAttempt.cancel(true);
                executor.shutdownNow();
            }
        }
    }

    @Test
    void livePendingWriterIntentShouldBlockReadersUntilItExpires() throws Exception {
        String pendingKey = pendingWritersKey(MANUAL_PENDING_KEY);
        redis.commands().zadd(pendingKey, System.currentTimeMillis() + 250L, "manual-writer");
        redis.commands().pexpire(pendingKey, 1000L);

        try (RedisLockBackend backend = redis.newBackend(30L)) {
            assertThat(tryAcquireRead(backend, MANUAL_PENDING_KEY, Duration.ofMillis(150))).isFalse();

            Thread.sleep(350L);

            assertThat(tryAcquireRead(backend, MANUAL_PENDING_KEY, Duration.ofMillis(500))).isTrue();
            assertThat(pendingWriterCount(MANUAL_PENDING_KEY)).isZero();
        }
    }

    private static boolean tryAcquireRead(RedisLockBackend backend, String key, Duration waitTime) throws Exception {
        try (BackendSession session = backend.openSession();
             BackendLockLease lease = session.acquire(readRequest(key, waitTime))) {
            return lease.isValid();
        } catch (LockAcquisitionTimeoutException exception) {
            return false;
        }
    }

    private static boolean tryAcquireWrite(RedisLockBackend backend, String key, Duration waitTime) throws Exception {
        try (BackendSession session = backend.openSession();
             BackendLockLease lease = session.acquire(writeRequest(key, waitTime))) {
            return lease.isValid();
        } catch (LockAcquisitionTimeoutException exception) {
            return false;
        }
    }

    private static boolean awaitPendingWriterIntent(String key) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadlineNanos) {
            if (pendingWriterCount(key) > 0) {
                return true;
            }
            Thread.sleep(25L);
        }
        return false;
    }

    private static long pendingWriterCount(String key) {
        Long count = redis.commands().zcard(pendingWritersKey(key));
        return count == null ? 0L : count;
    }

    private static String pendingWritersKey(String key) {
        return "lock:%s:write:pending".formatted(key);
    }

    private static LockRequest readRequest(String key, Duration waitTime) {
        return new LockRequest(
            new LockKey(key),
            LockMode.READ,
            WaitPolicy.timed(waitTime)
        );
    }

    private static LockRequest writeRequest(String key, Duration waitTime) {
        return new LockRequest(
            new LockKey(key),
            LockMode.WRITE,
            WaitPolicy.timed(waitTime)
        );
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
