package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
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
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class ZooKeeperLockBackend implements LockBackend {

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
    public BackendSession openSession() {
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
            return switch (request.mode()) {
                case MUTEX -> acquireMutex(request);
                case READ, WRITE -> acquireReadWrite(request);
            };
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
            if (!sessionValidSupplier.getAsBoolean()) {
                return;
            }
            RuntimeException failure = null;
            for (ZooKeeperLease lease : new ArrayList<>(activeLeases.values())) {
                try {
                    lease.release();
                } catch (LockOwnershipLostException ignored) {
                    // Lost ownership is an expected terminal state during cleanup.
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

        private ZooKeeperLease acquireMutex(LockRequest request) throws InterruptedException {
            long deadlineNanos = request.waitPolicy().unbounded()
                ? Long.MAX_VALUE
                : System.nanoTime() + request.waitPolicy().waitTime().toNanos();
            String ownerPath = mutexOwnerPath(request.key().value());
            while (true) {
                ensureActive();
                ZooKeeperLease lease;
                try {
                    lease = tryAcquireMutex(request.key());
                } catch (RuntimeException exception) {
                    throw exception;
                } catch (Exception exception) {
                    throw new LockBackendException(
                        "Failed to acquire ZooKeeper mutex for key " + request.key().value(),
                        exception
                    );
                }
                if (lease != null) {
                    activeLeases.put(lease.ownerPath(), lease);
                    return lease;
                }
                long remainingNanos = remainingNanos(deadlineNanos);
                if (!request.waitPolicy().unbounded() && remainingNanos <= 0L) {
                    throw timeout(request.key());
                }
                try {
                    boolean deleted = awaitNodeDeletion(ownerPath, remainingNanos);
                    if (!request.waitPolicy().unbounded() && !deleted) {
                        throw timeout(request.key());
                    }
                } catch (RuntimeException exception) {
                    throw exception;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw exception;
                } catch (Exception exception) {
                    throw new LockBackendException(
                        "Failed while waiting for ZooKeeper mutex on key " + request.key().value(),
                        exception
                    );
                }
            }
        }

        private ZooKeeperLease acquireReadWrite(LockRequest request) throws InterruptedException {
            String contenderPath = null;
            try {
                ensureActive();
                contenderPath = createContenderNode(request);
                byte[] nodeOwnerData = ownerData(sessionId);
                String nodeName = nodeName(contenderPath);
                long deadlineNanos = request.waitPolicy().unbounded()
                    ? Long.MAX_VALUE
                    : System.nanoTime() + request.waitPolicy().waitTime().toNanos();
                while (true) {
                    ensureActive();
                    List<QueueNode> nodes = queueNodes(request.key().value());
                    QueueNode current = currentNode(nodes, nodeName);
                    if (current == null) {
                        throw new LockSessionLostException(
                            "ZooKeeper contender node disappeared for key " + request.key().value()
                        );
                    }
                    if (canAcquire(nodes, current)) {
                        long fence = nextFence(request.key().value());
                        ZooKeeperLease lease = new ZooKeeperLease(
                            request.key(),
                            request.mode(),
                            new FencingToken(fence),
                            contenderPath,
                            nodeOwnerData,
                            this
                        );
                        activeLeases.put(lease.ownerPath(), lease);
                        return lease;
                    }
                    long remainingNanos = remainingNanos(deadlineNanos);
                    if (!request.waitPolicy().unbounded() && remainingNanos <= 0L) {
                        deleteIfExists(contenderPath);
                        throw timeout(request.key());
                    }
                    String predecessorPath = watchedPredecessor(request.key().value(), nodes, current);
                    if (predecessorPath == null) {
                        continue;
                    }
                    boolean deleted = awaitNodeDeletion(predecessorPath, remainingNanos);
                    if (!request.waitPolicy().unbounded() && !deleted) {
                        deleteIfExists(contenderPath);
                        throw timeout(request.key());
                    }
                }
            } catch (InterruptedException exception) {
                if (contenderPath != null) {
                    deleteIfExists(contenderPath);
                }
                Thread.currentThread().interrupt();
                throw exception;
            } catch (RuntimeException exception) {
                if (contenderPath != null) {
                    deleteIfExists(contenderPath);
                }
                throw exception;
            } catch (Exception exception) {
                if (contenderPath != null) {
                    deleteIfExists(contenderPath);
                }
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
            long fence = nextFence(key.value());
            byte[] nodeOwnerData = ownerData(sessionId);
            try {
                curatorFramework.create()
                    .creatingParentsIfNeeded()
                    .withMode(CreateMode.EPHEMERAL)
                    .forPath(ownerPath, nodeOwnerData);
            } catch (KeeperException.NodeExistsException exception) {
                return null;
            }
            return new ZooKeeperLease(key, LockMode.MUTEX, new FencingToken(fence), ownerPath, nodeOwnerData, this);
        }

        private String createContenderNode(LockRequest request) throws Exception {
            String prefix = switch (request.mode()) {
                case READ -> "read-";
                case WRITE -> "write-";
                case MUTEX -> throw new IllegalArgumentException("Mutex mode does not use queue nodes");
            };
            return curatorFramework.create()
                .creatingParentsIfNeeded()
                .withMode(CreateMode.EPHEMERAL_SEQUENTIAL)
                .forPath(queueRootPath(request.key().value()) + "/" + prefix, ownerData(sessionId));
        }

        private List<QueueNode> queueNodes(String key) throws Exception {
            try {
                return curatorFramework.getChildren().forPath(queueRootPath(key)).stream()
                    .map(ZooKeeperLockBackend.this::queueNode)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingLong(QueueNode::sequence))
                    .toList();
            } catch (KeeperException.NoNodeException exception) {
                return List.of();
            }
        }

        private QueueNode currentNode(List<QueueNode> nodes, String nodeName) {
            return nodes.stream()
                .filter(node -> node.name().equals(nodeName))
                .findFirst()
                .orElse(null);
        }

        private boolean canAcquire(List<QueueNode> nodes, QueueNode current) {
            if (current.mode() == LockMode.WRITE) {
                return nodes.stream()
                    .takeWhile(node -> node.sequence() < current.sequence())
                    .findAny()
                    .isEmpty();
            }
            return nodes.stream()
                .takeWhile(node -> node.sequence() < current.sequence())
                .noneMatch(node -> node.mode() == LockMode.WRITE);
        }

        private String watchedPredecessor(String key, List<QueueNode> nodes, QueueNode current) {
            if (current.mode() == LockMode.WRITE) {
                return nodes.stream()
                    .filter(node -> node.sequence() < current.sequence())
                    .max(Comparator.comparingLong(QueueNode::sequence))
                    .map(node -> queueRootPath(key) + "/" + node.name())
                    .orElse(null);
            }
            return nodes.stream()
                .filter(node -> node.sequence() < current.sequence() && node.mode() == LockMode.WRITE)
                .max(Comparator.comparingLong(QueueNode::sequence))
                .map(node -> queueRootPath(key) + "/" + node.name())
                .orElse(null);
        }

        private boolean awaitNodeDeletion(String path, long remainingNanos) throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            CuratorWatcher watcher = event -> latch.countDown();
            Stat stat = curatorFramework.checkExists().usingWatcher(watcher).forPath(path);
            if (stat == null) {
                return true;
            }
            if (remainingNanos == Long.MAX_VALUE) {
                latch.await();
                return true;
            }
            return latch.await(Math.max(1L, remainingNanos), TimeUnit.NANOSECONDS);
        }

        private void forgetLease(ZooKeeperLease lease) {
            activeLeases.remove(lease.ownerPath());
        }

        private void deleteIfExists(String path) {
            try {
                curatorFramework.delete().forPath(path);
            } catch (KeeperException.NoNodeException ignored) {
            } catch (Exception exception) {
                throw new LockBackendException("Failed to delete ZooKeeper node " + path, exception);
            }
        }

        private void ensureActive() {
            if (closed.get()) {
                throw new IllegalStateException("ZooKeeper session is already closed");
            }
            if (!sessionValidSupplier.getAsBoolean()) {
                throw new LockSessionLostException("ZooKeeper session lost: " + sessionId);
            }
        }

        private long remainingNanos(long deadlineNanos) {
            return deadlineNanos == Long.MAX_VALUE ? Long.MAX_VALUE : deadlineNanos - System.nanoTime();
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
                throw new LockOwnershipLostException("ZooKeeper lock ownership lost for key " + key.value());
            }

            try {
                Stat stat = curatorFramework.checkExists().forPath(ownerPath);
                if (stat == null || !ownerNodeStillBelongsToSession()) {
                    markLost();
                    throw new LockOwnershipLostException("ZooKeeper lock ownership lost for key " + key.value());
                }
                curatorFramework.delete().forPath(ownerPath);
                state.set(LeaseState.RELEASED);
                session.forgetLease(this);
            } catch (LockOwnershipLostException exception) {
                throw exception;
            } catch (KeeperException.NoNodeException exception) {
                markLost();
                throw new LockOwnershipLostException("ZooKeeper lock ownership lost for key " + key.value());
            } catch (Exception exception) {
                throw new LockBackendException("Failed to release ZooKeeper lock for key " + key.value(), exception);
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
            lostReleaseReported.set(true);
            session.forgetLease(this);
        }

        private String ownerPath() {
            return ownerPath;
        }
    }

    private QueueNode queueNode(String name) {
        if (name.startsWith("read-")) {
            return new QueueNode(name, LockMode.READ, sequence(name));
        }
        if (name.startsWith("write-")) {
            return new QueueNode(name, LockMode.WRITE, sequence(name));
        }
        return null;
    }

    private long sequence(String nodeName) {
        return Long.parseLong(nodeName.substring(nodeName.lastIndexOf('-') + 1));
    }

    private long nextFence(String key) throws Exception {
        String counterPath = fenceCounterPath(key);
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

    private String queueRootPath(String key) {
        return configuration.basePath() + "/rw/" + encodeKeySegment(key) + "/locks";
    }

    private String fenceCounterPath(String key) {
        return configuration.basePath() + "/fence/" + encodeKeySegment(key);
    }

    private String encodeKeySegment(String key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] ownerData(String sessionId) {
        return sessionId.getBytes(StandardCharsets.UTF_8);
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

    private static String nodeName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static LockAcquisitionTimeoutException timeout(LockKey key) {
        return new LockAcquisitionTimeoutException("Timed out acquiring ZooKeeper lock for key " + key.value());
    }

    private record QueueNode(String name, LockMode mode, long sequence) {
    }
}
