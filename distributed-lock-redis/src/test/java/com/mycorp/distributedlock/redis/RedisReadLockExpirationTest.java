package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LeasePolicy;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("redis-integration")
class RedisReadLockExpirationTest {

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
    void expiredReaderShouldNotBlockWriterAfterActiveReaderReleases() throws Exception {
        RedisLockBackend expiredReaderBackend = redis.newBackend(1L);
        BackendSession expiredReaderSession = expiredReaderBackend.openSession();
        BackendLockLease expiredReaderLease = expiredReaderSession.acquire(readRequest("read:stale-writer"));
        expiredReaderBackend.close();

        try (RedisLockBackend activeBackend = redis.newBackend(1L);
             BackendSession activeReaderSession = activeBackend.openSession();
             BackendLockLease activeReaderLease = activeReaderSession.acquire(readRequest("read:stale-writer"))) {
            Thread.sleep(Duration.ofSeconds(2).toMillis());

            activeReaderLease.release();

            try (BackendSession writerSession = activeBackend.openSession();
                 BackendLockLease writerLease = writerSession.acquire(writeRequest("read:stale-writer"))) {
                assertThat(writerLease.isValid()).isTrue();
            }
        } finally {
            closeQuietly(expiredReaderLease);
            closeQuietly(expiredReaderSession);
        }
    }

    @Test
    void readLeaseValidityShouldNotBeExtendedByAnotherReaderRenewal() throws Exception {
        RedisLockBackend expiredReaderBackend = redis.newBackend(1L);
        BackendSession expiredReaderSession = expiredReaderBackend.openSession();
        BackendLockLease expiredReaderLease = expiredReaderSession.acquire(readRequest("read:stale-valid"));
        try (RedisLockBackend activeBackend = redis.newBackend(1L);
             BackendSession activeReaderSession = activeBackend.openSession();
             BackendLockLease activeReaderLease = activeReaderSession.acquire(readRequest("read:stale-valid"))) {
            cancelRenewal(expiredReaderLease);

            Thread.sleep(Duration.ofSeconds(2).toMillis());

            assertThat(expiredReaderLease.isValid()).isFalse();
            assertThat(activeReaderLease.isValid()).isTrue();
        } finally {
            closeQuietly(expiredReaderLease);
            closeQuietly(expiredReaderSession);
            closeQuietly(expiredReaderBackend);
        }
    }

    @Test
    void shortReaderShouldNotShrinkSharedReadKeyExpiryBelowLongReaderScore() throws Exception {
        try (RedisLockBackend backend = redis.newBackend(30L);
             BackendSession longReaderSession = backend.openSession();
             BackendLockLease longReaderLease = longReaderSession.acquire(readRequest(
                 "read:mixed-fixed",
                 LeasePolicy.fixed(Duration.ofMillis(2_000))
             ));
             BackendSession shortReaderSession = backend.openSession();
             BackendLockLease shortReaderLease = shortReaderSession.acquire(readRequest(
                 "read:mixed-fixed",
                 LeasePolicy.fixed(Duration.ofMillis(240))
             ))) {
            cancelRenewal(longReaderLease);

            shortReaderLease.release();
            Thread.sleep(500L);

            assertThat(longReaderLease.isValid()).isTrue();

            try (BackendSession writerSession = backend.openSession()) {
                assertThatThrownBy(() -> writerSession.acquire(writeRequest("read:mixed-fixed")))
                    .isInstanceOf(LockAcquisitionTimeoutException.class);
            }
        }
    }

    private static LockRequest readRequest(String key) {
        return new LockRequest(
            new LockKey(key),
            LockMode.READ,
            WaitPolicy.indefinite()
        );
    }

    private static LockRequest readRequest(String key, LeasePolicy leasePolicy) {
        return new LockRequest(
            new LockKey(key),
            LockMode.READ,
            WaitPolicy.indefinite(),
            leasePolicy
        );
    }

    private static LockRequest writeRequest(String key) {
        return new LockRequest(
            new LockKey(key),
            LockMode.WRITE,
            WaitPolicy.timed(Duration.ofMillis(500))
        );
    }

    private static void cancelRenewal(BackendLockLease lease) throws Exception {
        Field renewalTaskField = lease.getClass().getDeclaredField("renewalTask");
        renewalTaskField.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<ScheduledFuture<?>> renewalTask =
            (AtomicReference<ScheduledFuture<?>>) renewalTaskField.get(lease);
        ScheduledFuture<?> scheduled = renewalTask.getAndSet(null);
        if (scheduled == null) {
            return;
        }
        scheduled.cancel(false);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
