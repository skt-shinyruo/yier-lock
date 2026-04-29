package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LeasePolicy;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.api.exception.UnsupportedLockCapabilityException;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.testkit.FencingContract;
import com.mycorp.distributedlock.testkit.LeasePolicyContract;
import com.mycorp.distributedlock.testkit.LockClientContract;
import com.mycorp.distributedlock.testkit.MutexLockContract;
import com.mycorp.distributedlock.testkit.ReadWriteLockContract;
import com.mycorp.distributedlock.testkit.ReentryContract;
import com.mycorp.distributedlock.testkit.SessionLifecycleContract;
import com.mycorp.distributedlock.testkit.WaitPolicyContract;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
