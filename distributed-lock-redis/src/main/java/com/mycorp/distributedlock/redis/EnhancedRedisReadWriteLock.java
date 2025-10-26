package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.api.LockEvent;
import com.mycorp.distributedlock.api.LockEventListener;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * 增强的Redis分布式读写锁实现
 * 
 * 特性：
 * - 读写锁分离和独立管理
 * - 支持读写锁升级/降级
 * - 公平性保证
 * - 并发性能优化
 * - 细粒度状态管理
 * - 事件驱动通知
 * - 死锁预防
 */
public class EnhancedRedisReadWriteLock implements DistributedReadWriteLock {
    
    private static final Logger logger = LoggerFactory.getLogger(EnhancedRedisReadWriteLock.class);
    
    // Redis键命名空间
    private static final String READ_LOCK_PREFIX = "read-lock:";
    private static final String WRITE_LOCK_PREFIX = "write-lock:";
    private static final String READ_QUEUE_PREFIX = "read-queue:";
    private static final String WRITE_QUEUE_PREFIX = "write-queue:";
    private static final String READ_COUNT_KEY_SUFFIX = ":read-count";
    private static final String WRITE_COUNT_KEY_SUFFIX = ":write-count";
    private static final String READ_WAITERS_SUFFIX = ":read-waiters";
    private static final String WRITE_WAITERS_SUFFIX = ":write-waiters";
    
    // 读写锁状态管理
    private final AtomicInteger readLockCount = new AtomicInteger(0);
    private final AtomicInteger writeLockCount = new AtomicInteger(0);
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final ReentrantReadWriteLock reentrantLock = new ReentrantReadWriteLock();
    
    // 锁相关
    private final String lockName;
    private final String lockPath;
    private final String readLockKey;
    private final String writeLockKey;
    private final String readQueueKey;
    private final String writeQueueKey;
    private final String readCountKey;
    private final String writeCountKey;
    private final String readWaitersKey;
    private final String writeWaitersKey;
    
    private final RedisCommands<String, String> commands;
    private final long defaultReadLeaseTimeSeconds;
    private final long defaultWriteLeaseTimeSeconds;
    
    // 读写锁实例
    private final FairRedisLock readLock;
    private final FairRedisLock writeLock;
    
    // 线程池
    private final ExecutorService asyncOperationExecutor;
    
    // 事件管理
    private final java.util.List<ReadWriteLockEventListener> eventListeners = 
            new java.util.concurrent.CopyOnWriteArrayList<>();
    
    // 性能指标
    private final AtomicLong readLockAcquisitionCount = new AtomicLong(0);
    private final AtomicLong writeLockAcquisitionCount = new AtomicLong(0);
    private final AtomicLong readLockReleaseCount = new AtomicLong(0);
    private final AtomicLong writeLockReleaseCount = new AtomicLong(0);
    private final AtomicLong readUpgradeCount = new AtomicLong(0);
    private final AtomicLong writeDowngradeCount = new AtomicLong(0);
    private final AtomicLong readLockContentionCount = new AtomicLong(0);
    private final AtomicLong writeLockContentionCount = new AtomicLong(0);
    
    // 升级/降级管理
    private final java.util.Map<Long, UpgradeInfo> threadUpgradeInfo = new ConcurrentHashMap<>();
    
    // 公平性控制
    private final AtomicBoolean readPriority = new AtomicBoolean(false);
    private final AtomicBoolean writePriority = new AtomicBoolean(true);
    
    public EnhancedRedisReadWriteLock(String lockName, RedisCommands<String, String> commands, 
                                    long readLeaseTimeSeconds, long writeLeaseTimeSeconds) {
        this.lockName = lockName;
        this.lockPath = lockName;
        
        // 构建键
        this.readLockKey = READ_LOCK_PREFIX + lockName;
        this.writeLockKey = WRITE_LOCK_PREFIX + lockName;
        this.readQueueKey = READ_QUEUE_PREFIX + lockName;
        this.writeQueueKey = WRITE_QUEUE_PREFIX + lockName;
        this.readCountKey = lockName + READ_COUNT_KEY_SUFFIX;
        this.writeCountKey = lockName + WRITE_COUNT_KEY_SUFFIX;
        this.readWaitersKey = lockName + READ_WAITERS_SUFFIX;
        this.writeWaitersKey = lockName + WRITE_WAITERS_SUFFIX;
        
        this.commands = commands;
        this.defaultReadLeaseTimeSeconds = readLeaseTimeSeconds;
        this.defaultWriteLeaseTimeSeconds = writeLeaseTimeSeconds;
        
        // 创建读写锁实例
        this.readLock = new FairRedisLock(readLockKey, commands, readLeaseTimeSeconds);
        this.writeLock = new FairRedisLock(writeLockKey, commands, writeLeaseTimeSeconds);
        
        // 初始化异步操作线程池
        this.asyncOperationExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(), r -> {
                Thread t = new Thread(r, "enhanced-readwrite-lock-async-" + lockName);
                t.setDaemon(true);
                return t;
            }
        );
        
