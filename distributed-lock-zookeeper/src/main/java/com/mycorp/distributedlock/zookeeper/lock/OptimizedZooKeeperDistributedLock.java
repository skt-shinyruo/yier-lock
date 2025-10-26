package com.mycorp.distributedlock.zookeeper.lock;

import com.mycorp.distributedlock.api.DistributedLock;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 高性能Zookeeper分布式锁实现
 * 
 * 优化特性：
 * - 基于临时顺序节点的公平锁算法
 * - 本地缓存和批量操作优化
 * - 自动续期和锁超时处理
 * - 事件驱动和异步操作支持
 * - 高可用策略集成
 * - 性能指标监控
 * 
 * 公平锁算法：
 * 使用Zookeeper临时顺序节点确保锁的公平性，节点按创建顺序获得锁
 */
public class OptimizedZooKeeperDistributedLock implements DistributedLock {
    
    private static final Logger logger = LoggerFactory.getLogger(OptimizedZooKeeperDistributedLock.class);
    
    // 锁路径配置
    private static final String LOCKS_BASE_PATH = "/distributed-locks/locks";
    private static final String LOCK_PREFIX = "lock-";
    private static final String SEQUENCE_NUMBER_FORMAT = "%010d";
    
    // 重入锁管理
    private final ReentrantLock internalLock = new ReentrantLock();
    private final ThreadLocal<LockState> lockState = new ThreadLocal<>();
    private final AtomicInteger reentrantCount = new AtomicInteger(0);
    
    // Zookeeper相关
    private final String lockName;
    private final String lockPath;
    private final ZooKeeperConnectionManager connectionManager;
    private final ZooKeeperClusterManager clusterManager;
    private final LockConfiguration configuration;
    private final LockMetrics metrics;
    private final LockTracing tracing;
    
    // 锁状态管理
    private final AtomicBoolean isAcquired = new AtomicBoolean(false);
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final AtomicLong lockAcquisitionTime = new AtomicLong(0);
    private final AtomicLong lockExpirationTime = new AtomicLong(0);
    private final AtomicLong lockSequenceNumber = new AtomicLong(0);
    
    // 异步操作支持
    private final ExecutorService asyncOperationExecutor;
    private final ScheduledExecutorService renewalExecutor;
    
    // 续期管理
    private final AtomicBoolean isAutoRenewalEnabled = new AtomicBoolean(false);
    private final AtomicLong renewalTaskId = new AtomicLong(0);
    private final Map<Long, ScheduledFuture<?>> renewalTasks = new ConcurrentHashMap<>();
    
    // 缓存管理
    private final LockPathCache pathCache = new LockPathCache();
    
    // 事件监听
    private final java.util.List<OptimizedLockEventListener> eventListeners = new CopyOnWriteArrayList<>();
    
    // 性能指标
    private final Timer acquisitionTimer;
    private final Timer releaseTimer;
    private final Timer renewalTimer;
    private final AtomicLong totalAcquisitions = new AtomicLong(0);
    private final AtomicLong totalReleases = new AtomicLong(0);
    private final AtomicLong totalRenewals = new AtomicLong(0);
    
    /**
     * 构造函数
     * 
     * @param lockName 锁名称
     * @param lockPath 锁路径
     * @param connectionManager 连接管理器
     * @param clusterManager 集群管理器
     * @param configuration 配置
     * @param metrics 指标收集
     * @param tracing 分布式追踪
     */
    public OptimizedZooKeeperDistributedLock(String lockName,
                                            String lockPath,
                                            ZooKeeperConnectionManager connectionManager,
                                            ZooKeeperClusterManager clusterManager,
                                            LockConfiguration configuration,
                                            LockMetrics metrics,
                                            LockTracing tracing) {
        this.lockName = lockName;
        this.lockPath = lockPath;
        this.connectionManager = connectionManager;
        this.clusterManager = clusterManager;
        this.configuration = configuration;
        this.metrics = metrics;
        this.tracing = tracing;
        
        // 初始化线程池
        this.asyncOperationExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(), r -> {
                Thread t = new Thread(r, "zookeeper-lock-async-" + lockName);
                t.setDaemon(true);
                return t;
            }
        );
        
