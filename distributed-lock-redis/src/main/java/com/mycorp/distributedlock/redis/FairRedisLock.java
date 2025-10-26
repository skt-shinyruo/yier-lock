package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.LockEvent;
import com.mycorp.distributedlock.api.LockEventListener;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.support.Barrier;
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
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Redis分布式公平锁实现
 * 
 * 公平锁特性：
 * - 基于Redis队列的公平性保证
 * - FIFO等待队列管理
 * - 死锁检测和预防
 * - 性能优化和监控
 * - 事件驱动通知
 */
public class FairRedisLock implements DistributedLock {
    
    private static final Logger logger = LoggerFactory.getLogger(FairRedisLock.class);
    
    // Redis键命名空间
    private static final String LOCK_PREFIX = "fair-lock:";
    private static final String QUEUE_PREFIX = "queue:";
    private static final String WAITERS_SET_PREFIX = "waiters:";
    private static final String OWNER_KEY_SUFFIX = ":owner";
    private static final String COUNT_KEY_SUFFIX = ":count";
    
    // 队列管理
    private static final String QUEUE_MANAGER_LOCK = "queue-manager-lock";
    private static final int MAX_QUEUE_SIZE = 10000;
    private static final int QUEUE_CLEANUP_INTERVAL = 30000; // 30秒
    
    // 锁相关
    private final String lockKey;
    private final String lockPath;
    private final String queueKey;
    private final String waitersSetKey;
    private final String ownerKey;
    private final String countKey;
    
    private final RedisCommands<String, String> commands;
    private final long defaultLeaseTimeSeconds;
    
    // 状态管理
    private final AtomicBoolean isAcquired = new AtomicBoolean(false);
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final AtomicInteger reentrantCount = new AtomicInteger(0);
    private final AtomicReference<Thread> owningThread = new AtomicReference<>();
    private final AtomicLong acquireTime = new AtomicLong(0);
    private final AtomicLong expirationTime = new AtomicLong(0);
    private final AtomicLong queuePosition = new AtomicLong(0);
    
    // 事件管理
    private final java.util.List<LockEventListener<DistributedLock>> eventListeners = 
            new java.util.concurrent.CopyOnWriteArrayList<>();
    
    // 队列管理
    private final FairLockQueueManager queueManager;
    private final ScheduledExecutorService queueCleanupExecutor;
    
    // 性能指标
    private final AtomicLong acquisitionCount = new AtomicLong(0);
    private final AtomicLong releaseCount = new AtomicLong(0);
    private final AtomicLong queueWaitTime = new AtomicLong(0);
    private final Timer acquisitionTimer;
    
