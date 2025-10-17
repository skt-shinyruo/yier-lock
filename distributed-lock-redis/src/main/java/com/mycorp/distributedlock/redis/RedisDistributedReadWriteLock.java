package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.api.exception.LockAcquisitionException;
import com.mycorp.distributedlock.api.exception.LockReleaseException;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import com.mycorp.distributedlock.core.util.LockKeyUtils;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class RedisDistributedReadWriteLock implements DistributedReadWriteLock {
    
    private static final Logger logger = LoggerFactory.getLogger(RedisDistributedReadWriteLock.class);
    
    private final String name;
    private final ReadLock readLock;
    private final WriteLock writeLock;
    
    public RedisDistributedReadWriteLock(String name,
                                       StatefulRedisConnection<String, String> connection,
                                       StatefulRedisPubSubConnection<String, String> pubSubConnection,
                                       LockConfiguration configuration,
                                       LockMetrics metrics,
                                       LockTracing tracing,
                                       ScheduledExecutorService watchdogExecutor) {
        this.name = name;
        this.readLock = new ReadLock(name, connection, pubSubConnection, configuration, metrics, tracing, watchdogExecutor);
        this.writeLock = new WriteLock(name, connection, pubSubConnection, configuration, metrics, tracing, watchdogExecutor);
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
        return name;
    }
    
    private static class ReadLock implements DistributedLock {
        private final String name;
        private final String lockKey;
        private final String channelKey;
        private final StatefulRedisConnection<String, String> connection;
        private final StatefulRedisPubSubConnection<String, String> pubSubConnection;
        private final LockConfiguration configuration;
        private final LockMetrics metrics;
        private final LockTracing tracing;
        private final ScheduledExecutorService watchdogExecutor;
        
        private final ThreadLocal<LockState> lockState = new ThreadLocal<>();
        private final AtomicReference<ScheduledFuture<?>> watchdogTask = new AtomicReference<>();
        
        private static class LockState {
            final String lockValue;
            final AtomicInteger reentrantCount;
            final Timer.Sample heldTimer;
            
            LockState(String lockValue, Timer.Sample heldTimer) {
                this.lockValue = lockValue;
                this.reentrantCount = new AtomicInteger(1);
                this.heldTimer = heldTimer;
            }
        }
        
        public ReadLock(String name,
                       StatefulRedisConnection<String, String> connection,
                       StatefulRedisPubSubConnection<String, String> pubSubConnection,
                       LockConfiguration configuration,
                       LockMetrics metrics,
                       LockTracing tracing,
                       ScheduledExecutorService watchdogExecutor) {
            this.name = name + ":read";
            this.lockKey = LockKeyUtils.generateReadLockKey(name);
            this.channelKey = LockKeyUtils.generateChannelKey(name);
            this.connection = connection;
            this.pubSubConnection = pubSubConnection;
            this.configuration = configuration;
            this.metrics = metrics;
            this.tracing = tracing;
            this.watchdogExecutor = watchdogExecutor;
        }
        
        @Override
        public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
            Duration leaseDuration = Duration.ofNanos(unit.toNanos(leaseTime));
            lockInternal(null, leaseDuration, true);
        }
        
        @Override
        public void lock() throws InterruptedException {
            lock(configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
        }
        
        @Override
        public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
            Duration waitDuration = Duration.ofNanos(unit.toNanos(waitTime));
            Duration leaseDuration = Duration.ofNanos(unit.toNanos(leaseTime));
            return lockInternal(waitDuration, leaseDuration, false);
        }
        
        @Override
        public boolean tryLock() throws InterruptedException {
            return tryLock(0, configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
        }
        
        @Override
        public void unlock() {
            LockState state = lockState.get();
            if (state == null) {
                throw new LockReleaseException("Read lock not held by current thread: " + name);
            }
            
            try (var spanContext = tracing.startLockAcquisitionSpan(name, "unlock")) {
                if (state.reentrantCount.decrementAndGet() > 0) {
                    logger.debug("Decremented reentrant count for read lock {}, count: {}", 
                        name, state.reentrantCount.get());
                    spanContext.setStatus("success");
                    return;
                }
                
                Timer.Sample acquisitionTimer = metrics.startAcquisitionTimer(name);
                
                try {
                    RedisCommands<String, String> sync = connection.sync();
                    String script = "local mode = redis.call('HGET', KEYS[1], 'mode')\n" +
                        "if mode == 'read' then\n" +
                        "    local readers = redis.call('HGET', KEYS[1], 'readers')\n" +
                        "    if readers then\n" +
                        "        local readerList = cjson.decode(readers)\n" +
                        "        local newReaders = {}\n" +
                        "        local found = false\n" +
                        "        for i, reader in ipairs(readerList) do\n" +
                        "            if reader ~= ARGV[1] then\n" +
                        "                table.insert(newReaders, reader)\n" +
                        "            else\n" +
                        "                found = true\n" +
                        "            end\n" +
                        "        end\n" +
                        "        if found then\n" +
                        "            if #newReaders == 0 then\n" +
                        "                redis.call('DEL', KEYS[1])\n" +
                        "                redis.call('PUBLISH', KEYS[2], 'unlocked')\n" +
                        "            else\n" +
                        "                redis.call('HSET', KEYS[1], 'readers', cjson.encode(newReaders))\n" +
                        "                redis.call('PEXPIRE', KEYS[1], ARGV[2])\n" +
                        "            end\n" +
                        "            return 1\n" +
                        "        end\n" +
                        "    end\n" +
                        "end\n" +
                        "return 0";
                    
                    Long result = sync.eval(script, ScriptOutputType.INTEGER,
                        new String[]{lockKey, channelKey},
                        state.lockValue, String.valueOf(configuration.getDefaultLeaseTime().toMillis()));
                    
                    if (result != null && result == 1) {
                        lockState.remove();
                        stopWatchdog();
                        metrics.recordHeldTime(state.heldTimer, name);
                        metrics.recordAcquisitionTime(acquisitionTimer, name, "success");
                        metrics.incrementLockReleaseCounter(name, "success");
                        spanContext.setStatus("success");
                        logger.debug("Successfully released read lock: {}", name);
                    } else {
                        metrics.recordAcquisitionTime(acquisitionTimer, name, "failure");
                        metrics.incrementLockReleaseCounter(name, "failure");
                        spanContext.setStatus("failure");
                        throw new LockReleaseException("Failed to release read lock, not owned by current thread: " + name);
                    }
                } catch (Exception e) {
                    metrics.recordAcquisitionTime(acquisitionTimer, name, "error");
                    metrics.incrementLockReleaseCounter(name, "error");
                    spanContext.setError(e);
                    throw new LockReleaseException("Error releasing read lock: " + name, e);
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
                    throw new LockAcquisitionException("Interrupted while acquiring read lock: " + name, e);
                }
            });
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
                    throw new LockAcquisitionException("Interrupted while acquiring read lock: " + name, e);
                }
            });
        }
        
        @Override
        public CompletableFuture<Boolean> tryLockAsync() {
            return tryLockAsync(0, configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
        }
        
        @Override
        public CompletableFuture<Void> unlockAsync() {
            return CompletableFuture.runAsync(this::unlock);
        }
        
        @Override
        public boolean isLocked() {
            try {
                RedisCommands<String, String> sync = connection.sync();
                String mode = sync.hget(lockKey, "mode");
                return "read".equals(mode);
            } catch (Exception e) {
                logger.warn("Error checking if read lock exists: {}", name, e);
                return false;
            }
        }
        
        @Override
        public boolean isHeldByCurrentThread() {
            LockState state = lockState.get();
            return state != null && state.reentrantCount.get() > 0;
        }
        
        @Override
        public String getName() {
            return name;
        }
        
        private boolean lockInternal(Duration waitTime, Duration leaseTime, boolean blocking) throws InterruptedException {
            LockState state = lockState.get();
            if (state != null) {
                state.reentrantCount.incrementAndGet();
                logger.debug("Incremented reentrant count for read lock {}, count: {}", 
                    name, state.reentrantCount.get());
                return true;
            }
            
            try (var spanContext = tracing.startLockAcquisitionSpan(name, "lock")) {
                Timer.Sample acquisitionTimer = metrics.startAcquisitionTimer(name);
                long startTime = System.nanoTime();
                long waitTimeNanos = waitTime != null ? waitTime.toNanos() : Long.MAX_VALUE;
                
                String lockValue = LockKeyUtils.generateLockValue();
                
                try {
                    while (true) {
                        if (tryAcquireReadLock(lockValue, leaseTime)) {
                            Timer.Sample heldTimer = metrics.startHeldTimer(name);
                            lockState.set(new LockState(lockValue, heldTimer));
                            startWatchdog(lockValue, leaseTime);
                            metrics.recordAcquisitionTime(acquisitionTimer, name, "success");
                            metrics.incrementLockAcquisitionCounter(name, "success");
                            spanContext.setStatus("success");
                            logger.debug("Successfully acquired read lock: {}", name);
                            return true;
                        }
                        
                        if (!blocking || (waitTime != null && (System.nanoTime() - startTime) >= waitTimeNanos)) {
                            metrics.recordAcquisitionTime(acquisitionTimer, name, "timeout");
                            metrics.incrementContentionCounter(name);
                            metrics.incrementLockAcquisitionCounter(name, "timeout");
                            spanContext.setStatus("timeout");
                            return false;
                        }
                        
                        if (!waitForLockRelease(waitTimeNanos - (System.nanoTime() - startTime))) {
                            metrics.recordAcquisitionTime(acquisitionTimer, name, "timeout");
                            metrics.incrementContentionCounter(name);
                            metrics.incrementLockAcquisitionCounter(name, "timeout");
                            spanContext.setStatus("timeout");
                            return false;
                        }
                    }
                } catch (Exception e) {
                    metrics.recordAcquisitionTime(acquisitionTimer, name, "error");
                    metrics.incrementLockAcquisitionCounter(name, "error");
                    spanContext.setError(e);
                    throw new LockAcquisitionException("Error acquiring read lock: " + name, e);
                }
            }
        }
        
        private boolean tryAcquireReadLock(String lockValue, Duration leaseTime) {
            int maxRetries = configuration.getMaxRetries();
            Duration retryInterval = configuration.getRetryInterval();
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    return tryAcquireReadWithLua(lockValue, leaseTime);
                } catch (Exception e) {
                    logger.warn("Lua read acquire attempt {} failed for lock {}: {}", attempt + 1, name, e.getMessage());
                    if (attempt == maxRetries) {
                        logger.info("All Lua read acquire retries failed, falling back to basic for lock {}", name);
                        return fallbackAcquireRead(lockValue, leaseTime);
                    }
                    try {
                        Thread.sleep(retryInterval.toMillis() * (1L << attempt));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warn("Read acquire retry interrupted for lock {}", name, ie);
                        return false;
                    }
                }
            }
            return false;
        }

        private boolean tryAcquireReadWithLua(String lockValue, Duration leaseTime) {
            RedisCommands<String, String> sync = connection.sync();
            String script = "local mode = redis.call('HGET', KEYS[1], 'mode')\n" +
                "if mode == false then\n" +
                "    redis.call('HSET', KEYS[1], 'mode', 'read')\n" +
                "    redis.call('HSET', KEYS[1], 'readers', cjson.encode({ARGV[1]}))\n" +
                "    redis.call('PEXPIRE', KEYS[1], ARGV[2])\n" +
                "    return 1\n" +
                "elseif mode == 'read' then\n" +
                "    local readers = redis.call('HGET', KEYS[1], 'readers')\n" +
                "    if readers then\n" +
                "        local readerList = cjson.decode(readers)\n" +
                "        for i, reader in ipairs(readerList) do\n" +
                "            if reader == ARGV[1] then\n" +
                "                redis.call('PEXPIRE', KEYS[1], ARGV[2])\n" +
                "                return 1\n" +
                "            end\n" +
                "        end\n" +
                "        table.insert(readerList, ARGV[1])\n" +
                "        redis.call('HSET', KEYS[1], 'readers', cjson.encode(readerList))\n" +
                "        redis.call('PEXPIRE', KEYS[1], ARGV[2])\n" +
                "        return 1\n" +
                "    end\n" +
                "end\n" +
                "return 0";
            
            Long result = sync.eval(script, ScriptOutputType.INTEGER,
                new String[]{lockKey},
                lockValue, String.valueOf(leaseTime.toMillis()));
            
            return result != null && result == 1;
        }

        private boolean fallbackAcquireRead(String lockValue, Duration leaseTime) {
            try {
                RedisCommands<String, String> sync = connection.sync();
                // Basic read lock fallback, using SET NX for simplicity (not full readers list)
                io.lettuce.core.SetArgs setArgs = new io.lettuce.core.SetArgs();
                setArgs.nx();
                setArgs.px(leaseTime.toMillis());
                String result = sync.set(lockKey, lockValue, setArgs);
                if ("OK".equals(result)) {
                    logger.debug("Fallback read acquire succeeded for lock {}", name);
                    return true;
                }
                return false;
            } catch (Exception e) {
                logger.error("Fallback read acquire failed for lock {}", name, e);
                return false;
            }
        }
        
        private boolean waitForLockRelease(long remainingWaitTimeNanos) throws InterruptedException {
            if (remainingWaitTimeNanos <= 0) {
                return false;
            }
            
            CountDownLatch latch = new CountDownLatch(1);
            RedisPubSubAsyncCommands<String, String> pubSubAsync = pubSubConnection.async();
            io.lettuce.core.pubsub.RedisPubSubListener<String, String> listener =
                new io.lettuce.core.pubsub.RedisPubSubAdapter<>() {
                    @Override
                    public void message(String channel, String message) {
                        if (channelKey.equals(channel) && "unlocked".equals(message)) {
                            latch.countDown();
                        }
                    }
                };
            pubSubConnection.addListener(listener);
            pubSubAsync.subscribe(channelKey);
            try {
                return latch.await(remainingWaitTimeNanos, TimeUnit.NANOSECONDS);
            } finally {
                try {
                    pubSubAsync.unsubscribe(channelKey);
                } catch (Exception e) {
                    logger.debug("Error unsubscribing from channel {}: {}", channelKey, e.getMessage());
                }
                pubSubConnection.removeListener(listener);
            }
        }
        
        private void startWatchdog(String lockValue, Duration leaseTime) {
            if (watchdogExecutor == null || !configuration.isWatchdogEnabled()) {
                return;
            }
            
            Duration renewalInterval = configuration.getWatchdogRenewalInterval();
            ScheduledFuture<?> task = watchdogExecutor.scheduleAtFixedRate(() -> {
                try {
                    renewReadLock(lockValue, leaseTime);
                } catch (Exception e) {
                    logger.warn("Error renewing read lock: {}", name, e);
                }
            }, renewalInterval.toMillis(), renewalInterval.toMillis(), TimeUnit.MILLISECONDS);
            
            watchdogTask.set(task);
        }
        
        private void stopWatchdog() {
            ScheduledFuture<?> task = watchdogTask.getAndSet(null);
            if (task != null) {
                task.cancel(false);
            }
        }
        
        private void renewReadLock(String lockValue, Duration leaseTime) {
            int maxRetries = configuration.getMaxRetries();
            Duration retryInterval = configuration.getRetryInterval();
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    if (renewReadWithLua(lockValue, leaseTime)) {
                        return;
                    }
                } catch (Exception e) {
                    logger.warn("Lua read renew attempt {} failed for lock {}: {}", attempt + 1, name, e.getMessage());
                    if (attempt == maxRetries) {
                        logger.info("All Lua read renew retries failed, falling back to basic for lock {}", name);
                        fallbackRenewRead(lockValue, leaseTime);
                        return;
                    }
                    try {
                        Thread.sleep(retryInterval.toMillis() * (1L << attempt));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warn("Read renew retry interrupted for lock {}", name, ie);
                        return;
                    }
                }
            }
            logger.warn("All read renew attempts failed, stopping watchdog for lock {}", name);
            stopWatchdog();
        }

        private boolean renewReadWithLua(String lockValue, Duration leaseTime) {
            RedisCommands<String, String> sync = connection.sync();
            String script = "local mode = redis.call('HGET', KEYS[1], 'mode')\n" +
                "if mode == 'read' then\n" +
                "    local readers = redis.call('HGET', KEYS[1], 'readers')\n" +
                "    if readers then\n" +
                "        local readerList = cjson.decode(readers)\n" +
                "        for i, reader in ipairs(readerList) do\n" +
                "            if reader == ARGV[1] then\n" +
                "                redis.call('PEXPIRE', KEYS[1], ARGV[2])\n" +
                "                return 1\n" +
                "            end\n" +
                "        end\n" +
                "    end\n" +
                "end\n" +
                "return 0";
            
            Long result = sync.eval(script, ScriptOutputType.INTEGER,
                new String[]{lockKey},
                lockValue, String.valueOf(leaseTime.toMillis()));
            
            if (result != null && result == 1) {
                metrics.incrementWatchdogRenewalCounter(name);
                logger.trace("Renewed read lock: {}", name);
                return true;
            }
            return false;
        }

        private void fallbackRenewRead(String lockValue, Duration leaseTime) {
            try {
                RedisCommands<String, String> sync = connection.sync();
                Boolean result = sync.pexpire(lockKey, leaseTime.toMillis());
                if (result != null && result) {
                    logger.debug("Fallback read renew succeeded for lock {}", name);
                } else {
                    logger.warn("Fallback read renew failed for lock {}", name);
                    stopWatchdog();
                }
            } catch (Exception e) {
                logger.error("Fallback read renew failed for lock {}", name, e);
                stopWatchdog();
            }
        }
    }
    
    private static class WriteLock implements DistributedLock {
        private final String name;
        private final String lockKey;
        private final String channelKey;
        private final StatefulRedisConnection<String, String> connection;
        private final StatefulRedisPubSubConnection<String, String> pubSubConnection;
        private final LockConfiguration configuration;
        private final LockMetrics metrics;
        private final LockTracing tracing;
        private final ScheduledExecutorService watchdogExecutor;
        
        private final ThreadLocal<LockState> lockState = new ThreadLocal<>();
        private final AtomicReference<ScheduledFuture<?>> watchdogTask = new AtomicReference<>();
        
        private static class LockState {
            final String lockValue;
            final AtomicInteger reentrantCount;
            final Timer.Sample heldTimer;
            
            LockState(String lockValue, Timer.Sample heldTimer) {
                this.lockValue = lockValue;
                this.reentrantCount = new AtomicInteger(1);
                this.heldTimer = heldTimer;
            }
        }
        
        public WriteLock(String name,
                        StatefulRedisConnection<String, String> connection,
                        StatefulRedisPubSubConnection<String, String> pubSubConnection,
                        LockConfiguration configuration,
                        LockMetrics metrics,
                        LockTracing tracing,
                        ScheduledExecutorService watchdogExecutor) {
            this.name = name + ":write";
            this.lockKey = LockKeyUtils.generateWriteLockKey(name);
            this.channelKey = LockKeyUtils.generateChannelKey(name);
            this.connection = connection;
            this.pubSubConnection = pubSubConnection;
            this.configuration = configuration;
            this.metrics = metrics;
            this.tracing = tracing;
            this.watchdogExecutor = watchdogExecutor;
        }
        
        @Override
        public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
            Duration leaseDuration = Duration.ofNanos(unit.toNanos(leaseTime));
            lockInternal(null, leaseDuration, true);
        }
        
        @Override
        public void lock() throws InterruptedException {
            lock(configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
        }
        
        @Override
        public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
            Duration waitDuration = Duration.ofNanos(unit.toNanos(waitTime));
            Duration leaseDuration = Duration.ofNanos(unit.toNanos(leaseTime));
            return lockInternal(waitDuration, leaseDuration, false);
        }
        
        @Override
        public boolean tryLock() throws InterruptedException {
            return tryLock(0, configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
        }
        
        @Override
        public void unlock() {
            LockState state = lockState.get();
            if (state == null) {
                throw new LockReleaseException("Write lock not held by current thread: " + name);
            }
            
            try (var spanContext = tracing.startLockAcquisitionSpan(name, "unlock")) {
                if (state.reentrantCount.decrementAndGet() > 0) {
                    logger.debug("Decremented reentrant count for write lock {}, count: {}", 
                        name, state.reentrantCount.get());
                    spanContext.setStatus("success");
                    return;
                }
                
                Timer.Sample acquisitionTimer = metrics.startAcquisitionTimer(name);
                
                try {
                    RedisCommands<String, String> sync = connection.sync();
                    String script = "local mode = redis.call('HGET', KEYS[1], 'mode')\n" +
                        "local owner = redis.call('HGET', KEYS[1], 'owner')\n" +
                        "if mode == 'write' and owner == ARGV[1] then\n" +
                        "    redis.call('DEL', KEYS[1])\n" +
                        "    redis.call('PUBLISH', KEYS[2], 'unlocked')\n" +
                        "    return 1\n" +
                        "else\n" +
                        "    return 0\n" +
                        "end";
                    
                    Long result = sync.eval(script, ScriptOutputType.INTEGER,
                        new String[]{lockKey, channelKey},
                        state.lockValue);
                    
                    if (result != null && result == 1) {
                        lockState.remove();
                        stopWatchdog();
                        metrics.recordHeldTime(state.heldTimer, name);
                        metrics.recordAcquisitionTime(acquisitionTimer, name, "success");
                        metrics.incrementLockReleaseCounter(name, "success");
                        spanContext.setStatus("success");
                        logger.debug("Successfully released write lock: {}", name);
                    } else {
                        metrics.recordAcquisitionTime(acquisitionTimer, name, "failure");
                        metrics.incrementLockReleaseCounter(name, "failure");
                        spanContext.setStatus("failure");
                        throw new LockReleaseException("Failed to release write lock, not owned by current thread: " + name);
                    }
                } catch (Exception e) {
                    metrics.recordAcquisitionTime(acquisitionTimer, name, "error");
                    metrics.incrementLockReleaseCounter(name, "error");
                    spanContext.setError(e);
                    throw new LockReleaseException("Error releasing write lock: " + name, e);
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
                    throw new LockAcquisitionException("Interrupted while acquiring write lock: " + name, e);
                }
            });
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
                    throw new LockAcquisitionException("Interrupted while acquiring write lock: " + name, e);
                }
            });
        }
        
        @Override
        public CompletableFuture<Boolean> tryLockAsync() {
            return tryLockAsync(0, configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
        }
        
        @Override
        public CompletableFuture<Void> unlockAsync() {
            return CompletableFuture.runAsync(this::unlock);
        }
        
        @Override
        public boolean isLocked() {
            try {
                RedisCommands<String, String> sync = connection.sync();
                String mode = sync.hget(lockKey, "mode");
                return "write".equals(mode);
            } catch (Exception e) {
                logger.warn("Error checking if write lock exists: {}", name, e);
                return false;
            }
        }
        
        @Override
        public boolean isHeldByCurrentThread() {
            LockState state = lockState.get();
            return state != null && state.reentrantCount.get() > 0;
        }
        
        @Override
        public String getName() {
            return name;
        }
        
        private boolean lockInternal(Duration waitTime, Duration leaseTime, boolean blocking) throws InterruptedException {
            LockState state = lockState.get();
            if (state != null) {
                state.reentrantCount.incrementAndGet();
                logger.debug("Incremented reentrant count for write lock {}, count: {}", 
                    name, state.reentrantCount.get());
                return true;
            }
            
            try (var spanContext = tracing.startLockAcquisitionSpan(name, "lock")) {
                Timer.Sample acquisitionTimer = metrics.startAcquisitionTimer(name);
                long startTime = System.nanoTime();
                long waitTimeNanos = waitTime != null ? waitTime.toNanos() : Long.MAX_VALUE;
                
                String lockValue = LockKeyUtils.generateLockValue();
                
                try {
                    while (true) {
                        if (tryAcquireWriteLock(lockValue, leaseTime)) {
                            Timer.Sample heldTimer = metrics.startHeldTimer(name);
                            lockState.set(new LockState(lockValue, heldTimer));
                            startWatchdog(lockValue, leaseTime);
                            metrics.recordAcquisitionTime(acquisitionTimer, name, "success");
                            metrics.incrementLockAcquisitionCounter(name, "success");
                            spanContext.setStatus("success");
                            logger.debug("Successfully acquired write lock: {}", name);
                            return true;
                        }
                        
                        if (!blocking || (waitTime != null && (System.nanoTime() - startTime) >= waitTimeNanos)) {
                            metrics.recordAcquisitionTime(acquisitionTimer, name, "timeout");
                            metrics.incrementContentionCounter(name);
                            metrics.incrementLockAcquisitionCounter(name, "timeout");
                            spanContext.setStatus("timeout");
                            return false;
                        }
                        
                        if (!waitForLockRelease(waitTimeNanos - (System.nanoTime() - startTime))) {
                            metrics.recordAcquisitionTime(acquisitionTimer, name, "timeout");
                            metrics.incrementContentionCounter(name);
                            metrics.incrementLockAcquisitionCounter(name, "timeout");
                            spanContext.setStatus("timeout");
                            return false;
                        }
                    }
                } catch (Exception e) {
                    metrics.recordAcquisitionTime(acquisitionTimer, name, "error");
                    metrics.incrementLockAcquisitionCounter(name, "error");
                    spanContext.setError(e);
                    throw new LockAcquisitionException("Error acquiring write lock: " + name, e);
                }
            }
        }
        
        private boolean tryAcquireWriteLock(String lockValue, Duration leaseTime) {
            int maxRetries = configuration.getMaxRetries();
            Duration retryInterval = configuration.getRetryInterval();
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    return tryAcquireWriteWithLua(lockValue, leaseTime);
                } catch (Exception e) {
                    logger.warn("Lua write acquire attempt {} failed for lock {}: {}", attempt + 1, name, e.getMessage());
                    if (attempt == maxRetries) {
                        logger.info("All Lua write acquire retries failed, falling back to basic for lock {}", name);
                        return fallbackAcquireWrite(lockValue, leaseTime);
                    }
                    try {
                        Thread.sleep(retryInterval.toMillis() * (1L << attempt));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warn("Write acquire retry interrupted for lock {}", name, ie);
                        return false;
                    }
                }
            }
            return false;
        }

        private boolean tryAcquireWriteWithLua(String lockValue, Duration leaseTime) {
            RedisCommands<String, String> sync = connection.sync();
            String script = "local mode = redis.call('HGET', KEYS[1], 'mode')\n" +
                "if mode == false then\n" +
                "    redis.call('HSET', KEYS[1], 'mode', 'write')\n" +
                "    redis.call('HSET', KEYS[1], 'owner', ARGV[1])\n" +
                "    redis.call('PEXPIRE', KEYS[1], ARGV[2])\n" +
                "    return 1\n" +
                "elseif mode == 'write' then\n" +
                "    local owner = redis.call('HGET', KEYS[1], 'owner')\n" +
                "    if owner == ARGV[1] then\n" +
                "        redis.call('PEXPIRE', KEYS[1], ARGV[2])\n" +
                "        return 1\n" +
                "    end\n" +
                "end\n" +
                "return 0";
            
            Long result = sync.eval(script, ScriptOutputType.INTEGER,
                new String[]{lockKey},
                lockValue, String.valueOf(leaseTime.toMillis()));
            
            return result != null && result == 1;
        }

        private boolean fallbackAcquireWrite(String lockValue, Duration leaseTime) {
            try {
                RedisCommands<String, String> sync = connection.sync();
                io.lettuce.core.SetArgs setArgs = new io.lettuce.core.SetArgs();
                setArgs.nx();
                setArgs.px(leaseTime.toMillis());
                String result = sync.set(lockKey, lockValue, setArgs);
                if ("OK".equals(result)) {
                    logger.debug("Fallback write acquire succeeded for lock {}", name);
                    return true;
                }
                return false;
            } catch (Exception e) {
                logger.error("Fallback write acquire failed for lock {}", name, e);
                return false;
            }
        }
        
        private boolean waitForLockRelease(long remainingWaitTimeNanos) throws InterruptedException {
            if (remainingWaitTimeNanos <= 0) {
                return false;
            }
            
            CountDownLatch latch = new CountDownLatch(1);
            RedisPubSubAsyncCommands<String, String> pubSubAsync = pubSubConnection.async();
            io.lettuce.core.pubsub.RedisPubSubListener<String, String> listener =
                new io.lettuce.core.pubsub.RedisPubSubAdapter<>() {
                    @Override
                    public void message(String channel, String message) {
                        if (channelKey.equals(channel) && "unlocked".equals(message)) {
                            latch.countDown();
                        }
                    }
                };
            pubSubConnection.addListener(listener);
            pubSubAsync.subscribe(channelKey);
            try {
                return latch.await(remainingWaitTimeNanos, TimeUnit.NANOSECONDS);
            } finally {
                try {
                    pubSubAsync.unsubscribe(channelKey);
                } catch (Exception e) {
                    logger.debug("Error unsubscribing from channel {}: {}", channelKey, e.getMessage());
                }
                pubSubConnection.removeListener(listener);
            }
        }
        
        private void startWatchdog(String lockValue, Duration leaseTime) {
            if (watchdogExecutor == null || !configuration.isWatchdogEnabled()) {
                return;
            }
            
            Duration renewalInterval = configuration.getWatchdogRenewalInterval();
            ScheduledFuture<?> task = watchdogExecutor.scheduleAtFixedRate(() -> {
                try {
                    renewWriteLock(lockValue, leaseTime);
                } catch (Exception e) {
                    logger.warn("Error renewing write lock: {}", name, e);
                }
            }, renewalInterval.toMillis(), renewalInterval.toMillis(), TimeUnit.MILLISECONDS);
            
            watchdogTask.set(task);
        }
        
        private void stopWatchdog() {
            ScheduledFuture<?> task = watchdogTask.getAndSet(null);
            if (task != null) {
                task.cancel(false);
            }
        }
        
        private void renewWriteLock(String lockValue, Duration leaseTime) {
            int maxRetries = configuration.getMaxRetries();
            Duration retryInterval = configuration.getRetryInterval();
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    if (renewWriteWithLua(lockValue, leaseTime)) {
                        return;
                    }
                } catch (Exception e) {
                    logger.warn("Lua write renew attempt {} failed for lock {}: {}", attempt + 1, name, e.getMessage());
                    if (attempt == maxRetries) {
                        logger.info("All Lua write renew retries failed, falling back to basic for lock {}", name);
                        fallbackRenewWrite(lockValue, leaseTime);
                        return;
                    }
                    try {
                        Thread.sleep(retryInterval.toMillis() * (1L << attempt));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warn("Write renew retry interrupted for lock {}", name, ie);
                        return;
                    }
                }
            }
            logger.warn("All write renew attempts failed, stopping watchdog for lock {}", name);
            stopWatchdog();
        }

        private boolean renewWriteWithLua(String lockValue, Duration leaseTime) {
            RedisCommands<String, String> sync = connection.sync();
            String script = "local mode = redis.call('HGET', KEYS[1], 'mode')\n" +
                "local owner = redis.call('HGET', KEYS[1], 'owner')\n" +
                "if mode == 'write' and owner == ARGV[1] then\n" +
                "    redis.call('PEXPIRE', KEYS[1], ARGV[2])\n" +
                "    return 1\n" +
                "else\n" +
                "    return 0\n" +
                "end";
            
            Long result = sync.eval(script, ScriptOutputType.INTEGER,
                new String[]{lockKey},
                lockValue, String.valueOf(leaseTime.toMillis()));
            
            if (result != null && result == 1) {
                metrics.incrementWatchdogRenewalCounter(name);
                logger.trace("Renewed write lock: {}", name);
                return true;
            }
            return false;
        }

        private void fallbackRenewWrite(String lockValue, Duration leaseTime) {
            try {
                RedisCommands<String, String> sync = connection.sync();
                Boolean result = sync.pexpire(lockKey, leaseTime.toMillis());
                if (result != null && result) {
                    logger.debug("Fallback write renew succeeded for lock {}", name);
                } else {
                    logger.warn("Fallback write renew failed for lock {}", name);
                    stopWatchdog();
                }
            } catch (Exception e) {
                logger.error("Fallback write renew failed for lock {}", name, e);
                stopWatchdog();
            }
        }
    }
}
