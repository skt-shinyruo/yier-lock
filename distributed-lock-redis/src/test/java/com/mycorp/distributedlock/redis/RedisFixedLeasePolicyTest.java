package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.LeasePolicy;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.spi.BackendLease;
import com.mycorp.distributedlock.spi.BackendSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("redis-integration")
class RedisFixedLeasePolicyTest {

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
    void fixedRequestLeaseDurationShouldControlMutexOwnerTtl() throws Exception {
        try (RedisLockBackend backend = redis.newBackend(30L);
             BackendSession session = backend.openSession();
             BackendLease ignored = session.acquire(mutexRequest(
                 "redis:fixed:mutex-ttl",
                 LeasePolicy.fixed(Duration.ofMillis(750))
             ))) {

            assertThat(redis.commands().pttl(ownerKey("redis:fixed:mutex-ttl", LockMode.MUTEX)))
                .isBetween(1L, 750L);
        }
    }

    @Test
    void fixedRequestLeaseDurationShouldControlReaderZsetExpiry() throws Exception {
        try (RedisLockBackend backend = redis.newBackend(30L);
             BackendSession session = backend.openSession();
             BackendLease ignored = session.acquire(readRequest(
                 "redis:fixed:reader-ttl",
                 LeasePolicy.fixed(Duration.ofMillis(800))
             ))) {

            assertThat(redis.commands().pttl(readersKey("redis:fixed:reader-ttl")))
                .isBetween(1L, 800L);
        }
    }

    @Test
    void fixedRequestLeaseDurationShouldControlWriteOwnerTtl() throws Exception {
        try (RedisLockBackend backend = redis.newBackend(30L);
             BackendSession session = backend.openSession();
             BackendLease ignored = session.acquire(writeRequest(
                 "redis:fixed:write-ttl",
                 LeasePolicy.fixed(Duration.ofMillis(750))
             ))) {

            assertThat(redis.commands().pttl(ownerKey("redis:fixed:write-ttl", LockMode.WRITE)))
                .isBetween(1L, 750L);
        }
    }

    @Test
    void backendDefaultLeaseDurationShouldUseRedisConfiguration() throws Exception {
        try (RedisLockBackend backend = redis.newBackend(2L);
             BackendSession session = backend.openSession();
             BackendLease ignored = session.acquire(mutexRequest(
                 "redis:fixed:backend-default",
                 LeasePolicy.backendDefault()
             ))) {

            assertThat(redis.commands().pttl(ownerKey("redis:fixed:backend-default", LockMode.MUTEX)))
                .isBetween(1_000L, 2_000L);
        }
    }

    @Test
    void tryOnceShouldNotWaitBehindHeldLock() throws Exception {
        try (RedisLockBackend backend = redis.newBackend(30L);
             BackendSession holderSession = backend.openSession();
             BackendLease ignored = holderSession.acquire(mutexRequest(
                 "redis:fixed:try-once",
                 LeasePolicy.backendDefault()
             ));
             BackendSession contenderSession = backend.openSession()) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            long startNanos = System.nanoTime();
            Future<Throwable> attempt = executor.submit(() -> {
                try {
                    contenderSession.acquire(new LockRequest(
                        new LockKey("redis:fixed:try-once"),
                        LockMode.MUTEX,
                        WaitPolicy.tryOnce()
                    ));
                    return null;
                } catch (Throwable throwable) {
                    return throwable;
                }
            });

            try {
                Throwable failure = attempt.get(500L, TimeUnit.MILLISECONDS);
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

                assertThat(failure).isInstanceOf(LockAcquisitionTimeoutException.class);
                assertThat(elapsedMillis).isLessThan(100L);
            } finally {
                attempt.cancel(true);
                executor.shutdownNow();
            }
        }
    }

    @Test
    void fixedMutexLeaseShouldExpireWithoutRenewalByDefault() throws Exception {
        try (RedisLockBackend backend = redis.newBackend(30L)) {
            BackendSession holderSession = backend.openSession();
            BackendLease lease = holderSession.acquire(mutexRequest(
                "redis:fixed:mutex-expiry",
                LeasePolicy.fixed(Duration.ofMillis(240))
            ));
            BackendSession contenderSession = backend.openSession();
            try {
                Thread.sleep(500L);

                assertThat(lease.isValid()).isFalse();
                try (BackendLease contenderLease = contenderSession.acquire(new LockRequest(
                    new LockKey("redis:fixed:mutex-expiry"),
                    LockMode.MUTEX,
                    WaitPolicy.tryOnce(),
                    LeasePolicy.backendDefault()
                ))) {
                    assertThat(contenderLease.isValid()).isTrue();
                }
            } finally {
                closeQuietly(lease);
                closeQuietly(contenderSession);
                closeQuietly(holderSession);
            }
        }
    }

    @Test
    void fixedWriteLeaseShouldExpireWithoutRenewalByDefault() throws Exception {
        try (RedisLockBackend backend = redis.newBackend(30L)) {
            BackendSession holderSession = backend.openSession();
            BackendLease lease = holderSession.acquire(writeRequest(
                "redis:fixed:write-expiry",
                LeasePolicy.fixed(Duration.ofMillis(240))
            ));
            BackendSession contenderSession = backend.openSession();
            try {
                Thread.sleep(500L);

                assertThat(lease.isValid()).isFalse();
                try (BackendLease contenderLease = contenderSession.acquire(new LockRequest(
                    new LockKey("redis:fixed:write-expiry"),
                    LockMode.WRITE,
                    WaitPolicy.tryOnce(),
                    LeasePolicy.backendDefault()
                ))) {
                    assertThat(contenderLease.isValid()).isTrue();
                }
            } finally {
                closeQuietly(lease);
                closeQuietly(contenderSession);
                closeQuietly(holderSession);
            }
        }
    }

    @Test
    void fixedLeaseRenewalCompatibilityModeShouldKeepMutexLeaseAlive() throws Exception {
        RedisBackendConfiguration configuration = new RedisBackendConfiguration(
            redis.redisUri(),
            30L,
            RedisKeyStrategy.LEGACY,
            true,
            0
        );
        try (RedisLockBackend backend = new RedisLockBackend(configuration);
             BackendSession session = backend.openSession();
             BackendLease lease = session.acquire(mutexRequest(
                 "redis:fixed:compat-renewal",
                 LeasePolicy.fixed(Duration.ofMillis(240))
             ))) {

            Thread.sleep(500L);

            assertThat(lease.isValid()).isTrue();
            assertThat(redis.commands().pttl(ownerKey("redis:fixed:compat-renewal", LockMode.MUTEX)))
                .isBetween(1L, 240L);
        }
    }

    private static LockRequest mutexRequest(String key, LeasePolicy leasePolicy) {
        return new LockRequest(
            new LockKey(key),
            LockMode.MUTEX,
            WaitPolicy.indefinite(),
            leasePolicy
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

    private static LockRequest writeRequest(String key, LeasePolicy leasePolicy) {
        return new LockRequest(
            new LockKey(key),
            LockMode.WRITE,
            WaitPolicy.indefinite(),
            leasePolicy
        );
    }

    private static String ownerKey(String key, LockMode mode) {
        return "lock:%s:%s:owner".formatted(key, mode.name().toLowerCase());
    }

    private static String readersKey(String key) {
        return "lock:%s:read:owners".formatted(key);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
