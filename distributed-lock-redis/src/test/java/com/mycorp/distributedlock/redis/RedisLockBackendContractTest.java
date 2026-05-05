package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeasePolicy;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.api.exception.LockReentryException;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

@Tag("redis-integration")
class RedisMutexLockContractTest extends MutexLockContract implements RedisContractRuntime {
    @Override
    public LockRuntime createRuntime() {
        return RedisContractRuntime.super.createRuntime();
    }
}

@Tag("redis-integration")
class RedisWaitPolicyContractTest extends WaitPolicyContract implements RedisContractRuntime {
    @Override
    public LockRuntime createRuntime() {
        return RedisContractRuntime.super.createRuntime();
    }
}

@Tag("redis-integration")
class RedisFencingContractTest extends FencingContract implements RedisContractRuntime {
    @Override
    public LockRuntime createRuntime() {
        return RedisContractRuntime.super.createRuntime();
    }
}

@Tag("redis-integration")
class RedisReadWriteLockContractTest extends ReadWriteLockContract implements RedisContractRuntime {
    @Override
    public LockRuntime createRuntime() {
        return RedisContractRuntime.super.createRuntime();
    }
}

@Tag("redis-integration")
class RedisLeasePolicyContractTest extends LeasePolicyContract implements RedisContractRuntime {
    @Override
    public LockRuntime createRuntime() {
        return RedisContractRuntime.super.createRuntime();
    }
}

@Tag("redis-integration")
class RedisFixedLeasePolicyContractTest extends FixedLeasePolicyContract implements RedisContractRuntime {
    @Override
    public LockRuntime createRuntime() {
        return RedisContractRuntime.super.createRuntime();
    }
}

@Tag("redis-integration")
class RedisReentryContractTest extends ReentryContract implements RedisContractRuntime {
    @Override
    public LockRuntime createRuntime() {
        return RedisContractRuntime.super.createRuntime();
    }
}

@Tag("redis-integration")
class RedisSessionLifecycleContractTest extends SessionLifecycleContract implements RedisContractRuntime {
    @Override
    public LockRuntime createRuntime() {
        return RedisContractRuntime.super.createRuntime();
    }
}

interface RedisContractRuntime {
    @BeforeAll
    static void startRedis() throws Exception {
        RedisContractRuntimes.start();
    }

    @AfterAll
    static void stopRedis() throws Exception {
        RedisContractRuntimes.stop();
    }

    @BeforeEach
    default void resetRedis() {
        RedisContractRuntimes.reset();
    }

    default LockRuntime createRuntime() {
        return RedisContractRuntimes.createRuntime();
    }
}

final class RedisContractRuntimes {
    private static RedisTestSupport.RunningRedis redis;

    private RedisContractRuntimes() {
    }

    static void start() throws Exception {
        if (redis == null) {
            redis = RedisTestSupport.startRedis();
        }
    }

    static void stop() throws Exception {
        if (redis != null) {
            redis.close();
            redis = null;
        }
    }

    static void reset() {
        redis.flushAll();
    }

    static LockRuntime createRuntime() {
        return LockRuntimeBuilder.create()
            .backend("redis")
            .backendProvider(new RedisBackendProvider())
            .backendConfiguration(redis.configuration(30L))
            .build();
    }
}

abstract class LockClientContract {

    protected final ExecutorService executor = Executors.newSingleThreadExecutor();
    protected LockRuntime runtime;

    protected abstract LockRuntime createRuntime() throws Exception;

    @AfterEach
    protected void tearDown() throws Exception {
        executor.shutdownNow();
        if (runtime != null) {
            runtime.close();
        }
    }

    protected LockRequest request(String key, LockMode mode, Duration waitTime) {
        return request(key, mode, WaitPolicy.timed(waitTime));
    }

    protected LockRequest request(String key, LockMode mode, WaitPolicy waitPolicy) {
        return new LockRequest(new LockKey(key), mode, waitPolicy);
    }
}

abstract class MutexLockContract extends LockClientContract {

