package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.DistributedLock;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Consumer;

/**
 * Redis分布式锁 - 完整版本
 */
public class SimpleRedisLock implements DistributedLock {
    
    private static final Logger logger = LoggerFactory.getLogger(SimpleRedisLock.class);
    
    private final String lockKey;
    private final String lockValue;
    private final RedisCommands<String, String> commands;
    private final long defaultLeaseTimeSeconds;
    
    private final AtomicBoolean isAcquired = new AtomicBoolean(false);
    private final AtomicInteger reentrantCount = new AtomicInteger(0);
    private final AtomicLong acquireTime = new AtomicLong(0);
    private volatile Thread owningThread;
    
    // 自动续期支持
    private ScheduledFuture<?> autoRenewalTask;
    private static final ScheduledThreadPoolExecutor renewalExecutor = 
        new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "lock-renewal");
            t.setDaemon(true);
            return t;
        });
    
    public SimpleRedisLock(String lockKey, RedisCommands<String, String> commands, long leaseTimeSeconds) {
        this.lockKey = lockKey;
        this.lockValue = Thread.currentThread().getId() + ":" + System.nanoTime();
        this.commands = commands;
        this.defaultLeaseTimeSeconds = leaseTimeSeconds;
    }
    
    @Override
    public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
        try {
            if (!tryLock(30, leaseTime, unit)) {
                throw new RuntimeException("Failed to acquire lock within timeout");
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
        if (isAcquired.get() && Thread.currentThread() == owningThread) {
            reentrantCount.incrementAndGet();
            return true;
        }
        
        long startTime = System.currentTimeMillis();
        long timeout = unit != null ? TimeUnit.MILLISECONDS.convert(waitTime, unit) : 0;
        
        while (timeout <= 0 || System.currentTimeMillis() - startTime < timeout) {
            try {
                // 尝试获取锁
                String result = commands.set(lockKey, lockValue, 
                    io.lettuce.core.SetArgs.Builder.nx().ex(unit != null ? (int)unit.toSeconds(leaseTime) : (int)leaseTime));
                
                if ("OK".equals(result)) {
                    isAcquired.set(true);
                    owningThread = Thread.currentThread();
                    acquireTime.set(System.currentTimeMillis());
                    reentrantCount.incrementAndGet();
                    logger.debug("Lock {} acquired by thread {}", lockKey, Thread.currentThread().getName());
                    return true;
                }
            } catch (Exception e) {
                logger.error("Failed to acquire lock", e);
            }
            
            // 检查是否超时
            if (timeout > 0 && System.currentTimeMillis() - startTime >= timeout) {
                break;
            }
            
            // 等待一小段时间后重试
            Thread.sleep(100);
        }
        
        logger.debug("Failed to acquire lock {} within timeout", lockKey);
        return false;
    }
    
    @Override
    public boolean tryLock() throws InterruptedException {
        return tryLock(0, defaultLeaseTimeSeconds, TimeUnit.SECONDS);
    }
    
    @Override
    public void unlock() {
        if (!isAcquired.get()) {
            throw new IllegalMonitorStateException("Lock not held by current thread");
        }
        
        reentrantCount.decrementAndGet();
        if (reentrantCount.get() > 0) {
            return; // 仍有重入次数
        }
        
        try {
            // 停止自动续期
            if (autoRenewalTask != null) {
                autoRenewalTask.cancel(false);
                autoRenewalTask = null;
            }
            
            // 使用Lua脚本安全删除
            String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
            Long result = commands.eval(script, io.lettuce.core.ScriptOutputType.INTEGER, 
                new String[]{lockKey}, lockValue);
            
            isAcquired.set(false);
            owningThread = null;
            reentrantCount.set(0);
            
            logger.debug("Lock {} released by thread {}", lockKey, Thread.currentThread().getName());
        } catch (Exception e) {
            logger.error("Failed to release lock", e);
            throw new RuntimeException("Failed to release lock", e);
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
        return isAcquired.get() && Thread.currentThread() == owningThread;
    }
    
    @Override
    public String getName() {
        return lockKey;
    }
    
    @Override
    public ScheduledFuture<?> scheduleAutoRenewal(long renewInterval, TimeUnit unit, Consumer<DistributedLock.RenewalResult> renewalCallback) {
        if (!isHeldByCurrentThread()) {
            throw new IllegalMonitorStateException("Auto renewal can only be scheduled for locks held by current thread");
        }
        
        long intervalMs = unit != null ? unit.toMillis(renewInterval) : renewInterval;
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("Renewal interval must be positive");
        }
        
        // 停止之前的续期任务
        if (autoRenewalTask != null) {
            autoRenewalTask.cancel(false);
        }
        
        autoRenewalTask = renewalExecutor.scheduleAtFixedRate(() -> {
            try {
                if (isHeldByCurrentThread()) {
                    boolean renewed = renew();
                    if (renewalCallback != null) {
                        renewalCallback.accept(new DistributedLock.RenewalResult() {
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
                                return System.currentTimeMillis() + (defaultLeaseTimeSeconds * 1000);
                            }
                        });
                    }
                } else {
                    // 停止续期，因为锁不再被持有
                    if (autoRenewalTask != null) {
                        autoRenewalTask.cancel(false);
                        autoRenewalTask = null;
                    }
                }
            } catch (Exception e) {
                logger.error("Auto renewal failed for lock {}", lockKey, e);
                if (renewalCallback != null) {
                    renewalCallback.accept(new DistributedLock.RenewalResult() {
                        @Override
                        public boolean isSuccess() {
                            return false;
                        }
                        
                        @Override
                        public Throwable getFailureCause() {
                            return e;
                        }
                        
                        @Override
                        public long getRenewalTime() {
                            return System.currentTimeMillis();
                        }
                        
                        @Override
                        public long getNewExpirationTime() {
                            return 0;
                        }
                    });
                }
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        
        return autoRenewalTask;
    }
    
    private boolean renew() {
        try {
            // 重新设置过期时间
            String result = commands.set(lockKey, lockValue, 
                io.lettuce.core.SetArgs.Builder.xx().ex(defaultLeaseTimeSeconds));
            return "OK".equals(result);
        } catch (Exception e) {
            logger.error("Failed to renew lock", e);
            return false;
        }
    }
    
    @Override
    public int getReentrantCount() {
        return isHeldByCurrentThread() ? reentrantCount.get() : 0;
    }
    
    @Override
    public boolean isExpired() {
        try {
            Long ttl = commands.ttl(lockKey);
            return ttl == null || ttl <= 0;
        } catch (Exception e) {
            logger.error("Failed to check expiration for lock {}", lockKey, e);
            return false;
        }
    }
    
    @Override
    public long getRemainingTime(TimeUnit unit) {
        try {
            Long ttl = commands.ttl(lockKey);
            if (ttl == null || ttl < 0) {
                return 0;
            }
            return unit != null ? unit.convert(ttl, TimeUnit.SECONDS) : ttl;
        } catch (Exception e) {
            logger.error("Failed to get remaining time for lock {}", lockKey, e);
            return 0;
        }
    }
    
    @Override
    public String getLockHolder() {
        if (isAcquired.get() && owningThread != null) {
            return owningThread.getName();
        }
        return null;
    }
    
    
}