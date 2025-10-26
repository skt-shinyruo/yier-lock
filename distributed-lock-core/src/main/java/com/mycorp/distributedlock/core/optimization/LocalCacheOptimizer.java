package com.mycorp.distributedlock.core.optimization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.lang.ref.WeakReference;
import java.lang.ref.ReferenceQueue;

/**
 * 本地缓存优化器
 * 
 * 功能特性：
 * 1. 本地锁缓存机制实现
 * 2. 缓存失效和更新策略
 * 3. 缓存内存使用优化
 * 4. 缓存命中率和性能监控
 * 5. 缓存预热和淘汰策略
 * 6. 多级缓存支持
 */
public class LocalCacheOptimizer {
    
    private final Logger logger = LoggerFactory.getLogger(LocalCacheOptimizer.class);
    
    // 配置参数
    private final CacheConfig config;
    
    // 缓存统计
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder cacheEvictions = new LongAdder();
    private final LongAdder cacheUpdates = new LongAdder();
    private final AtomicLong totalCacheSize = new AtomicLong(0);
    private final AtomicLong peakCacheSize = new AtomicLong(0);
    
    // 性能指标
    private final AtomicLong avgCacheAccessTime = new AtomicLong(0);
    private final AtomicLong maxCacheAccessTime = new AtomicLong(0);
    
    // 缓存存储
    private final ConcurrentHashMap<String, CacheEntry> localCache;
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();
    
    // 淘汰策略
    private final EvictionStrategy evictionStrategy;
    private final ScheduledExecutorService maintenanceExecutor;
    
    // 弱引用队列用于清理过期引用
    private final ReferenceQueue<CacheEntry> referenceQueue;
    private final Map<String, WeakReference<CacheEntry>> weakReferences;
    
    public LocalCacheOptimizer(CacheConfig config) {
        this.config = config;
        this.localCache = new ConcurrentHashMap<>(config.getInitialCapacity());
        this.referenceQueue = new ReferenceQueue<>();
        this.weakReferences = new ConcurrentHashMap<>();
        this.evictionStrategy = createEvictionStrategy(config.getEvictionPolicy());
        
        // 启动维护任务
        this.maintenanceExecutor = Executors.newScheduledThreadPool(2);
        startMaintenanceTasks();
        
        logger.info("本地缓存优化器初始化完成 - 初始容量: {}, 最大大小: {}, 过期时间: {}ms",
                config.getInitialCapacity(), config.getMaxCacheSize(), config.getTimeToLive());
    }
    
    /**
     * 创建淘汰策略
     */
    private EvictionStrategy createEvictionStrategy(EvictionPolicy policy) {
        switch (policy) {
            case LRU:
                return new LRUEvictionStrategy();
            case LFU:
                return new LFUEvictionStrategy();
            case FIFO:
                return new FIFOEvictionStrategy();
            case TTL:
                return new TTLEvictionStrategy(config.getTimeToLive());
            default:
                return new LRUEvictionStrategy();
        }
    }
    
