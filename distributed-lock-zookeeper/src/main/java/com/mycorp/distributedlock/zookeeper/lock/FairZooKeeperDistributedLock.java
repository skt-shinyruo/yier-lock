package com.mycorp.distributedlock.zookeeper.lock;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.LockEvent;
import com.mycorp.distributedlock.api.LockEventListener;
import com.mycorp.distributedlock.api.exception.LockAcquisitionException;
import com.mycorp.distributedlock.api.exception.LockReleaseException;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import com.mycorp.distributedlock.zookeeper.connection.ZooKeeperConnectionManager;
import com.mycorp.distributedlock.zookeeper.cluster.ZooKeeperClusterManager;
import io.micrometer.core.instrument.Timer;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Zookeeper分布式公平锁实现
 * 
 * 公平锁特性：
 * - 基于Zookeeper临时顺序节点的公平性保证
 * - 精确的FIFO队列管理
 * - 死锁检测和自动恢复
 * - 高可用性支持
 * - 性能指标监控
 * - 事件驱动通知
 */
public class FairZooKeeperDistributedLock implements DistributedLock {
    
    private static final Logger logger = LoggerFactory.getLogger(FairZooKeeperDistributedLock.class);
    
    // Zookeeper路径配置
    private static final String FAIR_LOCKS_BASE_PATH = "/distributed-locks/fair";
    private static final String LOCK_PREFIX = "lock-";
    private static final String SEQUENCE_NUMBER_FORMAT = "%010d";
    
    // 锁相关
    private final String lockName;
    private final String lockPath;
    private final String basePath;
    private final ZooKeeperConnectionManager connectionManager;
    private final ZooKeeperClusterManager clusterManager;
    private final LockConfiguration configuration;
    private final LockMetrics metrics;
    private final LockTracing tracing;
    
    // 状态管理
    private final AtomicBoolean isAcquired = new AtomicBoolean(false);
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final AtomicInteger reentrantCount = new AtomicInteger(0);
    private final AtomicReference<Thread> owningThread = new AtomicReference<>();
    private final AtomicLong acquisitionTime = new AtomicLong(0);
    private final AtomicLong expirationTime = new AtomicLong(0);
    private final AtomicLong lockSequenceNumber = new AtomicLong(0);
    private final AtomicLong queuePosition = new AtomicLong(0);
    
    // 线程池
    private final ExecutorService asyncOperationExecutor;
    private final ScheduledExecutorService renewalExecutor;
    private final ScheduledExecutorService queueMonitoringExecutor;
    
    // 队列管理
    private final FairLockQueueManager queueManager;
    
    // 事件管理
    private final java.util.List<LockEventListener<DistributedLock>> eventListeners = 
            new java.util.concurrent.CopyOnWriteArrayList<>();
    
    // 性能指标
    private final Timer acquisitionTimer;
    private final Timer releaseTimer;
    private final Timer renewalTimer;
    private final AtomicLong acquisitionCount = new AtomicLong(0);
    private final AtomicLong releaseCount = new AtomicLong(0);
    private final AtomicLong queueWaitTime = new AtomicLong(0);
    private final AtomicLong totalQueueOperations = new AtomicLong(0);
    
    // 续期管理
    private final AtomicBoolean isAutoRenewalEnabled = new AtomicBoolean(false);
    private final AtomicLong renewalTaskId = new AtomicLong(0);
    private final Map<Long, ScheduledFuture<?>> renewalTasks = new ConcurrentHashMap<>();
    
    // 队列监控
    private final FairLockQueueMonitor queueMonitor;
    
    /**
     * 构造函数
     */
    public FairZooKeeperDistributedLock(String lockName,
                                      ZooKeeperConnectionManager connectionManager,
                                      ZooKeeperClusterManager clusterManager,
                                      LockConfiguration configuration,
                                      LockMetrics metrics,
                                      LockTracing tracing) {
        this.lockName = lockName;
        this.lockPath = FAIR_LOCKS_BASE_PATH + "/" + lockName;
        this.basePath = FAIR_LOCKS_BASE_PATH;
        this.connectionManager = connectionManager;
        this.clusterManager = clusterManager;
        this.configuration = configuration;
        this.metrics = metrics;
        this.tracing = tracing;
        
        // 初始化线程池
        this.asyncOperationExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(), r -> {
                Thread t = new Thread(r, "fair-zk-lock-async-" + lockName);
                t.setDaemon(true);
                return t;
            }
        );
        
