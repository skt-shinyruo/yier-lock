package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitMode;
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
import org.apache.curator.framework.state.ConnectionState;
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
import java.util.concurrent.atomic.AtomicReference;

public final class ZooKeeperLockBackend implements LockBackend {

    private final ZooKeeperBackendConfiguration configuration;

    public ZooKeeperLockBackend(ZooKeeperBackendConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        CuratorFramework curatorFramework = newClient();
        curatorFramework.start();
        try {
            awaitConnected(curatorFramework);
        } finally {
            curatorFramework.close();
        }
    }

    @Override
    public BackendSession openSession() {
        CuratorFramework curatorFramework = newClient();
        curatorFramework.start();
        awaitConnected(curatorFramework);
        return new ZooKeeperBackendSession(UUID.randomUUID().toString(), curatorFramework);
    }

    @Override
    public void close() {
        // No shared backend resources remain once each session owns its own client.
    }

    private CuratorFramework newClient() {
        return CuratorFrameworkFactory.newClient(
            configuration.connectString(),
            new ExponentialBackoffRetry(1_000, 3)
        );
    }

    private void awaitConnected(CuratorFramework curatorFramework) {
        try {
            boolean connected = curatorFramework.blockUntilConnected(10, TimeUnit.SECONDS);
            if (!connected) {
                curatorFramework.close();
                throw new LockBackendException(
                    "Failed to connect to ZooKeeper within 10 seconds: " + configuration.connectString()
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            curatorFramework.close();
            throw new LockBackendException("Interrupted while connecting to ZooKeeper", exception);
        }
    }

    private final class ZooKeeperBackendSession implements BackendSession, CuratorBackedSession {

        private final String sessionId;
        private final CuratorFramework curatorFramework;
        private final ConcurrentMap<String, ZooKeeperLease> activeLeases = new ConcurrentHashMap<>();
        private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.ACTIVE);
        private final AtomicReference<RuntimeException> lossCause = new AtomicReference<>();
        private final Object terminalMonitor = new Object();

        private ZooKeeperBackendSession(String sessionId, CuratorFramework curatorFramework) {
            this.sessionId = sessionId;
            this.curatorFramework = curatorFramework;
            this.curatorFramework.getConnectionStateListenable().addListener((client, newState) -> {
                if (newState == ConnectionState.LOST || newState == ConnectionState.SUSPENDED) {
                    markSessionLost(new LockSessionLostException("ZooKeeper session lost: " + sessionId));
                }
            });
        }

        @Override
        public CuratorFramework curatorFramework() {
            return curatorFramework;
        }

        @Override
        public BackendLockLease acquire(LockRequest request) throws InterruptedException {
            ensureActive();
            return switch (request.mode()) {
                case MUTEX, READ, WRITE -> acquireQueued(request);
            };
        }

        @Override
        public SessionState state() {
            return state.get();
        }

        @Override
        public void close() {
            SessionState current = state.get();
            if (current == SessionState.CLOSED) {
                return;
            }
            boolean closingActiveSession = false;
            if (current == SessionState.ACTIVE) {
                closingActiveSession = state.compareAndSet(SessionState.ACTIVE, SessionState.CLOSED);
                current = state.get();
                if (closingActiveSession) {
                    signalTerminalWaiters();
                }
                if (!closingActiveSession && current == SessionState.CLOSED) {
                    return;
                }
            }
            RuntimeException failure = null;
            for (ZooKeeperLease lease : new ArrayList<>(activeLeases.values())) {
                try {
                    if (closingActiveSession) {
                        lease.releaseDuringSessionClose();
                    } else {
                        lease.release();
                    }
                } catch (LockOwnershipLostException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                } catch (RuntimeException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            curatorFramework.close();
            if (failure != null) {
                throw failure;
            }
        }

        private ZooKeeperLease acquireQueued(LockRequest request) throws InterruptedException {
            String contenderPath = null;
            try {
                ensureActive();
                contenderPath = createContenderNode(request);
                byte[] nodeOwnerData = ownerData(sessionId);
                String nodeName = nodeName(contenderPath);
                WaitMode waitMode = request.waitPolicy().mode();
                long deadlineNanos = switch (waitMode) {
                    case TRY_ONCE -> System.nanoTime();
                    case TIMED -> System.nanoTime() + request.waitPolicy().timeout().toNanos();
                    case INDEFINITE -> Long.MAX_VALUE;
                };
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
                    if (waitMode == WaitMode.TRY_ONCE || remainingNanos <= 0L) {
                        deleteIfExists(contenderPath);
                        throw timeout(request.key());
                    }
                    String predecessorPath = watchedPredecessor(request.key().value(), nodes, current);
                    if (predecessorPath == null) {
                        continue;
                    }
                    boolean deleted = awaitNodeDeletion(predecessorPath, remainingNanos);
                    if (waitMode != WaitMode.INDEFINITE && !deleted) {
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
                    try {
                        deleteIfExists(contenderPath);
                    } catch (RuntimeException cleanupFailure) {
                        exception.addSuppressed(cleanupFailure);
                    }
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

        private String createContenderNode(LockRequest request) throws Exception {
            String prefix = switch (request.mode()) {
                case MUTEX -> "mutex-";
                case READ -> "read-";
                case WRITE -> "write-";
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
            if (isExclusive(current.mode())) {
                return nodes.stream()
                    .takeWhile(node -> node.sequence() < current.sequence())
                    .findAny()
                    .isEmpty();
            }
            return nodes.stream()
                .takeWhile(node -> node.sequence() < current.sequence())
                .noneMatch(node -> isExclusive(node.mode()));
        }

        private String watchedPredecessor(String key, List<QueueNode> nodes, QueueNode current) {
            if (isExclusive(current.mode())) {
                return nodes.stream()
                    .filter(node -> node.sequence() < current.sequence())
                    .max(Comparator.comparingLong(QueueNode::sequence))
                    .map(node -> queueRootPath(key) + "/" + node.name())
                    .orElse(null);
            }
            return nodes.stream()
                .filter(node -> node.sequence() < current.sequence() && isExclusive(node.mode()))
                .max(Comparator.comparingLong(QueueNode::sequence))
                .map(node -> queueRootPath(key) + "/" + node.name())
                .orElse(null);
        }

        private boolean isExclusive(LockMode mode) {
            return mode == LockMode.MUTEX || mode == LockMode.WRITE;
        }

        private boolean awaitNodeDeletion(String path, long remainingNanos) throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            CuratorWatcher watcher = event -> latch.countDown();
            Stat stat = curatorFramework.checkExists().usingWatcher(watcher).forPath(path);
            if (stat == null) {
                return true;
            }
            long waitNanos = remainingNanos == Long.MAX_VALUE
                ? TimeUnit.MILLISECONDS.toNanos(250L)
                : Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(250L));
            synchronized (terminalMonitor) {
                if (state.get() != SessionState.ACTIVE) {
                    ensureActive();
                }
                terminalMonitor.wait(TimeUnit.NANOSECONDS.toMillis(waitNanos), (int) (waitNanos % 1_000_000L));
            }
            ensureActive();
            return latch.getCount() == 0L || curatorFramework.checkExists().forPath(path) == null;
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
            SessionState current = state.get();
            if (current == SessionState.CLOSED) {
                throw new IllegalStateException("ZooKeeper session is already closed");
            }
            if (current == SessionState.LOST) {
                throw new LockSessionLostException("ZooKeeper session lost: " + sessionId);
            }
        }

        private long remainingNanos(long deadlineNanos) {
            return deadlineNanos == Long.MAX_VALUE ? Long.MAX_VALUE : deadlineNanos - System.nanoTime();
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

        private RuntimeException lossCause() {
            RuntimeException cause = lossCause.get();
            return cause != null ? cause : new LockSessionLostException("ZooKeeper session lost: " + sessionId);
        }

        private void markSessionLost(RuntimeException cause) {
            lossCause.compareAndSet(null, cause);
            if (!state.compareAndSet(SessionState.ACTIVE, SessionState.LOST)) {
                return;
            }
            signalTerminalWaiters();
            for (ZooKeeperLease lease : new ArrayList<>(activeLeases.values())) {
                lease.markLost();
            }
        }

        private void signalTerminalWaiters() {
            synchronized (terminalMonitor) {
                terminalMonitor.notifyAll();
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
            if (session.state() != SessionState.ACTIVE) {
                markLost();
                return false;
            }
            if (!ownerNodeStillBelongsToSession()) {
                markLost();
                return false;
            }
            return true;
        }

        @Override
        public void release() {
            release(true);
        }

        private void releaseDuringSessionClose() {
            release(false);
        }

        private void release(boolean requireActiveSession) {
            LeaseState current = state.get();
            if (current == LeaseState.RELEASED) {
                return;
            }
            if (current == LeaseState.LOST) {
                session.forgetLease(this);
                throw new LockOwnershipLostException("ZooKeeper lock ownership lost for key " + key.value());
            }
            if (requireActiveSession && session.state() != SessionState.ACTIVE) {
                markLost();
                throw new LockOwnershipLostException("ZooKeeper lock ownership lost for key " + key.value());
            }

            try {
                Stat stat = session.curatorFramework.checkExists().forPath(ownerPath);
                if (stat == null || !ownerNodeStillBelongsToSession()) {
                    markLost();
                    throw new LockOwnershipLostException("ZooKeeper lock ownership lost for key " + key.value());
                }
                session.curatorFramework.delete().forPath(ownerPath);
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
                byte[] currentData = session.curatorFramework.getData().forPath(ownerPath);
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

    private QueueNode queueNode(String name) {
        if (name.startsWith("mutex-")) {
            return new QueueNode(name, LockMode.MUTEX, sequence(name));
        }
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