    /**
     * 获取缓存值
     */
    public CompletableFuture<CacheResult> get(String key) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            
            try {
                cacheLock.readLock().lock();
                
                // 查找缓存项
                CacheEntry entry = findCacheEntry(key);
                
                if (entry != null && !entry.isExpired()) {
                    // 缓存命中
                    cacheHits.increment();
                    entry.recordAccess();
                    
                    // 更新访问统计
                    updateAccessTime(startTime);
                    
                    return new CacheResult(true, entry.getValue(), null, true);
                } else {
                    // 缓存未命中
                    cacheMisses.increment();
                    updateAccessTime(startTime);
                    
                    // 清理过期项
                    cleanupExpiredEntries();
                    
                    return new CacheResult(false, null, "Cache miss", false);
                }
                
            } finally {
                cacheLock.readLock().unlock();
            }
        });
    }
    
    /**
     * 放入缓存
     */
    public CompletableFuture<CacheResult> put(String key, Object value) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            
            try {
                cacheLock.writeLock().lock();
                
                // 检查缓存大小限制
                enforceCacheSizeLimit();
                
                // 创建新的缓存项
                CacheEntry newEntry = new CacheEntry(key, value, System.currentTimeMillis());
                
                // 如果是弱引用策略，创建弱引用
                if (config.isWeakReferenceEnabled()) {
                    WeakReference<CacheEntry> weakRef = new WeakReference<>(newEntry, referenceQueue);
                    weakReferences.put(key, weakRef);
                }
                
                // 放入缓存
                CacheEntry oldEntry = localCache.put(key, newEntry);
                
                // 记录操作
                if (oldEntry != null) {
                    cacheUpdates.increment();
                    totalCacheSize.addAndGet(getEntrySize(newEntry) - getEntrySize(oldEntry));
                } else {
                    cacheUpdates.increment();
                    totalCacheSize.addAndGet(getEntrySize(newEntry));
                }
                
                // 更新峰值大小
                long currentSize = localCache.size();
                long currentPeak = peakCacheSize.get();
                while (currentSize > currentPeak) {
                    if (peakCacheSize.compareAndSet(currentPeak, currentSize)) {
                        break;
                    }
                    currentPeak = peakCacheSize.get();
                }
                
                updateAccessTime(startTime);
                
                return new CacheResult(true, value, null, false);
                
            } finally {
                cacheLock.writeLock().unlock();
            }
        });
    }
    
    /**
     * 批量获取缓存
     */
    public CompletableFuture<Map<String, CacheResult>> batchGet(List<String> keys) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, CacheResult> results = new ConcurrentHashMap<>();
            long startTime = System.nanoTime();
            
            try {
                cacheLock.readLock().lock();
                
                for (String key : keys) {
                    CacheEntry entry = findCacheEntry(key);
                    
                    if (entry != null && !entry.isExpired()) {
                        cacheHits.increment();
                        entry.recordAccess();
                        results.put(key, new CacheResult(true, entry.getValue(), null, true));
                    } else {
                        cacheMisses.increment();
                        results.put(key, new CacheResult(false, null, "Cache miss", false));
                    }
                }
                
                updateAccessTime(startTime);
                
                // 清理过期项
                if (keys.size() > 10) { // 只在大批量操作时清理
                    cleanupExpiredEntries();
                }
                
                return results;
                
            } finally {
                cacheLock.readLock().unlock();
            }
        });
    }
    
    /**
     * 批量放入缓存
     */
    public CompletableFuture<Map<String, CacheResult>> batchPut(Map<String, Object> entries) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, CacheResult> results = new ConcurrentHashMap<>();
            long startTime = System.nanoTime();
            
            try {
                cacheLock.writeLock().lock();
                
                for (Map.Entry<String, Object> entry : entries.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();
                    
                    // 检查缓存大小限制
                    if (localCache.size() >= config.getMaxCacheSize()) {
                        evictEntries(1);
                    }
                    
                    // 创建缓存项
                    CacheEntry newEntry = new CacheEntry(key, value, System.currentTimeMillis());
                    
                    // 创建弱引用（如果启用）
                    if (config.isWeakReferenceEnabled()) {
                        WeakReference<CacheEntry> weakRef = new WeakReference<>(newEntry, referenceQueue);
                        weakReferences.put(key, weakRef);
                    }
                    
                    // 放入缓存
                    CacheEntry oldEntry = localCache.put(key, newEntry);
                    
                    // 更新统计
                    if (oldEntry != null) {
                        cacheUpdates.increment();
                        totalCacheSize.addAndGet(getEntrySize(newEntry) - getEntrySize(oldEntry));
                    } else {
                        cacheUpdates.increment();
                        totalCacheSize.addAndGet(getEntrySize(newEntry));
                    }
                    
                    results.put(key, new CacheResult(true, value, null, false));
                }
                
                // 更新峰值大小
                long currentSize = localCache.size();
                long currentPeak = peakCacheSize.get();
                while (currentSize > currentPeak) {
                    if (peakCacheSize.compareAndSet(currentPeak, currentSize)) {
                        break;
                    }
                    currentPeak = peakCacheSize.get();
                }
                
                updateAccessTime(startTime);
                
                return results;
                
            } finally {
                cacheLock.writeLock().unlock();
            }
        });
    }
    
    /**
     * 移除缓存项
     */
    public CompletableFuture<CacheResult> remove(String key) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            
            try {
                cacheLock.writeLock().lock();
                
                CacheEntry removedEntry = localCache.remove(key);
                
                // 移除弱引用
                weakReferences.remove(key);
                
                // 更新统计
                if (removedEntry != null) {
                    cacheEvictions.increment();
                    totalCacheSize.addAndGet(-getEntrySize(removedEntry));
                    updateAccessTime(startTime);
                    return new CacheResult(true, removedEntry.getValue(), null, true);
                } else {
                    updateAccessTime(startTime);
                    return new CacheResult(false, null, "Key not found", false);
                }
                
            } finally {
                cacheLock.writeLock().unlock();
            }
        });
    }
    
    /**
     * 清空缓存
     */
    public CompletableFuture<Void> clear() {
        return CompletableFuture.runAsync(() -> {
            try {
                cacheLock.writeLock().lock();
                
                long evictedCount = localCache.size();
                localCache.clear();
                weakReferences.clear();
                
                cacheEvictions.add(evictedCount);
                totalCacheSize.set(0);
                
                logger.info("缓存已清空，移除 {} 个条目", evictedCount);
                
            } finally {
                cacheLock.writeLock().unlock();
            }
        });
    }
    
    /**
     * 预热缓存
     */
    public CompletableFuture<Void> warmUp(Map<String, Object> warmUpData) {
        return CompletableFuture.runAsync(() -> {
            logger.info("开始缓存预热，数据量: {}", warmUpData.size());
            
            try {
                cacheLock.writeLock().lock();
                
                for (Map.Entry<String, Object> entry : warmUpData.entrySet()) {
                    if (localCache.size() >= config.getMaxCacheSize()) {
                        evictEntries(1);
                    }
                    
                    CacheEntry cacheEntry = new CacheEntry(entry.getKey(), entry.getValue(), System.currentTimeMillis());
                    localCache.put(entry.getKey(), cacheEntry);
                    totalCacheSize.addAndGet(getEntrySize(cacheEntry));
                }
                
                long currentSize = localCache.size();
                long currentPeak = peakCacheSize.get();
                while (currentSize > currentPeak) {
                    if (peakCacheSize.compareAndSet(currentPeak, currentSize)) {
                        break;
                    }
                    currentPeak = peakCacheSize.get();
                }
                
                logger.info("缓存预热完成，预热数据量: {}", warmUpData.size());
                
            } finally {
                cacheLock.writeLock().unlock();
            }
        });
    }
    
    /**
     * 查找缓存项
     */
    private CacheEntry findCacheEntry(String key) {
        // 首先检查直接查找
        CacheEntry entry = localCache.get(key);
        
        // 如果启用了弱引用，也检查弱引用
        if (entry == null && config.isWeakReferenceEnabled()) {
            WeakReference<CacheEntry> weakRef = weakReferences.get(key);
            if (weakRef != null) {
                entry = weakRef.get();
                if (entry != null) {
                    // 将弱引用项重新放入主缓存
                    localCache.put(key, entry);
                    weakReferences.remove(key);
                } else {
                    // 弱引用已被清理
                    weakReferences.remove(key);
                }
            }
        }
        
        return entry;
    }
    
    /**
     * 执行缓存大小限制
     */
    private void enforceCacheSizeLimit() {
        if (localCache.size() >= config.getMaxCacheSize()) {
            int toEvict = localCache.size() - config.getMaxCacheSize() + 1;
            evictEntries(toEvict);
        }
    }
    
    /**
     * 淘汰缓存项
     */
    private void evictEntries(int count) {
        if (count <= 0 || localCache.isEmpty()) {
            return;
        }
        
        List<String> keysToEvict = evictionStrategy.selectEvictionKeys(localCache, count);
        
        for (String key : keysToEvict) {
            CacheEntry removedEntry = localCache.remove(key);
            if (removedEntry != null) {
                cacheEvictions.increment();
                totalCacheSize.addAndGet(-getEntrySize(removedEntry));
                weakReferences.remove(key);
            }
        }
        
        logger.debug("淘汰 {} 个缓存项，剩余: {}", keysToEvict.size(), localCache.size());
    }
    
    /**
     * 清理过期缓存项
     */
    private void cleanupExpiredEntries() {
        long currentTime = System.currentTimeMillis();
        List<String> expiredKeys = new ArrayList<>();
        
        for (CacheEntry entry : localCache.values()) {
            if (entry.isExpired(currentTime)) {
                expiredKeys.add(entry.getKey());
            }
        }
        
        for (String key : expiredKeys) {
            CacheEntry removedEntry = localCache.remove(key);
            if (removedEntry != null) {
                cacheEvictions.increment();
                totalCacheSize.addAndGet(-getEntrySize(removedEntry));
                weakReferences.remove(key);
            }
        }
        
        if (!expiredKeys.isEmpty()) {
            logger.debug("清理 {} 个过期缓存项", expiredKeys.size());
        }
    }
    
    /**
     * 获取缓存项大小
     */
    private long getEntrySize(CacheEntry entry) {
        // 简化的大小估算
        return 100 + (entry.getValue() != null ? entry.getValue().toString().length() * 2 : 0);
    }
    
    /**
     * 更新访问时间统计
     */
    private void updateAccessTime(long startTime) {
        long accessTime = System.nanoTime() - startTime;
        
        long currentAvg = avgCacheAccessTime.get();
        long newAvg = (currentAvg + accessTime) / 2;
        avgCacheAccessTime.set(newAvg);
        
        if (accessTime > maxCacheAccessTime.get()) {
            maxCacheAccessTime.set(accessTime);
        }
    }
    
    /**
     * 启动维护任务
     */
    private void startMaintenanceTasks() {
        // 定期清理过期项
        maintenanceExecutor.scheduleWithFixedDelay(() -> {
            try {
                cleanupExpiredEntries();
                processWeakReferences();
            } catch (Exception e) {
                logger.error("缓存维护任务异常", e);
            }
        }, 30, 60, TimeUnit.SECONDS);
        
        // 定期打印统计信息
        if (config.isMonitoringEnabled()) {
            maintenanceExecutor.scheduleWithFixedDelay(() -> {
                try {
                    printCacheStatistics();
                } catch (Exception e) {
                    logger.error("缓存统计打印异常", e);
                }
            }, 60, 120, TimeUnit.SECONDS);
        }
    }
    
    /**
     * 处理弱引用
     */
    private void processWeakReferences() {
        WeakReference<CacheEntry> weakRef;
        while ((weakRef = (WeakReference<CacheEntry>) referenceQueue.poll()) != null) {
            // 找到对应的键
            for (Map.Entry<String, WeakReference<CacheEntry>> entry : weakReferences.entrySet()) {
                if (entry.getValue() == weakRef) {
                    weakReferences.remove(entry.getKey());
                    break;
                }
            }
        }
    }
    
    /**
     * 打印缓存统计信息
     */
    private void printCacheStatistics() {
        long hits = cacheHits.sum();
        long misses = cacheMisses.sum();
        long total = hits + misses;
        double hitRate = total > 0 ? (double) hits / total * 100 : 0;
        
        logger.info("缓存统计 - 命中: {}, 未命中: {}, 命中率: {:.2f}%, 淘汰: {}, 更新: {}, 大小: {}, 峰值: {}, 平均访问时间: {}ns",
                hits, misses, hitRate, cacheEvictions.sum(), cacheUpdates.sum(),
                localCache.size(), peakCacheSize.get(), avgCacheAccessTime.get());
    }
    
    /**
     * 获取缓存统计信息
     */
    public CacheStatistics getStatistics() {
        long hits = cacheHits.sum();
        long misses = cacheMisses.sum();
        long total = hits + misses;
        double hitRate = total > 0 ? (double) hits / total * 100 : 0;
        
        return new CacheStatistics(
                hits,
                misses,
                hitRate,
                cacheEvictions.sum(),
                cacheUpdates.sum(),
                localCache.size(),
                peakCacheSize.get(),
                totalCacheSize.get(),
                avgCacheAccessTime.get() / 1_000_000, // 转换为毫秒
                maxCacheAccessTime.get() / 1_000_000
        );
    }
    
    /**
     * 关闭优化器
     */
    public void shutdown() {
        logger.info("关闭本地缓存优化器");
        
        if (maintenanceExecutor != null) {
            maintenanceExecutor.shutdown();
        }
        
        localCache.clear();
        weakReferences.clear();
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 缓存配置
     */
    public static class CacheConfig {
        private int initialCapacity = 16;
        private int maxCacheSize = 1000;
        private long timeToLive = 300000; // 5分钟
        private EvictionPolicy evictionPolicy = EvictionPolicy.LRU;
        private boolean weakReferenceEnabled = false;
        private boolean monitoringEnabled = true;
        
        // Getters and Setters
        public int getInitialCapacity() { return initialCapacity; }
        public void setInitialCapacity(int initialCapacity) { this.initialCapacity = initialCapacity; }
        public int getMaxCacheSize() { return maxCacheSize; }
        public void setMaxCacheSize(int maxCacheSize) { this.maxCacheSize = maxCacheSize; }
        public long getTimeToLive() { return timeToLive; }
        public void setTimeToLive(long timeToLive) { this.timeToLive = timeToLive; }
        public EvictionPolicy getEvictionPolicy() { return evictionPolicy; }
        public void setEvictionPolicy(EvictionPolicy evictionPolicy) { this.evictionPolicy = evictionPolicy; }
        public boolean isWeakReferenceEnabled() { return weakReferenceEnabled; }
        public void setWeakReferenceEnabled(boolean weakReferenceEnabled) { this.weakReferenceEnabled = weakReferenceEnabled; }
        public boolean isMonitoringEnabled() { return monitoringEnabled; }
        public void setMonitoringEnabled(boolean monitoringEnabled) { this.monitoringEnabled = monitoringEnabled; }
    }
    
    /**
     * 淘汰策略
     */
    public enum EvictionPolicy {
        LRU,  // 最近最少使用
        LFU,  // 最少使用
        FIFO, // 先进先出
        TTL   // 基于过期时间
    }
    
    /**
     * 缓存项
     */
    private static class CacheEntry {
        private final String key;
        private final Object value;
        private final long creationTime;
        private final AtomicLong accessCount = new AtomicLong(0);
        private final AtomicLong lastAccessTime = new AtomicLong(0);
        
        public CacheEntry(String key, Object value, long creationTime) {
            this.key = key;
            this.value = value;
            this.creationTime = creationTime;
            this.lastAccessTime.set(creationTime);
        }
        
        public void recordAccess() {
            accessCount.incrementAndGet();
            lastAccessTime.set(System.currentTimeMillis());
        }
        
        public boolean isExpired() {
            return isExpired(System.currentTimeMillis());
        }
        
        public boolean isExpired(long currentTime) {
            return (currentTime - creationTime) > 300000; // 5分钟默认过期时间
        }
        
        public String getKey() { return key; }
        public Object getValue() { return value; }
        public long getCreationTime() { return creationTime; }
        public long getAccessCount() { return accessCount.get(); }
        public long getLastAccessTime() { return lastAccessTime.get(); }
    }
    
    /**
     * 缓存结果
     */
    public static class CacheResult {
        private final boolean success;
        private final Object value;
        private final String message;
        private final boolean hit;
        
        public CacheResult(boolean success, Object value, String message, boolean hit) {
            this.success = success;
            this.value = value;
            this.message = message;
            this.hit = hit;
        }
        
        public boolean isSuccess() { return success; }
        public Object getValue() { return value; }
        public String getMessage() { return message; }
        public boolean isHit() { return hit; }
    }
    
    /**
     * 缓存统计信息
     */
    public static class CacheStatistics {
        private final long cacheHits;
        private final long cacheMisses;
        private final double hitRate;
        private final long cacheEvictions;
        private final long cacheUpdates;
        private final int currentCacheSize;
        private final long peakCacheSize;
        private final long totalCacheSize;
        private final double avgAccessTime;
        private final double maxAccessTime;
        
        public CacheStatistics(long cacheHits, long cacheMisses, double hitRate,
                             long cacheEvictions, long cacheUpdates, int currentCacheSize,
                             long peakCacheSize, long totalCacheSize, double avgAccessTime,
                             double maxAccessTime) {
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.hitRate = hitRate;
            this.cacheEvictions = cacheEvictions;
            this.cacheUpdates = cacheUpdates;
            this.currentCacheSize = currentCacheSize;
            this.peakCacheSize = peakCacheSize;
            this.totalCacheSize = totalCacheSize;
            this.avgAccessTime = avgAccessTime;
            this.maxAccessTime = maxAccessTime;
        }
        
        // Getters
        public long getCacheHits() { return cacheHits; }
        public long getCacheMisses() { return cacheMisses; }
        public double getHitRate() { return hitRate; }
        public long getCacheEvictions() { return cacheEvictions; }
        public long getCacheUpdates() { return cacheUpdates; }
        public int getCurrentCacheSize() { return currentCacheSize; }
        public long getPeakCacheSize() { return peakCacheSize; }
        public long getTotalCacheSize() { return totalCacheSize; }
        public double getAvgAccessTime() { return avgAccessTime; }
        public double getMaxAccessTime() { return maxAccessTime; }
    }
    
    // ==================== 淘汰策略实现 ====================
    
    /**
     * 淘汰策略接口
     */
    private interface EvictionStrategy {
        List<String> selectEvictionKeys(Map<String, CacheEntry> cache, int count);
    }
    
    /**
     * LRU淘汰策略
     */
    private static class LRUEvictionStrategy implements EvictionStrategy {
        @Override
        public List<String> selectEvictionKeys(Map<String, CacheEntry> cache, int count) {
            return cache.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(Comparator.comparingLong(CacheEntry::getLastAccessTime)))
                    .limit(count)
                    .map(Map.Entry::getKey)
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        }
    }
    
    /**
     * LFU淘汰策略
     */
    private static class LFUEvictionStrategy implements EvictionStrategy {
        @Override
        public List<String> selectEvictionKeys(Map<String, CacheEntry> cache, int count) {
            return cache.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(Comparator.comparingLong(CacheEntry::getAccessCount)))
                    .limit(count)
                    .map(Map.Entry::getKey)
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        }
    }
    
    /**
     * FIFO淘汰策略
     */
    private static class FIFOEvictionStrategy implements EvictionStrategy {
        @Override
        public List<String> selectEvictionKeys(Map<String, CacheEntry> cache, int count) {
            return cache.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(Comparator.comparingLong(CacheEntry::getCreationTime)))
                    .limit(count)
                    .map(Map.Entry::getKey)
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        }
    }
    
    /**
     * TTL淘汰策略
     */
    private static class TTLEvictionStrategy implements EvictionStrategy {
        private final long timeToLive;
        
        public TTLEvictionStrategy(long timeToLive) {
            this.timeToLive = timeToLive;
        }
        
        @Override
        public List<String> selectEvictionKeys(Map<String, CacheEntry> cache, int count) {
            long currentTime = System.currentTimeMillis();
            
            return cache.entrySet().stream()
                    .filter(entry -> (currentTime - entry.getValue().getCreationTime()) > timeToLive)
                    .sorted(Map.Entry.comparingByValue(Comparator.comparingLong(CacheEntry::getCreationTime)))
                    .limit(count)
                    .map(Map.Entry::getKey)
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        }
    }
}