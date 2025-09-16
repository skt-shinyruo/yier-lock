package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.exception.LockAcquisitionException;
import com.mycorp.distributedlock.api.exception.LockReleaseException;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import io.micrometer.core.instrument.Timer;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ZooKeeperDistributedLock implements DistributedLock {
    
    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperDistributedLock.class);
    
    private final String name;
    private final InterProcessMutex mutex;
    private final LockConfiguration configuration;
    private final LockMetrics metrics;
    private final LockTracing tracing;
    
    private final ThreadLocal<Timer.Sample> heldTimer = new ThreadLocal<>();
    
    public ZooKeeperDistributedLock(String name,
                                  CuratorFramework curatorFramework,
                                  LockConfiguration configuration,
                                  LockMetrics metrics,
                                  LockTracing tracing,
                                  String lockPath) {
        this.name = name;
        this.mutex = new InterProcessMutex(curatorFramework, lockPath);
        this.configuration = configuration;
        this.metrics = metrics;
        this.tracing = tracing;
    }
    
    @Override
    public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
        try (var spanContext = tracing.startLockAcquisitionSpan(name, "lock")) {
            Timer.Sample acquisitionTimer = metrics.startAcquisitionTimer(name);
            
            try {
                mutex.acquire();
                Timer.Sample heldTimerSample = metrics.startHeldTimer(name);
                heldTimer.set(heldTimerSample);
                
                metrics.recordAcquisitionTime(acquisitionTimer, name, "success");
                metrics.incrementLockAcquisitionCounter(name, "success");
                spanContext.setStatus("success");
                logger.debug("Successfully acquired ZooKeeper lock: {}", name);
            } catch (InterruptedException e) {
                metrics.recordAcquisitionTime(acquisitionTimer, name, "interrupted");
                metrics.incrementLockAcquisitionCounter(name, "interrupted");
                spanContext.setError(e);
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                metrics.recordAcquisitionTime(acquisitionTimer, name, "error");
                metrics.incrementLockAcquisitionCounter(name, "error");
                spanContext.setError(e);
                throw new LockAcquisitionException("Error acquiring ZooKeeper lock: " + name, e);
            }
        }
    }
    
    @Override
    public void lock() throws InterruptedException {
        lock(configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
    }
    
    @Override
    public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        try (var spanContext = tracing.startLockAcquisitionSpan(name, "tryLock")) {
            Timer.Sample acquisitionTimer = metrics.startAcquisitionTimer(name);
            
            try {
                boolean acquired = mutex.acquire(waitTime, unit);
                
                if (acquired) {
                    Timer.Sample heldTimerSample = metrics.startHeldTimer(name);
                    heldTimer.set(heldTimerSample);
                    
                    metrics.recordAcquisitionTime(acquisitionTimer, name, "success");
                    metrics.incrementLockAcquisitionCounter(name, "success");
                    spanContext.setStatus("success");
                    logger.debug("Successfully acquired ZooKeeper lock: {}", name);
                } else {
                    metrics.recordAcquisitionTime(acquisitionTimer, name, "timeout");
                    metrics.incrementContentionCounter(name);
                    metrics.incrementLockAcquisitionCounter(name, "timeout");
                    spanContext.setStatus("timeout");
                }
                
                return acquired;
            } catch (InterruptedException e) {
                metrics.recordAcquisitionTime(acquisitionTimer, name, "interrupted");
                metrics.incrementLockAcquisitionCounter(name, "interrupted");
                spanContext.setError(e);
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                metrics.recordAcquisitionTime(acquisitionTimer, name, "error");
                metrics.incrementLockAcquisitionCounter(name, "error");
                spanContext.setError(e);
                throw new LockAcquisitionException("Error trying to acquire ZooKeeper lock: " + name, e);
            }
        }
    }
    
    @Override
    public boolean tryLock() throws InterruptedException {
        return tryLock(0, configuration.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
    }
    
    @Override
    public void unlock() {
        try (var spanContext = tracing.startLockAcquisitionSpan(name, "unlock")) {
            Timer.Sample acquisitionTimer = metrics.startAcquisitionTimer(name);
            
            try {
                mutex.release();
                
                Timer.Sample heldTimerSample = heldTimer.get();
                if (heldTimerSample != null) {
                    heldTimerSample.stop();
                    heldTimer.remove();
                }
                
                metrics.recordAcquisitionTime(acquisitionTimer, name, "success");
                metrics.incrementLockReleaseCounter(name, "success");
                spanContext.setStatus("success");
                logger.debug("Successfully released ZooKeeper lock: {}", name);
            } catch (Exception e) {
                metrics.recordAcquisitionTime(acquisitionTimer, name, "error");
                metrics.incrementLockReleaseCounter(name, "error");
                spanContext.setError(e);
                throw new LockReleaseException("Error releasing ZooKeeper lock: " + name, e);
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
                throw new LockAcquisitionException("Interrupted while acquiring ZooKeeper lock: " + name, e);
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
                throw new LockAcquisitionException("Interrupted while acquiring ZooKeeper lock: " + name, e);
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
        return mutex.isAcquiredInThisProcess();
    }
    
    @Override
    public boolean isHeldByCurrentThread() {
        return mutex.isOwnedByCurrentThread();
    }
    
    @Override
    public String getName() {
        return name;
    }
}