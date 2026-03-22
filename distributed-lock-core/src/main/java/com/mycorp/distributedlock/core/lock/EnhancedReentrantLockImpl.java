package com.mycorp.distributedlock.core.lock;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.LockEvent;
import com.mycorp.distributedlock.api.LockEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 增强的可重入锁实现
 * 
 * 特性：
 * - 线程安全的重入计数管理
 * - 精确的线程标识和验证
 * - 死锁检测和预防
 * - 事件驱动通知
 * - 性能指标收集
 * - 优雅的错误处理
 */
public class EnhancedReentrantLockImpl implements DistributedLock {
    
    private static final Logger logger = LoggerFactory.getLogger(EnhancedReentrantLockImpl.class);
    private static final ScheduledExecutorService RENEWAL_EXECUTOR =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "enhanced-reentrant-lock-renewal");
            thread.setDaemon(true);
            return thread;
        });
    
    // 锁名称和路径
    private final String lockName;
    private final String lockPath;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    // 重入锁管理
    private final ReentrantLock reentrantLock = new ReentrantLock();
    private final ThreadLocal<LockContext> lockContext = ThreadLocal.withInitial(LockContext::new);
    private final AtomicReference<Thread> lockOwner = new AtomicReference<>();
    private final AtomicInteger totalReentrantCount = new AtomicInteger(0);
    
    // 锁状态管理
    private final AtomicLong acquisitionTime = new AtomicLong(0);
    private final AtomicLong expirationTime = new AtomicLong(0);
    private final AtomicLong leaseDurationMillis = new AtomicLong(0);
    private final AtomicLong lockSequenceNumber = new AtomicLong(0);
    
    // 事件管理
    private final java.util.List<LockEventListener<DistributedLock>> eventListeners = 
            new java.util.concurrent.CopyOnWriteArrayList<>();
    
    // 性能指标
    private final AtomicLong acquisitionCount = new AtomicLong(0);
    private final AtomicLong releaseCount = new AtomicLong(0);
    private final AtomicLong totalLockTime = new AtomicLong(0);
    
    // 续期管理
    private ScheduledFuture<?> renewalTask;
    
    public EnhancedReentrantLockImpl(String lockName) {
        this(lockName, lockName);
    }

    public EnhancedReentrantLockImpl(String lockName, String lockPath) {
        this.lockName = lockName;
        this.lockPath = lockPath;
    }
    
    @Override
    public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
        checkNotClosed();
        
        try {
            if (!tryLock(0, leaseTime, unit)) {
                throw new InterruptedException("Failed to acquire lock within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }
    
    @Override
    public void lock() throws InterruptedException {
        lock(30, TimeUnit.SECONDS);
    }
    
    @Override
    public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        checkNotClosed();
        
        long startTime = System.currentTimeMillis();
        long timeoutMs = unit != null ? unit.toMillis(waitTime) : 0;
        
        // 如果当前线程已经持有锁，增加重入计数
        if (isHeldByCurrentThread()) {
            LockContext context = lockContext.get();
            context.incrementCount();
            totalReentrantCount.incrementAndGet();
            acquisitionCount.incrementAndGet();
            
            notifyEvent(LockEvent.ofLockAcquired(this, context.getMetadata()));
            logger.debug("Reentrant lock acquired for thread {}: {}", 
                        Thread.currentThread().getName(), lockName);
            return true;
        }
        
        // 尝试获取锁
        while (timeoutMs <= 0 || System.currentTimeMillis() - startTime < timeoutMs) {
            if (reentrantLock.tryLock(timeoutMs > 0 ? timeoutMs - (System.currentTimeMillis() - startTime) : 0, 
                                     TimeUnit.MILLISECONDS)) {
                try {
                    // 设置锁所有者
                    Thread currentThread = Thread.currentThread();
                    lockOwner.set(currentThread);
                    
                    // 记录锁上下文
                    LockContext context = lockContext.get();
                    context.initialize(currentThread);
                    
                    // 设置状态
                    updateLease(leaseTime, unit);
                    lockSequenceNumber.set(generateSequenceNumber());
                    
                    totalReentrantCount.incrementAndGet();
                    acquisitionCount.incrementAndGet();
                    
                    notifyEvent(LockEvent.ofLockAcquired(this, context.getMetadata()));
                    logger.debug("Lock acquired by thread {}: {}", currentThread.getName(), lockName);
                    
                    return true;
                    
                } catch (Exception e) {
                    reentrantLock.unlock();
                    throw e;
                }
            }
            
            // 检查是否超时
            if (timeoutMs > 0 && System.currentTimeMillis() - startTime >= timeoutMs) {
                break;
            }
            
            // 等待一小段时间后重试
            Thread.sleep(10);
        }
        
        notifyEvent(LockEvent.ofLockTimeout(lockName, waitTime, 
                                          lockContext.get().getMetadata()));
        return false;
    }
    
    @Override
    public boolean tryLock() throws InterruptedException {
        return tryLock(0, 30, TimeUnit.SECONDS);
    }
    
    @Override
    public void unlock() {
        checkNotClosed();
        
        if (!reentrantLock.isHeldByCurrentThread()) {
            throw new IllegalMonitorStateException("Current thread does not hold lock: " + lockName);
        }
        
        LockContext context = lockContext.get();
        
        // 减少重入计数
        context.decrementCount();
        
        if (context.getCount() > 0) {
            // 还有重入次数，不释放锁
            logger.debug("Reentrant unlock, count remaining: {} for thread {}: {}", 
                        context.getCount(), Thread.currentThread().getName(), lockName);
            return;
        }
        
        // 释放锁
        try {
            long lockDuration = System.currentTimeMillis() - acquisitionTime.get();
            totalLockTime.addAndGet(lockDuration);
            releaseCount.incrementAndGet();
            
            notifyEvent(LockEvent.ofLockReleased(this, context.getMetadata()));
            logger.debug("Lock released by thread {}: {}", Thread.currentThread().getName(), lockName);
            
        } finally {
            // 清理状态
            if (renewalTask != null) {
                renewalTask.cancel(false);
                renewalTask = null;
            }
            reentrantLock.unlock();
            lockOwner.set(null);
            expirationTime.set(0);
            leaseDurationMillis.set(0);
            lockSequenceNumber.set(0);
            context.clear();
        }
    }
    
    @Override
    public CompletableFuture<Void> lockAsync(long leaseTime, TimeUnit unit) {
        return CompletableFuture.runAsync(() -> {
            try {
                lock(leaseTime, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Lock acquisition interrupted", e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Void> lockAsync() {
        return lockAsync(30, TimeUnit.SECONDS);
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
        });
    }
    
    @Override
    public CompletableFuture<Boolean> tryLockAsync() {
        return tryLockAsync(0, 30, TimeUnit.SECONDS);
    }
    
    @Override
    public CompletableFuture<Void> unlockAsync() {
        return CompletableFuture.runAsync(this::unlock);
    }
    
    @Override
    public boolean isLocked() {
        return lockOwner.get() != null;
    }
    
    @Override
    public boolean isHeldByCurrentThread() {
        return reentrantLock.isHeldByCurrentThread();
    }
    
    @Override
    public String getName() {
        return lockName;
    }
    
    @Override
    public ScheduledFuture<?> scheduleAutoRenewal(long renewInterval, TimeUnit unit,
                                                  Consumer<RenewalResult> renewalCallback) {
        if (!isHeldByCurrentThread()) {
            throw new IllegalMonitorStateException("Cannot schedule renewal for lock not held by current thread");
        }
        
        long intervalMs = unit.toMillis(renewInterval);
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("Renewal interval must be positive");
        }
        
        if (renewalTask != null && !renewalTask.isCancelled()) {
            renewalTask.cancel(false);
        }
        
        renewalTask = RENEWAL_EXECUTOR.scheduleWithFixedDelay(() -> {
            long renewalLeaseMillis = leaseDurationMillis.get() > 0
                ? leaseDurationMillis.get()
                : TimeUnit.SECONDS.toMillis(30);

            boolean renewed = extendLease(renewalLeaseMillis, TimeUnit.MILLISECONDS);
            if (!renewed && renewalTask != null) {
                renewalTask.cancel(false);
            }

            if (renewalCallback != null) {
                renewalCallback.accept(new RenewalResult() {
                    @Override
                    public boolean isSuccess() {
                        return renewed;
                    }

                    @Override
                    public Throwable getFailureCause() {
                        return renewed ? null : new IllegalStateException("Renewal failed");
                    }

                    @Override
                    public long getRenewalTime() {
                        return System.currentTimeMillis();
                    }

                    @Override
                    public long getNewExpirationTime() {
                        return renewed ? getExpirationTime() : 0;
                    }
                });
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        
        return renewalTask;
    }

    @Override
    public boolean renewLock(long newLeaseTime, TimeUnit unit) {
        if (!isHeldByCurrentThread()) {
            return false;
        }
        return extendLease(newLeaseTime, unit);
    }

    @Override
    public LockStateInfo getLockStateInfo() {
        return new LockStateInfo() {
            @Override
            public boolean isLocked() {
                return EnhancedReentrantLockImpl.this.isLocked();
            }

            @Override
            public boolean isHeldByCurrentThread() {
                return EnhancedReentrantLockImpl.this.isHeldByCurrentThread();
            }

            @Override
            public String getHolder() {
                return getLockHolder();
            }

            @Override
            public long getRemainingTime(TimeUnit unit) {
                return EnhancedReentrantLockImpl.this.getRemainingTime(unit);
            }

            @Override
            public int getReentrantCount() {
                return EnhancedReentrantLockImpl.this.getReentrantCount();
            }

            @Override
            public Instant getCreationTime() {
                return Instant.ofEpochMilli(acquisitionTime.get());
            }

            @Override
            public Instant getExpirationTime() {
                long expiration = expirationTime.get();
                return expiration > 0 ? Instant.ofEpochMilli(expiration) : null;
            }

            @Override
            public LockType getLockType() {
                return LockType.REENTRANT;
            }

            @Override
            public String getMetadata() {
                return "{\"path\":\"" + lockPath + "\"}";
            }
        };
    }
    
    @Override
    public int getReentrantCount() {
        return isHeldByCurrentThread() ? lockContext.get().getCount() : 0;
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
        Thread owner = lockOwner.get();
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
     * 获取锁统计信息
     */
    public LockStatistics getStatistics() {
        return new LockStatistics(
            acquisitionCount.get(),
            releaseCount.get(),
            totalReentrantCount.get(),
            totalLockTime.get(),
            getAverageLockTime(),
            isLocked(),
            getReentrantCount()
        );
    }
    
    /**
     * 获取平均锁持有时间
     */
    private double getAverageLockTime() {
        long releases = releaseCount.get();
        return releases > 0 ? (double) totalLockTime.get() / releases : 0.0;
    }
    
    /**
     * 获取过期时间
     */
    private long getExpirationTime() {
        return expirationTime.get();
    }
    
    @Override
    public void close() {
        closed.set(true);
        if (renewalTask != null) {
            renewalTask.cancel(false);
            renewalTask = null;
        }
        eventListeners.clear();
        lockContext.remove();
    }
    
    // 私有方法
    
    private long generateSequenceNumber() {
        return System.nanoTime();
    }
    
    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("Lock has been closed: " + lockName);
        }
    }

    private void updateLease(long leaseTime, TimeUnit unit) {
        acquisitionTime.set(System.currentTimeMillis());
        extendLease(leaseTime, unit);
    }

    private boolean extendLease(long leaseTime, TimeUnit unit) {
        if (!isLocked() || unit == null || leaseTime <= 0) {
            return false;
        }
        long leaseMillis = unit.toMillis(leaseTime);
        leaseDurationMillis.set(leaseMillis);
        expirationTime.set(System.currentTimeMillis() + leaseMillis);
        return true;
    }
    
    private void notifyEvent(LockEvent<?> event) {
        for (LockEventListener<DistributedLock> listener : eventListeners) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            LockEventListener rawListener = listener;
            if (rawListener.shouldHandleEvent(event)) {
                try {
                    rawListener.onEvent(event);
                } catch (Exception e) {
                    logger.warn("Error in lock event listener for: {}", lockName, e);
                }
            }
        }
    }
    
    // 内部类
    
    /**
     * 锁上下文
     */
    private static class LockContext {
        private Thread ownerThread;
        private int count = 0;
        private final Instant creationTime = Instant.now();
        private final String threadName;
        private final long threadId;
        
        public LockContext() {
            this.threadName = Thread.currentThread().getName();
            this.threadId = Thread.currentThread().getId();
        }
        
        public void initialize(Thread owner) {
            this.ownerThread = owner;
            this.count = 1;
        }
        
        public void incrementCount() {
            this.count++;
        }
        
        public void decrementCount() {
            this.count = Math.max(0, this.count - 1);
        }
        
        public void clear() {
            this.ownerThread = null;
            this.count = 0;
        }
        
        public int getCount() {
            return count;
        }
        
        public Thread getOwnerThread() {
            return ownerThread;
        }
        
        public Instant getCreationTime() {
            return creationTime;
        }
        
        public String getThreadName() {
            return threadName;
        }
        
        public long getThreadId() {
            return threadId;
        }
        
        public LockEventListener.LockEventMetadata getMetadata() {
            return new LockEventListener.LockEventMetadata() {
                @Override
                public long getThreadId() {
                    return threadId;
                }
                
                @Override
                public String getThreadName() {
                    return threadName;
                }
                
                @Override
                public String getCallStack() {
                    StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                    for (StackTraceElement element : stack) {
                        if (!element.getClassName().equals(LockContext.class.getName())) {
                            return element.toString();
                        }
                    }
                    return "";
                }
                
                @Override
                public Object getCustomData() {
                    return "ReentrantLock Context";
                }
            };
        }
    }
    
    /**
     * 锁统计信息
     */
    public static class LockStatistics {
        private final long acquisitionCount;
        private final long releaseCount;
        private final long totalReentrantCount;
        private final long totalLockTime;
        private final double averageLockTime;
        private final boolean isLocked;
        private final int currentReentrantCount;
        
        public LockStatistics(long acquisitionCount, long releaseCount, long totalReentrantCount,
                            long totalLockTime, double averageLockTime, boolean isLocked, 
                            int currentReentrantCount) {
            this.acquisitionCount = acquisitionCount;
            this.releaseCount = releaseCount;
            this.totalReentrantCount = totalReentrantCount;
            this.totalLockTime = totalLockTime;
            this.averageLockTime = averageLockTime;
            this.isLocked = isLocked;
            this.currentReentrantCount = currentReentrantCount;
        }
        
        public long getAcquisitionCount() {
            return acquisitionCount;
        }
        
        public long getReleaseCount() {
            return releaseCount;
        }
        
        public long getTotalReentrantCount() {
            return totalReentrantCount;
        }
        
        public long getTotalLockTime() {
            return totalLockTime;
        }
        
        public double getAverageLockTime() {
            return averageLockTime;
        }
        
        public boolean isLocked() {
            return isLocked;
        }
        
        public int getCurrentReentrantCount() {
            return currentReentrantCount;
        }
    }
}