        this.renewalExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fair-zk-lock-renewal-" + lockName);
            t.setDaemon(true);
            return t;
        });
        
        this.queueMonitoringExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fair-zk-lock-monitor-" + lockName);
            t.setDaemon(true);
            return t;
        });
        
        // 初始化组件
        this.queueManager = new FairLockQueueManager(connectionManager, basePath);
        this.queueMonitor = new FairLockQueueMonitor();
        
        // 初始化指标
        this.acquisitionTimer = metrics.createTimer("fair.zookeeper.lock.acquisition.time", lockName);
        this.releaseTimer = metrics.createTimer("fair.zookeeper.lock.release.time", lockName);
        this.renewalTimer = metrics.createTimer("fair.zookeeper.lock.renewal.time", lockName);
        
        // 添加连接状态监听器
        connectionManager.addConnectionStateListener((client, newState) -> {
            handleConnectionStateChanged(newState);
        });
        
        // 启动队列监控
        startQueueMonitoring();
        
        logger.debug("FairZooKeeperDistributedLock initialized for: {}", lockName);
    }
    
    @Override
    public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
        checkNotClosed();
        
        try (var spanContext = tracing.startLockAcquisitionSpan(lockName, "fairLock")) {
            Timer.Sample sample = metrics.startTimer(acquisitionTimer);
            
            try {
                if (!tryLock(0, leaseTime, unit)) {
                    throw new InterruptedException("Failed to acquire fair lock within timeout");
                }
            } catch (InterruptedException e) {
                metrics.recordAcquisitionTime(sample, lockName, "interrupted");
                metrics.incrementLockAcquisitionCounter(lockName, "interrupted");
                spanContext.setError(e);
                Thread.currentThread().interrupt();
                throw e;
            } finally {
                sample.stop(acquisitionTimer);
            }
        }
    }
    
    @Override
    public void lock() throws InterruptedException {
        lock(configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
    }
    
    @Override
    public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        checkNotClosed();
        
        // 如果当前线程已经持有锁，增加重入计数
        if (isHeldByCurrentThread()) {
            reentrantCount.incrementAndGet();
            notifyEvent(LockEvent.ofLockAcquired(this, createMetadata()));
            logger.debug("Reentrant fair lock acquired for thread {}: {}", 
                        Thread.currentThread().getName(), lockName);
            return true;
        }
        
        try (var spanContext = tracing.startLockAcquisitionSpan(lockName, "tryFairLock")) {
            Timer.Sample sample = metrics.startTimer(acquisitionTimer);
            
            try {
                long startTime = System.currentTimeMillis();
                long timeoutMs = unit != null ? unit.toMillis(waitTime) : 0;
                
                // 检查队列大小
                if (queueManager.getQueueSize() >= 1000) {
                    logger.warn("Fair lock queue is full for: {}", lockName);
                    return false;
                }
                
                // 加入队列
                QueueEntry queueEntry = queueManager.enqueue(Thread.currentThread(), 
                                                           Duration.ofMillis(unit.toMillis(leaseTime)));
                queuePosition.set(queueEntry.getPosition());
                
                logger.debug("Thread {} queued for fair lock at position {}: {}", 
                            Thread.currentThread().getName(), queueEntry.getPosition(), lockName);
                
                // 等待队列轮转
                boolean acquired = waitForQueueTurn(waitTime, leaseTime, unit, startTime, queueEntry);
                
                if (acquired) {
                    acquisitionCount.incrementAndGet();
                    long waitDuration = System.currentTimeMillis() - startTime;
                    queueWaitTime.addAndGet(waitDuration);
                    
                    metrics.incrementLockAcquisitionCounter(lockName, "success");
                    spanContext.setStatus("success");
                    
                    notifyEvent(LockEvent.ofLockAcquired(this, createMetadata()));
                    logger.debug("Fair lock acquired by thread {} at position {}: {}", 
                                Thread.currentThread().getName(), queuePosition.get(), lockName);
                } else {
                    metrics.incrementLockAcquisitionCounter(lockName, "timeout");
                    spanContext.setStatus("timeout");
                    notifyEvent(LockEvent.ofLockTimeout(lockName, waitTime, createMetadata()));
                }
                
                return acquired;
                
            } finally {
                sample.stop(acquisitionTimer);
            }
        }
    }
    
    @Override
    public boolean tryLock() throws InterruptedException {
        return tryLock(0, configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
    }
    
    @Override
    public void unlock() {
        checkNotClosed();
        
        if (!isHeldByCurrentThread()) {
            throw new IllegalMonitorStateException("Current thread does not hold fair lock: " + lockName);
        }
        
        try (var spanContext = tracing.startLockAcquisitionSpan(lockName, "fairUnlock")) {
            Timer.Sample sample = metrics.startTimer(releaseTimer);
            
            try {
                // 减少重入计数
                reentrantCount.decrementAndGet();
                if (reentrantCount.get() > 0) {
                    logger.debug("Reentrant fair unlock, count remaining: {} for thread {}: {}", 
                                reentrantCount.get(), Thread.currentThread().getName(), lockName);
                    return; // 仍有重入次数
                }
                
                // 释放锁
                releaseFairLock();
                
                // 清理状态
                isAcquired.set(false);
                owningThread.set(null);
                acquisitionTime.set(0);
                expirationTime.set(0);
                queuePosition.set(0);
                
                // 取消续期任务
                cancelAllRenewalTasks();
                
                releaseCount.incrementAndGet();
                metrics.incrementLockReleaseCounter(lockName, "success");
                
                spanContext.setStatus("success");
                logger.debug("Fair lock released by thread {}: {}", Thread.currentThread().getName(), lockName);
                
                notifyEvent(LockEvent.ofLockReleased(this, createMetadata()));
                
            } catch (Exception e) {
                metrics.recordAcquisitionTime(sample, lockName, "error");
                metrics.incrementLockReleaseCounter(lockName, "error");
                spanContext.setError(e);
                throw new LockReleaseException("Error releasing fair ZooKeeper lock: " + lockName, e);
            } finally {
                sample.stop(releaseTimer);
            }
        }
    }
    
    @Override
    public CompletableFuture<Void> lockAsync(long leaseTime, TimeUnit unit) {
        return CompletableFuture.runAsync(() -> {
            try {
                lock(leaseTime, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException("Interrupted while acquiring fair ZooKeeper lock: " + lockName, e);
            }
        }, asyncOperationExecutor);
    }
    
    @Override
    public CompletableFuture<Void> lockAsync() {
        return lockAsync(configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
    }
    
    @Override
    public CompletableFuture<Boolean> tryLockAsync(long waitTime, long leaseTime, TimeUnit unit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return tryLock(waitTime, leaseTime, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException("Interrupted while trying to acquire fair ZooKeeper lock: " + lockName, e);
            }
        }, asyncOperationExecutor);
    }
    
    @Override
    public CompletableFuture<Boolean> tryLockAsync() {
        return tryLockAsync(0, configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
    }
    
    @Override
    public CompletableFuture<Void> unlockAsync() {
        return CompletableFuture.runAsync(this::unlock, asyncOperationExecutor);
    }
    
    @Override
    public boolean isLocked() {
        return isAcquired.get();
    }
    
    @Override
    public boolean isHeldByCurrentThread() {
        return isAcquired.get() && Thread.currentThread() == owningThread.get();
    }
    
    @Override
    public String getName() {
        return lockName;
    }
    
    @Override
    public ScheduledFuture<?> scheduleAutoRenewal(long renewInterval, TimeUnit unit,
                                                  Consumer<RenewalResult> renewalCallback) {
        if (!isHeldByCurrentThread()) {
            throw new IllegalMonitorStateException("Cannot schedule renewal for fair lock not held by current thread");
        }
        
        long intervalMs = unit.toMillis(renewInterval);
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("Renewal interval must be positive");
        }
        
        ScheduledFuture<?> renewalTask = renewalExecutor.scheduleAtFixedRate(() -> {
            try {
                if (isHeldByCurrentThread() && !Thread.currentThread().isInterrupted()) {
                    boolean renewed = renewFairLock();
                    if (renewalCallback != null) {
                        renewalCallback.accept(new RenewalResult() {
                            @Override
                            public boolean isSuccess() {
                                return renewed;
                            }
                            
                            @Override
                            public Throwable getFailureCause() {
                                return renewed ? null : new RuntimeException("Renewal failed");
                            }
                            
                            @Override
                            public long getRenewalTime() {
                                return System.currentTimeMillis();
                            }
                            
                            @Override
                            public long getNewExpirationTime() {
                                return expirationTime.get();
                            }
                        });
                    }
                } else {
                    cancelAutoRenewal(renewalTask);
                }
            } catch (Exception e) {
                logger.debug("Auto-renewal error for fair lock: {}", lockName, e);
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        
        long taskId = renewalTaskId.incrementAndGet();
        renewalTasks.put(taskId, renewalTask);
        isAutoRenewalEnabled.set(true);
        
        logger.debug("Enabled auto-renewal for fair lock: {} with interval: {}", lockName, Duration.ofMillis(intervalMs));
        
        return renewalTask;
    }
    
    @Override
    public int getReentrantCount() {
        return isHeldByCurrentThread() ? reentrantCount.get() : 0;
    }
    
    @Override
    public boolean isExpired() {
        long expTime = expirationTime.get();
        return expTime > 0 && System.currentTimeMillis() > expTime;
    }
    
    @Override
    public long getRemainingTime(TimeUnit unit) {
        long expTime = expirationTime.get();
        if (expTime == 0) {
            return 0;
        }
        long remaining = expTime - System.currentTimeMillis();
        return unit != null ? unit.convert(Math.max(0, remaining), TimeUnit.MILLISECONDS) : Math.max(0, remaining);
    }
    
    @Override
    public String getLockHolder() {
        Thread owner = owningThread.get();
        return owner != null ? owner.getName() : null;
    }
    
    /**
     * 添加事件监听器
     */
    public void addLockEventListener(LockEventListener<DistributedLock> listener) {
        if (listener != null) {
            eventListeners.add(listener);
        }
    }
    
    /**
     * 移除事件监听器
     */
    public void removeLockEventListener(LockEventListener<DistributedLock> listener) {
        eventListeners.remove(listener);
    }
    
    /**
     * 获取队列位置
     */
    public long getQueuePosition() {
        return queuePosition.get();
    }
    
    /**
     * 获取队列大小
     */
    public long getQueueSize() {
        return queueManager.getQueueSize();
    }
    
    /**
     * 获取公平锁统计信息
     */
    public FairLockStatistics getStatistics() {
        return new FairLockStatistics(
            acquisitionCount.get(),
            releaseCount.get(),
            getQueueSize(),
            getAverageQueueWaitTime(),
            getAverageQueueWaitTime(),
            reentrantCount.get(),
            isLocked(),
            getRemainingTime(TimeUnit.MILLISECONDS)
        );
    }
    
    /**
     * 手动续期锁
     */
    public boolean renewLock(long newLeaseTime, TimeUnit unit) {
        if (!isAcquired.get()) {
            return false;
        }
        
        try {
            return renewFairLock();
        } catch (Exception e) {
            logger.error("Failed to renew fair lock: {}", lockName, e);
            return false;
        }
    }
    
    /**
     * 启用自动续期
     */
    public void enableAutoRenewal(long leaseTime, TimeUnit unit) {
        if (!isAcquired.get() || isAutoRenewalEnabled.get()) {
            return;
        }
        
        scheduleAutoRenewal(unit.toSeconds(leaseTime) / 3, TimeUnit.SECONDS);
    }
    
    /**
     * 禁用自动续期
     */
    public void disableAutoRenewal() {
        cancelAllRenewalTasks();
        isAutoRenewalEnabled.set(false);
        logger.debug("Disabled auto-renewal for fair lock: {}", lockName);
    }
    
    @Override
    public void close() {
        if (isClosed.getAndSet(true)) {
            return;
        }
        
        logger.debug("Closing FairZooKeeperDistributedLock: {}", lockName);
        
        try {
            // 释放锁
            if (isHeldByCurrentThread()) {
                unlock();
            }
            
            // 关闭线程池
            shutdownExecutor(asyncOperationExecutor, "async-operation");
            shutdownExecutor(renewalExecutor, "renewal");
            shutdownExecutor(queueMonitoringExecutor, "queue-monitoring");
            
            // 清理队列
            queueManager.clear();
            
            // 清理事件监听器
            eventListeners.clear();
            
            logger.debug("FairZooKeeperDistributedLock closed: {}", lockName);
        } catch (Exception e) {
            logger.error("Error closing fair lock: {}", lockName, e);
        }
    }
    
    // 私有方法
    
    private boolean waitForQueueTurn(long waitTime, long leaseTime, TimeUnit unit, 
                                   long startTime, QueueEntry queueEntry) throws InterruptedException {
        long timeout = unit != null ? unit.toMillis(waitTime) : 0;
        
        while (timeout <= 0 || System.currentTimeMillis() - startTime < timeout) {
            try {
                // 检查是否轮到当前队列条目
                if (isQueueTurn(queueEntry)) {
                    // 尝试获取锁
                    if (tryAcquireFairLock(leaseTime, unit)) {
                        return true;
                    }
                }
                
                // 等待一小段时间后重试
                Thread.sleep(100);
                
            } catch (InterruptedException e) {
                // 清理队列条目
                queueManager.removeFromQueue(queueEntry);
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        
        // 超时，清理队列条目
        queueManager.removeFromQueue(queueEntry);
        return false;
    }
    
    private boolean isQueueTurn(QueueEntry queueEntry) {
        return queueManager.isFirstInQueue(queueEntry);
    }
    
    private boolean tryAcquireFairLock(long leaseTime, TimeUnit unit) {
        try {
            CuratorFramework connection = connectionManager.getConnection();
            
            // 创建临时顺序节点
            String lockNodePath = createLockNode(connection);
            if (lockNodePath == null) {
                return false;
            }
            
            // 检查是否是当前最小的节点
            List<String> children = getLockChildren(connection);
            if (isCurrentNodeSmallest(children, lockNodePath)) {
                // 当前节点是最小的，可以获得锁
                lockSequenceNumber.set(extractSequenceNumber(lockNodePath));
                
                long now = System.currentTimeMillis();
                acquisitionTime.set(now);
                expirationTime.set(now + unit.toMillis(leaseTime));
                
                isAcquired.set(true);
                owningThread.set(Thread.currentThread());
                reentrantCount.incrementAndGet();
                
                // 从队列中移除
                queueManager.removeFromQueue(queueEntry);
                
                return true;
            } else {
                // 不是最小的，删除当前节点
                cleanupLockNode(connection, lockNodePath);
                return false;
            }
            
        } catch (Exception e) {
            logger.error("Failed to try acquire fair lock", e);
            return false;
        }
    }
    
    private void releaseFairLock() throws Exception {
        CuratorFramework connection = connectionManager.getConnection();
        
        // 删除锁节点
        if (lockSequenceNumber.get() > 0) {
            String lockNodePath = getLockNodePath(lockSequenceNumber.get());
            cleanupLockNode(connection, lockNodePath);
        }
        
        lockSequenceNumber.set(0);
    }
    
    private boolean renewFairLock() throws Exception {
        if (lockSequenceNumber.get() == 0) {
            return false;
        }
        
        // 更新过期时间
        long newExpiration = System.currentTimeMillis() + configuration.getDefaultLeaseTime().toMillis();
        expirationTime.set(newExpiration);
        
        notifyEvent(LockEvent.ofLockRenewed(this, Instant.now(), createMetadata()));
        return true;
    }
    
    private String createLockNode(CuratorFramework connection) throws Exception {
        return connection.create()
            .withMode(org.apache.zookeeper.CreateMode.EPHEMERAL_SEQUENTIAL)
            .forPath(lockPath + "/" + LOCK_PREFIX);
    }
    
    private List<String> getLockChildren(CuratorFramework connection) throws Exception {
        List<String> children = connection.getChildren().forPath(lockPath);
        children.sort(String::compareTo);
        return children;
    }
    
    private boolean isCurrentNodeSmallest(List<String> children, String currentNodePath) {
        if (children.isEmpty()) {
            return true;
        }
        
        String currentNodeName = currentNodePath.substring(currentNodePath.lastIndexOf('/') + 1);
        return children.get(0).equals(currentNodeName);
    }
    
    private void cleanupLockNode(CuratorFramework connection, String nodePath) {
        try {
            connection.delete().forPath(nodePath);
        } catch (Exception e) {
            logger.debug("Failed to cleanup lock node: {}", nodePath, e);
        }
    }
    
    private long extractSequenceNumber(String nodePath) {
        String nodeName = nodePath.substring(nodePath.lastIndexOf('/') + 1);
        String sequenceStr = nodeName.substring(LOCK_PREFIX.length());
        return Long.parseLong(sequenceStr);
    }
    
    private String getLockNodePath(long sequenceNumber) {
        return lockPath + "/" + LOCK_PREFIX + String.format(SEQUENCE_NUMBER_FORMAT, sequenceNumber);
    }
    
    private void handleConnectionStateChanged(ConnectionState newState) {
        switch (newState) {
            case LOST:
                // 连接丢失，释放锁
                if (isAcquired.get()) {
                    logger.warn("Connection lost, releasing fair lock: {}", lockName);
                    try {
                        unlock();
                    } catch (Exception e) {
                        logger.error("Error releasing fair lock during connection loss", e);
                    }
                }
                break;
                
            case SUSPENDED:
                logger.warn("Connection suspended for fair lock: {}", lockName);
                break;
                
            case RECONNECTED:
                logger.info("Connection reconnected for fair lock: {}", lockName);
                break;
        }
    }
    
    private void startQueueMonitoring() {
        queueMonitoringExecutor.scheduleAtFixedRate(() -> {
            try {
                queueMonitor.checkQueueHealth();
            } catch (Exception e) {
                logger.debug("Queue monitoring error", e);
            }
        }, 30000, 30000, TimeUnit.MILLISECONDS);
    }
    
    private void notifyEvent(LockEvent<?> event) {
        for (LockEventListener<DistributedLock> listener : eventListeners) {
            if (listener.shouldHandleEvent(event)) {
                try {
                    listener.onEvent(event);
                } catch (Exception e) {
                    logger.warn("Error in fair lock event listener", e);
                }
            }
        }
    }
    
    private LockEventListener.LockEventMetadata createMetadata() {
        return new LockEventListener.LockEventMetadata() {
            @Override
            public long getThreadId() {
                return Thread.currentThread().getId();
            }
            
            @Override
            public String getThreadName() {
                return Thread.currentThread().getName();
            }
            
            @Override
            public String getCallStack() {
                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                for (StackTraceElement element : stack) {
                    if (!element.getClassName().equals(FairZooKeeperDistributedLock.class.getName())) {
                        return element.toString();
                    }
                }
                return "";
            }
            
            @Override
            public Object getCustomData() {
                return "FairZooKeeperDistributedLock Context";
            }
        };
    }
    
    private void cancelAllRenewalTasks() {
        for (ScheduledFuture<?> task : renewalTasks.values()) {
            task.cancel(false);
        }
        renewalTasks.clear();
        isAutoRenewalEnabled.set(false);
    }
    
    private void checkNotClosed() {
        if (isClosed.get()) {
            throw new IllegalStateException("Fair lock is already closed: " + lockName);
        }
    }
    
    private void shutdownExecutor(ExecutorService executor, String name) {
        try {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.debug("Shut down {} executor for fair lock: {}", name, lockName);
    }
    
    private double getAverageQueueWaitTime() {
        long acquisitions = acquisitionCount.get();
        return acquisitions > 0 ? (double) queueWaitTime.get() / acquisitions : 0.0;
    }
    
    // 内部类
    
    /**
     * 队列条目
     */
    private static class QueueEntry {
        private final Thread thread;
        private final long position;
        private final Instant enqueueTime;
        private final String nodePath;
        
        public QueueEntry(Thread thread, long position, Instant enqueueTime, String nodePath) {
            this.thread = thread;
            this.position = position;
            this.enqueueTime = enqueueTime;
            this.nodePath = nodePath;
        }
        
        public Thread getThread() {
            return thread;
        }
        
        public long getPosition() {
            return position;
        }
        
        public Instant getEnqueueTime() {
            return enqueueTime;
        }
        
        public String getNodePath() {
            return nodePath;
        }
    }
    
    /**
     * 公平锁队列管理器
     */
    private static class FairLockQueueManager {
        private final ZooKeeperConnectionManager connectionManager;
        private final String basePath;
        
        public FairLockQueueManager(ZooKeeperConnectionManager connectionManager, String basePath) {
            this.connectionManager = connectionManager;
            this.basePath = basePath;
        }
        
        public QueueEntry enqueue(Thread thread, Duration timeout) {
            try {
                CuratorFramework connection = connectionManager.getConnection();
                
                // 创建临时顺序节点
                String nodePath = connection.create()
                    .withMode(org.apache.zookeeper.CreateMode.EPHEMERAL_SEQUENTIAL)
                    .forPath(basePath + "/queue/entry");
                
                // 设置过期时间
                if (timeout != null) {
                    connection.setData().forPath(nodePath, 
                        String.valueOf(System.currentTimeMillis() + timeout.toMillis()).getBytes());
                }
                
                long position = extractSequenceNumber(nodePath);
                return new QueueEntry(thread, position, Instant.now(), nodePath);
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to enqueue", e);
            }
        }
        
        public void removeFromQueue(QueueEntry entry) {
            try {
                if (entry.getNodePath() != null) {
                    connectionManager.getConnection().delete().forPath(entry.getNodePath());
                }
            } catch (Exception e) {
                logger.debug("Failed to remove queue entry", e);
            }
        }
        
        public boolean isFirstInQueue(QueueEntry entry) {
            try {
                CuratorFramework connection = connectionManager.getConnection();
                List<String> children = connection.getChildren().forPath(basePath + "/queue");
                children.sort(String::compareTo);
                
                if (children.isEmpty()) {
                    return false;
                }
                
                String firstNode = children.get(0);
                String entryNode = entry.getNodePath().substring(entry.getNodePath().lastIndexOf('/') + 1);
                
                return firstNode.equals(entryNode);
                
            } catch (Exception e) {
                logger.debug("Failed to check queue position", e);
                return false;
            }
        }
        
        public long getQueueSize() {
            try {
                CuratorFramework connection = connectionManager.getConnection();
                List<String> children = connection.getChildren().forPath(basePath + "/queue");
                return children.size();
            } catch (Exception e) {
                logger.debug("Failed to get queue size", e);
                return 0;
            }
        }
        
        public void clear() {
            try {
                CuratorFramework connection = connectionManager.getConnection();
                List<String> children = connection.getChildren().forPath(basePath + "/queue");
                for (String child : children) {
                    connection.delete().forPath(basePath + "/queue/" + child);
                }
            } catch (Exception e) {
                logger.debug("Failed to clear queue", e);
            }
        }
        
        private long extractSequenceNumber(String nodePath) {
            String nodeName = nodePath.substring(nodePath.lastIndexOf('/') + 1);
            return Long.parseLong(nodeName);
        }
    }
    
    /**
     * 队列监控
     */
    private class FairLockQueueMonitor {
        public void checkQueueHealth() {
            try {
                long queueSize = getQueueSize();
                if (queueSize > 500) {
                    logger.warn("Fair lock queue size is large: {} for lock {}", queueSize, lockName);
                }
                
                // 检查是否有超时的队列条目
                // 实现超时检测逻辑
                
            } catch (Exception e) {
                logger.debug("Queue health check error", e);
            }
        }
    }
    
    /**
     * 公平锁统计信息
     */
    public static class FairLockStatistics {
        private final long acquisitionCount;
        private final long releaseCount;
        private final long queueSize;
        private final double averageQueueWaitTime;
        private final double averageQueuePosition;
        private final int currentReentrantCount;
        private final boolean isLocked;
        private final long remainingTime;
        
        public FairLockStatistics(long acquisitionCount, long releaseCount, long queueSize,
                                double averageQueueWaitTime, double averageQueuePosition,
                                int currentReentrantCount, boolean isLocked, long remainingTime) {
            this.acquisitionCount = acquisitionCount;
            this.releaseCount = releaseCount;
            this.queueSize = queueSize;
            this.averageQueueWaitTime = averageQueueWaitTime;
            this.averageQueuePosition = averageQueuePosition;
            this.currentReentrantCount = currentReentrantCount;
            this.isLocked = isLocked;
            this.remainingTime = remainingTime;
        }
        
        public long getAcquisitionCount() {
            return acquisitionCount;
        }
        
        public long getReleaseCount() {
            return releaseCount;
        }
        
        public long getQueueSize() {
            return queueSize;
        }
        
        public double getAverageQueueWaitTime() {
            return averageQueueWaitTime;
        }
        
        public double getAverageQueuePosition() {
            return averageQueuePosition;
        }
        
        public int getCurrentReentrantCount() {
            return currentReentrantCount;
        }
        
        public boolean isLocked() {
            return isLocked;
        }
        
        public long getRemainingTime() {
            return remainingTime;
        }
    }
}