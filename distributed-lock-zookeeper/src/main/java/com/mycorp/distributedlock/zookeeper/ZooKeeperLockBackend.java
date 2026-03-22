package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.core.backend.BackendLockHandle;
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

public final class ZooKeeperLockBackend implements LockBackend, AutoCloseable {

    private final ZooKeeperBackendConfiguration configuration;
    private final CuratorFramework curatorFramework;
    private final Map<String, InterProcessMutex> mutexes = new ConcurrentHashMap<>();
    private final Map<String, InterProcessReadWriteLock> readWriteLocks = new ConcurrentHashMap<>();

    public ZooKeeperLockBackend(ZooKeeperBackendConfiguration configuration) {
        this.configuration = configuration;
        this.curatorFramework = CuratorFrameworkFactory.newClient(
            configuration.connectString(),
            new ExponentialBackoffRetry(1_000, 3)
        );
        this.curatorFramework.start();
        try {
            this.curatorFramework.blockUntilConnected(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LockBackendException("Interrupted while connecting to ZooKeeper", exception);
        }
    }

    @Override
    public BackendLockHandle acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) throws InterruptedException {
        boolean acquired = switch (mode) {
            case MUTEX -> acquireMutex(resource, waitPolicy);
            case READ -> acquireRead(resource, waitPolicy);
            case WRITE -> acquireWrite(resource, waitPolicy);
        };
        if (!acquired) {
            return null;
        }
        return new ZooKeeperBackendHandle(resource.key(), mode, Thread.currentThread().getId());
    }

    @Override
    public void release(BackendLockHandle handle) {
        if (!(handle instanceof ZooKeeperBackendHandle zooKeeperHandle)) {
            throw new LockBackendException("Unsupported backend handle: " + handle);
        }

        try {
            switch (zooKeeperHandle.mode()) {
                case MUTEX -> mutex(resourcePath("mutex", zooKeeperHandle.key())).release();
                case READ -> readWrite(resourcePath("rw", zooKeeperHandle.key())).readLock().release();
                case WRITE -> readWrite(resourcePath("rw", zooKeeperHandle.key())).writeLock().release();
            }
        } catch (Exception exception) {
            throw new LockBackendException("Failed to release ZooKeeper lock for key " + zooKeeperHandle.key(), exception);
        }
    }

    @Override
    public boolean isHeldByCurrentExecution(BackendLockHandle handle) {
        if (!(handle instanceof ZooKeeperBackendHandle zooKeeperHandle)) {
            return false;
        }
        if (zooKeeperHandle.threadId() != Thread.currentThread().getId()) {
            return false;
        }

        return switch (zooKeeperHandle.mode()) {
            case MUTEX -> mutex(resourcePath("mutex", zooKeeperHandle.key())).isOwnedByCurrentThread();
            case READ -> readWrite(resourcePath("rw", zooKeeperHandle.key())).readLock().isOwnedByCurrentThread();
            case WRITE -> readWrite(resourcePath("rw", zooKeeperHandle.key())).writeLock().isOwnedByCurrentThread();
        };
    }

    @Override
    public void close() {
        curatorFramework.close();
    }

    private boolean acquireMutex(LockResource resource, WaitPolicy waitPolicy) throws InterruptedException {
        try {
            InterProcessMutex lock = mutex(resourcePath("mutex", resource.key()));
            if (waitPolicy.unbounded()) {
                lock.acquire();
                return true;
            }
            return lock.acquire(waitPolicy.waitTime().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (Exception exception) {
            throw new LockBackendException("Failed to acquire ZooKeeper mutex for key " + resource.key(), exception);
        }
    }

    private boolean acquireRead(LockResource resource, WaitPolicy waitPolicy) throws InterruptedException {
        try {
            InterProcessMutex lock = readWrite(resourcePath("rw", resource.key())).readLock();
            if (waitPolicy.unbounded()) {
                lock.acquire();
                return true;
            }
            return lock.acquire(waitPolicy.waitTime().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (Exception exception) {
            throw new LockBackendException("Failed to acquire ZooKeeper read lock for key " + resource.key(), exception);
        }
    }

    private boolean acquireWrite(LockResource resource, WaitPolicy waitPolicy) throws InterruptedException {
        try {
            InterProcessMutex lock = readWrite(resourcePath("rw", resource.key())).writeLock();
            if (waitPolicy.unbounded()) {
                lock.acquire();
                return true;
            }
            return lock.acquire(waitPolicy.waitTime().toMillis(), TimeUnit.MILLISECONDS);
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

    private record ZooKeeperBackendHandle(String key, LockMode mode, long threadId) implements BackendLockHandle {
    }
}
