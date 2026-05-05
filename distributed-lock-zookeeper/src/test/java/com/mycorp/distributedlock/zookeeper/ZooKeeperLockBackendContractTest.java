package com.mycorp.distributedlock.zookeeper;

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
import com.mycorp.distributedlock.api.exception.UnsupportedLockCapabilityException;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.KeeperException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

class ZooKeeperMutexLockContractTest extends MutexLockContract implements ZooKeeperContractRuntime {
    @Override
    public LockRuntime createRuntime() throws Exception {
        return ZooKeeperContractRuntime.super.createRuntime();
    }
}

class ZooKeeperWaitPolicyContractTest extends WaitPolicyContract implements ZooKeeperContractRuntime {
    @Override
    public LockRuntime createRuntime() throws Exception {
        return ZooKeeperContractRuntime.super.createRuntime();
    }
}

class ZooKeeperFencingContractTest extends FencingContract implements ZooKeeperContractRuntime {
    @Override
    public LockRuntime createRuntime() throws Exception {
        return ZooKeeperContractRuntime.super.createRuntime();
    }
}

class ZooKeeperReadWriteLockContractTest extends ReadWriteLockContract implements ZooKeeperContractRuntime {
    @Override
    public LockRuntime createRuntime() throws Exception {
        return ZooKeeperContractRuntime.super.createRuntime();
    }
}

class ZooKeeperLeasePolicyContractTest extends LeasePolicyContract implements ZooKeeperContractRuntime {
    @Override
    public LockRuntime createRuntime() throws Exception {
        return ZooKeeperContractRuntime.super.createRuntime();
    }
}

class ZooKeeperReentryContractTest extends ReentryContract implements ZooKeeperContractRuntime {
    @Override
    public LockRuntime createRuntime() throws Exception {
        return ZooKeeperContractRuntime.super.createRuntime();
    }
}

class ZooKeeperSessionLifecycleContractTest extends SessionLifecycleContract implements ZooKeeperContractRuntime {
    @Override
    public LockRuntime createRuntime() throws Exception {
        return ZooKeeperContractRuntime.super.createRuntime();
    }
}

class ZooKeeperBackendSpecificContractTest extends LockClientContract implements ZooKeeperContractRuntime {
    @Override
    public LockRuntime createRuntime() throws Exception {
        return ZooKeeperContractRuntime.super.createRuntime();
    }

    @Test
    void fixedLeaseShouldBeRejectedByCoreCapabilitiesWithoutCreatingContenderNode() throws Exception {
        try (LockRuntime runtime = createRuntime()) {
            LockRequest fixedLease = new LockRequest(
                new LockKey("zk:fixed:reject"),
                LockMode.MUTEX,
                WaitPolicy.tryOnce(),
                LeasePolicy.fixed(Duration.ofSeconds(5))
            );

            try (LockSession session = runtime.lockClient().openSession()) {
                assertThatThrownBy(() -> session.acquire(fixedLease))
                    .isInstanceOf(UnsupportedLockCapabilityException.class)
                    .hasMessageContaining("fixed lease");
            }

            assertThat(childrenUnderQueueRoot("zk:fixed:reject")).isEmpty();
        }
    }

    @Test
    void tryOnceTimeoutShouldRemoveContenderNodeWhileHolderOwnsKey() throws Exception {
        try (LockRuntime runtime = createRuntime();
             LockSession holder = runtime.lockClient().openSession();
             LockLease ignored = holder.acquire(request("zk:try-once:cleanup", LockMode.MUTEX, WaitPolicy.indefinite()));
             LockSession contender = runtime.lockClient().openSession()) {
            assertThatThrownBy(() -> contender.acquire(request(
                "zk:try-once:cleanup",
                LockMode.MUTEX,
                WaitPolicy.tryOnce()
            )))
                .isInstanceOf(LockAcquisitionTimeoutException.class);

            assertThat(childrenUnderQueueRoot("zk:try-once:cleanup")).hasSize(1);
        }
    }

    private List<String> childrenUnderQueueRoot(String key) throws Exception {
        try (CuratorFramework client = CuratorFrameworkFactory.newClient(
            ZooKeeperContractRuntimes.support(this).server().getConnectString(),
            new ExponentialBackoffRetry(1_000, 3)
        )) {
            client.start();
            assertThat(client.blockUntilConnected(10, TimeUnit.SECONDS)).isTrue();
            try {
                return client.getChildren().forPath(queueRootPath(key));
            } catch (KeeperException.NoNodeException exception) {
                return List.of();
            }
        }
    }

    private String queueRootPath(String key) {
        return ZooKeeperContractRuntimes.support(this).configuration().basePath() + "/rw/" + encodeKeySegment(key) + "/locks";
    }

    private static String encodeKeySegment(String key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }
}

interface ZooKeeperContractRuntime {
    default LockRuntime createRuntime() throws Exception {
        return ZooKeeperContractRuntimes.createRuntime(this);
    }

    @AfterEach
    default void closeServer() throws Exception {
        ZooKeeperContractRuntimes.close(this);
    }
}

final class ZooKeeperContractRuntimes {
    private static final Map<Object, ZooKeeperTestSupport> SUPPORTS = new IdentityHashMap<>();

    private ZooKeeperContractRuntimes() {
    }

    static synchronized LockRuntime createRuntime(Object owner) throws Exception {
        close(owner);
        ZooKeeperTestSupport support = new ZooKeeperTestSupport();
        SUPPORTS.put(owner, support);
        return support.runtime();
    }

    static synchronized ZooKeeperTestSupport support(Object owner) {
        return SUPPORTS.get(owner);
    }

    static synchronized void close(Object owner) throws Exception {
        ZooKeeperTestSupport support = SUPPORTS.remove(owner);
        if (support != null) {
            support.close();
        }
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
            assertThat(executor.submit(() -> tryAcquire("inventory:timed", LockMode.MUTEX, Duration.ofMillis(100))).get()).isFalse();
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

    private long latestToken;

    void write(FencingToken token, String value) {
        if (token.value() <= latestToken) {
            throw new IllegalStateException("stale fencing token");
        }
        latestToken = token.value();
    }
}