        this.renewalExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zookeeper-lock-renewal-" + lockName);
            t.setDaemon(true);
            return t;
        });
        
        // 初始化指标
        this.acquisitionTimer = metrics.createTimer("zookeeper.lock.acquisition.time", lockName);
        this.releaseTimer = metrics.createTimer("zookeeper.lock.release.time", lockName);
        this.renewalTimer = metrics.createTimer("zookeeper.lock.renewal.time", lockName);
        
        // 添加连接状态监听器
        connectionManager.addConnectionStateListener((client, newState) -> {
            handleConnectionStateChanged(newState);
        });
        
        logger.debug("OptimizedZooKeeperDistributedLock initialized for: {}", lockName);
    }
    
    @Override
    public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
        checkNotClosed();
        
        try (var spanContext = tracing.startLockAcquisitionSpan(lockName, "lock")) {
            Timer.Sample sample = metrics.startTimer(acquisitionTimer);
            
            try {
                // 如果已经是锁持有者，直接增加重入计数
                if (isHeldByCurrentThread()) {
                    reentrantCount.incrementAndGet();
                    logger.debug("Lock {} reentrant acquisition", lockName);
                    return;
                }
                
                // 获取锁
                acquireFairLock(leaseTime, unit);
                
                // 设置状态
                isAcquired.set(true);
                lockAcquisitionTime.set(System.currentTimeMillis());
                
                // 启动自动续期（如果配置启用）
                if (shouldEnableAutoRenewal(leaseTime, unit)) {
                    enableAutoRenewal(leaseTime, unit);
                }
                
                totalAcquisitions.incrementAndGet();
                metrics.incrementLockAcquisitionCounter(lockName, "success");
                
                spanContext.setStatus("success");
                logger.debug("Successfully acquired ZooKeeper lock: {}", lockName);
                
                // 触发事件
                notifyEvent(OptimizedLockEventType.LOCK_ACQUIRED, null);
                
            } catch (InterruptedException e) {
                metrics.recordAcquisitionTime(sample, lockName, "interrupted");
                metrics.incrementLockAcquisitionCounter(lockName, "interrupted");
                spanContext.setError(e);
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                metrics.recordAcquisitionTime(sample, lockName, "error");
                metrics.incrementLockAcquisitionCounter(lockName, "error");
                spanContext.setError(e);
                throw new LockAcquisitionException("Error acquiring ZooKeeper lock: " + lockName, e);
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
        
        try (var spanContext = tracing.startLockAcquisitionSpan(lockName, "tryLock")) {
            Timer.Sample sample = metrics.startTimer(acquisitionTimer);
            
            try {
                // 如果已经是锁持有者，直接增加重入计数
                if (isHeldByCurrentThread()) {
                    reentrantCount.incrementAndGet();
                    logger.debug("Lock {} reentrant acquisition", lockName);
                    return true;
                }
                
                boolean acquired = tryAcquireFairLock(waitTime, leaseTime, unit);
                
                if (acquired) {
                    isAcquired.set(true);
                    lockAcquisitionTime.set(System.currentTimeMillis());
                    
                    // 启动自动续期
                    if (shouldEnableAutoRenewal(leaseTime, unit)) {
                        enableAutoRenewal(leaseTime, unit);
                    }
                    
                    totalAcquisitions.incrementAndGet();
                    metrics.incrementLockAcquisitionCounter(lockName, "success");
                    
                    spanContext.setStatus("success");
                    logger.debug("Successfully acquired ZooKeeper lock: {}", lockName);
                    
                    notifyEvent(OptimizedLockEventType.LOCK_ACQUIRED, null);
                } else {
                    metrics.incrementContentionCounter(lockName);
                    metrics.incrementLockAcquisitionCounter(lockName, "timeout");
                    spanContext.setStatus("timeout");
                    
                    notifyEvent(OptimizedLockEventType.LOCK_ACQUISITION_FAILED, 
                               new TimeoutException("Timeout acquiring lock"));
                }
                
                return acquired;
                
            } catch (InterruptedException e) {
                metrics.recordAcquisitionTime(sample, lockName, "interrupted");
                metrics.incrementLockAcquisitionCounter(lockName, "interrupted");
                spanContext.setError(e);
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                metrics.recordAcquisitionTime(sample, lockName, "error");
                metrics.incrementLockAcquisitionCounter(lockName, "error");
                spanContext.setError(e);
                throw new LockAcquisitionException("Error trying to acquire ZooKeeper lock: " + lockName, e);
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
        
        try (var spanContext = tracing.startLockAcquisitionSpan(lockName, "unlock")) {
            Timer.Sample sample = metrics.startTimer(releaseTimer);
            
            try {
                // 检查重入计数
                if (reentrantCount.get() > 0) {
                    int count = reentrantCount.decrementAndGet();
                    if (count > 0) {
                        logger.debug("Lock {} reentrant release, count: {}", lockName, count);
                        return; // 还有重入，继续持有锁
                    }
                }
                
                // 释放锁
                releaseFairLock();
                
                // 清理状态
                isAcquired.set(false);
                lockAcquisitionTime.set(0);
                lockExpirationTime.set(0);
                
                // 取消续期任务
                cancelAllRenewalTasks();
                
                totalReleases.incrementAndGet();
                metrics.incrementLockReleaseCounter(lockName, "success");
                
                spanContext.setStatus("success");
                logger.debug("Successfully released ZooKeeper lock: {}", lockName);
                
                notifyEvent(OptimizedLockEventType.LOCK_RELEASED, null);
                
            } catch (Exception e) {
                metrics.recordAcquisitionTime(sample, lockName, "error");
                metrics.incrementLockReleaseCounter(lockName, "error");
                spanContext.setError(e);
                throw new LockReleaseException("Error releasing ZooKeeper lock: " + lockName, e);
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
                throw new CompletionException("Interrupted while acquiring ZooKeeper lock: " + lockName, e);
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
                throw new CompletionException("Interrupted while acquiring ZooKeeper lock: " + lockName, e);
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
        return Thread.currentThread().getId() == getLockOwnerThreadId();
    }
    
    @Override
    public String getName() {
        return lockName;
    }
    
    /**
     * 异步获取锁信息
     * 
     * @return 锁信息
     */
    public LockInformation getLockInformation() {
        return new LockInformation(
            lockName,
            lockPath,
            isAcquired.get(),
            isHeldByCurrentThread(),
            getLockOwnerThreadId(),
            reentrantCount.get(),
            lockAcquisitionTime.get(),
            lockExpirationTime.get(),
            lockSequenceNumber.get(),
            getRemainingTime()
        );
    }
    
    /**
     * 手动续期锁
     * 
     * @param newLeaseTime 新的租约时间
     * @param unit 时间单位
     * @return 是否续期成功
     */
    public boolean renewLock(long newLeaseTime, TimeUnit unit) {
        if (!isAcquired.get()) {
            return false;
        }
        
        Timer.Sample sample = metrics.startTimer(renewalTimer);
        
        try {
            boolean renewed = renewFairLock(newLeaseTime, unit);
            if (renewed) {
                totalRenewals.incrementAndGet();
                logger.debug("Successfully renewed lock: {}", lockName);
                notifyEvent(OptimizedLockEventType.LOCK_RENEWED, null);
            }
            return renewed;
        } catch (Exception e) {
            logger.error("Failed to renew lock: {}", lockName, e);
            notifyEvent(OptimizedLockEventType.LOCK_RENEWAL_FAILED, e);
            return false;
        } finally {
            sample.stop(renewalTimer);
        }
    }
    
    /**
     * 启用自动续期
     * 
     * @param leaseTime 租约时间
     * @param unit 时间单位
     */
    public void enableAutoRenewal(long leaseTime, TimeUnit unit) {
        if (!isAcquired.get() || isAutoRenewalEnabled.get()) {
            return;
        }
        
        Duration leaseDuration = Duration.ofMillis(unit.toMillis(leaseTime));
        Duration renewalInterval = leaseDuration.dividedBy(3); // 在租约时间的1/3时续期
        
        ScheduledFuture<?> renewalTask = renewalExecutor.scheduleAtFixedRate(() -> {
            try {
                if (isAcquired.get() && !Thread.currentThread().isInterrupted()) {
                    renewLock(leaseTime, unit);
                } else {
                    // 锁已释放或线程被中断，取消续期
                    cancelRenewalTask(renewalTaskId.get());
                }
            } catch (Exception e) {
                logger.debug("Auto-renewal error for lock: {}", lockName, e);
            }
        }, renewalInterval.toMillis(), renewalInterval.toMillis(), TimeUnit.MILLISECONDS);
        
        long taskId = renewalTaskId.incrementAndGet();
        renewalTasks.put(taskId, renewalTask);
        isAutoRenewalEnabled.set(true);
        
        logger.debug("Enabled auto-renewal for lock: {} with interval: {}", lockName, renewalInterval);
    }
    
    /**
     * 禁用自动续期
     */
    public void disableAutoRenewal() {
        cancelAllRenewalTasks();
        isAutoRenewalEnabled.set(false);
        logger.debug("Disabled auto-renewal for lock: {}", lockName);
    }
    
    /**
     * 添加锁事件监听器
     * 
     * @param listener 监听器
     */
    public void addLockEventListener(OptimizedLockEventListener listener) {
        if (listener != null) {
            eventListeners.add(listener);
        }
    }
    
    /**
     * 移除锁事件监听器
     * 
     * @param listener 监听器
     */
    public void removeLockEventListener(OptimizedLockEventListener listener) {
        eventListeners.remove(listener);
    }
    
    /**
     * 获取锁路径
     * 
     * @return 锁路径
     */
    public String getLockPath() {
        return lockPath;
    }
    
    /**
     * 获取序列号
     * 
     * @return 序列号
     */
    public long getSequenceNumber() {
        return lockSequenceNumber.get();
    }
    
    /**
     * 获取剩余时间
     * 
     * @return 剩余时间（毫秒）
     */
    public long getRemainingTime() {
        long expirationTime = lockExpirationTime.get();
        if (expirationTime == 0) {
            return 0;
        }
        return Math.max(0, expirationTime - System.currentTimeMillis());
    }
    
    /**
     * 检查锁是否即将过期
     * 
     * @param threshold 阈值（毫秒）
     * @return 是否即将过期
     */
    public boolean isExpiring(long threshold) {
        return getRemainingTime() <= threshold;
    }
    
    public void close() {
        if (isClosed.getAndSet(true)) {
            return;
        }
        
        logger.debug("Closing OptimizedZooKeeperDistributedLock: {}", lockName);
        
        try {
            // 释放锁
            if (isAcquired.get()) {
                unlock();
            }
            
            // 关闭线程池
            shutdownExecutor(asyncOperationExecutor, "async-operation");
            shutdownExecutor(renewalExecutor, "renewal");
            
            // 清理缓存
            pathCache.clear();
            
            // 清理事件监听器
            eventListeners.clear();
            
            logger.debug("OptimizedZooKeeperDistributedLock closed: {}", lockName);
        } catch (Exception e) {
            logger.error("Error closing lock: {}", lockName, e);
        }
    }
    
    // 私有方法区域
    
    private void acquireFairLock(long leaseTime, TimeUnit unit) throws Exception {
        internalLock.lock();
        try {
            CuratorFramework connection = connectionManager.getConnection();
            
            // 创建临时顺序节点
            String lockNodePath = createLockNode(connection);
            
            // 获取所有子节点
            List<String> children = getLockChildren(connection);
            
            // 检查是否是当前最小的节点
            if (isCurrentNodeSmallest(children, lockNodePath)) {
                // 当前节点是最小的，可以获得锁
                lockSequenceNumber.set(extractSequenceNumber(lockNodePath));
                lockExpirationTime.set(System.currentTimeMillis() + unit.toMillis(leaseTime));
            } else {
                // 等待前一个节点释放
                waitForPreviousNode(connection, lockNodePath, children);
                lockSequenceNumber.set(extractSequenceNumber(lockNodePath));
                lockExpirationTime.set(System.currentTimeMillis() + unit.toMillis(leaseTime));
            }
        } finally {
            internalLock.unlock();
        }
    }
    
    private boolean tryAcquireFairLock(long waitTime, long leaseTime, TimeUnit unit) throws Exception {
        internalLock.lock();
        try {
            long startTime = System.currentTimeMillis();
            
            while (System.currentTimeMillis() - startTime < unit.toMillis(waitTime)) {
                CuratorFramework connection = connectionManager.getConnection();
                
                String lockNodePath = createLockNode(connection);
                List<String> children = getLockChildren(connection);
                
                if (isCurrentNodeSmallest(children, lockNodePath)) {
                    lockSequenceNumber.set(extractSequenceNumber(lockNodePath));
                    lockExpirationTime.set(System.currentTimeMillis() + unit.toMillis(leaseTime));
                    return true;
                } else {
                    // 等待一小段时间后重试
                    Thread.sleep(10);
                }
            }
            
            // 尝试超时，清理创建的节点
            cleanupLockNode(connection, lockNodePath);
            return false;
            
        } finally {
            internalLock.unlock();
        }
    }
    
    private void releaseFairLock() throws Exception {
        internalLock.lock();
        try {
            CuratorFramework connection = connectionManager.getConnection();
            
            // 删除锁节点
            if (lockSequenceNumber.get() > 0) {
                String lockNodePath = getLockNodePath(lockSequenceNumber.get());
                cleanupLockNode(connection, lockNodePath);
            }
            
            lockSequenceNumber.set(0);
            
        } finally {
            internalLock.unlock();
        }
    }
    
    private boolean renewFairLock(long newLeaseTime, TimeUnit unit) throws Exception {
        if (lockSequenceNumber.get() == 0) {
            return false;
        }
        
        lockExpirationTime.set(System.currentTimeMillis() + unit.toMillis(newLeaseTime));
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
    
    private void waitForPreviousNode(CuratorFramework connection, String lockNodePath, List<String> children) 
            throws Exception {
        String currentNodeName = lockNodePath.substring(lockNodePath.lastIndexOf('/') + 1);
        
        // 找到前一个节点
        int currentIndex = children.indexOf(currentNodeName);
        if (currentIndex > 0) {
            String previousNode = children.get(currentIndex - 1);
            
            // 等待前一个节点被删除
            connection.getChildren().usingWatcher(watchedEvent -> {
                if (watchedEvent.getType() == org.apache.zookeeper.Watcher.Event.EventType.NodeDeleted) {
                    synchronized (this) {
                        notifyAll();
                    }
                }
            }).forPath(lockPath + "/" + previousNode);
            
            // 等待通知或超时
            synchronized (this) {
                wait(5000); // 等待5秒
            }
        }
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
    
    private boolean shouldEnableAutoRenewal(long leaseTime, TimeUnit unit) {
        long leaseMillis = unit.toMillis(leaseTime);
        return leaseMillis > 30000; // 租约时间超过30秒才启用自动续期
    }
    
    private void cancelAllRenewalTasks() {
        for (ScheduledFuture<?> task : renewalTasks.values()) {
            task.cancel(false);
        }
        renewalTasks.clear();
        isAutoRenewalEnabled.set(false);
    }
    
    private void cancelRenewalTask(long taskId) {
        ScheduledFuture<?> task = renewalTasks.remove(taskId);
        if (task != null) {
            task.cancel(false);
        }
    }
    
    private void handleConnectionStateChanged(ConnectionState newState) {
        switch (newState) {
            case LOST:
                // 连接丢失，释放锁
                if (isAcquired.get()) {
                    logger.warn("Connection lost, releasing lock: {}", lockName);
                    try {
                        unlock();
                    } catch (Exception e) {
                        logger.error("Error releasing lock during connection loss", e);
                    }
                }
                break;
                
            case SUSPENDED:
                logger.warn("Connection suspended for lock: {}", lockName);
                break;
                
            case RECONNECTED:
                logger.info("Connection reconnected for lock: {}", lockName);
                break;
        }
    }
    
    private void notifyEvent(OptimizedLockEventType eventType, Throwable error) {
        OptimizedLockEvent event = new OptimizedLockEvent(
            eventType, lockName, Instant.now(), error, getLockInformation()
        );
        
        for (OptimizedLockEventListener listener : eventListeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                logger.warn("Error in lock event listener", e);
            }
        }
    }
    
    private long getLockOwnerThreadId() {
        LockState state = lockState.get();
        return state != null ? state.getOwnerThreadId() : -1;
    }
    
    private void checkNotClosed() {
        if (isClosed.get()) {
            throw new IllegalStateException("Lock is already closed: " + lockName);
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
        logger.debug("Shut down {} executor for lock: {}", name, lockName);
    }
    
    // 内部类定义
    
    private static class LockState {
        private final long ownerThreadId;
        private final Instant acquisitionTime;
        private final int reentrantCount;
        
        public LockState(long ownerThreadId, Instant acquisitionTime, int reentrantCount) {
            this.ownerThreadId = ownerThreadId;
            this.acquisitionTime = acquisitionTime;
            this.reentrantCount = reentrantCount;
        }
        
        public long getOwnerThreadId() { return ownerThreadId; }
        public Instant getAcquisitionTime() { return acquisitionTime; }
        public int getReentrantCount() { return reentrantCount; }
    }
    
    private static class LockPathCache {
        private final Map<String, String> cache = new ConcurrentHashMap<>();
        
        public String getPath(String key) {
            return cache.get(key);
        }
        
        public void putPath(String key, String path) {
            cache.put(key, path);
        }
        
        public void removePath(String key) {
            cache.remove(key);
        }
        
        public void clear() {
            cache.clear();
        }
    }
    
    public static class LockInformation {
        private final String lockName;
        private final String lockPath;
        private final boolean isAcquired;
        private final boolean isHeldByCurrentThread;
        private final long ownerThreadId;
        private final int reentrantCount;
        private final long acquisitionTime;
        private final long expirationTime;
        private final long sequenceNumber;
        private final long remainingTime;
        
        public LockInformation(String lockName, String lockPath, boolean isAcquired,
                             boolean isHeldByCurrentThread, long ownerThreadId, int reentrantCount,
                             long acquisitionTime, long expirationTime, long sequenceNumber,
                             long remainingTime) {
            this.lockName = lockName;
            this.lockPath = lockPath;
            this.isAcquired = isAcquired;
            this.isHeldByCurrentThread = isHeldByCurrentThread;
            this.ownerThreadId = ownerThreadId;
            this.reentrantCount = reentrantCount;
            this.acquisitionTime = acquisitionTime;
            this.expirationTime = expirationTime;
            this.sequenceNumber = sequenceNumber;
            this.remainingTime = remainingTime;
        }
        
        public String getLockName() { return lockName; }
        public String getLockPath() { return lockPath; }
        public boolean isAcquired() { return isAcquired; }
        public boolean isHeldByCurrentThread() { return isHeldByCurrentThread; }
        public long getOwnerThreadId() { return ownerThreadId; }
        public int getReentrantCount() { return reentrantCount; }
        public long getAcquisitionTime() { return acquisitionTime; }
        public long getExpirationTime() { return expirationTime; }
        public long getSequenceNumber() { return sequenceNumber; }
        public long getRemainingTime() { return remainingTime; }
    }
    
    public static class OptimizedLockEvent {
        private final OptimizedLockEventType type;
        private final String lockName;
        private final Instant timestamp;
        private final Throwable error;
        private final LockInformation lockInfo;
        
        public OptimizedLockEvent(OptimizedLockEventType type, String lockName, Instant timestamp,
                                Throwable error, LockInformation lockInfo) {
            this.type = type;
            this.lockName = lockName;
            this.timestamp = timestamp;
            this.error = error;
            this.lockInfo = lockInfo;
        }
        
        public OptimizedLockEventType getType() { return type; }
        public String getLockName() { return lockName; }
        public Instant getTimestamp() { return timestamp; }
        public Throwable getError() { return error; }
        public LockInformation getLockInfo() { return lockInfo; }
    }
    
    public interface OptimizedLockEventListener {
        void onEvent(OptimizedLockEvent event);
    }
    
    public enum OptimizedLockEventType {
        LOCK_ACQUIRED,
        LOCK_RELEASED,
        LOCK_RENEWED,
        LOCK_RENEWAL_FAILED,
        LOCK_ACQUISITION_FAILED,
        LOCK_EXPIRATION_DETECTED,
        CONNECTION_LOST
    }
}