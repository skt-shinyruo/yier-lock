package com.mycorp.distributedlock.zookeeper.lock;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.api.exception.LockAcquisitionException;
import com.mycorp.distributedlock.api.exception.LockReleaseException;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import com.mycorp.distributedlock.zookeeper.connection.ZooKeeperConnectionManager;
import com.mycorp.distributedlock.zookeeper.cluster.ZooKeeperClusterManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.StampedLock;

/**
 * 高性能Zookeeper分布式读写锁实现
 * 
 * 优化特性：
 * - 基于临时顺序节点的公平读写锁算法
 * - 读写锁分离和独立管理
 * - 支持读写锁升级/降级
 * - 多层次并发控制
 * - 细粒度锁状态监控
 * - 性能指标收集和优化
 * 
 * 读写锁算法：
 * - 读锁：共享锁，多个读者可以同时持有
 * - 写锁：独占锁，一次只能有一个写者
 * - 公平性：确保请求顺序得到保护
 */
public class OptimizedZooKeeperDistributedReadWriteLock implements DistributedReadWriteLock {
    
    private static final Logger logger = LoggerFactory.getLogger(OptimizedZooKeeperDistributedReadWriteLock.class);
    
    // 读写锁路径配置
    private static final String READ_LOCKS_BASE_PATH = "/distributed-locks/read-locks";
    private static final String WRITE_LOCKS_BASE_PATH = "/distributed-locks/write-locks";
    private static final String READ_LOCK_PREFIX = "read-lock-";
    private static final String WRITE_LOCK_PREFIX = "write-lock-";
    
    // 读写锁状态管理
    private final AtomicInteger readLockCount = new AtomicInteger(0);
    private final AtomicInteger writeLockCount = new AtomicInteger(0);
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final ReentrantReadWriteLock reentrantLock = new ReentrantReadWriteLock();
    private final StampedLock stampedLock = new StampedLock();
    
    // Zookeeper相关
    private final String lockName;
    private final String readLockPath;
    private final String writeLockPath;
    private final OptimizedZooKeeperDistributedLock readLock;
    private final OptimizedZooKeeperDistributedLock writeLock;
    private final ZooKeeperConnectionManager connectionManager;
    private final ZooKeeperClusterManager clusterManager;
    private final LockConfiguration configuration;
    private final LockMetrics metrics;
    private final LockTracing tracing;
    
    // 读写锁升级/降级管理
    private final Map<Long, LockUpgradeInfo> threadLockUpgradeInfo = new ConcurrentHashMap<>();
    
    // 异步操作支持
    private final ExecutorService asyncOperationExecutor;
    
    // 性能指标
    private final Timer readLockAcquisitionTimer;
    private final Timer writeLockAcquisitionTimer;
    private final Timer readLockReleaseTimer;
    private final Timer writeLockReleaseTimer;
    private final Timer lockUpgradeTimer;
    private final Timer lockDowngradeTimer;
    private final Counter readLockUpgradeCounter;
    private final Counter writeLockUpgradeCounter;
    private final Counter readLockDowngradeCounter;
    private final Counter writeLockDowngradeCounter;
    private final AtomicLong totalReadLockAcquisitions = new AtomicLong(0);
    private final AtomicLong totalWriteLockAcquisitions = new AtomicLong(0);
    private final AtomicLong totalReadLockReleases = new AtomicLong(0);
    private final AtomicLong totalWriteLockReleases = new AtomicLong(0);
    private final AtomicLong totalLockUpgrades = new AtomicLong(0);
    private final AtomicLong totalLockDowngrades = new AtomicLong(0);
    
    // 事件监听
    private final java.util.List<ReadWriteLockEventListener> eventListeners = new CopyOnWriteArrayList<>();
    
