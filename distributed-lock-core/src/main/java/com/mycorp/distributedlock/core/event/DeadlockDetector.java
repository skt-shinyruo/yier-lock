package com.mycorp.distributedlock.core.event;

import com.mycorp.distributedlock.api.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 死锁检测器 - 简化版本
 */
public class DeadlockDetector {
    
    private static final Logger logger = LoggerFactory.getLogger(DeadlockDetector.class);
    
    private final Map<DistributedLock, Thread> lockHolders = new ConcurrentHashMap<>();
    private final Map<Thread, Set<DistributedLock>> threadLocks = new ConcurrentHashMap<>();
    private final AtomicBoolean enableDeadlockDetection = new AtomicBoolean(true);
    private final AtomicLong deadlockDetectionCount = new AtomicLong(0);
    
    public void registerLockAcquisition(DistributedLock lock) {
        Thread currentThread = Thread.currentThread();
        lockHolders.put(lock, currentThread);
        threadLocks.computeIfAbsent(currentThread, k -> ConcurrentHashMap.newKeySet()).add(lock);
        
        logger.debug("Thread {} acquired lock {}", currentThread.getName(), lock.getName());
    }
    
    public void registerLockRelease(DistributedLock lock) {
        Thread currentThread = Thread.currentThread();
        lockHolders.remove(lock);
        
        Set<DistributedLock> locks = threadLocks.get(currentThread);
        if (locks != null) {
            locks.remove(lock);
            if (locks.isEmpty()) {
                threadLocks.remove(currentThread);
            }
        }
        
        logger.debug("Thread {} released lock {}", currentThread.getName(), lock.getName());
    }
    
    public DeadlockInfo detectDeadlock() {
        if (!enableDeadlockDetection.get()) {
            return null;
        }
        
        // 简化死锁检测：检查相互等待的情况
        for (Map.Entry<DistributedLock, Thread> entry : lockHolders.entrySet()) {
            DistributedLock lock = entry.getKey();
            Thread holder = entry.getValue();
            
            // 检查这个线程是否在等待其他锁
            Set<DistributedLock> holderLocks = threadLocks.get(holder);
            if (holderLocks != null && !holderLocks.isEmpty()) {
                // 检查是否形成循环等待
                if (hasCircularWait(holder, new HashSet<>())) {
                    deadlockDetectionCount.incrementAndGet();
                    return createDeadlockInfo(holder, lock);
                }
            }
        }
        
        return null;
    }
    
    private boolean hasCircularWait(Thread thread, Set<Thread> visited) {
        if (visited.contains(thread)) {
            return true;
        }
        
        visited.add(thread);
        
        Set<DistributedLock> locks = threadLocks.get(thread);
        if (locks != null) {
            for (DistributedLock lock : locks) {
                Thread otherHolder = lockHolders.get(lock);
                if (otherHolder != null && !otherHolder.equals(thread)) {
                    if (hasCircularWait(otherHolder, visited)) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }
    
    private DeadlockInfo createDeadlockInfo(Thread thread, DistributedLock lock) {
        Set<Thread> involvedThreads = new HashSet<>();
        involvedThreads.add(thread);
        
        // 收集涉及的所有线程
        Set<DistributedLock> locks = threadLocks.get(thread);
        if (locks != null) {
            for (DistributedLock l : locks) {
                Thread holder = lockHolders.get(l);
                if (holder != null) {
                    involvedThreads.add(holder);
                }
            }
        }
        
        return new DeadlockInfo(
            System.currentTimeMillis(),
            involvedThreads,
            new ArrayList<>(locks != null ? locks : Collections.emptySet()),
            "Circular wait detected involving thread: " + thread.getName()
        );
    }
    
    public void enableDeadlockDetection(boolean enable) {
        enableDeadlockDetection.set(enable);
    }
    
    public boolean isDeadlockDetectionEnabled() {
        return enableDeadlockDetection.get();
    }
    
    public long getDeadlockDetectionCount() {
        return deadlockDetectionCount.get();
    }
    
    /**
     * 死锁信息
     */
    public static class DeadlockInfo {
        private final long timestamp;
        private final Set<Thread> involvedThreads;
        private final List<DistributedLock> involvedLocks;
        private final String description;
        
        public DeadlockInfo(long timestamp, Set<Thread> involvedThreads, 
                          List<DistributedLock> involvedLocks, String description) {
            this.timestamp = timestamp;
            this.involvedThreads = new HashSet<>(involvedThreads);
            this.involvedLocks = new ArrayList<>(involvedLocks);
            this.description = description;
        }
        
        public long getTimestamp() { return timestamp; }
        public Set<Thread> getInvolvedThreads() { return involvedThreads; }
        public List<DistributedLock> getInvolvedLocks() { return involvedLocks; }
        public String getDescription() { return description; }
        
        @Override
        public String toString() {
            return String.format("DeadlockInfo{timestamp=%d, threads=%d, locks=%d, description='%s'}", 
                               timestamp, involvedThreads.size(), involvedLocks.size(), description);
        }
    }
}