package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.framework.recipes.locks.InterProcessReadWriteLock;
import org.apache.curator.retry.ExponentialBackoffRetry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class ZooKeeperLockBackend implements LockBackend, AutoCloseable {

    private final ZooKeeperBackendConfiguration configuration;
    private final CuratorFramework curatorFramework;
    private final BooleanSupplier sessionValidSupplier;
    private final Map<String, InterProcessMutex> mutexes = new ConcurrentHashMap<>();
    private final Map<String, InterProcessReadWriteLock> readWriteLocks = new ConcurrentHashMap<>();

    public ZooKeeperLockBackend(ZooKeeperBackendConfiguration configuration) {
        this(configuration, null);
    }

    ZooKeeperLockBackend(ZooKeeperBackendConfiguration configuration, BooleanSupplier sessionValidSupplier) {
        this.configuration = configuration;
        this.curatorFramework = CuratorFrameworkFactory.newClient(
            configuration.connectString(),
            new ExponentialBackoffRetry(1_000, 3)
        );
        this.curatorFramework.start();
        this.sessionValidSupplier = sessionValidSupplier != null
            ? sessionValidSupplier
            : () -> curatorFramework.getZookeeperClient().isConnected();
        try {
            this.curatorFramework.blockUntilConnected(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LockBackendException("Interrupted while connecting to ZooKeeper", exception);
        }
    }

    @Override
    public BackendLockLease acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) throws InterruptedException {
        return switch (mode) {
            case MUTEX -> acquireMutex(resource, waitPolicy);
            case READ -> acquireRead(resource, waitPolicy);
            case WRITE -> acquireWrite(resource, waitPolicy);
        };
    }

    private void releaseLease(ZooKeeperLease lease) {
        if (!lease.isValidForCurrentExecution()) {
            throw new LockOwnershipLostException("ZooKeeper lock ownership lost for key " + lease.key());
        }
        try {
            lease.acquiredLock.release();
        } catch (LockOwnershipLostException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new LockBackendException("Failed to release ZooKeeper lock for key " + lease.key(), exception);
        }
    }

    private boolean isLeaseValid(ZooKeeperLease lease) {
        if (lease.threadId != Thread.currentThread().getId()) {
            return false;
        }
        return sessionValidSupplier.getAsBoolean() && lease.acquiredLock.isOwnedByCurrentThread();
    }

    @Override
    public void close() {
        curatorFramework.close();
    }

    private ZooKeeperLease acquireMutex(LockResource resource, WaitPolicy waitPolicy) throws InterruptedException {
        try {
            InterProcessMutex lock = mutex(resourcePath("mutex", resource.key()));
            if (waitPolicy.unbounded()) {
                lock.acquire();
                return new ZooKeeperLease(resource.key(), LockMode.MUTEX, Thread.currentThread().getId(), lock);
            }
            if (!lock.acquire(waitPolicy.waitTime().toMillis(), TimeUnit.MILLISECONDS)) {
                return null;
            }
            return new ZooKeeperLease(resource.key(), LockMode.MUTEX, Thread.currentThread().getId(), lock);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (Exception exception) {
            throw new LockBackendException("Failed to acquire ZooKeeper mutex for key " + resource.key(), exception);
        }
    }

    private ZooKeeperLease acquireRead(LockResource resource, WaitPolicy waitPolicy) throws InterruptedException {
        try {
            InterProcessMutex lock = readWrite(resourcePath("rw", resource.key())).readLock();
            if (waitPolicy.unbounded()) {
                lock.acquire();
                return new ZooKeeperLease(resource.key(), LockMode.READ, Thread.currentThread().getId(), lock);
            }
            if (!lock.acquire(waitPolicy.waitTime().toMillis(), TimeUnit.MILLISECONDS)) {
                return null;
            }
            return new ZooKeeperLease(resource.key(), LockMode.READ, Thread.currentThread().getId(), lock);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (Exception exception) {
            throw new LockBackendException("Failed to acquire ZooKeeper read lock for key " + resource.key(), exception);
        }
    }

    private ZooKeeperLease acquireWrite(LockResource resource, WaitPolicy waitPolicy) throws InterruptedException {
        try {
            InterProcessMutex lock = readWrite(resourcePath("rw", resource.key())).writeLock();
            if (waitPolicy.unbounded()) {
                lock.acquire();
                return new ZooKeeperLease(resource.key(), LockMode.WRITE, Thread.currentThread().getId(), lock);
            }
            if (!lock.acquire(waitPolicy.waitTime().toMillis(), TimeUnit.MILLISECONDS)) {
                return null;
            }
            return new ZooKeeperLease(resource.key(), LockMode.WRITE, Thread.currentThread().getId(), lock);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (Exception exception) {
            throw new LockBackendException("Failed to acquire ZooKeeper write lock for key " + resource.key(), exception);
        }
    }

    private InterProcessMutex mutex(String path) {
        return mutexes.computeIfAbsent(path, ignored -> new InterProcessMutex(curatorFramework, path));
    }

    private InterProcessReadWriteLock readWrite(String path) {
        return readWriteLocks.computeIfAbsent(path, ignored -> new InterProcessReadWriteLock(curatorFramework, path));
    }

    private String resourcePath(String kind, String key) {
        String normalizedKey = key.replace(':', '_').replace('/', '_');
        return configuration.basePath() + "/" + kind + "/" + normalizedKey;
    }

    private final class ZooKeeperLease implements BackendLockLease {

        private final String key;
        private final LockMode mode;
        private final long threadId;
        private final InterProcessMutex acquiredLock;

        private ZooKeeperLease(String key, LockMode mode, long threadId, InterProcessMutex acquiredLock) {
            this.key = key;
            this.mode = mode;
            this.threadId = threadId;
            this.acquiredLock = acquiredLock;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public LockMode mode() {
            return mode;
        }

        @Override
        public boolean isValidForCurrentExecution() {
            return isLeaseValid(this);
        }

        @Override
        public void release() {
            releaseLease(this);
        }
    }
}