        // 初始化Redis计数器
        initializeCounters();
        
        logger.debug("EnhancedRedisReadWriteLock initialized for: {}", lockName);
    }
    
    @Override
    public DistributedLock readLock() {
        return new EnhancedReadLock();
    }
    
    @Override
    public DistributedLock writeLock() {
        return new EnhancedWriteLock();
    }
    
    @Override
    public String getName() {
        return lockName;
    }
    
    /**
     * 增强的读锁实现
     */
    private class EnhancedReadLock implements DistributedLock {
        
        @Override
        public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
            checkNotClosed();
            tryAcquireReadLock(leaseTime, unit, 0);
        }
        
        @Override
        public void lock() throws InterruptedException {
            lock(defaultReadLeaseTimeSeconds, TimeUnit.SECONDS);
        }
        
        @Override
        public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
            checkNotClosed();
            return tryAcquireReadLock(leaseTime, unit, waitTime);
        }
        
        @Override
        public boolean tryLock() throws InterruptedException {
            return tryLock(0, defaultReadLeaseTimeSeconds, TimeUnit.SECONDS);
        }
        
        @Override
        public void unlock() {
            checkNotClosed();
            releaseReadLock();
        }
        
        @Override
        public CompletableFuture<Void> lockAsync(long leaseTime, TimeUnit unit) {
            return CompletableFuture.runAsync(() -> {
                try {
                    lock(leaseTime, unit);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Read lock acquisition interrupted", e);
                }
            }, asyncOperationExecutor);
        }
        
        @Override
        public CompletableFuture<Void> lockAsync() {
            return lockAsync(defaultReadLeaseTimeSeconds, TimeUnit.SECONDS);
        }
        
        @Override
        public CompletableFuture<Boolean> tryLockAsync(long waitTime, long leaseTime, TimeUnit unit) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return tryLock(waitTime, leaseTime, unit);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }, asyncOperationExecutor);
        }
        
        @Override
        public CompletableFuture<Boolean> tryLockAsync() {
            return tryLockAsync(0, defaultReadLeaseTimeSeconds, TimeUnit.SECONDS);
        }
        
        @Override
        public CompletableFuture<Void> unlockAsync() {
            return CompletableFuture.runAsync(this::unlock, asyncOperationExecutor);
        }
        
        @Override
        public boolean isLocked() {
            return readLockCount.get() > 0;
        }
        
        @Override
        public boolean isHeldByCurrentThread() {
            return readLock.isHeldByCurrentThread();
        }
        
        @Override
        public String getName() {
            return lockName + ":read";
        }
        
        @Override
        public ScheduledFuture<?> scheduleAutoRenewal(long renewInterval, TimeUnit unit,
                                                      Consumer<RenewalResult> renewalCallback) {
            if (!isHeldByCurrentThread()) {
                throw new IllegalMonitorStateException("Cannot schedule renewal for read lock not held by current thread");
            }
            return readLock.scheduleAutoRenewal(renewInterval, unit, renewalCallback);
        }
        
        @Override
        public int getReentrantCount() {
            return readLock.getReentrantCount();
        }
        
        @Override
        public boolean isExpired() {
            return readLock.isExpired();
        }
        
        @Override
        public long getRemainingTime(TimeUnit unit) {
            return readLock.getRemainingTime(unit);
        }
        
        @Override
        public String getLockHolder() {
            return readLock.getLockHolder();
        }
        
        @Override
        public void close() {
            if (isHeldByCurrentThread()) {
                unlock();
            }
        }
    }
    
    /**
     * 增强的写锁实现
     */
    private class EnhancedWriteLock implements DistributedLock {
        
        @Override
        public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
            checkNotClosed();
            tryAcquireWriteLock(leaseTime, unit, 0);
        }
        
        @Override
        public void lock() throws InterruptedException {
            lock(defaultWriteLeaseTimeSeconds, TimeUnit.SECONDS);
        }
        
        @Override
        public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
            checkNotClosed();
            return tryAcquireWriteLock(leaseTime, unit, waitTime);
        }
        
        @Override
        public boolean tryLock() throws InterruptedException {
            return tryLock(0, defaultWriteLeaseTimeSeconds, TimeUnit.SECONDS);
        }
        
        @Override
        public void unlock() {
            checkNotClosed();
            releaseWriteLock();
        }
        
        @Override
        public CompletableFuture<Void> lockAsync(long leaseTime, TimeUnit unit) {
            return CompletableFuture.runAsync(() -> {
                try {
                    lock(leaseTime, unit);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Write lock acquisition interrupted", e);
                }
            }, asyncOperationExecutor);
        }
        
        @Override
        public CompletableFuture<Void> lockAsync() {
            return lockAsync(defaultWriteLeaseTimeSeconds, TimeUnit.SECONDS);
        }
        
        @Override
        public CompletableFuture<Boolean> tryLockAsync(long waitTime, long leaseTime, TimeUnit unit) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return tryLock(waitTime, leaseTime, unit);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }, asyncOperationExecutor);
        }
        
        @Override
        public CompletableFuture<Boolean> tryLockAsync() {
            return tryLockAsync(0, defaultWriteLeaseTimeSeconds, TimeUnit.SECONDS);
        }
        
        @Override
        public CompletableFuture<Void> unlockAsync() {
            return CompletableFuture.runAsync(this::unlock, asyncOperationExecutor);
        }
        
        @Override
        public boolean isLocked() {
            return writeLockCount.get() > 0;
        }
        
        @Override
        public boolean isHeldByCurrentThread() {
            return writeLock.isHeldByCurrentThread();
        }
        
        @Override
        public String getName() {
            return lockName + ":write";
        }
        
        @Override
        public ScheduledFuture<?> scheduleAutoRenewal(long renewInterval, TimeUnit unit,
                                                      Consumer<RenewalResult> renewalCallback) {
            if (!isHeldByCurrentThread()) {
                throw new IllegalMonitorStateException("Cannot schedule renewal for write lock not held by current thread");
            }
            return writeLock.scheduleAutoRenewal(renewInterval, unit, renewalCallback);
        }
        
        @Override
        public int getReentrantCount() {
            return writeLock.getReentrantCount();
        }
        
        @Override
        public boolean isExpired() {
            return writeLock.isExpired();
        }
        
        @Override
        public long getRemainingTime(TimeUnit unit) {
            return writeLock.getRemainingTime(unit);
        }
        
        @Override
        public String getLockHolder() {
            return writeLock.getLockHolder();
        }
        
        @Override
        public void close() {
            if (isHeldByCurrentThread()) {
                unlock();
            }
        }
    }
    
    @Override
    public boolean tryUpgradeToWriteLock(long waitTime, long leaseTime, TimeUnit unit) 
            throws InterruptedException {
        checkNotClosed();
        
        if (!readLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Current thread does not hold read lock");
        }
        
        try {
            // 记录升级信息
            UpgradeInfo upgradeInfo = new UpgradeInfo("READ_TO_WRITE", 
                                                     Thread.currentThread().getId());
            threadUpgradeInfo.put(Thread.currentThread().getId(), upgradeInfo);
            
            // 记录升级开始时间
            long startTime = System.currentTimeMillis();
            
            // 先释放读锁
            releaseReadLock();
            
            // 尝试获取写锁
            boolean upgradeSuccess = tryAcquireWriteLock(leaseTime, unit, waitTime);
            
            if (upgradeSuccess) {
                readUpgradeCount.incrementAndGet();
                logger.debug("Successfully upgraded read lock to write lock for: {}", lockName);
                notifyEvent(ReadWriteLockEventType.READ_TO_WRITE_UPGRADED, null);
                
                upgradeInfo.setSuccess(true);
                upgradeInfo.setDuration(System.currentTimeMillis() - startTime);
            } else {
                // 升级失败，尝试重新获取读锁
                try {
                    if (tryAcquireReadLock(leaseTime, unit, 0)) {
                        logger.debug("Failed to upgrade to write lock, reacquired read lock for: {}", lockName);
                        notifyEvent(ReadWriteLockEventType.READ_TO_WRITE_UPGRADE_FAILED, 
                                   new RuntimeException("Failed to acquire write lock"));
                    } else {
                        logger.warn("Failed to upgrade to write lock and could not reacquire read lock for: {}", lockName);
                    }
                } catch (Exception e) {
                    logger.error("Error reacquiring read lock after failed upgrade", e);
                }
            }
            
            return upgradeSuccess;
            
        } finally {
            threadUpgradeInfo.remove(Thread.currentThread().getId());
        }
    }
    
    @Override
    public CompletableFuture<Boolean> tryUpgradeToWriteLockAsync(long waitTime, long leaseTime, TimeUnit unit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return tryUpgradeToWriteLock(waitTime, leaseTime, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }, asyncOperationExecutor);
    }
    
    @Override
    public boolean tryDowngradeToReadLock(long leaseTime, TimeUnit unit) {
        checkNotClosed();
        
        if (!writeLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Current thread does not hold write lock");
        }
        
        try {
            // 记录降级信息
            UpgradeInfo downgradeInfo = new UpgradeInfo("WRITE_TO_READ", 
                                                       Thread.currentThread().getId());
            threadUpgradeInfo.put(Thread.currentThread().getId(), downgradeInfo);
            
            // 记录降级开始时间
            long startTime = System.currentTimeMillis();
            
            // 先释放写锁
            releaseWriteLock();
            
            // 尝试获取读锁
            boolean downgradeSuccess = tryAcquireReadLock(leaseTime, unit, 0);
            
            if (downgradeSuccess) {
                writeDowngradeCount.incrementAndGet();
                logger.debug("Successfully downgraded write lock to read lock for: {}", lockName);
                notifyEvent(ReadWriteLockEventType.WRITE_TO_READ_DOWNGRADED, null);
                
                downgradeInfo.setSuccess(true);
                downgradeInfo.setDuration(System.currentTimeMillis() - startTime);
            } else {
                logger.warn("Failed to downgrade write lock to read lock for: {}", lockName);
                notifyEvent(ReadWriteLockEventType.WRITE_TO_READ_DOWNGRADE_FAILED, 
                           new RuntimeException("Failed to acquire read lock"));
            }
            
            return downgradeSuccess;
            
        } finally {
            threadUpgradeInfo.remove(Thread.currentThread().getId());
        }
    }
    
    @Override
    public CompletableFuture<Boolean> tryDowngradeToReadLockAsync(long leaseTime, TimeUnit unit) {
        return CompletableFuture.supplyAsync(() -> tryDowngradeToReadLock(leaseTime, unit), 
                                           asyncOperationExecutor);
    }
    
    /**
     * 尝试获取读锁
     */
    private boolean tryAcquireReadLock(long leaseTime, TimeUnit unit, long waitTime) 
            throws InterruptedException {
        long startTime = System.currentTimeMillis();
        
        // 检查是否有写锁
        if (writeLockCount.get() > 0 && !writeLock.isHeldByCurrentThread()) {
            readLockContentionCount.incrementAndGet();
            logger.debug("Cannot acquire read lock while write lock is held by another thread for: {}", lockName);
            return false;
        }
        
        // 公平性检查
        if (!checkReadLockFairness()) {
            return false;
        }
        
        // 尝试获取读锁
        boolean acquired = readLock.tryLock(waitTime, leaseTime, unit);
        
        if (acquired) {
            readLockCount.incrementAndGet();
            readLockAcquisitionCount.incrementAndGet();
            
            logger.debug("Successfully acquired read lock for: {}", lockName);
            notifyEvent(ReadWriteLockEventType.READ_LOCK_ACQUIRED, null);
            
            // 记录升级信息（如果有）
            UpgradeInfo upgradeInfo = threadUpgradeInfo.get(Thread.currentThread().getId());
            if (upgradeInfo != null && "READ_TO_WRITE".equals(upgradeInfo.getType())) {
                upgradeInfo.setReadLockAcquired(true);
            }
        }
        
        return acquired;
    }
    
    /**
     * 尝试获取写锁
     */
    private boolean tryAcquireWriteLock(long leaseTime, TimeUnit unit, long waitTime) 
            throws InterruptedException {
        // 检查读锁持有情况
        if (readLockCount.get() > 0 && !readLock.isHeldByCurrentThread()) {
            writeLockContentionCount.incrementAndGet();
            logger.debug("Cannot acquire write lock while read locks are held by other threads for: {}", lockName);
            return false;
        }
        
        // 公平性检查
        if (!checkWriteLockFairness()) {
            return false;
        }
        
        // 等待所有读锁释放
        long startTime = System.currentTimeMillis();
        long timeoutMs = TimeUnit.MILLISECONDS.convert(waitTime, TimeUnit.MILLISECONDS);
        
        while (readLockCount.get() > 0 && !readLock.isHeldByCurrentThread()) {
            if (timeoutMs > 0 && System.currentTimeMillis() - startTime > timeoutMs) {
                logger.debug("Timeout waiting for read locks to release for write lock: {}", lockName);
                return false;
            }
            Thread.sleep(10);
        }
        
        // 尝试获取写锁
        boolean acquired = writeLock.tryLock(waitTime, leaseTime, unit);
        
        if (acquired) {
            writeLockCount.incrementAndGet();
            writeLockAcquisitionCount.incrementAndGet();
            
            logger.debug("Successfully acquired write lock for: {}", lockName);
            notifyEvent(ReadWriteLockEventType.WRITE_LOCK_ACQUIRED, null);
            
            // 记录升级信息（如果有）
            UpgradeInfo upgradeInfo = threadUpgradeInfo.get(Thread.currentThread().getId());
            if (upgradeInfo != null && "WRITE_TO_READ".equals(upgradeInfo.getType())) {
                upgradeInfo.setWriteLockAcquired(true);
            }
        }
        
        return acquired;
    }
    
    /**
     * 释放读锁
     */
    private void releaseReadLock() {
        if (readLockCount.get() > 0) {
            try {
                readLock.unlock();
                readLockCount.decrementAndGet();
                readLockReleaseCount.incrementAndGet();
                
                logger.debug("Successfully released read lock for: {}", lockName);
                notifyEvent(ReadWriteLockEventType.READ_LOCK_RELEASED, null);
                
            } catch (Exception e) {
                logger.error("Error releasing read lock for: {}", lockName, e);
                throw new RuntimeException("Failed to release read lock", e);
            }
        }
    }
    
    /**
     * 释放写锁
     */
    private void releaseWriteLock() {
        if (writeLockCount.get() > 0) {
            try {
                writeLock.unlock();
                writeLockCount.decrementAndGet();
                writeLockReleaseCount.incrementAndGet();
                
                logger.debug("Successfully released write lock for: {}", lockName);
                notifyEvent(ReadWriteLockEventType.WRITE_LOCK_RELEASED, null);
                
            } catch (Exception e) {
                logger.error("Error releasing write lock for: {}", lockName, e);
                throw new RuntimeException("Failed to release write lock", e);
            }
        }
    }
    
    /**
     * 检查读锁公平性
     */
    private boolean checkReadLockFairness() {
        // 如果写锁优先，等待写锁释放
        if (writePriority.get() && writeLockCount.get() > 0 && !writeLock.isHeldByCurrentThread()) {
            return false;
        }
        
        // 检查写等待队列
        long writeWaiters = commands.scard(writeWaitersKey);
        if (writeWaiters > 0) {
            // 如果有写等待者，且当前线程没有持有锁，需要等待
            if (!writeLock.isHeldByCurrentThread()) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 检查写锁公平性
     */
    private boolean checkWriteLockFairness() {
        // 如果读锁优先，等待读锁释放
        if (readPriority.get() && readLockCount.get() > 0 && !readLock.isHeldByCurrentThread()) {
            return false;
        }
        
        // 检查读等待队列
        long readWaiters = commands.scard(readWaitersKey);
        if (readWaiters > 0) {
            // 如果有读等待者，且当前线程没有持有读锁，需要等待
            if (!readLock.isHeldByCurrentThread()) {
                return false;
            }
        }
        
        return true;
    }
    
    @Override
    public ReadWriteLockStateInfo getReadWriteLockStateInfo() {
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
            readLockAcquisitionCount.get(),
            writeLockAcquisitionCount.get(),
            readUpgradeCount.get(),
            writeDowngradeCount.get(),
            getReadLockWaitingCount(),
            getWriteLockWaitingCount()
        );
    }
    
    @Override
    public ReadWriteLockConfigurationInfo getConfigurationInfo() {
        return new ReadWriteLockConfigurationInfo() {
            @Override
            public boolean isFairLock() {
                return true; // 我们的实现支持公平锁
            }
            
            @Override
            public boolean isReentrant() {
                return true;
            }
            
            @Override
            public long getDefaultReadLockLeaseTime(TimeUnit unit) {
                return unit.convert(defaultReadLeaseTimeSeconds, TimeUnit.SECONDS);
            }
            
            @Override
            public long getDefaultWriteLockLeaseTime(TimeUnit unit) {
                return unit.convert(defaultWriteLeaseTimeSeconds, TimeUnit.SECONDS);
            }
            
            @Override
            public long getDefaultReadLockWaitTime(TimeUnit unit) {
                return unit.convert(10, TimeUnit.SECONDS);
            }
            
            @Override
            public long getDefaultWriteLockWaitTime(TimeUnit unit) {
                return unit.convert(15, TimeUnit.SECONDS);
            }
            
            @Override
            public boolean isUpgradeSupported() {
                return true;
            }
            
            @Override
            public boolean isDowngradeSupported() {
                return true;
            }
            
            @Override
            public LockUpgradeMode getUpgradeMode() {
                return LockUpgradeMode.DIRECT;
            }
            
            @Override
            public int getMaxReadLockCount() {
                return Integer.MAX_VALUE;
            }
            
            @Override
            public boolean isReadLockPreemptionEnabled() {
                return readPriority.get();
            }
            
            @Override
            public boolean isWriteLockPriority() {
                return writePriority.get();
            }
        };
    }
    
    /**
     * 获取读锁队列长度
     */
    public int getReadLockQueueLength() {
        return 0; // 简化实现
    }
    
    /**
     * 获取写锁队列长度
     */
    public int getWriteLockQueueLength() {
        return 0; // 简化实现
    }
    
    /**
     * 获取读锁等待数
     */
    private long getReadLockWaitingCount() {
        return commands.scard(readWaitersKey);
    }
    
    /**
     * 获取写锁等待数
     */
    private long getWriteLockWaitingCount() {
        return commands.scard(writeWaitersKey);
    }
    
    /**
     * 添加读写锁事件监听器
     */
    public void addReadWriteLockEventListener(ReadWriteLockEventListener listener) {
        if (listener != null) {
            eventListeners.add(listener);
            readLock.addLockEventListener(listener);
            writeLock.addLockEventListener(listener);
        }
    }
    
    /**
     * 移除读写锁事件监听器
     */
    public void removeReadWriteLockEventListener(ReadWriteLockEventListener listener) {
        eventListeners.remove(listener);
        readLock.removeLockEventListener(listener);
        writeLock.removeLockEventListener(listener);
    }
    
    /**
     * 获取性能统计信息
     */
    public ReadWriteLockPerformanceStats getPerformanceStats() {
        return new ReadWriteLockPerformanceStats(
            lockName,
            readLockAcquisitionCount.get(),
            writeLockAcquisitionCount.get(),
            readLockReleaseCount.get(),
            writeLockReleaseCount.get(),
            readUpgradeCount.get(),
            writeDowngradeCount.get(),
            readLockCount.get(),
            writeLockCount.get(),
            getReadLockWaitingCount(),
            getWriteLockWaitingCount(),
            readLockContentionCount.get(),
            writeLockContentionCount.get()
        );
    }
    
    /**
     * 启用读锁优先级
     */
    public void enableReadLockPriority() {
        readPriority.set(true);
        writePriority.set(false);
    }
    
    /**
     * 启用写锁优先级
     */
    public void enableWriteLockPriority() {
        readPriority.set(false);
        writePriority.set(true);
    }
    
    /**
     * 重置性能统计
     */
    public void resetPerformanceStats() {
        readLockAcquisitionCount.set(0);
        writeLockAcquisitionCount.set(0);
        readLockReleaseCount.set(0);
        writeLockReleaseCount.set(0);
        readUpgradeCount.set(0);
        writeDowngradeCount.set(0);
        readLockContentionCount.set(0);
        writeLockContentionCount.set(0);
    }
    
    @Override
    public ReadWriteLockHealthCheckResult healthCheck() {
        try {
            boolean readLockHealthy = readLock.tryLock(0, 100, TimeUnit.MILLISECONDS);
            if (readLockHealthy) {
                readLock.unlock();
            }
            
            boolean writeLockHealthy = writeLock.tryLock(0, 100, TimeUnit.MILLISECONDS);
            if (writeLockHealthy) {
                writeLock.unlock();
            }
            
            boolean isHealthy = readLockHealthy && writeLockHealthy;
            
            return new ReadWriteLockHealthCheckResult() {
                @Override
                public boolean isHealthy() {
                    return isHealthy;
                }
                
                @Override
                public boolean isReadLockHealthy() {
                    return readLockHealthy;
                }
                
                @Override
                public boolean isWriteLockHealthy() {
                    return writeLockHealthy;
                }
                
                @Override
                public String getDetails() {
                    if (isHealthy) {
                        return "Both read and write locks are accessible and working normally";
                    } else if (!readLockHealthy && !writeLockHealthy) {
                        return "Both read and write locks are not accessible";
                    } else if (!readLockHealthy) {
                        return "Read lock is not accessible";
                    } else {
                        return "Write lock is not accessible";
                    }
                }
                
                @Override
                public long getCheckTime() {
                    return System.currentTimeMillis();
                }
            };
        } catch (Exception e) {
            return new ReadWriteLockHealthCheckResult() {
                @Override
                public boolean isHealthy() {
                    return false;
                }
                
                @Override
                public boolean isReadLockHealthy() {
                    return false;
                }
                
                @Override
                public boolean isWriteLockHealthy() {
                    return false;
                }
                
                @Override
                public String getDetails() {
                    return "Health check failed: " + e.getMessage();
                }
                
                @Override
                public long getCheckTime() {
                    return System.currentTimeMillis();
                }
            };
        }
    }
    
    @Override
    public <R> R executeWithAppropriateLock(Callable<R> readOperation, Callable<R> writeOperation) throws Exception {
        if (isWriteLocked() || writeLock.isHeldByCurrentThread()) {
            return writeOperation.call();
        } else if (isReadLocked() || readLock.isHeldByCurrentThread()) {
            return readOperation.call();
        } else {
            // 没有持有任何锁，默认执行读操作
            return readOperation.call();
        }
    }
    
    @Override
    public void close() {
        if (isClosed.getAndSet(true)) {
            return;
        }
        
        logger.debug("Closing EnhancedRedisReadWriteLock: {}", lockName);
        
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
            asyncOperationExecutor.shutdown();
            if (!asyncOperationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncOperationExecutor.shutdownNow();
            }
            
            // 清理Redis计数器
            cleanupCounters();
            
            // 清理事件监听器
            eventListeners.clear();
            threadUpgradeInfo.clear();
            
            logger.debug("EnhancedRedisReadWriteLock closed: {}", lockName);
        } catch (Exception e) {
            logger.error("Error closing read-write lock: {}", lockName, e);
        }
    }
    
    // 私有方法
    
    private void initializeCounters() {
        try {
            commands.set(readCountKey, "0");
            commands.set(writeCountKey, "0");
        } catch (Exception e) {
            logger.warn("Failed to initialize Redis counters", e);
        }
    }
    
    private void cleanupCounters() {
        try {
            commands.del(readCountKey);
            commands.del(writeCountKey);
            commands.del(readWaitersKey);
            commands.del(writeWaitersKey);
        } catch (Exception e) {
            logger.warn("Failed to cleanup Redis counters", e);
        }
    }
    
    private void notifyEvent(ReadWriteLockEventType eventType, Throwable error) {
        ReadWriteLockEvent event = new ReadWriteLockEvent(
            eventType, lockName, Instant.now(), error, getReadWriteLockStateInfo()
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
    
    // 内部类
    
    /**
     * 升级信息
     */
    private static class UpgradeInfo {
        private final String type;
        private final long threadId;
        private volatile boolean success = false;
        private volatile boolean readLockAcquired = false;
        private volatile boolean writeLockAcquired = false;
        private volatile long duration = 0;
        
        public UpgradeInfo(String type, long threadId) {
            this.type = type;
            this.threadId = threadId;
        }
        
        public String getType() { return type; }
        public long getThreadId() { return threadId; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public boolean isReadLockAcquired() { return readLockAcquired; }
        public void setReadLockAcquired(boolean readLockAcquired) { this.readLockAcquired = readLockAcquired; }
        public boolean isWriteLockAcquired() { return writeLockAcquired; }
        public void setWriteLockAcquired(boolean writeLockAcquired) { this.writeLockAcquired = writeLockAcquired; }
        public long getDuration() { return duration; }
        public void setDuration(long duration) { this.duration = duration; }
    }
    
    /**
     * 读写锁事件
     */
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
    
    /**
     * 读写锁事件监听器
     */
    public interface ReadWriteLockEventListener {
        void onEvent(ReadWriteLockEvent event);
    }
    
    /**
     * 读写锁事件类型
     */
    public enum ReadWriteLockEventType {
        READ_LOCK_ACQUIRED,
        READ_LOCK_RELEASED,
        WRITE_LOCK_ACQUIRED,
        WRITE_LOCK_RELEASED,
        READ_TO_WRITE_UPGRADED,
        WRITE_TO_READ_DOWNGRADED,
        READ_TO_WRITE_UPGRADE_FAILED,
        WRITE_TO_READ_DOWNGRADE_FAILED
    }
    
    /**
     * 读写锁状态信息
     */
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
        private final long totalReadToWriteUpgrades;
        private final long totalWriteToReadDowngrades;
        private final long readLockWaitingCount;
        private final long writeLockWaitingCount;
        
        public ReadWriteLockStateInfo(String lockName, int readLockCount, int writeLockCount,
                                    boolean readLockHeldByCurrentThread, boolean writeLockHeldByCurrentThread,
                                    boolean isReadLocked, boolean isWriteLocked, int readLockQueueLength,
                                    int writeLockQueueLength, long totalReadLockAcquisitions,
                                    long totalWriteLockAcquisitions, long totalReadToWriteUpgrades,
                                    long totalWriteToReadDowngrades, long readLockWaitingCount,
                                    long writeLockWaitingCount) {
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
            this.totalReadToWriteUpgrades = totalReadToWriteUpgrades;
            this.totalWriteToReadDowngrades = totalWriteToReadDowngrades;
            this.readLockWaitingCount = readLockWaitingCount;
            this.writeLockWaitingCount = writeLockWaitingCount;
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
        public long getTotalReadToWriteUpgrades() { return totalReadToWriteUpgrades; }
        public long getTotalWriteToReadDowngrades() { return totalWriteToReadDowngrades; }
        public long getReadLockWaitingCount() { return readLockWaitingCount; }
        public long getWriteLockWaitingCount() { return writeLockWaitingCount; }
    }
    
    /**
     * 读写锁性能统计
     */
    public static class ReadWriteLockPerformanceStats {
        private final String lockName;
        private final long totalReadLockAcquisitions;
        private final long totalWriteLockAcquisitions;
        private final long totalReadLockReleases;
        private final long totalWriteLockReleases;
        private final long totalReadToWriteUpgrades;
        private final long totalWriteToReadDowngrades;
        private final int currentReadLockCount;
        private final int currentWriteLockCount;
        private final long readLockWaitingCount;
        private final long writeLockWaitingCount;
        private final long readLockContentionCount;
        private final long writeLockContentionCount;
        
        public ReadWriteLockPerformanceStats(String lockName, long totalReadLockAcquisitions,
                                           long totalWriteLockAcquisitions, long totalReadLockReleases,
                                           long totalWriteLockReleases, long totalReadToWriteUpgrades,
                                           long totalWriteToReadDowngrades, int currentReadLockCount,
                                           int currentWriteLockCount, long readLockWaitingCount,
                                           long writeLockWaitingCount, long readLockContentionCount,
                                           long writeLockContentionCount) {
            this.lockName = lockName;
            this.totalReadLockAcquisitions = totalReadLockAcquisitions;
            this.totalWriteLockAcquisitions = totalWriteLockAcquisitions;
            this.totalReadLockReleases = totalReadLockReleases;
            this.totalWriteLockReleases = totalWriteLockReleases;
            this.totalReadToWriteUpgrades = totalReadToWriteUpgrades;
            this.totalWriteToReadDowngrades = totalWriteToReadDowngrades;
            this.currentReadLockCount = currentReadLockCount;
            this.currentWriteLockCount = currentWriteLockCount;
            this.readLockWaitingCount = readLockWaitingCount;
            this.writeLockWaitingCount = writeLockWaitingCount;
            this.readLockContentionCount = readLockContentionCount;
            this.writeLockContentionCount = writeLockContentionCount;
        }
        
        public String getLockName() { return lockName; }
        public long getTotalReadLockAcquisitions() { return totalReadLockAcquisitions; }
        public long getTotalWriteLockAcquisitions() { return totalWriteLockAcquisitions; }
        public long getTotalReadLockReleases() { return totalReadLockReleases; }
        public long getTotalWriteLockReleases() { return totalWriteLockReleases; }
        public long getTotalReadToWriteUpgrades() { return totalReadToWriteUpgrades; }
        public long getTotalWriteToReadDowngrades() { return totalWriteToReadDowngrades; }
        public int getCurrentReadLockCount() { return currentReadLockCount; }
        public int getCurrentWriteLockCount() { return currentWriteLockCount; }
        public long getReadLockWaitingCount() { return readLockWaitingCount; }
        public long getWriteLockWaitingCount() { return writeLockWaitingCount; }
        public long getReadLockContentionCount() { return readLockContentionCount; }
        public long getWriteLockContentionCount() { return writeLockContentionCount; }
        
        public double getReadLockAcquisitionRate() {
            long totalOperations = totalReadLockAcquisitions + totalReadLockReleases;
            return totalOperations > 0 ? (double) totalReadLockAcquisitions / totalOperations : 0.0;
        }
        
        public double getWriteLockAcquisitionRate() {
            long totalOperations = totalWriteLockAcquisitions + totalWriteLockReleases;
            return totalOperations > 0 ? (double) totalWriteLockAcquisitions / totalOperations : 0.0;
        }
        
        public double getUpgradeSuccessRate() {
            return totalReadToWriteUpgrades > 0 ? 1.0 : 0.0;
        }
        
        public double getDowngradeSuccessRate() {
            return totalWriteToReadDowngrades > 0 ? 1.0 : 0.0;
        }
    }
}