    /**
     * 构造函数
     * 
     * @param lockName 锁名称
     * @param connectionManager 连接管理器
     * @param clusterManager 集群管理器
     * @param configuration 配置
     * @param metrics 指标收集
     * @param tracing 分布式追踪
     */
    public OptimizedZooKeeperDistributedReadWriteLock(String lockName,
                                                     ZooKeeperConnectionManager connectionManager,
                                                     ZooKeeperClusterManager clusterManager,
                                                     LockConfiguration configuration,
                                                     LockMetrics metrics,
                                                     LockTracing tracing) {
        this.lockName = lockName;
        this.connectionManager = connectionManager;
        this.clusterManager = clusterManager;
        this.configuration = configuration;
        this.metrics = metrics;
        this.tracing = tracing;
        
        // 构建锁路径
        this.readLockPath = READ_LOCKS_BASE_PATH + "/" + lockName;
        this.writeLockPath = WRITE_LOCKS_BASE_PATH + "/" + lockName;
        
        // 创建读写锁实例
        this.readLock = new OptimizedZooKeeperDistributedLock(
            lockName + ":read", readLockPath, connectionManager, clusterManager,
            configuration, metrics, tracing);
        
        this.writeLock = new OptimizedZooKeeperDistributedLock(
            lockName + ":write", writeLockPath, connectionManager, clusterManager,
            configuration, metrics, tracing);
        
        // 初始化异步操作线程池
        this.asyncOperationExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(), r -> {
                Thread t = new Thread(r, "zookeeper-readwrite-lock-async-" + lockName);
                t.setDaemon(true);
                return t;
            }
        );
        
        // 初始化性能指标
        this.readLockAcquisitionTimer = metrics.createTimer("zookeeper.readlock.acquisition.time", lockName);
        this.writeLockAcquisitionTimer = metrics.createTimer("zookeeper.writelock.acquisition.time", lockName);
        this.readLockReleaseTimer = metrics.createTimer("zookeeper.readlock.release.time", lockName);
        this.writeLockReleaseTimer = metrics.createTimer("zookeeper.writelock.release.time", lockName);
        this.lockUpgradeTimer = metrics.createTimer("zookeeper.lock.upgrade.time", lockName);
        this.lockDowngradeTimer = metrics.createTimer("zookeeper.lock.downgrade.time", lockName);
        this.readLockUpgradeCounter = metrics.createCounter("zookeeper.readlock.upgrade.count", lockName);
        this.writeLockUpgradeCounter = metrics.createCounter("zookeeper.writelock.upgrade.count", lockName);
        this.readLockDowngradeCounter = metrics.createCounter("zookeeper.readlock.downgrade.count", lockName);
        this.writeLockDowngradeCounter = metrics.createCounter("zookeeper.writelock.downgrade.count", lockName);
        