    // 续期管理
    private ScheduledFuture<?> renewalTask;
    private final ExecutorService renewalExecutor = 
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "fair-lock-renewal-" + lockKey);
                t.setDaemon(true);
                return t;
            });
    
    public FairRedisLock(String lockKey, RedisCommands<String, String> commands, long leaseTimeSeconds) {
        this.lockKey = LOCK_PREFIX + lockKey;
        this.lockPath = lockKey;
        this.queueKey = QUEUE_PREFIX + lockKey;
        this.waitersSetKey = WAITERS_SET_PREFIX + lockKey;
        this.ownerKey = lockKey + OWNER_KEY_SUFFIX;
        this.countKey = lockKey + COUNT_KEY_SUFFIX;
        
        this.commands = commands;
        this.defaultLeaseTimeSeconds = leaseTimeSeconds;
        this.queueManager = new FairLockQueueManager(commands, lockKey);
        this.queueCleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "queue-cleanup-" + lockKey);
            t.setDaemon(true);
            return t;
        });
        
        // 启动队列清理任务
        startQueueCleanupTask();
        
        logger.debug("FairRedisLock initialized for: {}", lockKey);
    }
    
    @Override
    public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
        checkNotClosed();
        
        try {
            if (!tryLock(0, leaseTime, unit)) {
                throw new InterruptedException("Failed to acquire fair lock within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }
    
    @Override
    public void lock() throws InterruptedException {
        lock(defaultLeaseTimeSeconds, TimeUnit.SECONDS);
    }
    
    @Override
    public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        checkNotClosed();
        
        if (isHeldByCurrentThread()) {
            reentrantCount.incrementAndGet();
            return true;
        }
        
        long startTime = System.currentTimeMillis();
        
        // 检查队列长度
        long queueSize = commands.scard(waitersSetKey);
        if (queueSize >= MAX_QUEUE_SIZE) {
            logger.warn("Fair lock queue is full for: {}", lockKey);
            return false;
        }
        
        // 获取队列管理锁
        if (!acquireQueueManagerLock()) {
            return false;
        }
        
        try {
            // 注册到等待队列
            String threadId = Thread.currentThread().getId() + ":" + UUID.randomUUID().toString();
            long position = queueManager.enqueue(threadId, Duration.ofSeconds(unit.toSeconds(leaseTime)));
            queuePosition.set(position);
            
            logger.debug("Thread {} queued for fair lock at position {}: {}", 
                        Thread.currentThread().getName(), position, lockKey);
            
            // 等待队列管理
            return waitForLockTurn(waitTime, leaseTime, unit, startTime, threadId);
            
        } finally {
            releaseQueueManagerLock();
        }
    }
    
    @Override
    public boolean tryLock() throws InterruptedException {
        return tryLock(0, defaultLeaseTimeSeconds, TimeUnit.SECONDS);
    }
    
    @Override
    public void unlock() {
        checkNotClosed();
        
        if (!isHeldByCurrentThread()) {
            throw new IllegalMonitorStateException("Current thread does not hold lock: " + lockKey);
        }
        
        reentrantCount.decrementAndGet();
        if (reentrantCount.get() > 0) {
            logger.debug("Reentrant unlock, count remaining: {} for thread {}: {}", 
                        reentrantCount.get(), Thread.currentThread().getName(), lockKey);
            return; // 仍有重入次数
        }
        
        try {
            // 停止续期
            if (renewalTask != null) {
                renewalTask.cancel(false);
                renewalTask = null;
            }
            
            // 安全释放锁
            boolean released = releaseLockSafely();
            if (released) {
                releaseCount.incrementAndGet();
                
                // 通知下一个等待者
                notifyNextWaiter();
                
                logger.debug("Lock released by thread {}: {}", Thread.currentThread().getName(), lockKey);
            } else {
                logger.warn("Failed to release lock safely: {}", lockKey);
            }
            
        } catch (Exception e) {
            logger.error("Error releasing fair lock", e);
            throw new RuntimeException("Failed to release fair lock", e);
        } finally {
            // 清理状态
            isAcquired.set(false);
            owningThread.set(null);
            acquireTime.set(0);
            expirationTime.set(0);
            queuePosition.set(0);
            
            // 通知事件
            notifyEvent(LockEvent.ofLockReleased(this, createMetadata()));
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
        return lockAsync(defaultLeaseTimeSeconds, TimeUnit.SECONDS);
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
        return tryLockAsync(0, defaultLeaseTimeSeconds, TimeUnit.SECONDS);
    }
    
    @Override
    public CompletableFuture<Void> unlockAsync() {
        return CompletableFuture.runAsync(this::unlock);
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
        return lockPath;
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
        
        renewalTask = renewalExecutor.scheduleAtFixedRate(() -> {
            try {
                if (isHeldByCurrentThread() && !Thread.currentThread().isInterrupted()) {
                    boolean renewed = renewLock();
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
                logger.error("Auto renewal failed for fair lock: {}", lockKey, e);
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        
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
        return commands.scard(waitersSetKey);
    }
    
    /**
     * 获取统计信息
     */
    public FairLockStatistics getStatistics() {
        return new FairLockStatistics(
            acquisitionCount.get(),
            releaseCount.get(),
            getQueueSize(),
            getAverageQueueWaitTime(),
            reentrantCount.get(),
            isLocked(),
            getRemainingTime(TimeUnit.MILLISECONDS)
        );
    }
    
    @Override
    public void close() {
        if (isClosed.getAndSet(true)) {
            return;
        }
        
        logger.debug("Closing FairRedisLock: {}", lockKey);
        
        try {
            // 释放锁
            if (isHeldByCurrentThread()) {
                unlock();
            }
            
            // 关闭线程池
            renewalExecutor.shutdown();
            queueCleanupExecutor.shutdown();
            
            if (!renewalExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                renewalExecutor.shutdownNow();
            }
            if (!queueCleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                queueCleanupExecutor.shutdownNow();
            }
            
            // 清理队列
            queueManager.clear();
            
            // 清理监听器
            eventListeners.clear();
            
            logger.debug("FairRedisLock closed: {}", lockKey);
        } catch (Exception e) {
            logger.error("Error closing FairRedisLock", e);
        }
    }
    
    // 私有方法
    
    private boolean acquireQueueManagerLock() {
        try {
            String script = 
                "if redis.call('set', KEYS[1], '1', 'NX', 'EX', 10) then return 1 else return 0 end";
            Long result = commands.eval(script, io.lettuce.core.ScriptOutputType.INTEGER,
                new String[]{QUEUE_MANAGER_LOCK});
            return "1".equals(result.toString());
        } catch (Exception e) {
            logger.error("Failed to acquire queue manager lock", e);
            return false;
        }
    }
    
    private void releaseQueueManagerLock() {
        try {
            commands.del(QUEUE_MANAGER_LOCK);
        } catch (Exception e) {
            logger.debug("Failed to release queue manager lock", e);
        }
    }
    
    private boolean waitForLockTurn(long waitTime, long leaseTime, TimeUnit unit, 
                                   long startTime, String threadId) throws InterruptedException {
        long timeout = unit.toMillis(waitTime);
        while (timeout <= 0 || System.currentTimeMillis() - startTime < timeout) {
            // 检查是否轮到当前线程
            if (isMyTurn(threadId)) {
                // 尝试获取锁
                if (tryAcquireLock(leaseTime, unit)) {
                    long waitDuration = System.currentTimeMillis() - startTime;
                    queueWaitTime.addAndGet(waitDuration);
                    acquisitionCount.incrementAndGet();
                    
                    notifyEvent(LockEvent.ofLockAcquired(this, createMetadata()));
                    logger.debug("Fair lock acquired by thread {} at position {}: {}", 
                                Thread.currentThread().getName(), queuePosition.get(), lockKey);
                    return true;
                }
            }
            
            // 等待一小段时间后重试
            Thread.sleep(50);
        }
        
        // 超时，清理队列条目
        queueManager.removeFromQueue(threadId);
        notifyEvent(LockEvent.ofLockTimeout(lockKey, waitTime, createMetadata()));
        return false;
    }
    
    private boolean isMyTurn(String threadId) {
        String script = 
            "local current = redis.call('get', KEYS[1]) " +
            "if current == false then return 1 end " +
            "if current == ARGV[1] then return 1 end " +
            "return 0";
        
        try {
            Long result = commands.eval(script, io.lettuce.core.ScriptOutputType.INTEGER,
                new String[]{ownerKey}, threadId);
            return "1".equals(result.toString());
        } catch (Exception e) {
            logger.error("Failed to check turn", e);
            return false;
        }
    }
    
    private boolean tryAcquireLock(long leaseTime, TimeUnit unit) {
        String script = 
            "if redis.call('get', KEYS[1]) == false then " +
            "  redis.call('set', KEYS[1], ARGV[1]) " +
            "  redis.call('expire', KEYS[1], ARGV[2]) " +
            "  redis.call('del', KEYS[2]) " +
            "  return 1 " +
            "else " +
            "  return 0 " +
            "end";
        
        try {
            Long result = commands.eval(script, io.lettuce.core.ScriptOutputType.INTEGER,
                new String[]{lockKey, ownerKey}, 
                Thread.currentThread().getId() + ":" + UUID.randomUUID().toString(),
                String.valueOf(unit.toSeconds(leaseTime)));
            
            boolean acquired = "1".equals(result.toString());
            if (acquired) {
                isAcquired.set(true);
                owningThread.set(Thread.currentThread());
                acquireTime.set(System.currentTimeMillis());
                expirationTime.set(System.currentTimeMillis() + unit.toMillis(leaseTime));
                reentrantCount.incrementAndGet();
            }
            
            return acquired;
        } catch (Exception e) {
            logger.error("Failed to acquire fair lock", e);
            return false;
        }
    }
    
    private boolean releaseLockSafely() {
        String script = 
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  redis.call('del', KEYS[1]) " +
            "  return 1 " +
            "else " +
            "  return 0 " +
            "end";
        
        try {
            Long result = commands.eval(script, io.lettuce.core.ScriptOutputType.INTEGER,
                new String[]{lockKey}, owningThread.get().getId() + ":.*");
            return "1".equals(result.toString());
        } catch (Exception e) {
            logger.error("Failed to release fair lock safely", e);
            return false;
        }
    }
    
    private void notifyNextWaiter() {
        // 通知队列中的下一个等待者
        String script = 
            "local waiters = redis.call('zrange', KEYS[1], 0, 0, 'WITHSCORES') " +
            "if #waiters > 0 then " +
            "  redis.call('zrem', KEYS[1], waiters[1]) " +
            "  redis.call('srem', KEYS[2], waiters[1]) " +
            "end";
        
        try {
            commands.eval(script, io.lettuce.core.ScriptOutputType.VALUE,
                new String[]{queueKey, waitersSetKey});
        } catch (Exception e) {
            logger.error("Failed to notify next waiter", e);
        }
    }
    
    private boolean renewLock() {
        String script = 
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  redis.call('expire', KEYS[1], ARGV[2]) " +
            "  return 1 " +
            "else " +
            "  return 0 " +
            "end";
        
        try {
            Long result = commands.eval(script, io.lettuce.core.ScriptOutputType.INTEGER,
                new String[]{lockKey}, owningThread.get().getId() + ":.*",
                String.valueOf(defaultLeaseTimeSeconds));
            
            if ("1".equals(result.toString())) {
                expirationTime.set(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(defaultLeaseTimeSeconds));
                notifyEvent(LockEvent.ofLockRenewed(this, Instant.now(), createMetadata()));
                return true;
            }
            return false;
        } catch (Exception e) {
            logger.error("Failed to renew fair lock", e);
            return false;
        }
    }
    
    private void startQueueCleanupTask() {
        queueCleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                queueManager.cleanupExpiredEntries();
            } catch (Exception e) {
                logger.debug("Queue cleanup error", e);
            }
        }, QUEUE_CLEANUP_INTERVAL, QUEUE_CLEANUP_INTERVAL, TimeUnit.MILLISECONDS);
    }
    
    private void notifyEvent(LockEvent<?> event) {
        for (LockEventListener<DistributedLock> listener : eventListeners) {
            if (listener.shouldHandleEvent(event)) {
                try {
                    listener.onEvent(event);
                } catch (Exception e) {
                    logger.warn("Error in lock event listener", e);
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
                    if (!element.getClassName().equals(FairRedisLock.class.getName())) {
                        return element.toString();
                    }
                }
                return "";
            }
            
            @Override
            public Object getCustomData() {
                return "FairRedisLock Context";
            }
        };
    }
    
    private void checkNotClosed() {
        if (isClosed.get()) {
            throw new IllegalStateException("Lock is already closed: " + lockKey);
        }
    }
    
    private double getAverageQueueWaitTime() {
        long acquisitions = acquisitionCount.get();
        return acquisitions > 0 ? (double) queueWaitTime.get() / acquisitions : 0.0;
    }
    
    // 内部类
    
    /**
     * 公平锁队列管理器
     */
    private static class FairLockQueueManager {
        private final RedisCommands<String, String> commands;
        private final String lockKey;
        
        public FairLockQueueManager(RedisCommands<String, String> commands, String lockKey) {
            this.commands = commands;
            this.lockKey = lockKey;
        }
        
        public long enqueue(String threadId, Duration timeout) {
            long score = System.currentTimeMillis();
            commands.zadd("queue:" + lockKey, score, threadId);
            commands.sadd("waiters:" + lockKey, threadId);
            
            if (timeout != null) {
                commands.expire("queue:" + lockKey, (int) timeout.getSeconds());
            }
            
            return score;
        }
        
        public void removeFromQueue(String threadId) {
            commands.zrem("queue:" + lockKey, threadId);
            commands.srem("waiters:" + lockKey, threadId);
        }
        
        public void clear() {
            commands.del("queue:" + lockKey);
            commands.del("waiters:" + lockKey);
        }
        
        public void cleanupExpiredEntries() {
            long cutoff = System.currentTimeMillis() - 300000; // 5分钟前
            commands.zremrangebyscore("queue:" + lockKey, 0, cutoff);
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
        private final int currentReentrantCount;
        private final boolean isLocked;
        private final long remainingTime;
        
        public FairLockStatistics(long acquisitionCount, long releaseCount, long queueSize,
                                double averageQueueWaitTime, int currentReentrantCount,
                                boolean isLocked, long remainingTime) {
            this.acquisitionCount = acquisitionCount;
            this.releaseCount = releaseCount;
            this.queueSize = queueSize;
            this.averageQueueWaitTime = averageQueueWaitTime;
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