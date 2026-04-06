package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockCapabilities;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.api.exception.LockSessionLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class ZooKeeperLockBackend implements LockBackend {

    private static final LockCapabilities CAPABILITIES = new LockCapabilities(true, true, true, true);

    private final ZooKeeperBackendConfiguration configuration;
    private final CuratorFramework curatorFramework;
    private final BooleanSupplier sessionValidSupplier;

    public ZooKeeperLockBackend(ZooKeeperBackendConfiguration configuration) {
        this(configuration, null);
    }

    ZooKeeperLockBackend(ZooKeeperBackendConfiguration configuration, BooleanSupplier sessionValidSupplier) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.curatorFramework = CuratorFrameworkFactory.newClient(
            configuration.connectString(),
            new ExponentialBackoffRetry(1_000, 3)
        );
        this.curatorFramework.start();
        this.sessionValidSupplier = sessionValidSupplier != null
            ? sessionValidSupplier
            : () -> curatorFramework.getZookeeperClient().isConnected();
        try {
            boolean connected = this.curatorFramework.blockUntilConnected(10, TimeUnit.SECONDS);
            if (!connected) {
                this.curatorFramework.close();
                throw new LockBackendException(
                    "Failed to connect to ZooKeeper within 10 seconds: " + configuration.connectString()
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LockBackendException("Interrupted while connecting to ZooKeeper", exception);
        }
    }

    @Override
    public LockCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public BackendSession openSession(SessionRequest request) {
        Objects.requireNonNull(request, "request");
        return new ZooKeeperBackendSession(UUID.randomUUID().toString());
    }

    @Override
    public void close() {
        curatorFramework.close();
    }

    private final class ZooKeeperBackendSession implements BackendSession {

        private final String sessionId;
        private final ConcurrentMap<String, ZooKeeperLease> activeLeases = new ConcurrentHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        private ZooKeeperBackendSession(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public BackendLockLease acquire(LockRequest request) throws InterruptedException {
            ensureActive();

            long deadlineNanos = request.waitPolicy().unbounded()
                ? Long.MAX_VALUE
                : System.nanoTime() + request.waitPolicy().waitTime().toNanos();

            do {
                ZooKeeperLease lease = tryAcquire(request);
                if (lease != null) {
                    activeLeases.put(lease.ownerPath(), lease);
                    return lease;
                }
                if (!request.waitPolicy().unbounded() && System.nanoTime() >= deadlineNanos) {
                    throw new LockAcquisitionTimeoutException(
                        "Timed out acquiring ZooKeeper lock for key " + request.key().value()
                    );
                }
                Thread.sleep(request.waitPolicy().unbounded()
                    ? 25L
                    : Math.min(25L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()))));
            } while (true);
        }

        @Override
        public SessionState state() {
            if (closed.get()) {
                return SessionState.CLOSED;
            }
            return sessionValidSupplier.getAsBoolean() ? SessionState.ACTIVE : SessionState.LOST;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            RuntimeException failure = null;
            for (ZooKeeperLease lease : new ArrayList<>(activeLeases.values())) {
                try {
                    lease.release();
                } catch (RuntimeException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private ZooKeeperLease tryAcquire(LockRequest request) {
            try {
                return switch (request.mode()) {
                    case MUTEX -> tryAcquireMutex(request.key());
                    case READ -> tryAcquireRead(request.key());
                    case WRITE -> tryAcquireWrite(request.key());
                };
            } catch (KeeperException.NodeExistsException exception) {
                return null;
            } catch (KeeperException.NoNodeException exception) {
                return null;
            } catch (Exception exception) {
                throw new LockBackendException(
                    "Failed to acquire ZooKeeper lock for key " + request.key().value(),
                    exception
                );
            }
        }

        private ZooKeeperLease tryAcquireMutex(LockKey key) throws Exception {
            String ownerPath = mutexOwnerPath(key.value());
            if (curatorFramework.checkExists().forPath(ownerPath) != null) {
                return null;
            }
            long fence = nextFence(key.value(), LockMode.MUTEX);
            byte[] ownerData = ownerData(sessionId, fence);
            try {
                curatorFramework.create()
                    .creatingParentsIfNeeded()
                    .withMode(CreateMode.EPHEMERAL)
                    .forPath(ownerPath, ownerData);
            } catch (KeeperException.NodeExistsException exception) {
                return null;
            }
            return new ZooKeeperLease(key, LockMode.MUTEX, new FencingToken(fence), ownerPath, ownerData, this);
        }

        private ZooKeeperLease tryAcquireRead(LockKey key) throws Exception {
            String writePath = writeOwnerPath(key.value());
            if (curatorFramework.checkExists().forPath(writePath) != null) {
                return null;
            }
            long fence = nextFence(key.value(), LockMode.READ);
            String ownerPath = readOwnersRootPath(key.value()) + "/" + sessionId + "-" + UUID.randomUUID();
            byte[] ownerData = ownerData(sessionId, fence);
            curatorFramework.create()
                .creatingParentsIfNeeded()
                .withMode(CreateMode.EPHEMERAL)
                .forPath(ownerPath, ownerData);
            return new ZooKeeperLease(key, LockMode.READ, new FencingToken(fence), ownerPath, ownerData, this);
        }

        private ZooKeeperLease tryAcquireWrite(LockKey key) throws Exception {
            String ownerPath = writeOwnerPath(key.value());
            if (curatorFramework.checkExists().forPath(ownerPath) != null) {
                return null;
            }
            Stat readRoot = curatorFramework.checkExists().forPath(readOwnersRootPath(key.value()));
            if (readRoot != null && !curatorFramework.getChildren().forPath(readOwnersRootPath(key.value())).isEmpty()) {
                return null;
            }
            long fence = nextFence(key.value(), LockMode.WRITE);
            byte[] ownerData = ownerData(sessionId, fence);
            try {
                curatorFramework.create()
                    .creatingParentsIfNeeded()
                    .withMode(CreateMode.EPHEMERAL)
                    .forPath(ownerPath, ownerData);
            } catch (KeeperException.NodeExistsException exception) {
                return null;
            }
            return new ZooKeeperLease(key, LockMode.WRITE, new FencingToken(fence), ownerPath, ownerData, this);
        }

        private void forgetLease(ZooKeeperLease lease) {
            activeLeases.remove(lease.ownerPath());
        }

        private void ensureActive() {
            if (closed.get()) {
                throw new IllegalStateException("ZooKeeper session is already closed");
            }
            if (!sessionValidSupplier.getAsBoolean()) {
                throw new LockSessionLostException("ZooKeeper session lost: " + sessionId);
            }
        }
    }

    private final class ZooKeeperLease implements BackendLockLease {

        private final LockKey key;
        private final LockMode mode;
        private final FencingToken fencingToken;
        private final String ownerPath;
        private final byte[] ownerData;
        private final ZooKeeperBackendSession session;
        private final AtomicReference<LeaseState> state = new AtomicReference<>(LeaseState.ACTIVE);
        private final AtomicBoolean lostReleaseReported = new AtomicBoolean();

        private ZooKeeperLease(
            LockKey key,
            LockMode mode,
            FencingToken fencingToken,
            String ownerPath,
            byte[] ownerData,
            ZooKeeperBackendSession session
        ) {
            this.key = key;
            this.mode = mode;
            this.fencingToken = fencingToken;
            this.ownerPath = ownerPath;
            this.ownerData = ownerData;
            this.session = session;
        }

        @Override
        public LockKey key() {
            return key;
        }

        @Override
        public LockMode mode() {
            return mode;
        }

        @Override
        public FencingToken fencingToken() {
            return fencingToken;
        }

        @Override
        public LeaseState state() {
            return state.get();
        }

        @Override
        public boolean isValid() {
            if (state.get() != LeaseState.ACTIVE) {
                return false;
            }
            if (!sessionValidSupplier.getAsBoolean()) {
                return false;
            }
            return ownerNodeStillBelongsToSession();
        }

        @Override
        public void release() {
            LeaseState current = state.get();
            if (current == LeaseState.RELEASED) {
                return;
            }
            if (current == LeaseState.LOST && lostReleaseReported.get()) {
                return;
            }
            if (!sessionValidSupplier.getAsBoolean()) {
                markLost();
                lostReleaseReported.set(true);
                throw new LockOwnershipLostException("ZooKeeper lock ownership lost for key " + key.value());
            }

            try {
                Stat stat = curatorFramework.checkExists().forPath(ownerPath);
                if (stat == null || !ownerNodeStillBelongsToSession()) {
                    markLost();
                    lostReleaseReported.set(true);
                    throw new LockOwnershipLostException("ZooKeeper lock ownership lost for key " + key.value());
                }
                curatorFramework.delete().forPath(ownerPath);
                state.set(LeaseState.RELEASED);
                session.forgetLease(this);
                cleanupEmptyReadRoot();
            } catch (LockOwnershipLostException exception) {
                throw exception;
            } catch (KeeperException.NoNodeException exception) {
                markLost();
                lostReleaseReported.set(true);
                throw new LockOwnershipLostException("ZooKeeper lock ownership lost for key " + key.value());
            } catch (Exception exception) {
                throw new LockBackendException("Failed to release ZooKeeper lock for key " + key.value(), exception);
            }
        }

        private void cleanupEmptyReadRoot() throws Exception {
            if (mode != LockMode.READ) {
                return;
            }
            String rootPath = readOwnersRootPath(key.value());
            Stat stat = curatorFramework.checkExists().forPath(rootPath);
            if (stat != null && curatorFramework.getChildren().forPath(rootPath).isEmpty()) {
                try {
                    curatorFramework.delete().forPath(rootPath);
                } catch (KeeperException.NotEmptyException | KeeperException.NoNodeException ignored) {
                }
            }
        }

        private boolean ownerNodeStillBelongsToSession() {
            try {
                byte[] currentData = curatorFramework.getData().forPath(ownerPath);
                return Arrays.equals(currentData, ownerData);
            } catch (KeeperException.NoNodeException exception) {
                return false;
            } catch (Exception exception) {
                throw new LockBackendException("Failed to inspect ZooKeeper lock for key " + key.value(), exception);
            }
        }

        private void markLost() {
            state.compareAndSet(LeaseState.ACTIVE, LeaseState.LOST);
            session.forgetLease(this);
        }

        private String ownerPath() {
            return ownerPath;
        }
    }

    private long nextFence(String key, LockMode mode) throws Exception {
        String counterPath = fenceCounterPath(key, mode);
        ensurePersistentCounter(counterPath);
        while (true) {
            Stat stat = new Stat();
            byte[] current = curatorFramework.getData().storingStatIn(stat).forPath(counterPath);
            long next = bytesToLong(current) + 1L;
            try {
                curatorFramework.setData()
                    .withVersion(stat.getVersion())
                    .forPath(counterPath, longToBytes(next));
                return next;
            } catch (KeeperException.BadVersionException ignored) {
                // Retry optimistic update until the counter is advanced successfully.
            }
        }
    }

    private void ensurePersistentCounter(String counterPath) throws Exception {
        if (curatorFramework.checkExists().forPath(counterPath) != null) {
            return;
        }
        try {
            curatorFramework.create()
                .creatingParentsIfNeeded()
                .withMode(CreateMode.PERSISTENT)
                .forPath(counterPath, longToBytes(0L));
        } catch (KeeperException.NodeExistsException ignored) {
        }
    }

    private String mutexOwnerPath(String key) {
        return configuration.basePath() + "/mutex/" + encodeKeySegment(key) + "/owner";
    }

    private String readOwnersRootPath(String key) {
        return configuration.basePath() + "/rw/" + encodeKeySegment(key) + "/readers";
    }

    private String writeOwnerPath(String key) {
        return configuration.basePath() + "/rw/" + encodeKeySegment(key) + "/write-owner";
    }

    private String fenceCounterPath(String key, LockMode mode) {
        return configuration.basePath() + "/fence/" + normalizeMode(mode) + "/" + encodeKeySegment(key);
    }

    private String encodeKeySegment(String key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizeMode(LockMode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    private static byte[] ownerData(String sessionId, long fence) {
        return (sessionId + ":" + fence).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] longToBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private static long bytesToLong(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return 0L;
        }
        return ByteBuffer.wrap(bytes).getLong();
    }
}