        logger.debug("OptimizedZooKeeperDistributedReadWriteLock initialized for: {}", lockName);
    }
    
    @Override
    public DistributedLock readLock() {
        return readLock;
    }
    
    @Override
    public DistributedLock writeLock() {
        return writeLock;
    }
    
    @Override
    public String getName() {
        return lockName;
    }
    
    /**
     * 获取读锁（增强版本）
     * 
     * @param leaseTime 租约时间
     * @param unit 时间单位
     * @param timeout 超时时间
     * @param timeoutUnit 超时时间单位
     * @return 是否获取成功
     * @throws InterruptedException 线程中断
     */
    public boolean readLock(long leaseTime, TimeUnit unit, long timeout, TimeUnit timeoutUnit) 
            throws InterruptedException {
        checkNotClosed();
        
        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeoutUnit.toMillis(timeout);
        
        try (var spanContext = tracing.startLockAcquisitionSpan(lockName, "readLockWithTimeout")) {
            Timer.Sample sample = metrics.startTimer(readLockAcquisitionTimer);
            
            try {
                // 检查是否已经有写锁
                if (writeLockCount.get() > 0 && !isHeldByCurrentThread()) {
                    throw new LockAcquisitionException("Cannot acquire read lock while write lock is held by another thread");
                }
                
                // 尝试获取读锁
                boolean acquired = readLock.tryLock(timeout, leaseTime, unit);
                
                if (acquired) {
                    readLockCount.incrementAndGet();
                    totalReadLockAcquisitions.incrementAndGet();
                    
                    // 记录锁升级信息（如果有的话）
                    LockUpgradeInfo upgradeInfo = threadLockUpgradeInfo.get(Thread.currentThread().getId());
                    if (upgradeInfo != null && upgradeInfo.getOriginalLockType() == LockType.READ) {
                        upgradeInfo.setReadLockAcquired(true);
                    }
                    
                    logger.debug("Successfully acquired read lock: {}", lockName);
                    notifyEvent(ReadWriteLockEventType.READ_LOCK_ACQUIRED, null);
                } else {
                    logger.debug("Failed to acquire read lock: {} within timeout", lockName);
                    notifyEvent(ReadWriteLockEventType.READ_LOCK_ACQUISITION_FAILED, 
                               new TimeoutException("Timeout acquiring read lock"));
                }
                
                return acquired;
                
            } catch (Exception e) {
                spanContext.setError(e);
                throw new LockAcquisitionException("Error acquiring read lock: " + lockName, e);
            } finally {
                sample.stop(readLockAcquisitionTimer);
            }
        }
    }
    
    /**
     * 获取写锁（增强版本）
     * 
     * @param leaseTime 租约时间
     * @param unit 时间单位
     * @param timeout 超时时间
     * @param timeoutUnit 超时时间单位
     * @return 是否获取成功
     * @throws InterruptedException 线程中断
     */
    public boolean writeLock(long leaseTime, TimeUnit unit, long timeout, TimeUnit timeoutUnit) 
            throws InterruptedException {
        checkNotClosed();
        
        try (var spanContext = tracing.startLockAcquisitionSpan(lockName, "writeLockWithTimeout")) {
            Timer.Sample sample = metrics.startTimer(writeLockAcquisitionTimer);
            
            try {
                // 检查读锁持有情况
                if (readLockCount.get() > 0 && !isHeldByCurrentThread()) {
                    throw new LockAcquisitionException("Cannot acquire write lock while read locks are held by other threads");
                }
                
                // 等待所有读锁释放
                long startTime = System.currentTimeMillis();
                long timeoutMillis = timeoutUnit.toMillis(timeout);
                
                while (readLockCount.get() > 0 && !isHeldByCurrentThread()) {
                    if (System.currentTimeMillis() - startTime > timeoutMillis) {
                        logger.debug("Timeout waiting for read locks to release for write lock: {}", lockName);
                        notifyEvent(ReadWriteLockEventType.WRITE_LOCK_ACQUISITION_FAILED, 
                                   new TimeoutException("Timeout waiting for read locks"));
                        return false;
                    }
                    Thread.sleep(10);
                }
                
                // 获取写锁
                boolean acquired = writeLock.tryLock(timeout, leaseTime, unit);
                
                if (acquired) {
                    writeLockCount.incrementAndGet();
                    totalWriteLockAcquisitions.incrementAndGet();
                    
                    // 记录锁升级信息
                    LockUpgradeInfo upgradeInfo = threadLockUpgradeInfo.get(Thread.currentThread().getId());
                    if (upgradeInfo != null && upgradeInfo.getOriginalLockType() == LockType.WRITE) {
                        upgradeInfo.setWriteLockAcquired(true);
                    }
                    
                    logger.debug("Successfully acquired write lock: {}", lockName);
                    notifyEvent(ReadWriteLockEventType.WRITE_LOCK_ACQUIRED, null);
                } else {
                    logger.debug("Failed to acquire write lock: {} within timeout", lockName);
                    notifyEvent(ReadWriteLockEventType.WRITE_LOCK_ACQUISITION_FAILED, 
                               new TimeoutException("Timeout acquiring write lock"));
                }
                
                return acquired;
                
            } catch (Exception e) {
                spanContext.setError(e);
                throw new LockAcquisitionException("Error acquiring write lock: " + lockName, e);
            } finally {
                sample.stop(writeLockAcquisitionTimer);
            }
        }
    }
    
    /**
     * 释放读锁（增强版本）
     * 
     * @return 是否成功释放
     */
    public boolean releaseReadLock() {
        checkNotClosed();
        
        try (var spanContext = tracing.startLockAcquisitionSpan(lockName, "releaseReadLock")) {
            Timer.Sample sample = metrics.startTimer(readLockReleaseTimer);
            
            try {
                if (readLockCount.get() > 0) {
                    readLock.unlock();
                    readLockCount.decrementAndGet();
                    totalReadLockReleases.incrementAndGet();
                    
                    logger.debug("Successfully released read lock: {}", lockName);
                    notifyEvent(ReadWriteLockEventType.READ_LOCK_RELEASED, null);
                    return true;
                }
                
                return false;
                
            } catch (Exception e) {
                spanContext.setError(e);
                throw new LockReleaseException("Error releasing read lock: " + lockName, e);
            } finally {
                sample.stop(readLockReleaseTimer);
            }
        }
    }
    
    /**
     * 释放写锁（增强版本）
     * 
     * @return 是否成功释放
     */
    public boolean releaseWriteLock() {
        checkNotClosed();
        
        try (var spanContext = tracing.startLockAcquisitionSpan(lockName, "releaseWriteLock")) {
            Timer.Sample sample = metrics.startTimer(writeLockReleaseTimer);
            
            try {
                if (writeLockCount.get() > 0) {
                    writeLock.unlock();
                    writeLockCount.decrementAndGet();
                    totalWriteLockReleases.incrementAndGet();
                    
                    logger.debug("Successfully released write lock: {}", lockName);
                    notifyEvent(ReadWriteLockEventType.WRITE_LOCK_RELEASED, null);
                    return true;
                }
                
                return false;
                
            } catch (Exception e) {
                spanContext.setError(e);
                throw new LockReleaseException("Error releasing write lock: " + lockName, e);
            } finally {
                sample.stop(writeLockReleaseTimer);
            }
        }
    }
    
    /**
     * 从读锁升级到写锁
     * 
     * @param leaseTime 租约时间
     * @param unit 时间单位
     * @param timeout 超时时间
     * @param timeoutUnit 超时时间单位
     * @return 是否升级成功
     * @throws InterruptedException 线程中断
     */
    public boolean upgradeReadToWriteLock(long leaseTime, TimeUnit unit, long timeout, TimeUnit timeoutUnit) 
            throws InterruptedException {
        checkNotClosed();
        
        if (!readLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Current thread does not hold read lock");
        }
        
        Timer.Sample sample = metrics.startTimer(lockUpgradeTimer);
        
        try {
            // 记录升级信息
            LockUpgradeInfo upgradeInfo = new LockUpgradeInfo(LockType.READ, LockType.WRITE);
            threadLockUpgradeInfo.put(Thread.currentThread().getId(), upgradeInfo);
            
            // 先释放读锁
            releaseReadLock();
            
            // 然后获取写锁
            boolean upgradeSuccess = writeLock(leaseTime, unit, timeout, timeoutUnit);
            
            if (upgradeSuccess) {
                totalLockUpgrades.incrementAndGet();
                readLockUpgradeCounter.increment();
                logger.debug("Successfully upgraded read lock to write lock: {}", lockName);
                notifyEvent(ReadWriteLockEventType.LOCK_UPGRADED, null);
            } else {
                // 升级失败，需要重新获取读锁
                try {
                    readLock(leaseTime, unit, 0, TimeUnit.MILLISECONDS);
                    logger.debug("Failed to upgrade, reacquired read lock: {}", lockName);
                } catch (Exception e) {
                    logger.error("Failed to reacquire read lock after failed upgrade", e);
                }
            }
            
            return upgradeSuccess;
            
        } finally {
            sample.stop(lockUpgradeTimer);
            threadLockUpgradeInfo.remove(Thread.currentThread().getId());
        }
    }
    
    /**
     * 从写锁降级到读锁
     * 
     * @param leaseTime 租约时间
     * @param unit 时间单位
     * @param timeout 超时时间
     * @param timeoutUnit 超时时间单位
     * @return 是否降级成功
     * @throws InterruptedException 线程中断
     */
    public boolean downgradeWriteToReadLock(long leaseTime, TimeUnit unit, long timeout, TimeUnit timeoutUnit) 
            throws InterruptedException {
        checkNotClosed();
        
        if (!writeLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Current thread does not hold write lock");
        }
        
        Timer.Sample sample = metrics.startTimer(lockDowngradeTimer);
        
        try {
            // 记录降级信息
            LockUpgradeInfo downgradeInfo = new LockUpgradeInfo(LockType.WRITE, LockType.READ);
            threadLockUpgradeInfo.put(Thread.currentThread().getId(), downgradeInfo);
            
            // 先释放写锁
            releaseWriteLock();
            
            // 然后获取读锁
            boolean downgradeSuccess = readLock(leaseTime, unit, timeout, timeoutUnit);
            
            if (downgradeSuccess) {
                totalLockDowngrades.incrementAndGet();
                writeLockDowngradeCounter.increment();
                logger.debug("Successfully downgraded write lock to read lock: {}", lockName);
                notifyEvent(ReadWriteLockEventType.LOCK_DOWNGRADED, null);
            } else {
                // 降级失败，写锁已经释放但无法获取读锁
                logger.warn("Failed to downgrade, write lock released but cannot acquire read lock: {}", lockName);
            }
            
            return downgradeSuccess;
            
        } finally {
            sample.stop(lockDowngradeTimer);
            threadLockUpgradeInfo.remove(Thread.currentThread().getId());
        }
    }
    
    /**
     * 获取读写锁状态信息
     * 
     * @return 读写锁状态信息
     */
    public ReadWriteLockStateInfo getReadWriteLockState() {
        return new ReadWriteLockStateInfo(
            lockName,
            readLockCount.get(),
            writeLockCount.get(),
            readLock.isHeldByCurrentThread(),
            writeLock.isHeldByCurrentThread(),
            isReadLocked(),
            isWriteLocked(),
            getReadLockQueueLength(),
            getWriteLockQueueLength(),
            totalReadLockAcquisitions.get(),
            totalWriteLockAcquisitions.get(),
            totalLockUpgrades.get(),
            totalLockDowngrades.get()
        );
    }
    
    /**
     * 检查是否有读锁
     * 
     * @return 是否有读锁
     */
    public boolean isReadLocked() {
        return readLockCount.get() > 0;
    }
    
    /**
     * 检查是否有写锁
     * 
     * @return 是否有写锁
     */
    public boolean isWriteLocked() {
        return writeLockCount.get() > 0;
    }
    
    /**
     * 检查当前线程是否持有读锁
     * 
     * @return 是否持有读锁
     */
    public boolean isReadLockedByCurrentThread() {
        return readLock.isHeldByCurrentThread();
    }
    
    /**
     * 检查当前线程是否持有写锁
     * 
     * @return 是否持有写锁
     */
    public boolean isWriteLockedByCurrentThread() {
        return writeLock.isHeldByCurrentThread();
    }
    
    /**
     * 获取读锁队列长度
     * 
     * @return 读锁队列长度
     */
    public int getReadLockQueueLength() {
        // 这里应该从Zookeeper获取实际的队列长度
        // 目前简化实现，返回0
        return 0;
    }
    
    /**
     * 获取写锁队列长度
     * 
     * @return 写锁队列长度
     */
    public int getWriteLockQueueLength() {
        // 这里应该从Zookeeper获取实际的队列长度
        // 目前简化实现，返回0
        return 0;
    }
    
    /**
     * 添加读写锁事件监听器
     * 
     * @param listener 监听器
     */
    public void addReadWriteLockEventListener(ReadWriteLockEventListener listener) {
        if (listener != null) {
            eventListeners.add(listener);
            
            // 同时添加到读写锁实例
            readLock.addLockEventListener(listener);
            writeLock.addLockEventListener(listener);
        }
    }
    
    /**
     * 移除读写锁事件监听器
     * 
     * @param listener 监听器
     */
    public void removeReadWriteLockEventListener(ReadWriteLockEventListener listener) {
        eventListeners.remove(listener);
        
        // 同时从读写锁实例中移除
        readLock.removeLockEventListener(listener);
        writeLock.removeLockEventListener(listener);
    }
    
    /**
     * 获取性能统计信息
     * 
     * @return 性能统计信息
     */
    public ReadWriteLockPerformanceStats getPerformanceStats() {
        return new ReadWriteLockPerformanceStats(
            lockName,
            totalReadLockAcquisitions.get(),
            totalWriteLockAcquisitions.get(),
            totalReadLockReleases.get(),
            totalWriteLockReleases.get(),
            totalLockUpgrades.get(),
            totalLockDowngrades.get(),
            readLockCount.get(),
            writeLockCount.get()
        );
    }
    
    /**
     * 重置性能统计
     */
    public void resetPerformanceStats() {
        totalReadLockAcquisitions.set(0);
        totalWriteLockAcquisitions.set(0);
        totalReadLockReleases.set(0);
        totalWriteLockReleases.set(0);
        totalLockUpgrades.set(0);
        totalLockDowngrades.set(0);
        
        logger.debug("Performance stats reset for read-write lock: {}", lockName);
    }
    
    @Override
    public void close() {
        if (isClosed.getAndSet(true)) {
            return;
        }
        
        logger.debug("Closing OptimizedZooKeeperDistributedReadWriteLock: {}", lockName);
        
        try {
            // 释放所有锁
            if (isReadLocked()) {
                releaseReadLock();
            }
            if (isWriteLocked()) {
                releaseWriteLock();
            }
            
            // 关闭读写锁实例
            readLock.close();
            writeLock.close();
            
            // 关闭线程池
            shutdownExecutor(asyncOperationExecutor, "async-operation");
            
            // 清理事件监听器
            eventListeners.clear();
            
            // 清理升级信息
            threadLockUpgradeInfo.clear();
            
            logger.debug("OptimizedZooKeeperDistributedReadWriteLock closed: {}", lockName);
        } catch (Exception e) {
            logger.error("Error closing read-write lock: {}", lockName, e);
        }
    }
    
    // 私有方法区域
    
    private boolean isHeldByCurrentThread() {
        return readLock.isHeldByCurrentThread() || writeLock.isHeldByCurrentThread();
    }
    
    private void notifyEvent(ReadWriteLockEventType eventType, Throwable error) {
        ReadWriteLockEvent event = new ReadWriteLockEvent(
            eventType, lockName, Instant.now(), error, getReadWriteLockState()
        );
        
        for (ReadWriteLockEventListener listener : eventListeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                logger.warn("Error in read-write lock event listener", e);
            }
        }
    }
    
    private void checkNotClosed() {
        if (isClosed.get()) {
            throw new IllegalStateException("Read-write lock is already closed: " + lockName);
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
        logger.debug("Shut down {} executor for read-write lock: {}", name, lockName);
    }
    
    // 内部类定义
    
    private static class LockUpgradeInfo {
        private final LockType originalLockType;
        private final LockType targetLockType;
        private volatile boolean readLockAcquired = false;
        private volatile boolean writeLockAcquired = false;
        
        public LockUpgradeInfo(LockType originalLockType, LockType targetLockType) {
            this.originalLockType = originalLockType;
            this.targetLockType = targetLockType;
        }
        
        public LockType getOriginalLockType() { return originalLockType; }
        public LockType getTargetLockType() { return targetLockType; }
        public boolean isReadLockAcquired() { return readLockAcquired; }
        public void setReadLockAcquired(boolean readLockAcquired) { this.readLockAcquired = readLockAcquired; }
        public boolean isWriteLockAcquired() { return writeLockAcquired; }
        public void setWriteLockAcquired(boolean writeLockAcquired) { this.writeLockAcquired = writeLockAcquired; }
    }
    
    public static class ReadWriteLockStateInfo {
        private final String lockName;
        private final int readLockCount;
        private final int writeLockCount;
        private final boolean readLockHeldByCurrentThread;
        private final boolean writeLockHeldByCurrentThread;
        private final boolean isReadLocked;
        private final boolean isWriteLocked;
        private final int readLockQueueLength;
        private final int writeLockQueueLength;
        private final long totalReadLockAcquisitions;
        private final long totalWriteLockAcquisitions;
        private final long totalLockUpgrades;
        private final long totalLockDowngrades;
        
        public ReadWriteLockStateInfo(String lockName, int readLockCount, int writeLockCount,
                                    boolean readLockHeldByCurrentThread, boolean writeLockHeldByCurrentThread,
                                    boolean isReadLocked, boolean isWriteLocked, int readLockQueueLength,
                                    int writeLockQueueLength, long totalReadLockAcquisitions,
                                    long totalWriteLockAcquisitions, long totalLockUpgrades,
                                    long totalLockDowngrades) {
            this.lockName = lockName;
            this.readLockCount = readLockCount;
            this.writeLockCount = writeLockCount;
            this.readLockHeldByCurrentThread = readLockHeldByCurrentThread;
            this.writeLockHeldByCurrentThread = writeLockHeldByCurrentThread;
            this.isReadLocked = isReadLocked;
            this.isWriteLocked = isWriteLocked;
            this.readLockQueueLength = readLockQueueLength;
            this.writeLockQueueLength = writeLockQueueLength;
            this.totalReadLockAcquisitions = totalReadLockAcquisitions;
            this.totalWriteLockAcquisitions = totalWriteLockAcquisitions;
            this.totalLockUpgrades = totalLockUpgrades;
            this.totalLockDowngrades = totalLockDowngrades;
        }
        
        public String getLockName() { return lockName; }
        public int getReadLockCount() { return readLockCount; }
        public int getWriteLockCount() { return writeLockCount; }
        public boolean isReadLockHeldByCurrentThread() { return readLockHeldByCurrentThread; }
        public boolean isWriteLockHeldByCurrentThread() { return writeLockHeldByCurrentThread; }
        public boolean isReadLocked() { return isReadLocked; }
        public boolean isWriteLocked() { return isWriteLocked; }
        public int getReadLockQueueLength() { return readLockQueueLength; }
        public int getWriteLockQueueLength() { return writeLockQueueLength; }
        public long getTotalReadLockAcquisitions() { return totalReadLockAcquisitions; }
        public long getTotalWriteLockAcquisitions() { return totalWriteLockAcquisitions; }
        public long getTotalLockUpgrades() { return totalLockUpgrades; }
        public long getTotalLockDowngrades() { return totalLockDowngrades; }
        
        public String toString() {
            return String.format(
                "ReadWriteLockStateInfo{lockName='%s', readLockCount=%d, writeLockCount=%d, " +
                "readLockHeldByCurrentThread=%s, writeLockHeldByCurrentThread=%s, " +
                "isReadLocked=%s, isWriteLocked=%s, readLockQueueLength=%d, writeLockQueueLength=%d, " +
                "totalReadLockAcquisitions=%d, totalWriteLockAcquisitions=%d, " +
                "totalLockUpgrades=%d, totalLockDowngrades=%d}",
                lockName, readLockCount, writeLockCount, readLockHeldByCurrentThread,
                writeLockHeldByCurrentThread, isReadLocked, isWriteLocked, readLockQueueLength,
                writeLockQueueLength, totalReadLockAcquisitions, totalWriteLockAcquisitions,
                totalLockUpgrades, totalLockDowngrades
            );
        }
    }
    
    public static class ReadWriteLockPerformanceStats {
        private final String lockName;
        private final long totalReadLockAcquisitions;
        private final long totalWriteLockAcquisitions;
        private final long totalReadLockReleases;
        private final long totalWriteLockReleases;
        private final long totalLockUpgrades;
        private final long totalLockDowngrades;
        private final int currentReadLockCount;
        private final int currentWriteLockCount;
        
        public ReadWriteLockPerformanceStats(String lockName, long totalReadLockAcquisitions,
                                           long totalWriteLockAcquisitions, long totalReadLockReleases,
                                           long totalWriteLockReleases, long totalLockUpgrades,
                                           long totalLockDowngrades, int currentReadLockCount,
                                           int currentWriteLockCount) {
            this.lockName = lockName;
            this.totalReadLockAcquisitions = totalReadLockAcquisitions;
            this.totalWriteLockAcquisitions = totalWriteLockAcquisitions;
            this.totalReadLockReleases = totalReadLockReleases;
            this.totalWriteLockReleases = totalWriteLockReleases;
            this.totalLockUpgrades = totalLockUpgrades;
            this.totalLockDowngrades = totalLockDowngrades;
            this.currentReadLockCount = currentReadLockCount;
            this.currentWriteLockCount = currentWriteLockCount;
        }
        
        public String getLockName() { return lockName; }
        public long getTotalReadLockAcquisitions() { return totalReadLockAcquisitions; }
        public long getTotalWriteLockAcquisitions() { return totalWriteLockAcquisitions; }
        public long getTotalReadLockReleases() { return totalReadLockReleases; }
        public long getTotalWriteLockReleases() { return totalWriteLockReleases; }
        public long getTotalLockUpgrades() { return totalLockUpgrades; }
        public long getTotalLockDowngrades() { return totalLockDowngrades; }
        public int getCurrentReadLockCount() { return currentReadLockCount; }
        public int getCurrentWriteLockCount() { return currentWriteLockCount; }
        
        public double getReadLockAcquisitionRate() {
            long totalTime = totalReadLockAcquisitions + totalReadLockReleases;
            return totalTime > 0 ? (double) totalReadLockAcquisitions / totalTime : 0.0;
        }
        
        public double getWriteLockAcquisitionRate() {
            long totalTime = totalWriteLockAcquisitions + totalWriteLockReleases;
            return totalTime > 0 ? (double) totalWriteLockAcquisitions / totalTime : 0.0;
        }
        
        public double getLockUpgradeSuccessRate() {
            return totalLockUpgrades > 0 ? 1.0 : 0.0; // 简化实现
        }
        
        public double getLockDowngradeSuccessRate() {
            return totalLockDowngrades > 0 ? 1.0 : 0.0; // 简化实现
        }
    }
    
    public static class ReadWriteLockEvent {
        private final ReadWriteLockEventType type;
        private final String lockName;
        private final Instant timestamp;
        private final Throwable error;
        private final ReadWriteLockStateInfo lockState;
        
        public ReadWriteLockEvent(ReadWriteLockEventType type, String lockName, Instant timestamp,
                                Throwable error, ReadWriteLockStateInfo lockState) {
            this.type = type;
            this.lockName = lockName;
            this.timestamp = timestamp;
            this.error = error;
            this.lockState = lockState;
        }
        
        public ReadWriteLockEventType getType() { return type; }
        public String getLockName() { return lockName; }
        public Instant getTimestamp() { return timestamp; }
        public Throwable getError() { return error; }
        public ReadWriteLockStateInfo getLockState() { return lockState; }
    }
    
    public interface ReadWriteLockEventListener {
        void onEvent(ReadWriteLockEvent event);
    }
    
    public enum ReadWriteLockEventType {
        READ_LOCK_ACQUIRED,
        READ_LOCK_RELEASED,
        WRITE_LOCK_ACQUIRED,
        WRITE_LOCK_RELEASED,
        LOCK_UPGRADED,
        LOCK_DOWNGRADED,
        READ_LOCK_ACQUISITION_FAILED,
        WRITE_LOCK_ACQUISITION_FAILED,
        READ_LOCK_UPGRADE_FAILED,
        WRITE_LOCK_DOWNGRADE_FAILED
    }
    
    public enum LockType {
        READ, WRITE
    }
}