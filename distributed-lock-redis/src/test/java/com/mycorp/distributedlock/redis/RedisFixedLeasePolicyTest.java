package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.LeasePolicy;
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
             BackendLockLease ignored = session.acquire(mutexRequest(
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
             BackendLockLease ignored = session.acquire(readRequest(
                 "redis:fixed:reader-ttl",
                 LeasePolicy.fixed(Duration.ofMillis(800))
             ))) {

            assertThat(redis.commands().pttl(readersKey("redis:fixed:reader-ttl")))
                .isBetween(1L, 800L);
        }
    }

    @Test
    void backendDefaultLeaseDurationShouldUseRedisConfiguration() throws Exception {
        try (RedisLockBackend backend = redis.newBackend(2L);
             BackendSession session = backend.openSession();
             BackendLockLease ignored = session.acquire(mutexRequest(
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
             BackendLockLease ignored = holderSession.acquire(mutexRequest(
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
                assertThat(elapsedMillis).isLessThan(250L);
            } finally {
                attempt.cancel(true);
                executor.shutdownNow();
            }
        }
    }

    @Test
    void renewalShouldUseFixedLeaseDurationInsteadOfBackendDefault() throws Exception {
        try (RedisLockBackend backend = redis.newBackend(30L);
             BackendSession session = backend.openSession();
             BackendLockLease lease = session.acquire(mutexRequest(
                 "redis:fixed:renewal",
                 LeasePolicy.fixed(Duration.ofMillis(900))
             ))) {

            Thread.sleep(1_200L);

            assertThat(lease.isValid()).isTrue();
            assertThat(redis.commands().pttl(ownerKey("redis:fixed:renewal", LockMode.MUTEX)))
                .isBetween(1L, 900L);
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

    private static String ownerKey(String key, LockMode mode) {
        return "lock:%s:%s:owner".formatted(key, mode.name().toLowerCase());
    }

    private static String readersKey(String key) {
        return "lock:%s:read:owners".formatted(key);
    }
}