    @Test
    void mutexShouldExcludeConcurrentSessions() throws Exception {
        runtime = createRuntime();
        try (LockSession holder = runtime.lockClient().openSession();
             LockLease ignored = holder.acquire(request("inventory:mutex", LockMode.MUTEX, Duration.ofSeconds(1)))) {
            assertThat(executor.submit(() -> tryAcquire("inventory:mutex", LockMode.MUTEX, Duration.ofMillis(100))).get()).isFalse();
        }
    }

    @Test
    void sameSessionShouldAllowDifferentKeys() throws Exception {
        runtime = createRuntime();
        try (LockSession session = runtime.lockClient().openSession();
             LockLease first = session.acquire(request("inventory:first", LockMode.MUTEX, Duration.ofSeconds(1)));
             LockLease second = session.acquire(request("inventory:second", LockMode.MUTEX, Duration.ofSeconds(1)))) {
            assertThat(first.key()).isEqualTo(new LockKey("inventory:first"));
            assertThat(second.key()).isEqualTo(new LockKey("inventory:second"));
        }
    }

    private boolean tryAcquire(String key, LockMode mode, Duration waitTime) throws Exception {
        return tryAcquire(key, mode, WaitPolicy.timed(waitTime));
    }

    private boolean tryAcquire(String key, LockMode mode, WaitPolicy waitPolicy) throws Exception {
        try (LockSession contender = runtime.lockClient().openSession();
             LockLease ignored = contender.acquire(request(key, mode, waitPolicy))) {
            return true;
        } catch (LockAcquisitionTimeoutException exception) {
            return false;
        }
    }
}

abstract class WaitPolicyContract extends LockClientContract {

    @Test
    void tryOnceShouldFailImmediatelyWhenKeyIsHeld() throws Exception {
        runtime = createRuntime();
        try (LockSession holder = runtime.lockClient().openSession();
             LockLease ignored = holder.acquire(request("inventory:try-once", LockMode.MUTEX, WaitPolicy.indefinite()));
             LockSession contender = runtime.lockClient().openSession()) {
            Future<Duration> acquireAttempt = executor.submit(() -> {
                long startedNanos = System.nanoTime();
                try (LockLease lease = contender.acquire(request("inventory:try-once", LockMode.MUTEX, WaitPolicy.tryOnce()))) {
                    fail("TRY_ONCE acquired a lock that was already held by another session");
                    return Duration.ofNanos(System.nanoTime() - startedNanos);
                } catch (LockAcquisitionTimeoutException exception) {
                    return Duration.ofNanos(System.nanoTime() - startedNanos);
                }
            });

            Duration elapsed;
            try {
                elapsed = acquireAttempt.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException exception) {
                acquireAttempt.cancel(true);
                fail("TRY_ONCE acquisition did not complete within 2 seconds while the key was held");
                return;
            }

            assertThat(elapsed).isLessThan(Duration.ofMillis(500));
        }
    }

    @Test
    void timedShouldTimeOutWhenKeyIsHeld() throws Exception {
        runtime = createRuntime();
        try (LockSession holder = runtime.lockClient().openSession();
             LockLease ignored = holder.acquire(request("inventory:timed", LockMode.MUTEX, Duration.ofSeconds(1)))) {
            assertThat(executor.submit(() -> tryAcquire("inventory:timed", LockMode.MUTEX, Duration.ofMillis(100))).get())
                .isFalse();
        }
    }

    private boolean tryAcquire(String key, LockMode mode, Duration waitTime) throws Exception {
        try (LockSession contender = runtime.lockClient().openSession();
             LockLease ignored = contender.acquire(request(key, mode, waitTime))) {
            return true;
        } catch (LockAcquisitionTimeoutException exception) {
            return false;
        }
    }
}

abstract class FencingContract extends LockClientContract {

    @Test
    void fencingTokenShouldIncreaseAcrossSequentialLeases() throws Exception {
        runtime = createRuntime();
        try (LockSession session = runtime.lockClient().openSession()) {
            long first;
            try (LockLease lease = session.acquire(request("inventory:1", LockMode.MUTEX, Duration.ofSeconds(1)))) {
                first = lease.fencingToken().value();
            }
            try (LockLease lease = session.acquire(request("inventory:1", LockMode.MUTEX, Duration.ofSeconds(1)))) {
                assertThat(lease.fencingToken().value()).isGreaterThan(first);
            }
        }
    }

