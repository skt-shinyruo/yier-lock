package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitMode;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.api.exception.LockFailureContext;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.api.exception.LockSessionLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class ZooKeeperBackendSession implements BackendSession, CuratorBackedSession {

    private final String sessionId;
    private final CuratorFramework curatorFramework;
    private final ZooKeeperPathLayout pathLayout;
    private final ZooKeeperLockBackend backend;
    private final ZooKeeperQueue queue;
    private final ZooKeeperFencingCounter fencingCounter;
    private final ConcurrentMap<String, ZooKeeperLease> activeLeases = new ConcurrentHashMap<>();
    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.ACTIVE);
    private final AtomicReference<RuntimeException> lossCause = new AtomicReference<>();
    private final Object terminalMonitor = new Object();
    private final Object leaseRegistrationMonitor = new Object();

    ZooKeeperBackendSession(
        String sessionId,
        CuratorFramework curatorFramework,
        ZooKeeperPathLayout pathLayout,
        ZooKeeperLockBackend backend
    ) {
        this.sessionId = sessionId;
        this.curatorFramework = curatorFramework;
        this.pathLayout = pathLayout;
        this.backend = backend;
        this.queue = new ZooKeeperQueue(curatorFramework, pathLayout);
        this.fencingCounter = new ZooKeeperFencingCounter(curatorFramework, pathLayout);
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

    String sessionId() {
        return sessionId;
    }

    @Override
    public BackendLockLease acquire(LockRequest request) throws InterruptedException {
        ensureActive(request);
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
                failure = appendFailure(failure, exception);
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
        }
        curatorFramework.close();
        if (failure != null) {
            throw failure;
        }
        if (current == SessionState.LOST) {
            throw lossCause();
        }
    }

    private ZooKeeperLease acquireQueued(LockRequest request) throws InterruptedException {
        String contenderPath = null;
        try {
            ensureActive(request);
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
                ensureActive(request);
                List<ZooKeeperQueueNode> nodes = queue.nodes(request.key().value());
                ZooKeeperQueueNode current = queue.currentNode(nodes, nodeName);
                if (current == null) {
                    throw new LockSessionLostException(
                        "ZooKeeper contender node disappeared for key " + request.key().value(),
                        null,
                        LockFailureContext.fromRequest(request, "zookeeper", sessionId)
                    );
                }
                if (queue.canAcquire(nodes, current)) {
                    verifyContenderStillOwned(contenderPath, nodeOwnerData, request);
                    backend.beforeFenceIssued(contenderPath);
                    ensureActive(request);
                    long fence = fencingCounter.nextFence(request, sessionId, () -> ensureActive(request));
                    ensureActive(request);
                    verifyContenderStillOwned(contenderPath, nodeOwnerData, request);
                    List<ZooKeeperQueueNode> afterFenceNodes = queue.nodes(request.key().value());
                    ZooKeeperQueueNode afterFenceCurrent = queue.currentNode(afterFenceNodes, nodeName);
                    if (afterFenceCurrent == null || !queue.canAcquire(afterFenceNodes, afterFenceCurrent)) {
                        throw new LockSessionLostException(
                            "ZooKeeper contender node no longer owns lock for key " + request.key().value(),
                            null,
                            LockFailureContext.fromRequest(request, "zookeeper", sessionId)
                        );
                    }
                    ensureActive(request);
                    ZooKeeperLease lease = new ZooKeeperLease(
                        request.key(),
                        request.mode(),
                        new FencingToken(fence),
                        contenderPath,
                        nodeOwnerData,
                        this
                    );
                    registerActiveLease(lease, request);
                    return lease;
                }
                long remainingNanos = remainingNanos(deadlineNanos);
                if (waitMode == WaitMode.TRY_ONCE || remainingNanos <= 0L) {
                    LockAcquisitionTimeoutException failure = timeout(request, sessionId);
                    try {
                        deleteIfExists(contenderPath);
                    } catch (RuntimeException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                    throw failure;
                }
                String predecessorPath = queue.watchedPredecessor(request.key().value(), nodes, current);
                if (predecessorPath == null) {
                    continue;
                }
                awaitNodeDeletion(predecessorPath, remainingNanos, request);
            }
        } catch (InterruptedException exception) {
            if (contenderPath != null) {
                try {
                    deleteIfExists(contenderPath);
                } catch (RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
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
            if (exception instanceof LockBackendException backendException) {
                throw withContext(backendException, request);
            }
            throw exception;
        } catch (Exception exception) {
            LockBackendException failure = new LockBackendException(
                "Failed to acquire ZooKeeper lock for key " + request.key().value(),
                exception
            );
            if (contenderPath != null) {
                try {
                    deleteIfExists(contenderPath);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw withContext(failure, request);
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
            .forPath(pathLayout.queuePath(request.key()) + "/" + prefix, ownerData(sessionId));
    }

    private void verifyContenderStillOwned(String contenderPath, byte[] ownerData, LockRequest request) throws Exception {
        try {
            byte[] currentData = curatorFramework.getData().forPath(contenderPath);
            if (!Arrays.equals(currentData, ownerData)) {
                throw new LockSessionLostException(
                    "ZooKeeper contender node ownership changed for key " + request.key().value(),
                    null,
                    LockFailureContext.fromRequest(request, "zookeeper", sessionId)
                );
            }
        } catch (KeeperException.NoNodeException exception) {
            throw new LockSessionLostException(
                "ZooKeeper contender node disappeared for key " + request.key().value(),
                exception,
                LockFailureContext.fromRequest(request, "zookeeper", sessionId)
            );
        }
    }

    private boolean awaitNodeDeletion(String path, long remainingNanos, LockRequest request) throws Exception {
        AtomicBoolean predecessorDeleted = new AtomicBoolean(false);
        CuratorWatcher watcher = event -> {
            predecessorDeleted.set(true);
            signalTerminalWaiters();
        };
        Stat stat = curatorFramework.checkExists().usingWatcher(watcher).forPath(path);
        if (stat == null) {
            return true;
        }
        long waitNanos = remainingNanos == Long.MAX_VALUE
            ? TimeUnit.MILLISECONDS.toNanos(250L)
            : Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(250L));
        synchronized (terminalMonitor) {
            if (state.get() != SessionState.ACTIVE) {
                ensureActive(request);
            }
            if (!predecessorDeleted.get()) {
                terminalMonitor.wait(TimeUnit.NANOSECONDS.toMillis(waitNanos), (int) (waitNanos % 1_000_000L));
            }
        }
        ensureActive(request);
        return predecessorDeleted.get() || curatorFramework.checkExists().forPath(path) == null;
    }

    void forgetLease(ZooKeeperLease lease) {
        activeLeases.remove(lease.ownerPath());
    }

    private void registerActiveLease(ZooKeeperLease lease, LockRequest request) {
        synchronized (leaseRegistrationMonitor) {
            ensureActive(request);
            backend.beforeLeaseRegistered(lease.ownerPath());
            activeLeases.put(lease.ownerPath(), lease);
            try {
                ensureActive(request);
            } catch (RuntimeException exception) {
                activeLeases.remove(lease.ownerPath(), lease);
                lease.markLost();
                throw exception;
            }
        }
    }

    private void deleteIfExists(String path) {
        try {
            curatorFramework.delete().forPath(path);
        } catch (KeeperException.NoNodeException ignored) {
        } catch (Exception exception) {
            throw new LockBackendException("Failed to delete ZooKeeper node " + path, exception);
        }
    }

    private void ensureActive(LockRequest request) {
        SessionState current = state.get();
        if (current == SessionState.CLOSED) {
            throw new IllegalStateException("ZooKeeper session is already closed");
        }
        if (current == SessionState.LOST) {
            throw new LockSessionLostException(
                "ZooKeeper session lost: " + sessionId,
                null,
                LockFailureContext.fromRequest(request, "zookeeper", sessionId)
            );
        }
    }

    private long remainingNanos(long deadlineNanos) {
        return deadlineNanos == Long.MAX_VALUE ? Long.MAX_VALUE : deadlineNanos - System.nanoTime();
    }

    private LockBackendException withContext(LockBackendException exception, LockRequest request) {
        if (!exception.context().equals(LockFailureContext.empty())) {
            return exception;
        }
        LockBackendException contextual = new LockBackendException(
            exception.getMessage(),
            exception.getCause(),
            LockFailureContext.fromRequest(request, "zookeeper", sessionId)
        );
        for (Throwable suppressed : exception.getSuppressed()) {
            contextual.addSuppressed(suppressed);
        }
        return contextual;
    }

    private RuntimeException lossCause() {
        RuntimeException cause = lossCause.get();
        return cause != null ? cause : new LockSessionLostException("ZooKeeper session lost: " + sessionId);
    }

    private void markSessionLost(RuntimeException cause) {
        synchronized (leaseRegistrationMonitor) {
            lossCause.compareAndSet(null, cause);
            if (!state.compareAndSet(SessionState.ACTIVE, SessionState.LOST)) {
                return;
            }
            signalTerminalWaiters();
            for (ZooKeeperLease lease : new ArrayList<>(activeLeases.values())) {
                lease.markLost();
            }
        }
    }

    private void signalTerminalWaiters() {
        synchronized (terminalMonitor) {
            terminalMonitor.notifyAll();
        }
    }

    private RuntimeException appendFailure(RuntimeException failure, RuntimeException exception) {
        if (failure == null) {
            return exception;
        }
        failure.addSuppressed(exception);
        return failure;
    }

    private static byte[] ownerData(String sessionId) {
        return sessionId.getBytes(StandardCharsets.UTF_8);
    }

    private static String nodeName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static LockAcquisitionTimeoutException timeout(LockRequest request, String sessionId) {
        return new LockAcquisitionTimeoutException(
            "Timed out acquiring ZooKeeper lock for key " + request.key().value(),
            null,
            LockFailureContext.fromRequest(request, "zookeeper", sessionId)
        );
    }
}