    @Test
    void fencingTokenShouldIncreaseAcrossModesForTheSameKey() throws Exception {
        runtime = createRuntime();
        try (LockSession session = runtime.lockClient().openSession()) {
            long first;
            try (LockLease lease = session.acquire(request("inventory:fence", LockMode.READ, Duration.ofSeconds(1)))) {
                first = lease.fencingToken().value();
            }
            try (LockLease lease = session.acquire(request("inventory:fence", LockMode.WRITE, Duration.ofSeconds(1)))) {
                assertThat(lease.fencingToken().value()).isGreaterThan(first);
            }
        }
    }

    @Test
    void staleTokenShouldBeRejectedByGuardedResource() {
        FencedResource resource = new FencedResource();

        resource.write(new FencingToken(2L), "new");

        assertThatThrownBy(() -> resource.write(new FencingToken(1L), "old"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("stale fencing token");
    }
}

abstract class ReadWriteLockContract extends LockClientContract {

    @Test
    void readersShouldShareTheSameKeyAcrossSessions() throws Exception {
        runtime = createRuntime();
        try (LockSession first = runtime.lockClient().openSession();
             LockLease ignored = first.acquire(request("inventory:rw", LockMode.READ, Duration.ofSeconds(1)))) {
            assertThat(executor.submit(() -> tryAcquire("inventory:rw", LockMode.READ, Duration.ofMillis(200))).get())
                .isTrue();
        }
    }

    @Test
    void writerShouldTimeOutWhileReaderIsHeld() throws Exception {
        runtime = createRuntime();
        try (LockSession reader = runtime.lockClient().openSession();
             LockLease ignored = reader.acquire(request("inventory:rw", LockMode.READ, Duration.ofSeconds(1)))) {
            assertThat(executor.submit(() -> tryAcquire("inventory:rw", LockMode.WRITE, Duration.ofMillis(100))).get())
                .isFalse();
        }
    }

    @Test
    void mutexShouldTimeOutWhileReaderIsHeld() throws Exception {
        runtime = createRuntime();
        try (LockSession reader = runtime.lockClient().openSession();
             LockLease ignored = reader.acquire(request("inventory:cross-mode", LockMode.READ, Duration.ofSeconds(1)))) {
            assertThat(executor.submit(() -> tryAcquire("inventory:cross-mode", LockMode.MUTEX, Duration.ofMillis(100))).get())
                .isFalse();
        }
    }

    @Test
    void readerShouldTimeOutWhileWriterIsHeld() throws Exception {
        runtime = createRuntime();
        try (LockSession writer = runtime.lockClient().openSession();
             LockLease ignored = writer.acquire(request("inventory:rw", LockMode.WRITE, Duration.ofSeconds(1)))) {
            assertThat(executor.submit(() -> tryAcquire("inventory:rw", LockMode.READ, Duration.ofMillis(100))).get())
                .isFalse();
        }
    }

    @Test
    void mutexShouldTimeOutWhileWriterIsHeld() throws Exception {
        runtime = createRuntime();
        try (LockSession writer = runtime.lockClient().openSession();
             LockLease ignored = writer.acquire(request("inventory:cross-mode", LockMode.WRITE, Duration.ofSeconds(1)))) {
            assertThat(executor.submit(() -> tryAcquire("inventory:cross-mode", LockMode.MUTEX, Duration.ofMillis(100))).get())
                .isFalse();
        }
    }

    @Test
    void readerShouldTimeOutWhileMutexIsHeld() throws Exception {
        runtime = createRuntime();
        try (LockSession mutex = runtime.lockClient().openSession();
             LockLease ignored = mutex.acquire(request("inventory:cross-mode", LockMode.MUTEX, Duration.ofSeconds(1)))) {
            assertThat(executor.submit(() -> tryAcquire("inventory:cross-mode", LockMode.READ, Duration.ofMillis(100))).get())
                .isFalse();
        }
    }

    @Test
    void writerShouldTimeOutWhileMutexIsHeld() throws Exception {
        runtime = createRuntime();
        try (LockSession mutex = runtime.lockClient().openSession();
             LockLease ignored = mutex.acquire(request("inventory:cross-mode", LockMode.MUTEX, Duration.ofSeconds(1)))) {
            assertThat(executor.submit(() -> tryAcquire("inventory:cross-mode", LockMode.WRITE, Duration.ofMillis(100))).get())
                .isFalse();
        }
    }

    private boolean tryAcquire(String key, LockMode mode, Duration waitTime) throws Exception {
        return tryAcquire(key, mode, WaitPolicy.timed(waitTime));
    }

    private boolean tryAcquire(String key, LockMode mode, WaitPolicy waitPolicy) throws Exception {
        try (LockSession contender = runtime.lockClient().openSession();
             LockLease ignored = contender.acquire(request(key, mode, waitPolicy))) {
            return true;
        } catch (LockAcquisitionTimeoutException exception) {
            return false;
        }
    }
}

abstract class LeasePolicyContract extends LockClientContract {

    @Test
    void threeArgumentRequestShouldUseBackendDefaultLeasePolicy() {
        assertThat(request("inventory:lease-policy", LockMode.MUTEX, WaitPolicy.tryOnce()).leasePolicy())
            .isEqualTo(LeasePolicy.backendDefault());
    }
}

abstract class FixedLeasePolicyContract extends LockClientContract {

    @Test
    void fixedLeaseRequestShouldAcquireLeaseWhenSupported() throws Exception {
        runtime = createRuntime();
        try (LockSession session = runtime.lockClient().openSession();
             LockLease lease = session.acquire(new LockRequest(
                 new LockKey("inventory:fixed-lease"),
                 LockMode.MUTEX,
                 WaitPolicy.tryOnce(),
                 LeasePolicy.fixed(Duration.ofSeconds(5))
             ))) {
            assertThat(lease.key()).isEqualTo(new LockKey("inventory:fixed-lease"));
            assertThat(lease.mode()).isEqualTo(LockMode.MUTEX);
            assertThat(lease.isValid()).isTrue();
        }
    }
}

abstract class ReentryContract extends LockClientContract {

    @Test
    void sameSessionShouldRejectSameKeyReentry() throws Exception {
        runtime = createRuntime();
        try (LockSession session = runtime.lockClient().openSession();
             LockLease ignored = session.acquire(request("inventory:reentry", LockMode.MUTEX, Duration.ofSeconds(1)))) {
            assertThatThrownBy(() -> session.acquire(request("inventory:reentry", LockMode.MUTEX, WaitPolicy.tryOnce())))
                .isInstanceOf(LockReentryException.class);
        }
    }
}

abstract class SessionLifecycleContract extends LockClientContract {

    @Test
    void closingSessionShouldReleaseUnclosedLease() throws Exception {
        runtime = createRuntime();
        LockSession holder = runtime.lockClient().openSession();

        try {
            holder.acquire(request("inventory:session-close", LockMode.MUTEX, Duration.ofSeconds(1)));
        } finally {
            holder.close();
        }

        assertThat(executor.submit(() -> tryAcquire("inventory:session-close", LockMode.MUTEX, Duration.ofMillis(200))).get())
            .isTrue();
    }

    private boolean tryAcquire(String key, LockMode mode, Duration waitTime) throws Exception {
        try (LockSession contender = runtime.lockClient().openSession();
             LockLease ignored = contender.acquire(request(key, mode, waitTime))) {
            return true;
        } catch (LockAcquisitionTimeoutException exception) {
            return false;
        }
    }
}

final class FencedResource {

    private final AtomicLong latestToken = new AtomicLong();
    private final Consumer<FencingToken> afterRead;

    FencedResource() {
        this(ignored -> {
        });
    }

    FencedResource(Consumer<FencingToken> afterRead) {
        this.afterRead = afterRead;
    }

    void write(FencingToken token, String value) {
        while (true) {
            long previous = latestToken.get();
            afterRead.accept(token);
            if (token.value() <= previous) {
                throw new IllegalStateException("stale fencing token");
            }
            if (latestToken.compareAndSet(previous, token.value())) {
                return;
            }
        }
    }
}
